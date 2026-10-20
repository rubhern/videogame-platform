"""Content contract for the public-production dependency images (#275 increment 2).

  check-dependency-image-content.py <keycloak|catalogue-localizer> <evidence-directory>
      Reads filesystem.txt, base-filesystem.txt (the pinned upstream base image),
      environment.json and history.txt written by validate-dependency-image.sh and fails
      when this repository's layers add credentials, identity state, user data or
      translation-model material. Trivy's secret scanner covers every layer as well.
  check-dependency-image-content.py --compare-scan <image-trivy.json> <base-trivy.json>
      Owner scan policy: fails when the image has a HIGH/CRITICAL vulnerability or any
      secret finding that its pinned upstream base does not; reports inherited findings.
  check-dependency-image-content.py --self-test
      Proves that each forbidden content class and each added finding is detected.
"""

import json
import pathlib
import re
import sys

CREDENTIAL_NAME = re.compile(r"(PASSWORD|SECRET|TOKEN|CREDENTIAL|API_KEY|PRIVATE_KEY)", re.I)
CREDENTIAL_HISTORY = re.compile(r"(password|secret|token|credential)=[^ ]+", re.I)
COMMON_FORBIDDEN = [
    (re.compile(r"(^|/)\.env(\.[^/]*)?$"), "environment file"),
    (re.compile(r"(^|/)\.git(/|$)"), "Git metadata"),
    (re.compile(r"(^|/)(id_rsa|id_ed25519|\.netrc|\.pgpass|\.docker/config\.json)$"), "credential file"),
    (re.compile(r"\.(pem|key|p12|pfx|jks|keystore|kdbx|gpg|asc)$"), "key or keystore"),
    (re.compile(r"\.(sql|dump|pgdump)(\.gz)?$"), "database dump"),
    (re.compile(r"(^|/)(run/secrets|secrets)/[^/]+$"), "secret file"),
    (re.compile(r"realm[^/]*\.json$|[^/]*-users?-\d*\.json$"), "realm or user export"),
]
# Base-image trust stores are public CA bundles, not credentials.
ALLOWED_KEY_MATERIAL = re.compile(r"(^|/)(cacerts|ca-certificates\.crt|certifi/cacert\.pem)$"
                                  r"|^etc/ssl/|^etc/pki/(ca-trust|tls|java|product-default)/|^usr/share/ca-certificates/")
MODEL_FILE = re.compile(
    r"(^|/)(model\.bin|source\.spm|target\.spm|shared_vocabulary\.(json|txt)|revision\.txt)$"
    r"|\.(safetensors|ckpt|gguf|onnx|spm)$")
# Outside installed packages only: sacremoses ships language data such as nonbreaking_prefix.pt.
WEIGHT_FILE = re.compile(r"\.(pt|pth|h5|msgpack)$")
KEYCLOAK_STATE = re.compile(r"^opt/keycloak/data/(import|h2|transaction-logs|export)/.+")
LOCALIZER_APPLICATION = "opt/catalogue-localization/"
LOCALIZER_FILES = {"helper.py", "runtime-requirements.txt"}


def normalized(paths):
    return {path.strip().lstrip("./") for path in paths} - {""}


def violations(image, paths, environment, history, base_paths=()):
    found = []
    for path in sorted(normalized(paths) - normalized(base_paths)):
        if path.endswith("/"):
            continue
        if ALLOWED_KEY_MATERIAL.search(path):
            continue
        for pattern, label in COMMON_FORBIDDEN:
            if pattern.search(path):
                # Python packages ship test fixtures and certificates for their own suites.
                if label in ("key or keystore", "database dump") and "/site-packages/" in path and "/tests/" in path:
                    continue
                found.append(f"{label}: {path}")
        if image == "keycloak" and KEYCLOAK_STATE.match(path):
            found.append(f"identity state baked into the image: {path}")
        if image == "catalogue-localizer":
            if MODEL_FILE.search(path) or path.startswith("model/") \
                    or (WEIGHT_FILE.search(path) and "/site-packages/" not in path):
                found.append(f"translation model material: {path}")
            if path.startswith(LOCALIZER_APPLICATION) and path[len(LOCALIZER_APPLICATION):] not in LOCALIZER_FILES:
                found.append(f"unexpected localizer source file: {path}")
    for entry in environment or []:
        name = entry.split("=", 1)[0]
        if CREDENTIAL_NAME.search(name):
            found.append(f"credential-named environment variable: {name}")
    if CREDENTIAL_HISTORY.search(history):
        found.append("image history contains credential-like build data")
    return found


