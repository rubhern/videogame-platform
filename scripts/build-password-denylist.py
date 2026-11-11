#!/usr/bin/env python3
"""Build or check the common-password denylist that Keycloak's native passwordBlacklist policy reads.

Source: the NCSC top-100k passwords seen in breaches (derived from Have I Been Pwned), as
published in SecLists (MIT licence) at a pinned commit. Keycloak lowercases a candidate
before the lookup, so the list keeps unique lowercase entries the realm length policy
would otherwise accept.

  build-password-denylist.py --check   offline: the committed list is the reviewed output
  build-password-denylist.py --write   download the pinned source, verify it and regenerate
"""

import argparse
import hashlib
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_URL = ("https://raw.githubusercontent.com/danielmiessler/SecLists/1a7bb9127eca9e6ff2fc0301c597fe6e16a0cb56/"
              "Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt")
SOURCE_SHA256 = "c2e5696882c603b76bb67a47ee970897e5a76fc4c3f5547abe3d0ca340c576e0"
NAME = "gameometro-common-passwords.txt"
OUTPUT = ROOT / "docker/keycloak/password-blacklists" / NAME
# Keep in step with length(...) and maxLength(...) in both realm imports.
MINIMUM_LENGTH = 8
MAXIMUM_LENGTH = 128
OUTPUT_SHA256 = "20696c50382ea49683a5c6a2a6295dbd8f5e11f470fa209a75622a6210aca656"


def build(source: bytes) -> bytes:
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise SystemExit("The downloaded source differs from the pinned, reviewed revision.")
    entries = {line.lower() for line in source.decode("utf-8").splitlines()
               if MINIMUM_LENGTH <= len(line) <= MAXIMUM_LENGTH and line == line.strip()}
    return "".join(entry + "\n" for entry in sorted(entries)).encode("utf-8")


def check(data: bytes) -> list[str]:
    problems = []
    if hashlib.sha256(data).hexdigest() != OUTPUT_SHA256:
        problems.append("the committed list is not the reviewed output; regenerate it with --write")
    lines = data.decode("utf-8").split("\n")
    if lines.pop() != "":
        problems.append("the list must end with a newline")
    if lines != sorted(set(lines)):
        problems.append("entries must be unique and sorted")
    for line in lines:
        if line != line.lower() or line != line.strip() or not MINIMUM_LENGTH <= len(line) <= MAXIMUM_LENGTH:
            problems.append(f"entry outside the policy shape: {line[:20]!r}")
            break
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    arguments = parser.parse_args()
    if arguments.write:
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            data = build(response.read(4_000_000))
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_bytes(data)
        print(f"Wrote {OUTPUT.relative_to(ROOT)}: {data.count(b'\n')} entries, sha256 {hashlib.sha256(data).hexdigest()}")
        return 0
    problems = check(OUTPUT.read_bytes())
    for problem in problems:
        print(f"{OUTPUT.relative_to(ROOT)}: {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
