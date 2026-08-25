#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
evidence_directory="${IMAGE_EVIDENCE_DIRECTORY:-$repository_root/target/container-evidence}"
image_archive="$evidence_directory/application-image.oci.tar"
node_image="node:24.19.0-bookworm-slim@sha256:3638d9a6fe4030bd716be989438248074489337ba3275657f93595428be4fc03"
postgres_image="postgres:18.4-bookworm"
trivy_image="aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969"
run_id="${RANDOM}-$$"
network="videogame-platform-image-${run_id}"
postgres_container="videogame-platform-image-postgres-${run_id}"
trivy_cache="videogame-platform-trivy-${run_id}"
application_container=""
inspection_container=""
amd64_tag="videogame-platform:image-validation-${run_id}-amd64"
arm64_tag="videogame-platform:image-validation-${run_id}-arm64"

cleanup() {
  if [[ -n "$inspection_container" ]]; then
    docker rm --force "$inspection_container" >/dev/null 2>&1 || true
  fi
  if [[ -n "$application_container" ]]; then
    docker rm --force "$application_container" >/dev/null 2>&1 || true
  fi
  docker rm --force "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  docker volume rm "$trivy_cache" >/dev/null 2>&1 || true
  docker image rm "$amd64_tag" "$arm64_tag" >/dev/null 2>&1 || true
  rm -f -- \
    "$evidence_directory/application-amd64.jar" \
    "$evidence_directory/application-arm64.jar"
}

trap cleanup EXIT

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

application_version() {
  python3 - "$repository_root/pom.xml" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(pathlib.Path(sys.argv[1])).getroot()
version = root.findtext("m:version", namespaces=namespace)
if not version:
    raise SystemExit("pom.xml has no project version")
print(version)
PY
}

