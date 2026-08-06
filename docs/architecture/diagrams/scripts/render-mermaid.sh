#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIAGRAMS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SOURCE_DIR="${DIAGRAMS_DIR}/mermaid"
OUTPUT_DIR="${DIAGRAMS_DIR}/generated/mermaid"
MERMAID_CLI_VERSION="11.16.0"
MERMAID_CLI_IMAGE="ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:${MERMAID_CLI_VERSION}"

mkdir -p "${OUTPUT_DIR}"

if command -v npx >/dev/null 2>&1; then
  renderer="npx"
elif command -v docker >/dev/null 2>&1; then
  renderer="docker"
else
  printf 'Mermaid rendering requires npx or Docker.\n' >&2
  exit 1
fi

for source in "${SOURCE_DIR}"/*.mmd; do
  name="$(basename "${source}" .mmd)"
  echo "Rendering ${name}.mmd"
  if [[ "${renderer}" == "npx" ]]; then
    npx --yes -p "@mermaid-js/mermaid-cli@${MERMAID_CLI_VERSION}" mmdc \
      -i "${source}" \
      -o "${OUTPUT_DIR}/${name}.svg" \
      -b transparent
  else
    docker run \
      --rm \
      --user "$(id -u):$(id -g)" \
      -v "${DIAGRAMS_DIR}:/data" \
      "${MERMAID_CLI_IMAGE}" \
      -i "mermaid/${name}.mmd" \
      -o "generated/mermaid/${name}.svg" \
      -b transparent
  fi
done

echo "Mermaid SVGs generated in ${OUTPUT_DIR}"
