"""Content contract for the public-production dependency images (#275 increments 2 and 6).

  check-dependency-image-content.py <keycloak|catalogue-localizer|caddy> <evidence-directory>
      Reads filesystem.txt, base-filesystem.txt (the pinned upstream base image),
      environment.json and history.txt written by validate-dependency-image.sh and fails
      when this repository's layers add credentials, identity state, user data or
      translation-model material, or any file to the edge image, which may only replace
      the stock Caddy binary. Trivy's secret scanner covers every layer as well.
  check-dependency-image-content.py --compare-modules <image-build-info> <base-build-info>
      Edge module policy: the rebuilt Caddy binary must contain exactly the stock binary's
      Go modules at the same versions, plus the one approved rate-limit module, except for
      the listed security overrides of a known stock version.
  check-dependency-image-content.py --compare-scan <image-trivy.json> <base-trivy.json>
      Owner scan policy: fails when the image has a HIGH/CRITICAL vulnerability or any
      secret finding that its pinned upstream base does not; reports inherited findings.
  check-dependency-image-content.py --compare-govulncheck <govulncheck-json>
      Edge Go advisory policy: fails when govulncheck reports any advisory with a fixed
      version for the edge binary; advisories without a fix are recorded for review. The
      stripped binary gives module-level results, so this is deliberately conservative.
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
# The owner-approved edge module (#275 increment 6), at the commit caddy/Dockerfile pins.
EDGE_MODULE = "github.com/mholt/caddy-ratelimit"
EDGE_MODULE_VERSION = "v0.1.1-0.20260612195517-5625512f24f6"
# Security overrides in caddy/Dockerfile: {module: (stock version, patched version)}. A stock
# release that moves the module fails the policy, so the override is reviewed and removed.
EDGE_OVERRIDES = {"golang.org/x/net": ("v0.59.0", "v0.62.0")}
# Exact transitive upgrades required by x/net v0.62.0's module graph.
EDGE_TRANSITIVE_UPGRADES = {
    "golang.org/x/crypto": ("v0.57.0", "v0.58.0"),
    "golang.org/x/sync": ("v0.23.0", "v0.24.0"),
    "golang.org/x/sys": ("v0.48.0", "v0.49.0"),
    "golang.org/x/term": ("v0.46.0", "v0.47.0"),
    "golang.org/x/text": ("v0.42.0", "v0.43.0"),
}


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
        if image == "caddy":
            found.append(f"unexpected edge file: {path}")
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


def modules(build_info):
    """Return {module: (required version, replacement or None)} from `caddy build-info`."""
    found, last = {}, None
    for line in build_info.splitlines():
        fields = line.split()
        if len(fields) >= 3 and fields[0] == "dep":
            last = fields[1]
            found[last] = (fields[2], None)
        elif len(fields) >= 3 and fields[0] == "=>" and last:
            # A replacement follows its dep line; anything but the same module is refused.
            found[last] = (found[last][0], fields[2] if fields[1] == last else "foreign:" + fields[1])
    return found


def module_violations(image_info, base_info):
    image, base = modules(image_info), modules(base_info)
    found = []
    if not base or "github.com/caddyserver/caddy/v2" not in base:
        found.append("stock Caddy build information is missing")
    for name in sorted(set(image) | set(base)):
        if name == EDGE_MODULE:
            continue
        if name in EDGE_OVERRIDES or name in EDGE_TRANSITIVE_UPGRADES:
            stock, patched = (EDGE_OVERRIDES | EDGE_TRANSITIVE_UPGRADES)[name]
            # The replacement graph selects x/net v0.60.0 before applying the patch.
            expected = ("v0.60.0", patched) if name == "golang.org/x/net" else (patched, None)
            if base.get(name) != (stock, None) or image.get(name) != expected:
                found.append(f"security override {name} must replace stock {stock} with {patched}; "
                             f"stock {base.get(name)}, image {image.get(name)}")
        elif image.get(name) != base.get(name):
            found.append(f"module {name} differs from stock Caddy: {base.get(name)} -> {image.get(name)}")
    if image.get(EDGE_MODULE) != (EDGE_MODULE_VERSION, None) or EDGE_MODULE in base:
        found.append(f"approved module {EDGE_MODULE} must be added at {EDGE_MODULE_VERSION}, found {image.get(EDGE_MODULE)}")
    return found


def compare_modules(image_path, base_path):
    found = module_violations(pathlib.Path(image_path).read_text(), pathlib.Path(base_path).read_text())
    for item in found:
        print(f"VIOLATION {item}")
    overrides = ", ".join(f"{name} {stock} -> {patched}" for name, (stock, patched) in EDGE_OVERRIDES.items())
    print(f"Edge module policy: {len(found)} violation(s); only {EDGE_MODULE} {EDGE_MODULE_VERSION} is added "
          f"to stock Caddy, with security overrides: {overrides}.")
    return 1 if found else 0


def go_advisories(stream):
    """Return {advisory: fixed version or None} from govulncheck's JSON message stream."""
    decoder, index, found = json.JSONDecoder(), 0, {}
    while index < len(stream):
        while index < len(stream) and stream[index].isspace():
            index += 1
        if index == len(stream):
            break
        message, index = decoder.raw_decode(stream, index)
        finding = message.get("finding") if isinstance(message, dict) else None
        if finding:
            found[finding["osv"]] = found.get(finding["osv"]) or finding.get("fixed_version")
    return found


