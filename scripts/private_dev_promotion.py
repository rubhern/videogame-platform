#!/usr/bin/env python3
"""Trusted-main evidence and installed deployment-contract checks; no host mutations."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile

REPOSITORY = "rubhern/videogame-platform"
IMAGE_REPOSITORY = f"ghcr.io/{REPOSITORY}"
API_ROOT = f"https://api.github.com/repos/{REPOSITORY}"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024


class Refused(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Refused(message)


def validate_inputs(revision, digest, run_id, attempt):
    require(re.fullmatch(r"[0-9a-f]{40}", revision), "A full lowercase source SHA is required.")
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", digest), "An immutable sha256 digest is required.")
    for value in (run_id, attempt):
        require(re.fullmatch(r"[1-9][0-9]{0,19}", str(value)), "CI run/attempt must be positive integers.")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, token=None):
        self.token = token

    def request(self, path, archive=False):
        headers = {
            "Accept": "application/vnd.github+json", "User-Agent": "vgp-private-dev-promotion",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(API_ROOT + path, headers=headers)
        try:
            response = urllib.request.build_opener(NoRedirect).open(request, timeout=30)
        except urllib.error.HTTPError as error:
            if not archive or error.code != 302:
                raise Refused(f"GitHub evidence is unavailable (HTTP {error.code}); promotion refused.") from None
            location = error.headers.get("Location", "")
            require(location.startswith("https://"), "Artifact redirect must use HTTPS.")
            # Signed storage requests never receive the GitHub credential.
            response = urllib.request.urlopen(location, timeout=30)
        with response:
            data = response.read(MAX_RESPONSE_BYTES + 1)
        require(len(data) <= MAX_RESPONSE_BYTES, "GitHub evidence exceeded the bounded response size.")
        return data if archive else json.loads(data)


def verify_run(api, run_id, revision, workflow, gates, attempt=None):
    run = api.request(f"/actions/runs/{run_id}")
    require(
        run.get("head_repository", {}).get("full_name") == REPOSITORY
        and run.get("event") == "push"
        and run.get("head_branch") == "main"
        and run.get("head_sha") == revision
        and run.get("path") == f".github/workflows/{workflow}"
        and run.get("status") == "completed"
        and run.get("conclusion") == "success",
        f"{workflow} must be a successful trusted-main push for the selected revision.",
    )
    if attempt is not None:
        require(run.get("run_attempt") == int(attempt), "The selected CI run attempt is stale.")
    jobs = api.request(f"/actions/runs/{run_id}/jobs?filter=latest&per_page=100")
    require(jobs.get("total_count", 101) <= 100, "CI job evidence exceeds the supported bound.")
    for gate in gates:
        matching = [job for job in jobs["jobs"] if job.get("name") == gate]
        require(len(matching) == 1 and matching[0].get("conclusion") == "success", f"Required CI job is not successful: {gate}")
    return run


def verify_trusted_main(api, revision, digest, run_id, attempt):
    validate_inputs(revision, digest, run_id, attempt)
    run = verify_run(api, run_id, revision, "build-and-verify.yml", (
        "Required quality gate", "Publish immutable application image",
    ), attempt)
    comparison = api.request(f"/compare/{revision}...main")
    require(
        comparison.get("status") in ("ahead", "identical")
        and comparison.get("merge_base_commit", {}).get("sha") == revision,
        "The selected revision is not on current main.",
    )
    security_runs = api.request(
        f"/actions/workflows/security.yml/runs?head_sha={revision}&branch=main&event=push&per_page=1"
    )["workflow_runs"]
    require(len(security_runs) == 1, "Trusted-main security evidence is absent.")
    security_id = security_runs[0]["id"]
    verify_run(api, security_id, revision, "security.yml", ("Required security gate",))
    return run, security_id


def verify_publication(api, run, revision, digest):
    name = f"application-image-publication-{revision}"
    artifacts = api.request(f"/actions/runs/{run['id']}/artifacts?per_page=100")
    require(artifacts.get("total_count", 101) <= 100, "Publication evidence exceeds the supported bound.")
    matches = [item for item in artifacts["artifacts"] if item["name"] == name]
    require(len(matches) == 1 and not matches[0]["expired"], "The retained publication record is absent/expired.")
    artifact = matches[0]
    require(artifact["created_at"] >= run["run_started_at"], "Publication evidence predates the current run attempt.")
    archive = api.request(f"/actions/artifacts/{artifact['id']}/zip", archive=True)
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        require(bundle.namelist() == ["published-image.txt"], "Unexpected publication archive contents.")
        require(bundle.getinfo("published-image.txt").file_size <= 64 * 1024, "Publication record is oversized.")
        record = bundle.read("published-image.txt").decode("utf-8").splitlines()
    require(
        [line for line in record if line.startswith("tag=")] == [f"tag={IMAGE_REPOSITORY}:{revision}"]
        and [line for line in record if line.startswith("digest=")] == [f"digest={IMAGE_REPOSITORY}@{digest}"],
        "The supplied digest/revision does not match the validated publication record.",
    )


def verify_environment(api):
    environment = api.request("/environments/dev")
    rules = environment.get("protection_rules", [])
    reviewers = [rule for rule in rules if rule.get("type") == "required_reviewers"]
    require(
        len(reviewers) == 1
        and reviewers[0].get("prevent_self_review") is False
        and [(item.get("type"), item.get("reviewer", {}).get("login")) for item in reviewers[0].get("reviewers", [])] == [("User", "rubhern")]
        and (environment.get("deployment_branch_policy") or {}).get("custom_branch_policies") is True,
        "dev must require owner approval and restrict deployments to main.",
    )
    policies = api.request("/environments/dev/deployment-branch-policies?per_page=100")
    require(
        policies.get("total_count") == 1
        and [(item.get("name"), item.get("type")) for item in policies["branch_policies"]] == [("main", "branch")],
        "dev must allow only the main branch, with no tag policy.",
    )


def is_deployment_contract(path):
    return not path.endswith(".md") and (
        path.startswith(("deploy/private-dev/", "docker/keycloak/", "docker/postgres/", "tools/catalogue-localization/"))
        or path in (
            "scripts/private_dev_promotion.py", "scripts/validate-private-dev-runtime.sh",
            "scripts/private-dev-metrics-check.py", "scripts/private-dev-logs-check.py",
            "scripts/provision-grafana-reader.sh",
        )
    )


def deployment_contract(repository, revision, installed=False):
    tree = subprocess.check_output(
        ["git", "ls-tree", "-r", "-z", revision], cwd=repository,
    )
    entries = []
    for entry in tree.split(b"\0"):
        if not entry:
            continue
        metadata, path = entry.split(b"\t", 1)
        if is_deployment_contract(path.decode("utf-8")):
            require(metadata.split()[1] == b"blob" and metadata.split()[0] in (b"100644", b"100755"),
                    "Deployment contracts must be regular Git files.")
            entries.append(entry)
    require(entries, "No deployment contract was found.")
    if installed:
        paths = [entry.split(b"\t", 1)[1].decode("utf-8") for entry in entries]
        status = subprocess.check_output(
            ["git", "status", "--porcelain", "--untracked-files=all"], cwd=repository,
        )
        require(not status, "The installed deployment checkout must be clean.")
        # Verify actual bytes as well: index assume-unchanged/skip-worktree flags
        # must never hide drift in the privileged installed deployment code.
        for path in paths:
            file = Path(repository) / path
            require(file.is_file() and not file.is_symlink(), "Installed contract file is missing or unsafe.")
            blob = subprocess.check_output(["git", "hash-object", "--", path], cwd=repository).strip()
            mode, _, expected = next(entry.split(b"\t", 1)[0].split() for entry in entries if entry.endswith(b"\t" + path.encode()))
            require(blob == expected, "Installed deployment contract bytes differ from Git.")
            require(bool(file.stat().st_mode & 0o111) == (mode == b"100755"),
                    "Installed deployment contract executable permissions differ from Git.")
    return contract_hash(entries)


def contract_hash(entries):
    return hashlib.sha256(b"\0".join(sorted(entries, key=lambda entry: entry.split(b"\t", 1)[1]))).hexdigest()


def remote_deployment_contract(api, revision):
    tree = api.request(f"/git/trees/{revision}?recursive=1")
    require(not tree.get("truncated", True) and len(tree["tree"]) <= 10000,
            "Source tree exceeds the supported evidence bound; promotion refused.")
    entries = []
    for item in tree["tree"]:
        if item["type"] == "tree":
            continue
        if is_deployment_contract(item["path"]):
            require(item["type"] == "blob" and item["mode"] in ("100644", "100755"),
                    "Remote deployment contracts must be regular Git files.")
            entries.append(f"{item['mode']} blob {item['sha']}\t{item['path']}".encode())
    require(entries, "The source revision has no deployment contract.")
    return contract_hash(entries)


def receipt_summary(receipt, revision, digest, run_id, attempt):
    require(receipt.get("sourceRevision") == revision
            and receipt.get("image") == f"{IMAGE_REPOSITORY}@{digest}"
            and receipt.get("sourceRunId") == int(run_id)
            and receipt.get("sourceRunAttempt") == int(attempt),
            "The deployment receipt does not match the selected artifact/run.")
    require(receipt.get("outcome") in ("success", "failure")
            and re.fullmatch(r"[a-z-]{1,100}", receipt.get("phase", "")),
            "The receipt has an invalid outcome/phase.")
    version = receipt.get("applicationVersion") or "not-reported"
    migration = receipt.get("migrationVersion") or "not-reported"
    require(re.fullmatch(r"[0-9A-Za-z.-]{1,100}", version)
            and re.fullmatch(r"[0-9A-Za-z.-]{1,100}", migration),
            "The receipt has invalid version metadata.")
    require(receipt["outcome"] != "success" or receipt["phase"] == "complete",
            "A successful receipt must record the complete deployment phase.")
    return (
        f"### Private-dev application promotion\n\n"
        f"- Outcome: **{receipt['outcome']}**; phase: `{receipt['phase']}`\n"
        f"- Artifact: `{IMAGE_REPOSITORY}@{digest}`\n"
        f"- Application: `{version}`; source: `{revision}`; migration: `{migration}`\n"
        f"- [Validated source CI](https://github.com/{REPOSITORY}/actions/runs/{run_id}) (attempt {attempt})\n"
        "The retained receipt points to the original protected host evidence. "
        "Product acceptance and named release remain owner actions.\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--installed-contract", action="store_true", help="Print the clean installed HEAD fingerprint; never acknowledges a runtime rollout.")
    parser.add_argument("--receipt", type=Path, help="Validate and summarize a sanitized SSH receipt without network access.")
    parser.add_argument("--revision")
    parser.add_argument("--digest")
    parser.add_argument("--run-id")
    parser.add_argument("--attempt")
    args = parser.parse_args()
    if args.installed_contract:
        print(deployment_contract(Path(__file__).resolve().parents[1], "HEAD", installed=True))
        return
    require(all((args.revision, args.digest, args.run_id, args.attempt)), "Revision, digest, source run and attempt are required.")
    validate_inputs(args.revision, args.digest, args.run_id, args.attempt)
    if args.receipt:
        receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
        summary = receipt_summary(receipt, args.revision, args.digest, args.run_id, args.attempt)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
                output.write(summary)
        else:
            print(summary)
        require(receipt["outcome"] == "success", "The private host recorded a failed deployment; inspect its protected evidence.")
        return
    api = GitHub(os.environ.get("GH_TOKEN"))
    verify_environment(api)
    run, security_id = verify_trusted_main(api, args.revision, args.digest, args.run_id, args.attempt)
    verify_publication(api, run, args.revision, args.digest)
    contract = deployment_contract(Path(__file__).resolve().parents[1], args.revision)
    result = {"contract": contract, "securityRunId": security_id}
    print(json.dumps(result))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"contract={contract}\n")


if __name__ == "__main__":
    try:
        main()
    except (Refused, OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        # Do not print remote error bodies, URLs, headers or credentials.
        print(str(error) if isinstance(error, Refused) else "Promotion evidence could not be verified.", file=sys.stderr)
        sys.exit(1)
