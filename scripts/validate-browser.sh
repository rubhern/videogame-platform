#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
playwright_image="mcr.microsoft.com/playwright@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e"

cd "$repository_root"

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to execute the pinned Playwright browser gate." >&2
  exit 1
fi

docker run --rm \
  --ipc=host \
  --network none \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --volume "$repository_root:/work" \
  --workdir /work \
  "$playwright_image" \
  npm run frontend:test:e2e
