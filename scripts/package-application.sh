#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repository_root"

npm ci
npm run frontend:generate-api
npm run frontend:build
./mvnw -Pwith-frontend clean package

printf 'Combined application package: %s\n' \
  "$repository_root/backend/target/videogame-platform-backend-0.4.0-SNAPSHOT.jar"
