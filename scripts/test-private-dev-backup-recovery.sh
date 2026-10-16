#!/usr/bin/env bash
#
# Offline rehearsal of the private-dev backup/recovery logic.
#
# It exercises the real encryption, integrity, retention and rollback-decision code
# from deploy/private-dev without Docker or PostgreSQL, using synthetic dump files and
# a throwaway GPG keypair. This proves, by execution, the parts of issue #44 that do
# not require the owner-managed host:
#   - public-key encryption + decryption round-trip is faithful (bit-identical);
#   - SHA-256 integrity detects tampering;
#   - retention keeps the newest N verifiable backups and prunes the rest;
#   - the rollback-vs-forward-fix decision matrix is correct.
#
# The database dump/restore and live smoke remain host-only and are out of this
# harness by design. Run from the repository root: bash scripts/test-private-dev-backup-recovery.sh

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lib="$repository_root/deploy/private-dev/lib/backup-common.sh"
assess="$repository_root/deploy/private-dev/bin/assess-recovery-strategy"
verify="$repository_root/deploy/private-dev/bin/verify-private-dev-backup"
# shellcheck source=deploy/private-dev/lib/backup-common.sh
source "$lib"

workdir="$(mktemp -d)"
trap 'rm -rf -- "$workdir"' EXIT

pass_count=0
fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}
pass() {
  pass_count=$((pass_count + 1))
  printf 'ok   %s\n' "$1"
}

bc_require_commands awk gpg python3 sha256sum

# --- Static syntax check of every backup/recovery script -----------------------
for script in \
  "$repository_root"/deploy/private-dev/bin/backup-private-dev \
  "$repository_root"/deploy/private-dev/bin/verify-private-dev-backup \
  "$repository_root"/deploy/private-dev/bin/restore-private-dev \
  "$assess" \
  "$lib"; do
  bash -n "$script" || fail "syntax error in $script"
done
pass "all backup/recovery scripts parse"

