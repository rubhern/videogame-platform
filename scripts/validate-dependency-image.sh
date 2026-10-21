#!/usr/bin/env bash

# Builds, inspects and scans one public-production dependency image as a retained AMD64 OCI
# archive, the same way the application image is validated, so trusted main can publish the
# exact checked bytes by digest. The Dockerfiles are reused unchanged; OCI labels are added at
# build time. Nothing is pushed here, and no credential, realm, user data or model is used.
#
#   keycloak             deploy/private-dev/keycloak/Dockerfile with event metrics compiled in
#   catalogue-localizer  tools/catalogue-localization/Dockerfile, without any model
#   caddy                deploy/public-production/caddy/Dockerfile: stock Caddy plus the approved
#                        rate-limit module, checked against the stock binary's module graph

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="${1:-}"
trivy_image="aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969"
skopeo_image="quay.io/skopeo/stable:v1.20.0@sha256:47853bb9fb24202af9110531ebd6e43c5f97701254ca290596640290d17942f4"
run_id="${RANDOM}-$$"
local_tag="videogame-platform-dependency-validation:${image}-${run_id}"
trivy_cache="videogame-platform-dependency-trivy-${run_id}"
inspection_container=""

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

case "$image" in
  keycloak)
    dockerfile="deploy/private-dev/keycloak/Dockerfile"
    context="."
    build_arguments=(--build-arg KEYCLOAK_EVENT_METRICS_USER_ENABLED=true)
    # The upstream Keycloak version is the image version; the revision label tells builds apart.
    image_version="$(sed -n 's/^ARG KEYCLOAK_IMAGE=quay\.io\/keycloak\/keycloak:\([0-9.]*\)@sha256:.*/\1/p' "$repository_root/$dockerfile")"
    expected_user="1000"
    base_image="$(sed -n 's/^ARG KEYCLOAK_IMAGE=//p' "$repository_root/$dockerfile")"
    ;;
  catalogue-localizer)
    dockerfile="tools/catalogue-localization/Dockerfile"
    context="tools/catalogue-localization"
    build_arguments=()
    image_version="$(sed -n 's/^ *image: videogame-platform\/catalogue-localizer:\([0-9.]*\)$/\1/p' "$repository_root/tools/catalogue-localization/compose.yaml")"
    expected_user="10001:10001"
    base_image="$(sed -n 's/^FROM //p' "$repository_root/$dockerfile")"
    ;;
  caddy)
    dockerfile="deploy/public-production/caddy/Dockerfile"
    context="deploy/public-production/caddy"
    build_arguments=()
    image_version="$(sed -n 's/^ARG CADDY_VERSION=v\([0-9.]*\)$/\1/p' "$repository_root/$dockerfile")"
    # The stock image runs Caddy as root inside the container; Compose drops every capability
    # except NET_BIND_SERVICE.
    expected_user=""
    base_image="$(sed -n 's/^ARG CADDY_IMAGE=//p' "$repository_root/$dockerfile")"
    ;;
  *)
    fail "Usage: bash scripts/validate-dependency-image.sh keycloak|catalogue-localizer|caddy"
    ;;
