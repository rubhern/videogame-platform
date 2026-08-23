#!/usr/bin/env bash

set -Eeuo pipefail

if (($# == 0 || $# % 3 != 0)); then
  printf 'Usage: verify-ci-results.sh <job> <expected:true|false> <result> [...]\n' >&2
  exit 2
fi

failed=false
while (($# > 0)); do
  job="$1"
  expected="$2"
  result="$3"
  shift 3

  case "$expected:$result" in
    true:success | false:skipped)
      printf '%-32s expected=%-5s result=%s\n' "$job" "$expected" "$result"
      ;;
    true:*)
      printf '%s was required but finished with result: %s\n' "$job" "$result" >&2
      failed=true
      ;;
    false:*)
      printf '%s was not applicable but finished with result: %s (expected skipped)\n' \
        "$job" "$result" >&2
      failed=true
      ;;
    *)
      printf '%s has invalid applicability value: %s\n' "$job" "$expected" >&2
      failed=true
      ;;
  esac
done

if [[ "$failed" == "true" ]]; then
  exit 1
fi

printf 'All applicable CI jobs succeeded and all inapplicable jobs were skipped.\n'