# --- Throwaway keypair (owner holds the private key offline in reality) --------
export GNUPGHOME="$workdir/gnupg"
mkdir -p -- "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
gpg --batch --gen-key >/dev/null 2>&1 <<'EOF'
%no-protection
Key-Type: eddsa
Key-Curve: ed25519
Subkey-Type: ecdh
Subkey-Curve: cv25519
Name-Real: VGP Backup Rehearsal
Name-Email: backup-rehearsal@example.invalid
Expire-Date: 0
%commit
EOF
recipient="backup-rehearsal@example.invalid"
fingerprint="$(bc_gpg_recipient_fingerprint "$GNUPGHOME" "$recipient")"
[[ ${#fingerprint} -eq 40 ]] || fail "recipient fingerprint not resolved"
pass "throwaway recipient key resolved ($fingerprint)"

# --- Build one backup from synthetic dumps -------------------------------------
make_backup() {
  local backup_dir="$1" seed="$2"
  mkdir -p -- "$backup_dir"
  local records="$backup_dir/.records"
  : >"$records"
  local database plaintext
  for database in videogame_platform videogame_keycloak; do
    plaintext="$workdir/${database}-${seed}${BC_DUMP_SUFFIX}"
    # Deterministic pseudo-random content standing in for a pg_dump artifact.
    python3 -c "import hashlib,sys; sys.stdout.buffer.write(hashlib.sha256(sys.argv[1].encode()).digest()*4096)" \
      "${database}-${seed}" >"$plaintext"
    bc_package_dump "$database" "$plaintext" "$backup_dir" "$GNUPGHOME" "$recipient" >>"$records"
  done
  VGP_BACKUP_TARGET="vgpdev" \
  VGP_BACKUP_ID="$(basename "$backup_dir")" \
  VGP_BACKUP_CREATED_AT="2026-09-18T00:00:00Z" \
  VGP_POSTGRES_SERVER_VERSION="18.4" \
  VGP_BACKUP_RECIPIENT_FINGERPRINT="$fingerprint" \
  VGP_DB_META_videogame_platform="10:20260913.120000" \
  VGP_DB_META_videogame_keycloak=":" \
    bc_write_manifest "$backup_dir/$BC_MANIFEST_NAME" "$records"
  rm -f -- "$records"
  bc_write_checksums "$backup_dir"
}

backups_root="$workdir/backups"
mkdir -p -- "$backups_root"
primary="$backups_root/20260918T000001Z"
make_backup "$primary" "one"
bc_verify_backup "$primary" || fail "freshly written backup failed verification"
pass "backup packaged and self-verifies"

# --- Decryption round-trip is faithful -----------------------------------------
decrypted="$workdir/roundtrip.dump"
bc_decrypt_file "$primary/videogame_platform${BC_CIPHERTEXT_SUFFIX}" "$decrypted" "$GNUPGHOME"
original="$workdir/videogame_platform-one${BC_DUMP_SUFFIX}"
[[ "$(bc_sha256_file "$decrypted")" == "$(bc_sha256_file "$original")" ]] ||
  fail "decrypted artifact does not match the original plaintext"
manifest_plaintext_sha="$(
  python3 -c 'import json,sys; m=json.load(open(sys.argv[1]));
print(next(a["plaintextSha256"] for a in m["artifacts"] if a["database"]=="videogame_platform"))' \
    "$primary/$BC_MANIFEST_NAME"
)"
[[ "$manifest_plaintext_sha" == "$(bc_sha256_file "$original")" ]] ||
  fail "manifest plaintext checksum does not match the original"
pass "encryption round-trip is bit-identical and matches the manifest"

# --- Tampering is detected -----------------------------------------------------
cp -r "$primary" "$workdir/tampered"
printf 'x' >>"$workdir/tampered/videogame_keycloak${BC_CIPHERTEXT_SUFFIX}"
if bc_verify_backup "$workdir/tampered" >/dev/null 2>&1; then
  fail "tampered ciphertext passed verification"
fi
pass "tampered ciphertext is rejected by integrity check"

cp -r "$primary" "$workdir/truncated"
# Drop an artifact and its checksum line: the manifest/artifact cross-check must catch it.
rm -f -- "$workdir/truncated/videogame_keycloak${BC_CIPHERTEXT_SUFFIX}"
grep -v 'videogame_keycloak' "$workdir/truncated/$BC_CHECKSUMS_NAME" >"$workdir/truncated/$BC_CHECKSUMS_NAME.new"
mv "$workdir/truncated/$BC_CHECKSUMS_NAME.new" "$workdir/truncated/$BC_CHECKSUMS_NAME"
if bc_verify_backup "$workdir/truncated" >/dev/null 2>&1; then
  fail "backup with a missing artifact passed verification"
fi
pass "missing artifact is rejected even with a matching checksums file"

# --- Standalone verifier over a root, with a retention expectation -------------
make_backup "$backups_root/20260918T000002Z" "two"
make_backup "$backups_root/20260918T000003Z" "three"
bash "$verify" --backups-root "$backups_root" --expect-at-least 3 >/dev/null ||
  fail "verifier failed over a healthy backups root"
pass "standalone verifier confirms retained verifiable backups"

# --- Retention keeps newest N and prunes older ---------------------------------
prune_output="$(bc_prune_backups "$backups_root" 2)"
grep -q "prune $backups_root/20260918T000001Z" <<<"$prune_output" ||
  fail "retention did not prune the oldest backup"
[[ -d "$backups_root/20260918T000003Z" && ! -d "$backups_root/20260918T000001Z" ]] ||
  fail "retention kept the wrong set of backups"
bash "$verify" --backups-root "$backups_root" --expect-at-least 2 >/dev/null ||
  fail "surviving backups do not verify after pruning"
pass "retention keeps the newest two verifiable backups and prunes the rest"

# --- Restore modes: live-project protection, host-loss guards, replay gate -----
# Docker and the ledger driver are fakes; the restore script, its backup verification and its
# decryption are real. scripts/validate-personal-data.sh proves the replay itself.
restore="$repository_root/deploy/private-dev/bin/restore-private-dev"
fake_bin="$workdir/fake-bin"
mkdir -p -- "$fake_bin"
real_python="$(command -v python3)"
cat >"$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
arguments=" $* "
case "$arguments" in
  " info "*) exit 0 ;;
  " ps --all --quiet "*) printf '%s' "${FAKE_CONTAINERS:-}"; exit 0 ;;
  " volume ls --quiet "*) printf '%s' "${FAKE_VOLUMES:-}"; exit 0 ;;
  *" up "*" postgres "*) exit "${FAKE_POSTGRES_UP_EXIT:-0}" ;;
  *" up "*" keycloak "*) exit 0 ;;
  *" down "*) exit 0 ;;
  *" pg_restore "*) cat >/dev/null; exit 0 ;;
  *"flyway_schema_history"*) printf '20260913.120000\n'; exit 0 ;;
  *"to_regclass"*) printf 't\n'; exit 0 ;;
  *"FROM realm WHERE"*) printf '1\n'; exit 0 ;;
  *"FROM user_entity"*) printf '3\n'; exit 0 ;;
