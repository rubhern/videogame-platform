#!/usr/bin/env bash
set -euo pipefail
portal_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_dir="$(dirname "$portal_dir")"
container_id=""
network_id=""
cleanup() {
  if [[ -n "$container_id" ]]; then docker rm --force "$container_id" >/dev/null; fi
  if [[ -n "$network_id" ]]; then docker network rm "$network_id" >/dev/null; fi
}
trap cleanup EXIT
[[ -f "$portal_dir/dist/pagefind/pagefind.js" ]] || { echo 'Build the portal first.' >&2; exit 1; }
image_id="$(docker build --quiet "$portal_dir")"
DOCUMENTATION_IMAGE="$image_id" docker compose --file "$repository_dir/documentation/deploy/compose.private-dev.yaml" config --format json |
  python3 -c '
import json,sys
c=json.load(sys.stdin); assert set(c["services"])=={"documentation"}
s=c["services"]["documentation"]
assert s["ports"]==[{"mode":"ingress","host_ip":"127.0.0.1","target":8080,"published":"4322","protocol":"tcp"}]
assert s["user"]=="101:101" and s["read_only"] and s["cap_drop"]==["ALL"]
assert not s.get("secrets") and not s.get("depends_on") and not s.get("volumes")
assert set(s["networks"])=={"documentation"}
assert int(s["mem_limit"])==67108864 and float(s["cpus"])==0.25 and s["pids_limit"]==32
'
network_id="$(docker network create "vgp-documentation-test-$$")"
container_id="$(docker run --detach --read-only --user 101:101 --cap-drop ALL \
  --security-opt no-new-privileges --memory 64m --cpus .25 --pids-limit 32 \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m,uid=101,gid=101,mode=1770 \
  --network "$network_id" --publish 127.0.0.1::8080 "$image_id")"
port="$(docker port "$container_id" 8080/tcp)"
base_url="http://$port"
for attempt in {1..30}; do
  if curl --silent --fail "$base_url/" >/dev/null; then break; fi
  if [[ "$attempt" == 30 ]]; then docker logs "$container_id"; exit 1; fi
  sleep .2
done
for resource in / /docs/ /diagrams/c4/ /diagrams/Containers-dark.svg /diagrams/oidc-bff-session-sequence-light.svg /pagefind/pagefind.js /pagefind/wasm.en.pagefind; do
  curl --fail --silent "$base_url$resource" >/dev/null
done
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/no-such-page/")" == 404 ]]
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/50x.html")" == 404 ]]
curl --silent --head --header 'Host: documentation.test:9443' "$base_url/docs" | tr -d '\r' | rg --quiet '^Location: /docs/$'

[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/.env")" == 403 ]]
curl --silent --head "$base_url/" | rg --quiet 'Content-Security-Policy:.*connect-src .self.'
[[ "$(docker inspect --format '{{.Config.User}}' "$container_id")" == '101:101' ]]
echo 'Static container smoke passed: non-root, restricted resources, loopback, real 404, CSP, diagrams and search assets.'

if [[ "${1:-}" == "--browser" ]]; then
  PORTAL_BASE_URL="$base_url" npm --prefix "$portal_dir" run test:browser
fi