def compare_govulncheck(path):
    advisories = go_advisories(pathlib.Path(path).read_text())
    fixable = sorted(f"{name} (fixed in {fixed})" for name, fixed in advisories.items() if fixed)
    unfixed = sorted(name for name, fixed in advisories.items() if not fixed)
    for item in fixable:
        print(f"FIXABLE {item}")
    print(f"Edge Go advisory policy: {len(fixable)} advisory(ies) with an available fix; "
          f"{len(unfixed)} without a fix, recorded for review: {', '.join(unfixed) or 'none'}.")
    return 1 if fixable else 0


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
        "caddy": [],
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
        ("caddy", "etc/caddy/Caddyfile.local", "unexpected edge file"),
        ("caddy", "usr/bin/xcaddy", "unexpected edge file"),
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
    stock = ("go\tgo1.26.8\ndep\tgithub.com/caddyserver/caddy/v2\tv2.11.7\th1:x\ndep\tgolang.org/x/net\tv0.59.0\th1:y\n"
             "dep\tgolang.org/x/text\tv0.42.0\th1:t\n")
    for name, (old, _) in EDGE_TRANSITIVE_UPGRADES.items():
        if name not in modules(stock):
            stock += f"dep\t{name}\t{old}\th1:t\n"
    approved = stock.replace("v0.59.0\th1:y\n", "v0.60.0\th1:y\n=>\tgolang.org/x/net\tv0.62.0\th1:p\n") \
        + f"dep\t{EDGE_MODULE}\t{EDGE_MODULE_VERSION}\th1:z\n"
    for name, (old, new) in EDGE_TRANSITIVE_UPGRADES.items():
        approved = approved.replace(f"{name}\t{old}", f"{name}\t{new}")
    if module_violations(approved, stock):
        failures.append(f"edge module policy rejected the approved build: {module_violations(approved, stock)}")
    module_cases = [
        ("missing approved module", stock, "must be added"),
        ("another module version", approved.replace(EDGE_MODULE_VERSION, "v0.1.0"), "must be added"),
        ("changed stock dependency", approved.replace("x/text\tv0.43.0", "x/text\tv0.41.0"), "security override"),
        ("extra module", approved + "dep\texample.com/plugin\tv1.0.0\th1:w\n", "example.com/plugin differs"),
        ("replaced module", approved.replace("v0.43.0\th1:t\n", "v0.43.0\th1:t\n=>\tgolang.org/x/text\tv0.44.0\th1:r\n"),
         "security override"),
        ("missing security override", stock + f"dep\t{EDGE_MODULE}\t{EDGE_MODULE_VERSION}\th1:z\n", "security override"),
        ("previous vulnerable override", approved.replace("x/net\tv0.62.0", "x/net\tv0.60.0"), "security override"),
        ("override to a fork", approved.replace("golang.org/x/net\tv0.62.0", "example.com/net\tv0.62.0"),
         "security override"),
    ]
    for label, image_info, fragment in module_cases:
        if not any(fragment in item for item in module_violations(image_info, stock)):
            failures.append(f"edge module policy accepted a {label}")
    if not module_violations(approved, ""):
        failures.append("edge module policy accepted missing stock build information")
    # Stock Caddy shipping the fix must fail, so the override is removed rather than kept.
    if not any("security override" in item for item in module_violations(
            approved, stock.replace("x/net\tv0.59.0", "x/net\tv0.62.0"))):
        failures.append("edge module policy kept an override that stock Caddy no longer needs")
    stream = ('{"config":{"scan_mode":"binary"}}\n{"finding":{"osv":"GO-0000-0001","trace":[{"module":"stdlib"}]}}\n'
              '{"finding":{"osv":"GO-0000-0002","fixed_version":"v0.60.0","trace":[{"module":"golang.org/x/net"}]}}\n'
              '{"finding":{"osv":"GO-0000-0002","trace":[{"module":"golang.org/x/net"}]}}')
    if go_advisories(stream) != {"GO-0000-0001": None, "GO-0000-0002": "v0.60.0"}:
        failures.append(f"Go advisory policy misread the govulncheck stream: {go_advisories(stream)}")
    for failure in failures:
        print(f"FAIL {failure}")
    print(f"Dependency image content self-test: {len(bad) + 9 + len(scan_cases) + len(module_cases)} deliberate violations checked.")
    return 1 if failures else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if len(sys.argv) == 4 and sys.argv[1] == "--compare-scan":
        return compare_scan(sys.argv[2], sys.argv[3])
    if len(sys.argv) == 3 and sys.argv[1] == "--compare-govulncheck":
        return compare_govulncheck(sys.argv[2])
    if len(sys.argv) == 4 and sys.argv[1] == "--compare-modules":
        return compare_modules(sys.argv[2], sys.argv[3])
    if len(sys.argv) != 3 or sys.argv[1] not in ("keycloak", "catalogue-localizer", "caddy"):
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