esac
printf 'Unexpected fake docker command: %s\n' "$*" >&2
exit 92
EOF
cat >"$fake_bin/python3" <<EOF
#!/usr/bin/env bash
if [[ " \$* " == *"/personal-data-ledger "* ]]; then
  printf '%s\n' "\$*" >>"\$FAKE_REPLAY_LOG"
  [[ "\${FAKE_REPLAY_EXIT:-0}" == 0 ]] || exit "\$FAKE_REPLAY_EXIT"
  receipt=""; previous=""
  for argument in "\$@"; do [[ "\$previous" == --receipt ]] && receipt="\$argument"; previous="\$argument"; done
  printf '{"kind":"deletion-ledger-replay","entries":1,"verified":1,"outcome":"verified"}\n' >"\$receipt"
  exit 0
fi
exec "$real_python" "\$@"
EOF
chmod +x "$fake_bin/docker" "$fake_bin/python3"

restore_env="$workdir/runtime.env"
printf 'COMPOSE_PROJECT_NAME=videogame-platform-dev\nKEYCLOAK_LOOPBACK_PORT=8180\n' >"$restore_env"
ledger_directory="$workdir/ledger"
install -d -m 0700 "$ledger_directory"
ledger_copy="$ledger_directory/deletion-ledger.jsonl"
install -m 0600 /dev/null "$ledger_copy"
restore_evidence="$workdir/restore-evidence"
mkdir -p -- "$restore_evidence"
marker="$restore_evidence/DELETION-REPLAY-PENDING"
retained="$backups_root/20260918T000003Z"
docker_log="$workdir/docker.log"
replay_log="$workdir/replay.log"

run_restore() {
  : >"$docker_log"
  : >"$replay_log"
  PATH="$fake_bin:$PATH" FAKE_DOCKER_LOG="$docker_log" FAKE_REPLAY_LOG="$replay_log" \
    "$restore" "$@" --env-file "$restore_env" --evidence-directory "$restore_evidence"
}
expect_refusal() {
  local description="$1" expected="$2"; shift 2
  local output
  if output="$(run_restore "$@" 2>&1)"; then
    fail "$description was accepted"
  fi
  grep -Fq -- "$expected" <<<"$output" || fail "$description refused for another reason: $output"
}
assert_no_container_work() {
  ! grep -Eq ' (up|down|exec) ' "$docker_log" || fail "$1 changed container state: $(cat "$docker_log")"
}

expect_refusal "rehearsal over the live project" "Refusing to restore over the live project" \
  --backup "$retained" --gnupg-home "$GNUPGHOME" --isolated-project videogame-platform-dev \
  --confirm-destroy-isolated-target
assert_no_container_work "rehearsal over the live project"
pass "a rehearsal never targets the live project"

expect_refusal "host-loss recovery without a ledger" "requires --deletion-ledger" \
  --host-loss-recovery --backup "$retained" --gnupg-home "$GNUPGHOME"
chmod 0644 "$ledger_copy"
expect_refusal "host-loss recovery with a readable ledger copy" "0600 file in a 0700 directory" \
  --host-loss-recovery --backup "$retained" --gnupg-home "$GNUPGHOME" --deletion-ledger "$ledger_copy"
chmod 0600 "$ledger_copy"
assert_no_container_work "an invalid host-loss request"
[[ ! -e "$marker" ]] || fail "an invalid host-loss request blocked deployments"
pass "host-loss recovery requires a private deletion-ledger copy"

for leftover in FAKE_CONTAINERS FAKE_VOLUMES; do
  if env "$leftover=abc123" PATH="$fake_bin:$PATH" FAKE_DOCKER_LOG="$docker_log" FAKE_REPLAY_LOG="$replay_log" \
      "$restore" --host-loss-recovery --backup "$retained" --gnupg-home "$GNUPGHOME" \
      --deletion-ledger "$ledger_copy" --env-file "$restore_env" --evidence-directory "$restore_evidence" \
      >"$workdir/leftover.out" 2>&1; then
    fail "host-loss recovery accepted a host with an existing $leftover"
  fi
  grep -Fq "needs a host without any container or volume" "$workdir/leftover.out" ||
    fail "host-loss refusal for $leftover is unclear: $(cat "$workdir/leftover.out")"
  assert_no_container_work "host-loss recovery on a used host"
  [[ ! -e "$marker" ]] || fail "a refused host-loss recovery blocked deployments"
done
pass "host-loss recovery refuses any host holding the live project's containers or volumes"

