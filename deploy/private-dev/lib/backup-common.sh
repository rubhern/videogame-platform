# shellcheck shell=bash
#
# Shared, Docker-independent building blocks for private-dev backup, integrity,
# retention and restore. The host scripts source this file; the offline rehearsal
# harness (scripts/test-private-dev-backup-recovery.sh) exercises the same functions
# so the encryption, integrity and retention behaviour is proven without a database.
#
# Nothing here reads a database or invokes Docker. Callers collect the PostgreSQL
# dumps (see bin/backup-private-dev) and hand the resulting plaintext files to the
# packaging functions below. This keeps the security-critical logic testable.
#
# Design invariants (see docs/architecture/deployment/mvp-platform-and-delivery.md):
# - Backups are encrypted with a GPG public key. The host holds only the public key,
#   so a compromised host can produce new backups but cannot read existing ones.
# - Integrity is a SHA-256 manifest over both the ciphertext and the decrypted
#   plaintext, verifiable off-host without the private key.
# - Roles and credentials are never in a backup; they are bootstrapped from the
#   protected secret files at restore time.

set -Eeuo pipefail

# Marker files/names used across the backup layout.
readonly BC_MANIFEST_NAME="manifest.json"
readonly BC_CHECKSUMS_NAME="SHA256SUMS"
readonly BC_DUMP_SUFFIX=".dump"
readonly BC_CIPHERTEXT_SUFFIX=".dump.gpg"

# bc_fail reports and returns non-zero. Because a helper's `return` only leaves the
# helper, callers must abort explicitly. bc_die does both, so failure paths that must
# stop the calling function use `|| bc_die "msg"`.
bc_fail() {
  printf '%s\n' "$1" >&2
  return 1
}

bc_die() {
  printf '%s\n' "$1" >&2
  exit 1
}

bc_require_commands() {
  local command_name
  for command_name in "$@"; do
    command -v "$command_name" >/dev/null 2>&1 ||
      bc_die "Required command is unavailable: $command_name"
  done
}

# sha256 of a file, hex only.
bc_sha256_file() {
  sha256sum -- "$1" | awk '{ print $1 }'
}

# Resolve the fingerprint of the single encryption recipient in a public keyring so
# the manifest records a stable, non-secret identifier and encryption cannot silently
# fall back to a wrong or absent key.
bc_gpg_recipient_fingerprint() {
  local gnupg_home="$1" recipient="$2" fingerprint
  fingerprint="$(
    gpg --homedir "$gnupg_home" --batch --no-tty --with-colons \
      --list-keys "$recipient" 2>/dev/null |
      awk -F: '$1 == "fpr" { print $10; exit }'
  )"
  [[ "$fingerprint" =~ ^[0-9A-F]{40}$ ]] ||
    bc_die "No usable GPG public key for recipient '$recipient' in $gnupg_home."
  printf '%s' "$fingerprint"
}

# Encrypt a plaintext file to the recipient's public key. The host never needs the
# private key for this. --trust-model always is safe here because the operator has
# explicitly selected the recipient key they own.
bc_encrypt_file() {
  local plaintext="$1" ciphertext="$2" gnupg_home="$3" recipient="$4"
  gpg --homedir "$gnupg_home" --batch --yes --no-tty \
    --trust-model always \
    --cipher-algo AES256 --compress-algo none \
    --recipient "$recipient" \
    --output "$ciphertext" --encrypt "$plaintext"
}

# Decrypt a backup artifact. Requires the private key (and its passphrase) and is
# therefore run only in a controlled restore context, never on the private-dev host.
bc_decrypt_file() {
  local ciphertext="$1" plaintext="$2" gnupg_home="$3"
  gpg --homedir "$gnupg_home" --batch --yes --no-tty \
    --output "$plaintext" --decrypt "$ciphertext"
}

