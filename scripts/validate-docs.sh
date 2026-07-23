#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

required_files=(
  "AGENTS.md"
  "README.md"
  "docs/product/product-brief.md"
  "docs/product/assumptions.md"
  "docs/product/open-questions.md"
  "docs/product/glossary.md"
  "docs/reference/video-game-platform-vision.pdf"
  "docs/development/codex-setup.md"
  ".agents/skills/product-brief-review/SKILL.md"
)

for file in "${required_files[@]}"; do
  if [[ ! -f "$file" ]]; then
    printf 'Missing required file: %s\n' "$file" >&2
    exit 1
  fi
done

python3 - "$ROOT_DIR" <<'PY'
import pathlib
import re
import sys
from urllib.parse import unquote

root = pathlib.Path(sys.argv[1])
link_pattern = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
errors = []

for markdown in root.rglob("*.md"):
    if ".git" in markdown.parts:
        continue
    text = markdown.read_text(encoding="utf-8")
    for raw_link in link_pattern.findall(text):
        link = raw_link.strip().split(maxsplit=1)[0].strip("<>")
        if not link or link.startswith(("#", "http://", "https://", "mailto:")):
            continue
        relative = unquote(link.split("#", 1)[0])
        target = (markdown.parent / relative).resolve()
        try:
            target.relative_to(root.resolve())
        except ValueError:
            errors.append(f"{markdown.relative_to(root)}: link leaves repository: {link}")
            continue
        if not target.exists():
            errors.append(f"{markdown.relative_to(root)}: missing target: {link}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
PY

while IFS= read -r file; do
  case "$file" in
    scripts/*.sh)
      [[ -x "$file" ]] || {
        printf 'Shell script must be executable: %s\n' "$file" >&2
        exit 1
      }
      ;;
    *)
      [[ ! -x "$file" ]] || {
        printf 'Unexpected executable file: %s\n' "$file" >&2
        exit 1
      }
      ;;
  esac
done < <(git ls-files)

[[ -x "scripts/validate-docs.sh" ]] || {
  printf 'Shell script must be executable: scripts/validate-docs.sh\n' >&2
  exit 1
}

git diff --check
printf 'Documentation validation passed.\n'