esac
[[ "$image_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Cannot derive the $image image version from its owner."
[[ "$base_image" =~ @sha256:[0-9a-f]{64}$ ]] || fail "The $image base image is not pinned by digest."

evidence_directory="${IMAGE_EVIDENCE_DIRECTORY:-$repository_root/target/dependency-image-evidence/$image}"
image_archive="$evidence_directory/image.oci.tar"

cleanup() {
  if [[ -n "$inspection_container" ]]; then
    docker rm --force "$inspection_container" >/dev/null 2>&1 || true
  fi
  docker volume rm "$trivy_cache" >/dev/null 2>&1 || true
  docker image rm "$local_tag" >/dev/null 2>&1 || true
}
trap cleanup EXIT

python3 -I "$repository_root/scripts/check-dependency-image-content.py" --self-test
docker info >/dev/null 2>&1 || fail "Docker must be running to validate the $image image."
docker buildx version >/dev/null 2>&1 || fail "Docker Buildx is required."

cd "$repository_root"
rm -rf -- "$evidence_directory"
mkdir -p "$evidence_directory"

source_url="${SOURCE_URL:-https://github.com/rubhern/videogame-platform}"
if [[ -n "${SOURCE_REVISION:-}" ]]; then
  source_revision="$SOURCE_REVISION"
elif [[ -z "$(git status --short)" ]]; then
  source_revision="$(git rev-parse HEAD)"
else
  source_revision="$(git rev-parse --short=12 HEAD)-dirty"
fi

cache_arguments=()
if [[ "${GITHUB_ACTIONS:-false}" == "true" ]]; then
  cache_arguments+=(--cache-from "type=gha,scope=dependency-$image" --cache-to "type=gha,mode=max,scope=dependency-$image")
fi

printf 'Building the %s OCI image for linux/amd64.\n' "$image"
docker build --check --file "$dockerfile" "$context"
docker buildx build \
  --file "$dockerfile" \
  "${build_arguments[@]}" \
  "${cache_arguments[@]}" \
  --provenance=false \
  --platform linux/amd64 \
  --label "org.opencontainers.image.source=$source_url" \
  --label "org.opencontainers.image.revision=$source_revision" \
  --label "org.opencontainers.image.version=$image_version" \
  --label "org.opencontainers.image.title=videogame-platform-$image" \
  --output "type=oci,dest=$image_archive" \
  --metadata-file "$evidence_directory/build-metadata.json" \
  "$context"

python3 scripts/inspect-application-oci.py "$image_archive" "$evidence_directory"
(cd "$evidence_directory" && sha256sum image.oci.tar >SHA256SUMS)

# Import the archive rather than rebuilding, so every inspected and scanned layer is published.
docker run --rm \
  --volume "$evidence_directory:/evidence:ro" \
  --volume /var/run/docker.sock:/var/run/docker.sock \
  "$skopeo_image" copy oci-archive:/evidence/image.oci.tar "docker-daemon:$local_tag" >/dev/null
loaded_config_digest="sha256:$(docker run --rm --volume /var/run/docker.sock:/var/run/docker.sock \
  "$skopeo_image" inspect --raw --config "docker-daemon:$local_tag" | sha256sum | cut -d ' ' -f 1)"
[[ "$loaded_config_digest" == "$(cat "$evidence_directory/image-config-digest.txt")" ]] || \
  fail "Imported $image configuration differs from the retained OCI archive."

label() {
  docker image inspect --format "{{index .Config.Labels \"$1\"}}" "$local_tag"
}
[[ "$(docker image inspect --format '{{.Architecture}}' "$local_tag")" == "amd64" ]] || fail "$image is not amd64."
[[ "$(docker image inspect --format '{{.Config.User}}' "$local_tag")" == "$expected_user" ]] || \
  fail "$image runs as an unexpected user."
[[ "$(label org.opencontainers.image.source)" == "$source_url" ]] || fail "$image has an unexpected source label."
[[ "$(label org.opencontainers.image.revision)" == "$source_revision" ]] || fail "$image has an unexpected revision label."
[[ "$(label org.opencontainers.image.version)" == "$image_version" ]] || fail "$image has an unexpected version label."

docker image inspect --format '{{json .Config.Env}}' "$local_tag" >"$evidence_directory/environment.json"
docker history --no-trunc "$local_tag" >"$evidence_directory/history.txt"
inspection_container="videogame-platform-dependency-inspect-${run_id}"
docker create --name "$inspection_container" "$local_tag" >/dev/null
docker export "$inspection_container" | tar --list >"$evidence_directory/filesystem.txt"
docker rm "$inspection_container" >/dev/null
# The pinned upstream base, so content rules judge only what this repository's layers add.
docker pull --quiet --platform linux/amd64 "$base_image" >/dev/null
inspection_container="$(docker create --platform linux/amd64 "$base_image")"
docker export "$inspection_container" | tar --list >"$evidence_directory/base-filesystem.txt"
docker rm "$inspection_container" >/dev/null
inspection_container=""
python3 scripts/check-dependency-image-content.py "$image" "$evidence_directory"

# Behaviour the runtime relies on, without network, credentials or a model.
hardened=(--rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:rw,noexec,nosuid,size=64m)
if [[ "$image" == "keycloak" ]]; then
  docker run "${hardened[@]}" --tmpfs /opt/keycloak/data/tmp:rw,noexec,nosuid,size=64m,mode=0770 \
    "$local_tag" show-config >"$evidence_directory/show-config.txt" 2>&1 || {
      cat "$evidence_directory/show-config.txt" >&2
      fail "The keycloak image cannot report its build configuration."
    }
  for option in 'kc.db =  postgres' 'kc.health-enabled =  true' 'kc.metrics-enabled =  true' \
      'kc.event-metrics-user-enabled =  true'; do
    grep -Fq "$option" "$evidence_directory/show-config.txt" || fail "The keycloak build lacks '$option'."
  done
  [[ "$(docker run "${hardened[@]}" --entrypoint /bin/sh "$local_tag" -c \
      'ls /opt/keycloak/themes/gameometro/login/theme.properties /opt/keycloak/themes/gameometro/email/theme.properties | wc -l')" == "2" ]] || \
    fail "The keycloak image lacks the gameometro login or email theme."
elif [[ "$image" == "caddy" ]]; then
  # Both binaries carry the stock file capability; Compose grants the edge exactly this one.
  hardened+=(--cap-add NET_BIND_SERVICE)
  docker run "${hardened[@]}" "$local_tag" caddy build-info >"$evidence_directory/build-info.txt"
  docker run "${hardened[@]}" "$base_image" caddy build-info >"$evidence_directory/base-build-info.txt"
  python3 -I scripts/check-dependency-image-content.py --compare-modules \
    "$evidence_directory/build-info.txt" "$evidence_directory/base-build-info.txt" | tee "$evidence_directory/module-policy.txt"
  # Symbol-level Go advisories, which Trivy's image scan does not cover for the standard
  # library. govulncheck runs in the pinned builder image against the exact binary.
  builder_image="$(sed -n 's/^ARG CADDY_BUILDER_IMAGE=//p' "$repository_root/$dockerfile")"
  inspection_container="$(docker create "$local_tag")"
  docker cp --quiet "$inspection_container:/usr/bin/caddy" "$evidence_directory/caddy" 2>/dev/null
  docker rm "$inspection_container" >/dev/null
  inspection_container=""
  docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:rw,exec,nosuid,size=512m \
    --env GOBIN=/tmp/bin --env GOPATH=/tmp/go --env GOCACHE=/tmp/cache --env GOPROXY=https://proxy.golang.org \
    --env GOSUMDB=sum.golang.org --volume "$evidence_directory/caddy:/scan/caddy:ro" "$builder_image" \
    sh -c 'go install golang.org/x/vuln/cmd/govulncheck@v1.9.0 >/dev/null && /tmp/bin/govulncheck -mode=binary -format json /scan/caddy' \
    >"$evidence_directory/govulncheck.json"
  rm -f -- "$evidence_directory/caddy"
  python3 -I scripts/check-dependency-image-content.py --compare-govulncheck "$evidence_directory/govulncheck.json" \
    | tee "$evidence_directory/go-advisory-policy.txt"
  docker run "${hardened[@]}" "$local_tag" caddy list-modules >"$evidence_directory/modules.txt"
  grep -qx 'http.handlers.rate_limit' "$evidence_directory/modules.txt" || fail "The caddy image lacks the rate-limit module."
  # The reviewed production Caddyfile must load with this exact binary.
  docker run "${hardened[@]}" --tmpfs /config:rw,noexec,nosuid,size=4m --tmpfs /data:rw,noexec,nosuid,size=4m \
    --volume "$repository_root/deploy/public-production/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" \
    "$local_tag" caddy adapt --config /etc/caddy/Caddyfile >/dev/null || fail "The caddy image cannot adapt the production Caddyfile."
else
  docker run "${hardened[@]}" --entrypoint python "$local_tag" -I -c \
    'import ctranslate2, sentencepiece, sacremoses' || fail "The localizer runtime dependencies do not import."
  # Without the externally mounted model the helper must refuse to start rather than serve.
  if timeout 60 docker run "${hardened[@]}" "$local_tag" >"$evidence_directory/no-model.txt" 2>&1; then
    fail "The localizer started without a mounted model."
  fi
fi

docker volume create "$trivy_cache" >/dev/null
docker run --rm "$trivy_image" --version >"$evidence_directory/trivy-version.txt"
trivy() {
  docker run --rm \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume "$trivy_cache:/root/.cache/trivy" \
    "$trivy_image" image --no-progress "$@"
}
# Owner policy (#275): this repository's layers may add no HIGH/CRITICAL vulnerability or
# secret finding; findings inherited from the pinned upstream base are recorded, not gated.
trivy --scanners vuln,secret --format json "$local_tag" >"$evidence_directory/trivy.json"
trivy --scanners vuln,secret --format json "$base_image" >"$evidence_directory/trivy-base.json"
trivy --scanners vuln,secret --severity HIGH,CRITICAL --format table "$local_tag" >"$evidence_directory/trivy.txt"
trivy --format cyclonedx "$local_tag" >"$evidence_directory/sbom.cdx.json"
python3 scripts/check-dependency-image-content.py --compare-scan \
  "$evidence_directory/trivy.json" "$evidence_directory/trivy-base.json" | tee "$evidence_directory/scan-policy.txt"

printf 'image=videogame-platform-%s\nversion=%s\nrevision=%s\ndigest=%s\n' \
  "$image" "$image_version" "$source_revision" "$(cat "$evidence_directory/image-digest.txt")" \
  | tee "$evidence_directory/identity.txt"
printf 'Dependency image validation passed for %s. Evidence: %s\n' "$image" "$evidence_directory"
