#!/usr/bin/env bash

backend_artifact_repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

backend_reactor_version() {
  python3 - "$backend_artifact_repository_root/pom.xml" <<'PY'
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

resolve_backend_jar() {
  local artifact_directory="${1:-$backend_artifact_repository_root/backend/target}"
  local artifact
  local -a artifacts=()

  while IFS= read -r -d '' artifact; do
    artifacts+=("$artifact")
  done < <(find "$artifact_directory" -maxdepth 1 -type f -name '*.jar' -print0 2>/dev/null)

  if (( ${#artifacts[@]} != 1 )); then
    printf 'Expected exactly one packaged backend JAR in %s, found %d.\n' \
      "$artifact_directory" "${#artifacts[@]}" >&2
    if (( ${#artifacts[@]} > 0 )); then
      printf 'Resolved candidates:\n' >&2
      printf '  %s\n' "${artifacts[@]}" >&2
    else
      printf 'Run bash scripts/package-application.sh before resolving the backend JAR.\n' >&2
    fi
    return 1
  fi

  printf '%s\n' "${artifacts[0]}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-jar}" in
    jar)
      resolve_backend_jar
      ;;
    version)
      backend_reactor_version
      ;;
    *)
      printf 'Usage: bash scripts/backend-artifact.sh [jar|version]\n' >&2
      exit 1
      ;;
  esac
fi