GATED_SEVERITIES = {"HIGH", "CRITICAL"}


def scan_findings(report):
    """Return {key: description} for gated vulnerabilities and every secret finding."""
    findings = {}
    for result in report.get("Results") or []:
        for item in result.get("Vulnerabilities") or []:
            if item.get("Severity") in GATED_SEVERITIES:
                key = ("vulnerability", item["VulnerabilityID"], item.get("PkgName"), item.get("InstalledVersion"))
                findings[key] = f"{item['Severity']} {item['VulnerabilityID']} in {item.get('PkgName')} " \
                                f"{item.get('InstalledVersion')} (fixed: {item.get('FixedVersion') or 'none'})"
        for item in result.get("Secrets") or []:
            key = ("secret", item.get("RuleID"), result.get("Target"), item.get("StartLine"))
            findings[key] = f"secret {item.get('RuleID')} in {result.get('Target')}"
    return findings


def added_findings(image_report, base_report):
    image, base = scan_findings(image_report), scan_findings(base_report)
    return [image[key] for key in sorted(set(image) - set(base))], len(set(image) & set(base))


def compare_scan(image_path, base_path):
    added, inherited = added_findings(json.loads(pathlib.Path(image_path).read_text()),
                                      json.loads(pathlib.Path(base_path).read_text()))
    for finding in added:
        print(f"ADDED {finding}")
    print(f"Scan policy: {len(added)} finding(s) added by this repository's layers; "
          f"{inherited} HIGH/CRITICAL or secret finding(s) inherited from the pinned base, recorded for review.")
    return 1 if added else 0