# Package one already-collected plaintext dump into an encrypted artifact inside a
# staging backup directory, echoing a compact record the manifest writer consumes.
# Output line: <artifact-name>\t<plaintextSha256>\t<ciphertextSha256>\t<ciphertextBytes>
bc_package_dump() {
  local database="$1" plaintext="$2" backup_dir="$3" gnupg_home="$4" recipient="$5"
  local artifact_name="${database}${BC_CIPHERTEXT_SUFFIX}"
  local ciphertext="$backup_dir/$artifact_name"
  local plaintext_sha ciphertext_sha ciphertext_bytes
  plaintext_sha="$(bc_sha256_file "$plaintext")"
  bc_encrypt_file "$plaintext" "$ciphertext" "$gnupg_home" "$recipient"
  ciphertext_sha="$(bc_sha256_file "$ciphertext")"
  ciphertext_bytes="$(wc -c <"$ciphertext" | tr -d '[:space:]')"
  printf '%s\t%s\t%s\t%s\n' \
    "$artifact_name" "$plaintext_sha" "$ciphertext_sha" "$ciphertext_bytes"
}

# Write the plaintext manifest (no secrets) describing a backup. Reads the packaged
# artifact records from a file (the bc_package_dump lines) plus scalar metadata via the
# environment so it stays a single deterministic serializer. The records path is a file
# argument, not stdin, because the Python program itself arrives on stdin here.
bc_write_manifest() {
  local manifest_path="$1" records_path="$2"
  python3 - "$manifest_path" "$records_path" <<'PY'
import json
import os
import sys

artifacts = []
databases = {}
with open(sys.argv[2], encoding="utf-8") as handle:
    record_lines = handle.read().splitlines()
for line in record_lines:
    if not line.strip():
        continue
    name, plaintext_sha, ciphertext_sha, ciphertext_bytes = line.split("\t")
    database = name.split(".dump.gpg")[0]
    artifacts.append(
        {
            "name": name,
            "database": database,
            "plaintextSha256": plaintext_sha,
            "ciphertextSha256": ciphertext_sha,
            "ciphertextBytes": int(ciphertext_bytes),
        }
    )

# Per-database schema metadata is passed as VGP_DB_META_<database>="applied:version".
for key, value in os.environ.items():
    if not key.startswith("VGP_DB_META_"):
        continue
    database = key[len("VGP_DB_META_"):]
    applied, _, schema_version = value.partition(":")
    databases[database] = {
        "appliedMigrations": int(applied) if applied else None,
        "schemaHistoryVersion": schema_version or None,
    }

record = {
    "schemaVersion": "1",
    "environment": "dev",
    "target": os.environ["VGP_BACKUP_TARGET"],
    "backupId": os.environ["VGP_BACKUP_ID"],
    "createdAt": os.environ["VGP_BACKUP_CREATED_AT"],
    "postgresServerVersion": os.environ.get("VGP_POSTGRES_SERVER_VERSION") or None,
    "encryption": {
        "method": "gpg-public-key",
        "cipher": "AES256",
        "recipientFingerprint": os.environ["VGP_BACKUP_RECIPIENT_FINGERPRINT"],
    },
    "databases": databases,
    "artifacts": artifacts,
    "notes": (
        "Logical per-database pg_dump custom-format artifacts, GPG public-key "
        "encrypted. Roles and credentials are bootstrapped from protected secret "
        "files at restore and are not stored here. Provider/catalogue data is "
        "reconstructable via IGDB synchronization; identity configuration and "
        "product-owned curation are not."
    ),
}
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    handle.write(json.dumps(record, indent=2, sort_keys=True) + "\n")
PY
}

# Write a SHA256SUMS covering every encrypted artifact and the manifest, so integrity
# can be re-checked anywhere with only sha256sum. Paths are stored relative to the
# backup directory.
bc_write_checksums() {
  local backup_dir="$1"
  (
    cd "$backup_dir"
    # Deterministic ordering keeps the checksums file stable and diff-friendly.
    local artifact
    for artifact in *"$BC_CIPHERTEXT_SUFFIX"; do
      [[ -e "$artifact" ]] || continue
      sha256sum -- "$artifact"
    done
    sha256sum -- "$BC_MANIFEST_NAME"
  ) >"$backup_dir/$BC_CHECKSUMS_NAME"
}

