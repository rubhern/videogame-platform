#!/usr/bin/env python3
"""Render the real private-dev Compose admission settings; never start or build services."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

repository = Path(__file__).resolve().parents[1]
compose = ["docker-compose"] if shutil.which("docker-compose") else ["docker", "compose"]
environment = {
    key: value for key, value in os.environ.items()
    if not key.startswith(("COMPOSE_", "APPLICATION_", "PRIVATE_DEV_", "KEYCLOAK_",
                           "CATALOGUE_", "SOURCE_", "SMOKE_", "GRAFANA_"))
}
keys = ("APPLICATION_API_ADMISSION_ENABLED", "APPLICATION_API_MAX_REQUESTS", "APPLICATION_API_MAX_WRITES")
template = (repository / "deploy/private-dev/runtime.env.example").read_text()
# All paths and inputs are disposable; no protected runtime file or actual secret is read.
template = "\n".join(line for line in template.splitlines() if not line.startswith(tuple(key + "=" for key in keys)))
with tempfile.TemporaryDirectory(prefix="vgp-admission-compose-") as temporary:
    root = Path(temporary)
    template = template.replace("/etc/videogame-platform/dev/secrets", str(root / "secrets"))
    template = template.replace("/var/lib/videogame-platform/dev/catalogue-models/tcbig-initial", str(root / "model"))
    selected_image = None
    cases = (
        ({}, ("false", "8", "2")),
        ({keys[0]: "true", keys[1]: "3", keys[2]: "1"}, ("true", "3", "1")),
        ({keys[0]: "false", keys[1]: "3", keys[2]: "1"}, ("false", "3", "1")),
    )
    for overrides, expected in cases:
        runtime = root / "runtime.env"
        runtime.write_text(template + "\n" + "\n".join(f"{key}={value}" for key, value in overrides.items()) + "\n")
        rendered = subprocess.run(
            [*compose, "--env-file", str(runtime), "--file", str(repository / "deploy/private-dev/compose.yaml"),
             "--profile", "application", "--profile", "deployment", "config", "--format", "json"],
            env=environment, capture_output=True, text=True, check=True,
        )
        application = json.loads(rendered.stdout)["services"]["application"]
        assert tuple(application["environment"][key] for key in keys) == expected
        assert "build" not in application, "private-dev must select an existing immutable application image"
        if selected_image is None:
            selected_image = application["image"]
        assert application["image"] == selected_image, "admission configuration must not change the application artifact"
print("Private-dev Compose: admission off by default; runtime opt-in/overrides/off reach the same application image.")