inspect_oci_archive() {
  python3 - "$image_archive" "$evidence_directory" <<'PY'
import json
import pathlib
import sys
import tarfile

archive = pathlib.Path(sys.argv[1])
evidence = pathlib.Path(sys.argv[2])

with tarfile.open(archive, "r") as image:
    index = json.load(image.extractfile("index.json"))
    descriptors = index.get("manifests", [])
    if len(descriptors) != 1:
        raise SystemExit(f"expected one OCI index descriptor, found {len(descriptors)}")
    descriptor = descriptors[0]
    digest = descriptor.get("digest", "")
    algorithm, separator, value = digest.partition(":")
    if separator != ":" or algorithm != "sha256" or len(value) != 64:
        raise SystemExit(f"unexpected OCI index digest: {digest}")
    manifest = json.load(image.extractfile(f"blobs/{algorithm}/{value}"))

platforms = {
    (entry.get("platform", {}).get("os"), entry.get("platform", {}).get("architecture"))
    for entry in manifest.get("manifests", [])
}
expected = {("linux", "amd64"), ("linux", "arm64")}
if platforms != expected:
    raise SystemExit(f"expected only {sorted(expected)}, found {sorted(platforms)}")

(evidence / "oci-index.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
(evidence / "image-digest.txt").write_text(digest + "\n", encoding="utf-8")
(evidence / "manifest-platforms.txt").write_text(
    "linux/amd64\nlinux/arm64\n", encoding="utf-8"
)
print(f"Verified OCI index {digest}: linux/amd64 and linux/arm64")
PY
}

wait_for_postgres() {
  for _ in $(seq 1 90); do
    if docker exec --env PGPASSWORD="$application_password" "$postgres_container" \
        psql --host=127.0.0.1 --username=videogame_app \
        --dbname=videogame_platform --command="SELECT 1" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  docker logs "$postgres_container" >&2 || true
  fail "The disposable PostgreSQL image-validation database did not become ready."
}

verify_http_boundary() {
  local architecture="$1"
  docker run --rm --interactive \
    --network "$network" \
    --env EXPECTED_VERSION="$image_version" \
    --env EXPECTED_REVISION="$source_revision" \
    "$node_image" \
    node --input-type=module <<'NODE'
const baseUrl = "http://application:8080";
const managementBaseUrl = "http://application:8081";
const expectedVersion = process.env.EXPECTED_VERSION;
const expectedRevision = process.env.EXPECTED_REVISION;

async function request(path) {
  const response = await fetch(baseUrl + path, {
    headers: { Accept: "application/json, text/html;q=0.9" },
  });
  return { response, body: await response.text() };
}

async function managementRequest(path) {
  const response = await fetch(managementBaseUrl + path, {
    headers: { Accept: "application/json" },
  });
  return { response, body: await response.text() };
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const liveness = await managementRequest("/actuator/health/liveness");
assert(liveness.response.ok && JSON.parse(liveness.body).status === "UP", "liveness failed");

const readiness = await managementRequest("/actuator/health/readiness");
assert(readiness.response.ok && JSON.parse(readiness.body).status === "UP", "readiness failed");

const root = await request("/");
assert(root.response.ok && root.body.includes('<div id="root">'), "packaged frontend is missing");

const browserRoute = await request("/games/pragmata");
assert(
  browserRoute.response.ok && browserRoute.body.includes('<div id="root">'),
  "SPA browser route did not return the entry point",
);

const releases = await request("/api/v1/releases?view=recent&page=1&pageSize=1");
assert(releases.response.ok, "release API failed");
assert(releases.response.headers.get("content-type")?.includes("application/json"), "release API is not JSON");
assert(JSON.parse(releases.body).items?.length === 1, "release API payload is invalid or empty");

const session = await request("/api/v1/session");
assert(session.response.ok && JSON.parse(session.body).authenticated === false, "anonymous BFF session failed");

const productMetrics = await request("/actuator/metrics");
assert(productMetrics.response.status === 404, "management metrics leaked onto the product port");

for (const path of ["/api/not-a-route", "/auth/not-a-route", "/actuator/not-a-route"]) {
  const result = await request(path);
  assert(result.response.status === 404, `${path} was not kept server-owned`);
  assert(!result.body.includes('<div id="root">'), `${path} was captured by the SPA`);
}

const info = await managementRequest("/actuator/info");
const build = JSON.parse(info.body).build;
assert(info.response.ok, "application info failed");
assert(build.version === expectedVersion, "application version does not match the image label");
assert(build.sourceRevision === expectedRevision, "source revision does not match the image label");
NODE
  printf 'HTTP/runtime boundary passed for linux/%s.\n' "$architecture"
}

seed_catalogue_for_runtime_evidence() {
  docker exec --interactive \
    --env PGPASSWORD="$migration_password" \
    "$postgres_container" \
    psql \
      --host=127.0.0.1 \
      --username=videogame_app_migrator \
      --dbname=videogame_platform \
      --set=ON_ERROR_STOP=1 \
      <"$repository_root/backend/src/main/resources/db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql" \
      >/dev/null
}

verify_runtime_image() {
  local architecture="$1"
  local tag="$2"
  local runtime_evidence="$evidence_directory/runtime-${architecture}.txt"
  local inspected_architecture
  local configured_user
  local actual_uid
  local source_label
  local revision_label
  local version_label
  local application_jar="$evidence_directory/application-${architecture}.jar"

  inspected_architecture="$(docker image inspect --format '{{.Architecture}}' "$tag")"
  [[ "$inspected_architecture" == "$architecture" ]] || \
    fail "$tag reports architecture $inspected_architecture, expected $architecture."

  configured_user="$(docker image inspect --format '{{.Config.User}}' "$tag")"
  [[ "$configured_user" == "10001:10001" ]] || \
    fail "$tag configures unexpected runtime user: $configured_user"

  source_label="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.source"}}' "$tag")"
  revision_label="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$tag")"
  version_label="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "$tag")"
  [[ "$source_label" == "$source_url" ]] || fail "$tag has an unexpected source label."
  [[ "$revision_label" == "$source_revision" ]] || fail "$tag has an unexpected revision label."
  [[ "$version_label" == "$image_version" ]] || fail "$tag has an unexpected version label."

  docker history --no-trunc "$tag" >"$evidence_directory/history-${architecture}.txt"
  if grep -Eiq '(password|secret|token|credential)=[^ ]+' "$evidence_directory/history-${architecture}.txt"; then
    fail "$tag history contains credential-like build data."
  fi

  inspection_container="videogame-platform-image-inspect-${run_id}-${architecture}"
  docker create --name "$inspection_container" --platform "linux/$architecture" "$tag" >/dev/null
  docker export "$inspection_container" | tar --list >"$evidence_directory/filesystem-${architecture}.txt"
  docker cp "$inspection_container:/application/application.jar" "$application_jar"
  python3 - "$application_jar" "$evidence_directory/jar-contents-${architecture}.txt" <<'PY'