# Repeatable integrity check for one backup directory. Does not require the private
# key and never decrypts. Returns non-zero on any tampering or missing artifact.
bc_verify_backup() {
  local backup_dir="$1"
  # Every failure path returns (not exits): callers verify many backups in a loop.
  [[ -d "$backup_dir" ]] || { bc_fail "Backup directory does not exist: $backup_dir"; return 1; }
  [[ -f "$backup_dir/$BC_CHECKSUMS_NAME" ]] ||
    { bc_fail "Backup is missing $BC_CHECKSUMS_NAME: $backup_dir"; return 1; }
  [[ -f "$backup_dir/$BC_MANIFEST_NAME" ]] ||
    { bc_fail "Backup is missing $BC_MANIFEST_NAME: $backup_dir"; return 1; }
  (
    cd "$backup_dir"
    sha256sum --quiet --check "$BC_CHECKSUMS_NAME"
  ) || { bc_fail "Integrity check failed for backup: $backup_dir"; return 1; }
  # The checksums file protects artifacts and manifest, but not itself. Confirm the
  # manifest lists exactly the artifacts present so a truncated set is detected.
  python3 - "$backup_dir" <<'PY' || { bc_fail "Manifest/artifact cross-check failed: $backup_dir"; return 1; }
import json
import pathlib
import sys

backup_dir = pathlib.Path(sys.argv[1])
manifest = json.loads((backup_dir / "manifest.json").read_text(encoding="utf-8"))
listed = {artifact["name"] for artifact in manifest["artifacts"]}
present = {path.name for path in backup_dir.glob("*.dump.gpg")}
if not listed:
    raise SystemExit("manifest lists no artifacts")
if listed != present:
    missing = listed - present
    extra = present - listed
    raise SystemExit(f"manifest/artifact mismatch missing={missing} extra={extra}")
for artifact in manifest["artifacts"]:
    for field in ("plaintextSha256", "ciphertextSha256"):
        value = artifact.get(field, "")
        if not (isinstance(value, str) and len(value) == 64):
            raise SystemExit(f"artifact {artifact['name']} has invalid {field}")
PY
}

# Retention: keep the newest <keep_count> integral backups under <backups_root> and
# remove older ones. Directories are recognised by containing a checksums file. A
# backup that fails integrity is never counted as a kept copy and is reported, not
# silently pruned, so a corrupt newest backup cannot mask loss of good older ones.
bc_prune_backups() {
  local backups_root="$1" keep_count="$2"
  [[ "$keep_count" =~ ^[0-9]+$ && "$keep_count" -ge 1 ]] ||
    bc_die "Retention keep-count must be a positive integer: $keep_count"
  [[ -d "$backups_root" ]] || bc_die "Backups root does not exist: $backups_root"

  local -a candidates=()
  local entry
  # Newest first by directory name; backup ids are timestamp-prefixed and sortable.
  while IFS= read -r entry; do
    [[ -f "$entry/$BC_CHECKSUMS_NAME" ]] || continue
    candidates+=("$entry")
  done < <(find "$backups_root" -mindepth 1 -maxdepth 1 -type d | sort -r)

  local kept=0 index=0 dir
  for ((index = 0; index < ${#candidates[@]}; index++)); do
    dir="${candidates[index]}"
    if ((kept < keep_count)); then
      if bc_verify_backup "$dir" >/dev/null 2>&1; then
        kept=$((kept + 1))
        printf 'keep %s\n' "$dir"
      else
        printf 'corrupt-kept %s\n' "$dir" >&2
      fi
    else
      rm -rf -- "$dir"
      printf 'prune %s\n' "$dir"
    fi
  done
  if ((kept == 0)); then
    bc_die "Refusing to complete retention: no verifiable backup remains under $backups_root."
  fi
}
