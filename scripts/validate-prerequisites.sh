#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

failures=0

pass() {
  printf 'PASS  %-24s %s\n' "$1" "$2"
}

fail() {
  printf 'FAIL  %-24s %s\n' "$1" "$2" >&2
  failures=$((failures + 1))
}

first_line() {
  sed -n '1p' <<<"$1"
}

if [[ "$(uname -s)" == "Linux" && "$(uname -r)" == *[Mm]icrosoft-standard-WSL2* ]]; then
  pass "WSL2" "$(uname -r)"
else
  fail "WSL2" "run this local-workstation check from the supported Ubuntu WSL2 distribution"
fi

case "$ROOT_DIR" in
  /mnt/[a-zA-Z] | /mnt/[a-zA-Z]/*)
    fail "Repository location" "$ROOT_DIR is on a Windows-mounted filesystem; clone under /home instead"
    ;;
  *)
    pass "Repository location" "$ROOT_DIR"
    ;;
esac

if git_output="$(git --version 2>&1)"; then
  pass "Git" "$git_output"
else
  fail "Git" "git is missing or cannot run"
fi

if java_settings="$(java -XshowSettings:properties -version 2>&1)" &&
  java_output="$(java --version 2>&1)"; then
  java_major="$(sed -nE 's/^[[:space:]]*java\.specification\.version = ([0-9]+).*$/\1/p' <<<"$java_settings" | sed -n '1p')"
  if [[ "$java_major" == "25" ]]; then
    pass "Java" "$(first_line "$java_output")"
  else
    fail "Java" "expected Java 25, found: $(first_line "$java_output")"
  fi
else
  fail "Java" "java is missing or cannot run"
fi

if javac_output="$(javac --version 2>&1)"; then
  javac_major="$(sed -nE 's/^javac ([0-9]+).*$/\1/p' <<<"$javac_output")"
  if [[ "$javac_major" == "25" ]]; then
    pass "Java compiler" "$javac_output"
  else
    fail "Java compiler" "expected javac 25, found: $javac_output"
  fi
else
  fail "Java compiler" "javac is missing; install a JDK rather than a JRE"
fi

if node_output="$(node --version 2>&1)"; then
  node_major="$(sed -nE 's/^v([0-9]+).*$/\1/p' <<<"$node_output")"
  if [[ "$node_major" == "24" ]]; then
    pass "Node.js" "$node_output"
  else
    fail "Node.js" "expected Node.js 24, found: $node_output"
  fi
else
  fail "Node.js" "node is missing or cannot run"
fi

if npm_output="$(npm --version 2>&1)"; then
  if [[ "$npm_output" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-].*)?$ ]]; then
    pass "npm" "$npm_output"
  else
    fail "npm" "unexpected version output: $npm_output"
  fi
else
  fail "npm" "npm is missing or cannot run"
fi

if python_output="$(python3 --version 2>&1)"; then
  pass "Python" "$python_output"
else
  fail "Python" "python3 is missing; repository scripts read Maven and image metadata with it"
fi

if docker_output="$(docker version --format 'client={{.Client.Version}} server={{.Server.Version}}' 2>&1)"; then
  pass "Docker Desktop" "$docker_output"
else
  fail "Docker Desktop" "docker version failed without sudo: $(first_line "$docker_output")"
fi

if compose_output="$(docker compose version 2>&1)"; then
  pass "Docker Compose" "$compose_output"
else
  fail "Docker Compose" "docker compose version failed: $(first_line "$compose_output")"
fi

if [[ ! -x "$ROOT_DIR/mvnw" ]]; then
  fail "Maven Wrapper" "mvnw is missing or is not executable"
elif [[ ! -f "$ROOT_DIR/.mvn/wrapper/maven-wrapper.properties" ]]; then
  fail "Maven Wrapper" ".mvn/wrapper/maven-wrapper.properties is missing"
elif mvnw_output="$("$ROOT_DIR/mvnw" --version 2>&1)"; then
  mvnw_java_major="$(sed -nE 's/^Java version: ([0-9]+).*$/\1/p' <<<"$mvnw_output" | sed -n '1p')"
  if [[ "$mvnw_java_major" == "25" ]]; then
    maven_version="$(sed -n '1p' <<<"$mvnw_output")"
    maven_java="$(sed -n '/^Java version:/p' <<<"$mvnw_output")"
    pass "Maven Wrapper" "$maven_version; $maven_java"
  else
    fail "Maven Wrapper" "expected wrapper runtime Java 25; $(sed -n '/^Java version:/p' <<<"$mvnw_output")"
  fi
else
  fail "Maven Wrapper" "./mvnw --version failed: $(first_line "$mvnw_output")"
fi

if ((failures > 0)); then
  printf '\nPrerequisite validation failed with %d problem(s).\n' "$failures" >&2
  exit 1
fi

printf '\nAll mandatory local-development prerequisites passed.\n'