import pathlib
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
report = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive) as application:
    entries = sorted(application.namelist())
report.write_text("\n".join(entries) + "\n", encoding="utf-8")
for entry in entries:
    if entry.startswith("BOOT-INF/classes/db/dev-seed/"):
        raise SystemExit(f"production image contains development seed: {entry}")
    if entry.endswith(".java") or "/src/test/" in entry or "/src/main/" in entry:
        raise SystemExit(f"production image contains source or test material: {entry}")
PY
  rm -f -- "$application_jar"
  docker rm "$inspection_container" >/dev/null
  inspection_container=""

  if grep -Eq '(^|/)(\.git|\.env([^/]*|$)|node_modules)(/|$)|^application/(src|test|tests)(/|$)' \
      "$evidence_directory/filesystem-${architecture}.txt"; then
    fail "$tag contains source, credentials, dependency workspace, or test material."
  fi

  application_container="videogame-platform-image-application-${run_id}-${architecture}"
  docker run --detach \
    --name "$application_container" \
    --platform "linux/$architecture" \
    --network "$network" \
    --network-alias application \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --cap-drop ALL \
    --security-opt no-new-privileges \
    --env APPLICATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform \
    --env APPLICATION_DB_USERNAME=videogame_app \
    --env APPLICATION_DB_PASSWORD="$application_password" \
    --env APPLICATION_FLYWAY_ENABLED=true \
    --env APPLICATION_MIGRATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform \
    --env APPLICATION_MIGRATION_DB_USERNAME=videogame_app_migrator \
    --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password" \
    --env MANAGEMENT_SERVER_ADDRESS=0.0.0.0 \
    "$tag" >/dev/null

  for _ in $(seq 1 180); do
    if docker run --rm --network "$network" "$node_image" \
        node -e 'fetch("http://application:8081/actuator/health/readiness").then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))' \
        >/dev/null 2>&1; then
      break
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$application_container" 2>/dev/null || true)" != "true" ]]; then
      docker logs "$application_container" >&2 || true
      fail "$tag exited before becoming ready."
    fi
    sleep 1
  done

  if [[ "$architecture" == "amd64" ]]; then
    seed_catalogue_for_runtime_evidence
  fi

  verify_http_boundary "$architecture"

  actual_uid="$(docker exec "$application_container" id -u)"
  [[ "$actual_uid" == "10001" ]] || fail "$tag process runs as unexpected UID $actual_uid."

  {
    printf 'platform=linux/%s\n' "$architecture"
    printf 'configured_user=%s\n' "$configured_user"
    printf 'runtime_uid=%s\n' "$actual_uid"
    printf 'version=%s\n' "$version_label"
    printf 'revision=%s\n' "$revision_label"
    printf 'source=%s\n' "$source_label"
    printf 'liveness=UP\nreadiness=UP\nfrontend=served\napi=server-owned\nbff=server-owned\n'
    printf 'read_only_root=true\ncapabilities=dropped\nno_new_privileges=true\n'
  } >"$runtime_evidence"

  docker rm --force "$application_container" >/dev/null
  application_container=""
}

scan_image() {
  local architecture="$1"
  local tag="$2"

  docker run --rm \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume "$trivy_cache:/root/.cache/trivy" \
    "$trivy_image" image \
      --no-progress \
      --scanners vuln,secret \
      --severity HIGH,CRITICAL \
      --exit-code 1 \
      "$tag" | tee "$evidence_directory/trivy-${architecture}.txt"

  docker run --rm \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume "$trivy_cache:/root/.cache/trivy" \
    "$trivy_image" image \
      --no-progress \
      --scanners vuln,secret \
      --format json \
      "$tag" >"$evidence_directory/trivy-${architecture}.json"

  docker run --rm \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume "$trivy_cache:/root/.cache/trivy" \
    "$trivy_image" image \
      --no-progress \
      --format cyclonedx \
      "$tag" >"$evidence_directory/sbom-${architecture}.cdx.json"
}

require_command docker
require_command git
require_command python3
require_command sha256sum
require_command tar

docker info >/dev/null 2>&1 || fail "Docker must be running to validate the application image."
docker buildx version >/dev/null 2>&1 || fail "Docker Buildx is required."