def self_test():
    clean = {
        "keycloak": ["opt/keycloak/themes/gameometro/login/theme.properties", "etc/pki/java/cacerts",
                     "opt/keycloak/data/", "etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
                     "etc/nsswitch.conf.bak"],
        "catalogue-localizer": ["opt/catalogue-localization/helper.py",
                                "opt/catalogue-localization/runtime-requirements.txt",
                                "usr/local/lib/python3.12/site-packages/certifi/cacert.pem",
                                "usr/local/lib/python3.12/site-packages/numpy/tests/data/case.key",
                                "usr/local/lib/python3.12/site-packages/sacremoses/data/nonbreaking_prefix.pt"],
    }
    bad = [
        ("keycloak", "opt/keycloak/data/import/gameometro-realm.json", "identity state"),
        ("keycloak", "opt/keycloak/data/h2/keycloakdb.mv.db", "identity state"),
        ("keycloak", "opt/keycloak/conf/server.keystore", "key or keystore"),
        ("keycloak", "opt/keycloak/.env", "environment file"),
        ("keycloak", "tmp/export/gameometro-users-0.json", "realm or user export"),
        ("keycloak", "root/backup.sql.gz", "database dump"),
        ("keycloak", "run/secrets/keycloak_db_password", "secret file"),
        ("catalogue-localizer", "model/model.bin", "translation model"),
        ("catalogue-localizer", "opt/catalogue-localization/tcbig/source.spm", "translation model"),
        ("catalogue-localizer", "opt/models/opus-mt.safetensors", "translation model"),
        ("catalogue-localizer", "opt/catalogue-localization/install_model.py", "unexpected localizer source"),
        ("catalogue-localizer", "opt/catalogue-localization/.git/config", "Git metadata"),
        ("catalogue-localizer", "opt/weights/encoder.pt", "translation model"),
    ]
    failures = []
    for image, paths in clean.items():
        unexpected = violations(image, paths, ["PATH=/usr/bin", "KC_RUN_IN_CONTAINER=true"], "RUN pip install")
        if unexpected:
            failures.append(f"clean {image} listing rejected: {unexpected}")
    for image, path, fragment in bad:
        if not any(fragment in item for item in violations(image, clean[image] + [path], [], "")):
            failures.append(f"{image} accepted {path}")
    # Base-image material is out of scope, but the same path added by our layers is not.
    if violations("keycloak", ["etc/apt/trusted.gpg.d/debian.asc"], [], "", ["etc/apt/trusted.gpg.d/debian.asc"]):
        failures.append("a base-image path was attributed to this repository's layers")
    if not violations("keycloak", ["etc/apt/trusted.gpg.d/debian.asc", "opt/keycloak/conf/added.key"], [], "",
                      ["etc/apt/trusted.gpg.d/debian.asc"]):
        failures.append("a key added on top of the base image was accepted")
    for image in clean:
        if not violations(image, clean[image], ["KC_DB_PASSWORD=x"], ""):
            failures.append(f"{image} accepted a credential-named environment variable")
        if not violations(image, clean[image], [], "ARG SMTP_PASSWORD=hunter2"):
            failures.append(f"{image} accepted credential-like build history")
    def report(*items):
        results = {}
        for kind, identifier, target in items:
            entry = results.setdefault(target, {"Target": target})
            if kind == "secret":
                entry.setdefault("Secrets", []).append({"RuleID": identifier, "StartLine": 1})
            else:
                entry.setdefault("Vulnerabilities", []).append({
                    "VulnerabilityID": identifier, "PkgName": target, "InstalledVersion": "1.0", "Severity": kind})
        return {"Results": list(results.values())}

    base = report(("CRITICAL", "CVE-0000-0001", "libc"), ("HIGH", "CVE-0000-0002", "openssl"))
    if added_findings(report(("CRITICAL", "CVE-0000-0001", "libc"), ("MEDIUM", "CVE-0000-0003", "zlib")), base)[0]:
        failures.append("an inherited or ungated finding was attributed to this repository's layers")
    scan_cases = [
        ("added HIGH vulnerability", report(("HIGH", "CVE-0000-0004", "numpy")), "CVE-0000-0004"),
        ("added CRITICAL vulnerability", report(("CRITICAL", "CVE-0000-0005", "netty")), "CVE-0000-0005"),
        ("added secret", report(("secret", "aws-access-key-id", "opt/keycloak/conf/keycloak.conf")), "aws-access-key-id"),
    ]
    for label, image_report, fragment in scan_cases:
        if not any(fragment in finding for finding in added_findings(image_report, base)[0]):
            failures.append(f"scan policy accepted an {label}")
    for failure in failures:
        print(f"FAIL {failure}")
    print(f"Dependency image content self-test: {len(bad) + 6 + len(scan_cases)} deliberate violations checked.")
    return 1 if failures else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if len(sys.argv) == 4 and sys.argv[1] == "--compare-scan":
        return compare_scan(sys.argv[2], sys.argv[3])
    if len(sys.argv) != 3 or sys.argv[1] not in ("keycloak", "catalogue-localizer"):
        print(__doc__, file=sys.stderr)
        return 2
    evidence = pathlib.Path(sys.argv[2])
    found = violations(
        sys.argv[1],
        (evidence / "filesystem.txt").read_text().splitlines(),
        json.loads((evidence / "environment.json").read_text()),
        (evidence / "history.txt").read_text(),
        (evidence / "base-filesystem.txt").read_text().splitlines(),
    )
    for item in found:
        print(f"VIOLATION {item}")
    print(f"{sys.argv[1]} image content: {len(found)} violation(s).")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
