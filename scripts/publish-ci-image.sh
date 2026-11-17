#!/usr/bin/env bash
set -Eeuo pipefail

evidence="$(realpath "${1:?Evidence directory is required}")"
archive="${2:?OCI archive filename is required}"
expected_digest="$(cat "$evidence/image-digest.txt")"
[[ "$expected_digest" =~ ^sha256:[0-9a-f]{64}$ ]]

destination="docker://${IMAGE_NAME:?Image repository is required}:${IMAGE_TAG:?Image tag is required}"
auth=(--volume "$HOME/.docker/config.json:/auth.json:ro")
errors="$(mktemp)"
trap 'rm -f -- "$errors"' EXIT

if existing="$(docker run --rm "${auth[@]}" "${SKOPEO_IMAGE:?Pinned skopeo image is required}" \
    inspect --authfile /auth.json --format '{{.Digest}}' "$destination" 2>"$errors")"; then
  if [[ "$existing" != "$expected_digest" ]]; then
    echo 'The commit tag already identifies a different digest. Refusing to replace it.' >&2
    exit 1
  fi
  echo 'The exact scanned digest is already published.'
elif grep -q ': manifest unknown"$' "$errors"; then
  docker run --rm --volume "$evidence:/evidence:ro" "${auth[@]}" "$SKOPEO_IMAGE" \
    copy --all --preserve-digests --authfile /auth.json "oci-archive:/evidence/$archive" "$destination"
else
  echo 'Could not verify the current commit tag in GHCR. Publication refused.' >&2
  exit 1
fi
