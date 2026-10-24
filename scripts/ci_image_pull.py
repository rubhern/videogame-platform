#!/usr/bin/env python3
"""Acquire a digest-pinned CI image; retry registry HTTP failures, never its execution."""
import re
import subprocess
import sys
import time


def pull_image(image):
    if not re.fullmatch(r'[a-z0-9][a-zA-Z0-9._:/-]*@sha256:[0-9a-f]{64}', image):
        raise ValueError('CI image acquisition requires an immutable sha256 reference.')
    for attempt in range(3):
        result = subprocess.run(['docker', 'pull', '--quiet', image], text=True,
                                capture_output=True, timeout=120)
        if result.returncode == 0:
            return
        if attempt == 2 or not re.search(
                r'\b(?:429 Too Many Requests|500 Internal Server Error|502 Bad Gateway|'
                r'503 Service Unavailable|504 Gateway Timeout)\b', result.stderr):
            raise RuntimeError(result.stderr)
        delay = 5 * (attempt + 1)
        print(f'Temporary registry failure downloading pinned CI image; retrying in {delay}s '
              f'(attempt {attempt + 2}/3).', flush=True)
        time.sleep(delay)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python3 scripts/ci_image_pull.py IMAGE@sha256:DIGEST')
    try:
        pull_image(sys.argv[1])
    except (ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from None
