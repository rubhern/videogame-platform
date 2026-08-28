#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
terraform_directory="$repository_root/infrastructure/terraform"

if (($# > 0)); then
  printf 'Usage: validate-terraform.sh\n' >&2
  exit 2
fi

if ! command -v terraform >/dev/null 2>&1; then
  printf 'Terraform 1.5.7 is required; see infrastructure/terraform/.terraform-version.\n' >&2
  exit 1
fi

actual_version="$(terraform version -json | python3 -c 'import json,sys; print(json.load(sys.stdin)["terraform_version"])')"
if [[ "$actual_version" != "1.5.7" ]]; then
  printf 'Terraform 1.5.7 is required, found %s.\n' "$actual_version" >&2
  exit 1
fi

temporary_directory="$(mktemp -d)"
cleanup() {
  rm -r -- "$temporary_directory"
}
trap cleanup EXIT

terraform -chdir="$terraform_directory" fmt -check -recursive
TF_DATA_DIR="$temporary_directory/terraform-data" \
  terraform -chdir="$terraform_directory" init -backend=false -lockfile=readonly -input=false
TF_DATA_DIR="$temporary_directory/terraform-data" \
  terraform -chdir="$terraform_directory" validate
python3 "$repository_root/scripts/test-oci-terraform-plan-gate.py"

printf 'Terraform format, initialization, validation, and OCI security/cost policy tests passed.\n'