cd "$repository_root"
mkdir -p "$evidence_directory"
rm -f -- \
  "$image_archive" \
  "$evidence_directory/build-metadata.json" \
  "$evidence_directory/filesystem-amd64.txt" \
  "$evidence_directory/filesystem-arm64.txt" \
  "$evidence_directory/history-amd64.txt" \
  "$evidence_directory/history-arm64.txt" \
  "$evidence_directory/oci-index.json" \
  "$evidence_directory/image-digest.txt" \
  "$evidence_directory/jar-contents-amd64.txt" \
  "$evidence_directory/jar-contents-arm64.txt" \
  "$evidence_directory/manifest-platforms.txt" \
  "$evidence_directory/runtime-amd64.txt" \
  "$evidence_directory/runtime-arm64.txt" \
  "$evidence_directory/sbom-amd64.cdx.json" \
  "$evidence_directory/sbom-arm64.cdx.json" \
  "$evidence_directory/trivy-amd64.json" \
  "$evidence_directory/trivy-amd64.txt" \
  "$evidence_directory/trivy-arm64.json" \
  "$evidence_directory/trivy-arm64.txt" \
  "$evidence_directory/trivy-version.txt" \
  "$evidence_directory/SHA256SUMS"

image_version="${APPLICATION_VERSION:-$(application_version)}"
source_url="${SOURCE_URL:-https://github.com/rubhern/videogame-platform}"
if [[ -n "${SOURCE_REVISION:-}" ]]; then
  source_revision="$SOURCE_REVISION"
elif [[ -z "$(git status --short)" ]]; then
  source_revision="$(git rev-parse HEAD)"
else
  source_revision="$(git rev-parse --short=12 HEAD)-dirty"
fi

build_cache_arguments=()
if [[ "${GITHUB_ACTIONS:-false}" == "true" ]]; then
  build_cache_arguments+=(
    --cache-from type=gha,scope=application-container
    --cache-to type=gha,mode=max,scope=application-container
  )
fi

common_build_arguments=(
  --file Dockerfile
  --provenance=false
  --build-arg "APPLICATION_VERSION=$image_version"
  --build-arg "SOURCE_REVISION=$source_revision"
  --build-arg "SOURCE_URL=$source_url"
)

printf 'Building the immutable OCI index for linux/amd64 and linux/arm64.\n'
docker buildx build \
  "${common_build_arguments[@]}" \
  "${build_cache_arguments[@]}" \
  --platform linux/amd64,linux/arm64 \
  --output "type=oci,dest=$image_archive" \
  --metadata-file "$evidence_directory/build-metadata.json" \
  .

inspect_oci_archive
(
  cd "$evidence_directory"
  sha256sum application-image.oci.tar >SHA256SUMS
)

printf 'Loading platform images for runtime and scanner evidence.\n'
docker buildx build \
  "${common_build_arguments[@]}" \
  "${build_cache_arguments[@]}" \
  --platform linux/amd64 \
  --load \
  --tag "$amd64_tag" \
  .
docker buildx build \
  "${common_build_arguments[@]}" \
  "${build_cache_arguments[@]}" \
  --platform linux/arm64 \
  --load \
  --tag "$arm64_tag" \
  .

application_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
migration_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
keycloak_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
postgres_admin_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"

docker network create --internal "$network" >/dev/null
docker volume create "$trivy_cache" >/dev/null
docker run --detach \
  --name "$postgres_container" \
  --network "$network" \
  --network-alias postgres \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD="$postgres_admin_password" \
  --env POSTGRES_DB=postgres \
  --env POSTGRES_INITDB_ARGS="--auth-host=scram-sha-256 --auth-local=trust" \
  --env APPLICATION_DB_PASSWORD="$application_password" \
  --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password" \
  --env KEYCLOAK_DB_PASSWORD="$keycloak_password" \
  --volume "$repository_root/docker/postgres/init:/docker-entrypoint-initdb.d:ro" \
  "$postgres_image" >/dev/null
wait_for_postgres

docker run --rm "$trivy_image" --version >"$evidence_directory/trivy-version.txt"

for architecture in amd64 arm64; do
  if [[ "$architecture" == "amd64" ]]; then
    tag="$amd64_tag"
  else
    tag="$arm64_tag"
  fi
  printf 'Verifying linux/%s runtime image.\n' "$architecture"
  verify_runtime_image "$architecture" "$tag"
  printf 'Scanning linux/%s runtime image and generating its CycloneDX SBOM.\n' "$architecture"
  scan_image "$architecture" "$tag"
done

printf 'Container image validation passed. Evidence: %s\n' "$evidence_directory"