# A fresh host: nothing is removed, the block exists before any container work, a failed replay
# keeps it, and only a verified replay lifts it.
FAKE_REPLAY_EXIT=1 run_restore --host-loss-recovery --backup "$retained" --gnupg-home "$GNUPGHOME" \
  --deletion-ledger "$ledger_copy" >"$workdir/host-loss.out" 2>&1 &&
  fail "host-loss recovery succeeded although its replay failed"
grep -Fq -- "--resume-deletion-replay" "$workdir/host-loss.out" || fail "failed replay gave no next step"
! grep -q ' down ' "$docker_log" || fail "host-loss recovery removed something: $(cat "$docker_log")"
grep -q -- '--project-name videogame-platform-dev up --detach --wait --wait-timeout 180 postgres' "$docker_log" ||
  fail "host-loss recovery did not restore into the live project"
grep -q -- 'up --detach --no-deps --wait --wait-timeout 300 keycloak' "$docker_log" ||
  fail "host-loss recovery did not start Keycloak alone for the replay"
! grep -q ' application' "$docker_log" || fail "host-loss recovery touched the application"
grep -q -- "--keycloak-origin http://127.0.0.1:8180" "$replay_log" || fail "replay did not target the restored Keycloak"
grep -q -- "--ledger $ledger_copy" "$replay_log" || fail "replay did not use the ledger copy"
[[ -f "$marker" ]] || fail "a failed replay lifted the deployment block"
pass "host-loss recovery removes nothing and keeps deployments blocked after a failed replay"

FAKE_CONTAINERS=present run_restore --resume-deletion-replay --deletion-ledger "$ledger_copy" >/dev/null 2>&1 ||
  fail "resumed replay failed"
[[ ! -e "$marker" ]] || fail "a verified replay kept deployments blocked"
grep -q '"kind": "deletion-replay-resume"' "$restore_evidence"/restore-*-resume-videogame-platform-dev.json ||
  fail "resumed replay left no evidence"
grep -q '"outcome": "verified"' "$restore_evidence"/restore-*-resume-videogame-platform-dev.json ||
  fail "resume evidence lacks the replay receipt"
pass "only a verified replay lifts the deployment block"

expect_refusal "a resume without a pending replay" "nothing to resume" \
  --resume-deletion-replay --deletion-ledger "$ledger_copy"

# A rehearsal with the ledger replays against its own loopback Keycloak and records the receipt.
run_restore --backup "$retained" --gnupg-home "$GNUPGHOME" --isolated-project vgp-restore-rehearsal \
  --confirm-destroy-isolated-target --deletion-ledger "$ledger_copy" >/dev/null 2>&1 ||
  fail "rehearsal with replay failed"
grep -q -- "--project vgp-restore-rehearsal --keycloak-origin http://127.0.0.1:8190" "$replay_log" ||
  fail "rehearsal replay did not target the isolated project's Keycloak"
grep -q '"deletionReplay"' "$restore_evidence"/restore-*-rehearsal-vgp-restore-rehearsal.json ||
  fail "rehearsal evidence lacks the replay receipt"
[[ ! -e "$marker" ]] || fail "a rehearsal blocked live deployments"
pass "a rehearsal replays the ledger against its isolated project only"

# --- Rollback vs forward-fix decision matrix -----------------------------------
assert_decision() {
  local expected="$1"; shift
  local output status=0
  output="$("$assess" "$@" 2>&1)" || status=$?
  grep -q "Decision: $expected" <<<"$output" ||
    fail "expected decision $expected for [$*] but got: $(head -n1 <<<"$output")"
  case "$expected" in
    REFUSE) [[ $status -eq 3 ]] || fail "REFUSE must exit 3 (got $status) for [$*]" ;;
    *) [[ $status -eq 0 ]] || fail "$expected must exit 0 (got $status) for [$*]" ;;
  esac
}
assert_decision ROLLBACK \
  --current-schema-version 20260913.120000 --target-migration-version 20260913.120000
assert_decision FORWARD_FIX \
  --current-schema-version 20260913.120000 --target-migration-version 20260906.120000
assert_decision ROLLBACK \
  --current-schema-version 20260913.120000 --target-migration-version 20260906.120000 \
  --schema-compatible-confirmed
assert_decision REFUSE \
  --current-schema-version 20260906.120000 --target-migration-version 20260913.120000
pass "rollback/forward-fix/refuse decision matrix is correct"

printf '\nAll %s offline backup/recovery checks passed.\n' "$pass_count"
