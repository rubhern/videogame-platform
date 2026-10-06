#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
smoke_started=false
smoke_compose=()
cleanup() {
  if [[ "$smoke_started" == true ]]; then
    "${smoke_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT
fixture="$temporary_directory/repository"
mkdir -p "$fixture/scripts" "$fixture/backend" "$temporary_directory/bin"
cp "$repository_root/scripts/"{local-dependencies.sh,backend-artifact.sh} "$fixture/scripts/"
cp "$repository_root/pom.xml" "$fixture/"
cat >"$temporary_directory/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$LOCAL_LOCALIZATION_COMMAND_LOG"
DOCKER
chmod +x "$temporary_directory/bin/docker"
export LOCAL_LOCALIZATION_COMMAND_LOG="$temporary_directory/commands"
run() {
  PATH="$temporary_directory/bin:$PATH" bash "$fixture/scripts/local-dependencies.sh" "$@" >/dev/null
}
run up
run application
! grep -q 'compose.localization.yaml' "$LOCAL_LOCALIZATION_COMMAND_LOG"
initial_env="$(sha256sum "$fixture/.env" "$fixture/backend/.env")"
if CATALOGUE_TRANSLATION_MODEL_DIR="$fixture/missing" run localization 2>/dev/null; then
  printf 'Localization accepted a missing model.\n' >&2; exit 1
fi
model="$temporary_directory/model with spaces"
mkdir -p "$model"
printf '{}\n' >"$model/manifest.json"
printf 'fixture-v1\n' >"$model/revision.txt"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" run localization
grep -q 'compose.localization.yaml --profile localization up --build --detach --wait --wait-timeout 120 catalogue-localizer' "$LOCAL_LOCALIZATION_COMMAND_LOG"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" run up --localization
grep -q 'build catalogue-localizer' "$LOCAL_LOCALIZATION_COMMAND_LOG"
grep -q 'up --detach --wait --wait-timeout 600' "$LOCAL_LOCALIZATION_COMMAND_LOG"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" run application --observability --localization
CATALOGUE_TRANSLATION_MODEL_DIR="$model" run application --localization --observability
grep -q 'compose.observability.yaml --profile observability .*compose.localization.yaml --profile localization --profile full up --build' "$LOCAL_LOCALIZATION_COMMAND_LOG"
run verify-localization
grep -q 'exec -T catalogue-localizer python -c' "$LOCAL_LOCALIZATION_COMMAND_LOG"
[[ "$(sha256sum "$fixture/.env" "$fixture/backend/.env")" == "$initial_env" ]]
for arguments in 'up --localization --localization' 'reset --localization' 'application --unknown'; do
  read -r -a rejected <<<"$arguments"
  if run "${rejected[@]}" 2>/dev/null; then
    printf 'Unsupported localization arguments were accepted.\n' >&2; exit 1
  fi
done
# Cleanup/status must not need weights, download them, or delete the immutable model.
CATALOGUE_TRANSLATION_MODEL_DIR="$fixture/missing" run status
CATALOGUE_TRANSLATION_MODEL_DIR="$fixture/missing" run down
CATALOGUE_TRANSLATION_MODEL_DIR="$fixture/missing" run reset --yes
grep -q 'compose.localization.yaml --profile localization down --volumes --remove-orphans' "$LOCAL_LOCALIZATION_COMMAND_LOG"
[[ -f "$model/manifest.json" ]]
sed -i 's/COMPOSE_PROJECT_NAME=videogame-platform/COMPOSE_PROJECT_NAME=other-project/' "$fixture/.env"
if run reset --yes 2>/dev/null; then
  printf 'Local reset accepted an unrelated project.\n' >&2; exit 1
fi
printf 'Local localization opt-in, combined flags, env preservation, model prerequisite and lifecycle boundary passed.\n'

export APPLICATION_VERSION=validation
render=(docker compose --env-file "$repository_root/.env.example" --file "$repository_root/compose.yaml")
CATALOGUE_TRANSLATION_MODEL_DIR="$model" "${render[@]}" --profile full config --format json >"$temporary_directory/default.json"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" "${render[@]}" --file "$repository_root/compose.localization.yaml" \
  --profile full --profile localization config --format json >"$temporary_directory/localization.json"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" "${render[@]}" --file "$repository_root/compose.observability.yaml" \
  --file "$repository_root/compose.localization.yaml" --profile full --profile observability --profile localization \
  config --format json >"$temporary_directory/combined.json"
python3 - "$repository_root" "$temporary_directory" "$model" <<'PYTHON'
import json, pathlib, sys
root, directory, model = map(pathlib.Path, sys.argv[1:])
load = lambda name: json.loads((directory / name).read_text())
assert set(load('default.json')['services']) == {'postgres', 'keycloak', 'application'}
services = load('localization.json')['services']
assert set(services) == {'postgres', 'keycloak', 'application', 'catalogue-localizer'}
helper = services['catalogue-localizer']
assert pathlib.Path(helper['build']['context']) == root / 'tools/catalogue-localization'
assert set(helper['networks']) == {'default'}
assert len(helper['ports']) == 1 and helper['ports'][0]['host_ip'] == '127.0.0.1'
assert int(helper['ports'][0]['published']) == 8092
assert pathlib.Path(helper['volumes'][0]['source']) == model and helper['volumes'][0]['read_only']
assert helper['read_only'] and helper['cap_drop'] == ['ALL']
assert int(helper['mem_limit']) > 0 and float(helper['cpus']) > 0 and int(helper['pids_limit']) > 0
assert '/ready' in helper['healthcheck']['test'][-1]
assert services['application']['environment']['CATALOGUE_TRANSLATION_ENDPOINT'] == 'http://catalogue-localizer:8092/translate'
assert set(services['application']['depends_on']) == {'postgres', 'keycloak'}
combined = load('combined.json')['services']
assert combined['application']['environment']['SPRING_PROFILES_ACTIVE'] == 'oidc,structured'
assert combined['application']['environment']['TELEMETRY_OTLP_METRICS_ENDPOINT'] == 'http://telemetry:4318/v1/metrics'
assert combined['application']['environment']['CATALOGUE_TRANSLATION_ENDPOINT'] == 'http://catalogue-localizer:8092/translate'
assert set(combined['catalogue-localizer']['networks']) == {'default'}
print('Local Compose inheritance, loopback, mounted model, health, bounds and observability coexistence passed.')
PYTHON

private_render=(docker compose --env-file "$repository_root/deploy/private-dev/runtime.env.example"
  --file "$repository_root/deploy/private-dev/compose.yaml")
CATALOGUE_TRANSLATION_MODEL_DIR="$model" "${private_render[@]}" config --services >"$temporary_directory/private-default-services"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" "${private_render[@]}" --profile application --profile deployment \
  config --format json >"$temporary_directory/private.json"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" "${private_render[@]}" config --hash catalogue-localizer >"$temporary_directory/helper-hash"
CATALOGUE_TRANSLATION_MODEL_DIR="$model" \
APPLICATION_IMAGE=ghcr.io/rubhern/videogame-platform@sha256:2222222222222222222222222222222222222222222222222222222222222222 \
APPLICATION_VERSION=next-application \
  "${private_render[@]}" --profile application --profile deployment config --hash catalogue-localizer >"$temporary_directory/application-change-hash"
cmp "$temporary_directory/helper-hash" "$temporary_directory/application-change-hash"
CATALOGUE_TRANSLATION_MODEL_DIR="$model/next-immutable-model" "${private_render[@]}" config --hash catalogue-localizer >"$temporary_directory/model-change-hash"
if cmp --silent "$temporary_directory/helper-hash" "$temporary_directory/model-change-hash"; then
  printf 'The helper configuration hash ignored the protected model selection.\n' >&2; exit 1
fi
sed '/^CATALOGUE_TRANSLATION_MODEL_DIR=/d' "$repository_root/deploy/private-dev/runtime.env.example" >"$temporary_directory/no-model.env"
if env -u CATALOGUE_TRANSLATION_MODEL_DIR docker compose --env-file "$temporary_directory/no-model.env" \
    --file "$repository_root/deploy/private-dev/compose.yaml" config >"$temporary_directory/no-model.log" 2>&1; then
  printf 'Private dev accepted an unset required model directory.\n' >&2; exit 1
fi
grep -q CATALOGUE_TRANSLATION_MODEL_DIR "$temporary_directory/no-model.log"
python3 - "$temporary_directory" "$model" <<'PYTHON'
import json, pathlib, re, sys
directory, model = map(pathlib.Path, sys.argv[1:])
defaults = set((directory / "private-default-services").read_text().splitlines())
assert "catalogue-localizer" in defaults
assert not {"application", "migration", "deployment-smoke"} & defaults
services = json.loads((directory / "private.json").read_text())["services"]
helper = services["catalogue-localizer"]
assert not helper.get("profiles") and not helper.get("ports")
assert set(helper["networks"]) == {"data"}
assert helper["restart"] == "unless-stopped"
assert "/ready" in helper["healthcheck"]["test"][-1]
mount, = helper["volumes"]
assert pathlib.Path(mount["source"]) == model and mount["read_only"]
assert mount["bind"]["create_host_path"] is False
assert services["application"]["environment"]["CATALOGUE_TRANSLATION_ENDPOINT"] == "http://catalogue-localizer:8092/translate"
assert set(services["application"]["depends_on"]) == {"postgres", "keycloak"}
assert re.fullmatch(r"catalogue-localizer [0-9a-f]{64}\n", (directory / "helper-hash").read_text())
print("Private-dev base localization, internal endpoint, restart, no request dependency and application/model hash boundaries passed.")
PYTHON

[[ "${1:-}" == --smoke ]] || exit 0
# Explicit native proof uses an installed model and its own disposable Compose project.
[[ -n "${CATALOGUE_TRANSLATION_MODEL_DIR:-}" ]] || { printf 'Set an installed model directory for --smoke.\n' >&2; exit 1; }
smoke_compose=("${render[@]}" --project-name "vgp-local-localization-smoke-$$"
  --file "$repository_root/compose.localization.yaml" --profile localization)
smoke_started=true
"${smoke_compose[@]}" up --build --detach --wait --wait-timeout 120 catalogue-localizer
python3 - <<'PYTHON'
import json, urllib.request
assert urllib.request.urlopen('http://127.0.0.1:8092/ready', timeout=3).status == 200
request = urllib.request.Request('http://127.0.0.1:8092/translate',
    data=json.dumps({'text': 'Explore an open world full of adventures.'}).encode(),
    headers={'Content-Type': 'application/json'})
result = json.load(urllib.request.urlopen(request, timeout=30))
assert isinstance(result['text'], str) and result['text'].strip() and result['text'] != 'Explore an open world full of adventures.'
assert result['revision'].startswith('opus-')
print('Native OPUS-MT loopback translation with installed model passed.')
PYTHON
[[ "$("${smoke_compose[@]}" exec -T catalogue-localizer id -u)" == 10001 ]]
