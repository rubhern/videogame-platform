# shellcheck shell=bash
# Backup encryption primitives for public-production recovery (bin/recovery). Sourced, never
# executed. Backups exist only in production; manifests, checksums and retention belong to the
# recovery command, not to this file.

# Encrypt a plaintext file to the owner's public key. The runtime never needs the private key.
# --trust-model always is safe because the operator explicitly selected a key they own.
bc_encrypt_file() {
  local plaintext="$1" ciphertext="$2" gnupg_home="$3" recipient="$4"
  gpg --homedir "$gnupg_home" --batch --yes --no-tty \
    --trust-model always \
    --cipher-algo AES256 --compress-algo none \
    --recipient "$recipient" \
    --output "$ciphertext" --encrypt "$plaintext"
}

# Decrypt a backup artifact. Requires the private key, so it runs only in a controlled restore
# context, never on a runtime host.
bc_decrypt_file() {
  local ciphertext="$1" plaintext="$2" gnupg_home="$3"
  gpg --homedir "$gnupg_home" --batch --yes --no-tty \
    --output "$plaintext" --decrypt "$ciphertext"
}
