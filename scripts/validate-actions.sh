#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIONLINT_VERSION="1.7.12"

case "$(uname -m)" in
  x86_64)
    actionlint_arch="amd64"
    actionlint_sha256="8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"
    ;;
  aarch64 | arm64)
    actionlint_arch="arm64"
    actionlint_sha256="325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6"
    ;;
  *)
    printf 'Unsupported actionlint architecture: %s\n' "$(uname -m)" >&2
    exit 1
    ;;
esac

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

archive="actionlint_${ACTIONLINT_VERSION}_linux_${actionlint_arch}.tar.gz"
download_url="https://github.com/rhysd/actionlint/releases/download/v${ACTIONLINT_VERSION}/${archive}"

curl --fail --location --silent --show-error \
  --output "$temporary_directory/$archive" \
  "$download_url"
printf '%s  %s\n' "$actionlint_sha256" "$temporary_directory/$archive" |
  sha256sum --check --status
tar --extract --gzip --file "$temporary_directory/$archive" \
  --directory "$temporary_directory" actionlint

cd "$ROOT_DIR"
"$temporary_directory/actionlint" -color -shellcheck="" -pyflakes=""
printf 'GitHub Actions workflow validation passed with actionlint %s.\n' \
  "$ACTIONLINT_VERSION"
