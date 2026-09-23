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
