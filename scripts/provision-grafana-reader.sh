#!/usr/bin/env bash
set -Eeuo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ $# == 2 && ( "$1" == --local || "$1" == --private-dev ) ]] || {
  echo 'Usage: provision-grafana-reader.sh --local|--private-dev ENV_FILE' >&2; exit 1;
}
# Resolve secret and service from the authoritative rendered Compose config.
if [[ "$1" == --local ]]; then
  export APPLICATION_VERSION="$(bash "$repository_root/scripts/backend-artifact.sh" version)"
  compose=(docker compose --env-file "$2" --file "$repository_root/compose.yaml" --file "$repository_root/compose.observability.yaml" --profile observability)
else
  compose=(docker compose --env-file "$2" --file "$repository_root/deploy/private-dev/compose.yaml")
fi
secret_file="$("${compose[@]}" config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["secrets"]["grafana_database_password"]["file"])')"
[[ -s "$secret_file" && ! -L "$secret_file" ]] || { echo 'Dedicated Grafana database secret is missing or unsafe' >&2; exit 1; }
# psql variable quoted as an SQL literal; credentials never appear in argv or output.
python3 - "$secret_file" "$repository_root/deploy/private-dev/grafana/provision-reader.sql" <<'READER' | "${compose[@]}" exec -T --user postgres postgres psql --username=postgres --dbname=videogame_platform --set=ON_ERROR_STOP=1 --quiet >/dev/null
import pathlib, sys
secret=pathlib.Path(sys.argv[1]).read_text().strip()
if not secret or any(c in secret for c in "\r\n"):
    raise SystemExit('Invalid reader secret')
# psql backslash command quoting is separate from SQL literal quoting.
print("\\set reader_password '" + secret.replace('\\', '\\\\').replace("'", "\\'") + "'")
print(pathlib.Path(sys.argv[2]).read_text())
READER
echo 'Dedicated Grafana reader provisioned with view-only access and query limits.'
