#!/usr/bin/env python3
"""Trusted-main evidence checks for private-dev promotion; no host mutations."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

REPOSITORY = "rubhern/videogame-platform"
IMAGE_REPOSITORY = f"ghcr.io/{REPOSITORY}"
API_ROOT = f"https://api.github.com/repos/{REPOSITORY}"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
READ_ATTEMPTS = 5
# Promotion may be dispatched as soon as a commit lands on main; discovery waits
# for that commit's own CI instead of refusing while it is still running.
CI_WAIT_SECONDS = 90 * 60
CI_POLL_SECONDS = 30


class Refused(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Refused(message)


def validate_inputs(revision, digest, run_id, attempt):
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", digest), "An immutable sha256 digest is required.")
    validate_source_run(revision, run_id, attempt)


def validate_source_run(revision, run_id, attempt):
    require(re.fullmatch(r"[0-9a-f]{40}", revision), "A full lowercase source SHA is required.")
    for value in (run_id, attempt):
        require(re.fullmatch(r"[1-9][0-9]{0,19}", str(value)), "CI run/attempt must be positive integers.")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, token=None):
        self.token = token

    def request(self, path, archive=False):
        # Retry only transient read failures, never rejected or invalid evidence.
        for attempt in range(READ_ATTEMPTS):
            last = attempt == READ_ATTEMPTS - 1
            try:
                return self._request_once(path, archive)
            except urllib.error.HTTPError as error:
                status = error.code
                error.close()
                if status not in (500, 502, 503, 504) or last:
                    raise Refused(f"GitHub evidence is unavailable (HTTP {status}); promotion refused.") from None
            except (urllib.error.URLError, TimeoutError):
                if last:
                    raise Refused("GitHub evidence is unavailable (network failure); promotion refused.") from None
            print("Transient evidence read failure; retrying bounded read.", file=sys.stderr)
            time.sleep(2 ** attempt)

    def _request_once(self, path, archive=False):
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
                raise
            location = error.headers.get("Location", "")
            error.close()
            require(location.startswith("https://"), "Artifact redirect must use HTTPS.")
            # Signed storage requests never receive the GitHub credential.
            response = urllib.request.urlopen(location, timeout=30)
        with response:
            data = response.read(MAX_RESPONSE_BYTES + 1)
        require(len(data) <= MAX_RESPONSE_BYTES, "GitHub evidence exceeded the bounded response size.")
        return data if archive else json.loads(data)


def trusted_run(api, run_id, revision, workflow, attempt=None):
    run = api.request(f"/actions/runs/{run_id}")
    require(
        run.get("id") == int(run_id)
        and (run.get("head_repository") or {}).get("full_name") == REPOSITORY
        and run.get("event") == "push"
        and run.get("head_branch") == "main"
        and run.get("head_sha") == revision
        and run.get("path") == f".github/workflows/{workflow}",
        f"{workflow} must be a trusted-main push for the selected revision.",
    )
    if attempt is not None:
        require(run.get("run_attempt") == int(attempt), "The selected CI run attempt is stale.")
    return run


def run_jobs(api, run_id):
    jobs = api.request(f"/actions/runs/{run_id}/jobs?filter=latest&per_page=100")
    require(jobs.get("total_count", 101) == len(jobs["jobs"]) <= 100,
            "CI job evidence is incomplete or exceeds the supported bound.")
    return jobs["jobs"]


def verify_run(api, run_id, revision, workflow, gates, attempt=None):
    run = trusted_run(api, run_id, revision, workflow, attempt)
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            f"{workflow} must be a successful trusted-main push for the selected revision.")
    jobs = run_jobs(api, run_id)
    for gate in gates:
        matching = [job for job in jobs if job.get("name") == gate]
        require(len(matching) == 1 and matching[0].get("conclusion") == "success", f"Required CI job is not successful: {gate}")
    return run


def verify_trusted_main(api, revision, digest, run_id, attempt):
    validate_inputs(revision, digest, run_id, attempt)
    run = verify_run(api, run_id, revision, "build-and-verify.yml", (
        "Required quality gate", "Publish immutable application image",
    ), attempt)
    verify_main_ancestry(api, revision)
    security = api.request(
        f"/actions/workflows/security.yml/runs?head_sha={revision}&branch=main&event=push&per_page=100"
    )
    runs = security["workflow_runs"]
    require(security.get("total_count") == len(runs) == 1, "Trusted-main security evidence is absent or ambiguous.")
    security_id = runs[0]["id"]
    verify_run(api, security_id, revision, "security.yml", ("Required security gate",), runs[0]["run_attempt"])
    return run, security_id


def verify_main_ancestry(api, revision):
    comparison = api.request(f"/compare/{revision}...main")
    require(
        comparison.get("status") in ("ahead", "identical")
        and comparison.get("merge_base_commit", {}).get("sha") == revision,
        "The selected revision is not on current main.",
    )


def verify_publication(api, run, revision, digest=None):
    # A release-only rerun may leave the image and its publication untouched.
    # Prefer job-level timestamps and require a new publication if the image job
    # changed. Older callers/retained evidence keep the run-attempt boundary.
    fresh_after = run["run_started_at"]
    jobs = run_jobs(api, run["id"])
    publications = [job for job in jobs if job.get("name") == "Publish immutable application image"]
    builds = [job for job in jobs if job.get("name") == "AMD64 application image"]
    if (len(publications) == len(builds) == 1 and publications[0].get("started_at")
            and builds[0].get("completed_at")):
        publication, build = publications[0], builds[0]
        require(build.get("conclusion") == "success" and publication.get("conclusion") == "success"
                and build["completed_at"] <= publication["started_at"],
                "Publication does not follow the latest successful application image build.")
        fresh_after = publication["started_at"]
    name = f"application-image-publication-{revision}"
    artifacts = api.request(f"/actions/runs/{run['id']}/artifacts?per_page=100")
    require(artifacts.get("total_count", 101) <= 100, "Publication evidence exceeds the supported bound.")
    matches = [item for item in artifacts["artifacts"] if item["name"] == name]
    require(len(matches) == 1 and not matches[0]["expired"], "The retained publication record is absent/expired.")
    artifact = matches[0]
    require(artifact["created_at"] >= fresh_after, "Publication evidence predates the current run attempt.")
    archive = api.request(f"/actions/artifacts/{artifact['id']}/zip", archive=True)
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        require(bundle.namelist() == ["published-image.txt"], "Unexpected publication archive contents.")
        require(bundle.getinfo("published-image.txt").file_size <= 64 * 1024, "Publication record is oversized.")
        record = bundle.read("published-image.txt").decode("utf-8").splitlines()
    tags = [line for line in record if line.startswith("tag=")]
    digests = [line for line in record if line.startswith("digest=")]
    require(tags == [f"tag={IMAGE_REPOSITORY}:{revision}"] and len(digests) == 1,
            "Publication must identify exactly one source tag and immutable image digest.")
    match = re.fullmatch(r"digest=" + re.escape(IMAGE_REPOSITORY) + r"@(sha256:[0-9a-f]{64})", digests[0])
    require(match is not None, "Publication must identify this repository's immutable OCI digest.")
    published_digest = match.group(1)
    require(digest is None or digest == published_digest,
            "The supplied digest/revision does not match the validated publication record.")
    return published_digest


def wait_for_main_ci(api, revision, timeout=CI_WAIT_SECONDS, interval=CI_POLL_SECONDS,
                     clock=time.monotonic, sleep=time.sleep):
    """Wait until the revision's trusted-main build and security runs have finished."""
    require(re.fullmatch(r"[0-9a-f]{40}", revision), "A full lowercase dispatch SHA is required.")
    deadline = clock() + timeout
    while True:
        pending = []
        for workflow in ("build-and-verify.yml", "security.yml"):
            runs = api.request(
                f"/actions/workflows/{workflow}/runs?head_sha={revision}&branch=main&event=push&per_page=100"
            )["workflow_runs"]
            if not runs or any(run.get("status") != "completed" for run in runs):
                pending.append(workflow)
                continue
            require(all(run.get("conclusion") == "success" for run in runs),
                    f"{workflow} finished without success for the dispatch SHA; promotion refused.")
        if not pending:
            return
        require(clock() < deadline,
                f"Trusted-main CI did not finish within {timeout // 60} minutes: {', '.join(pending)}.")
        print(f"Waiting for trusted-main CI: {', '.join(pending)}.", file=sys.stderr)
        sleep(interval)


def discover_promotion(api, revision):
    """Resolve a fully qualified SHA for production or the private-dev full-CI fallback."""
    require(re.fullmatch(r"[0-9a-f]{40}", revision), "A full lowercase dispatch SHA is required.")
    listing = api.request(
        f"/actions/workflows/build-and-verify.yml/runs?head_sha={revision}&branch=main&event=push&per_page=100"
    )
    # Do not filter on success: that could hide a newer failed/pending candidate
    # and silently fall back to an older successful run. Ambiguity is refused.
    require(listing.get("total_count") == 1 and len(listing["workflow_runs"]) == 1,
            "Dispatch SHA must have exactly one Build and verify push run; absent/ambiguous evidence refused.")
    candidate = listing["workflow_runs"][0]
    run_id, attempt = str(candidate["id"]), str(candidate["run_attempt"])
    validate_source_run(revision, run_id, attempt)
    run = verify_run(api, run_id, revision, "build-and-verify.yml", (
        "Required quality gate", "Publish immutable application image",
    ), attempt)
    digest = verify_publication(api, run, revision)
    # Re-read the current attempt after downloading publication evidence. A CI
    # rerun during discovery must not produce a stale successful selection.
    _, security_id = verify_trusted_main(api, revision, digest, run_id, attempt)
    return {"source_revision": revision, "image_digest": digest,
            "source_run_id": run_id, "source_run_attempt": attempt,
            "securityRunId": security_id}


# Reuse trusted-main jobs that also contribute to release qualification. Only this
# dev-only selection may admit a publication while the release workflow runs.
FAST_QUALITY = {
    "openapi": ("Documentation and API contracts",),
    "frontend": ("Frontend static, type, component and build checks",),
    "backend": ("Java backend, architecture and PostgreSQL integration",),
    "migrations": ("Fresh PostgreSQL 18 migration",),
    "identity": tuple(f"Real Keycloak OIDC BFF compatibility ({leg})" for leg in (
        "private-dev", "core", "renewal", "idle", "production", "personal-data",
        "personal-data-private", "social-sign-in",
    )),
    "private_dev_runtime": (
        "Private-dev topology and telemetry", "Private-dev alert rules and Grafana lifecycle",
        "Private-dev optimized Keycloak registration metrics",
        "Private-dev native Telegram routing against isolated mock",
        "Private-dev structured log collection and search",
    ),
    "localization_image": ("AMD64 localization helper image",),
}
FAST_SECURITY = {
    "dependencies": ("Dependency review",),
    "npm_dependencies": ("npm dependency audit",),
}
FAST_BASE = "Private-dev promotion protocol accepts a checked baseline."


def git_result(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=False)


def last_deployed_base(api, revision):
    listing = api.request("/actions/workflows/deploy-private-dev.yml/runs?"
                          "branch=main&event=workflow_dispatch&status=completed&per_page=1")
    runs = listing["workflow_runs"]
    if len(runs) != 1:
        return None
    previous = runs[0]
    base = previous.get("head_sha", "")
    if (previous.get("conclusion") != "success" or previous.get("head_branch") != "main"
            or previous.get("event") != "workflow_dispatch"
            or previous.get("path") != ".github/workflows/deploy-private-dev.yml"
            or (previous.get("head_repository") or {}).get("full_name") != REPOSITORY
            or (previous.get("actor") or {}).get("login") != "rubhern"
            or (previous.get("triggering_actor") or {}).get("login") != "rubhern"
            or not re.fullmatch(r"[0-9a-f]{40}", base)):
        return None
    deployment_jobs = [job for job in run_jobs(api, previous["id"])
                       if job.get("name") == "Owner-approved private-dev application promotion"]
    if len(deployment_jobs) != 1 or deployment_jobs[0].get("conclusion") != "success":
        return None
    ancestry = git_result("merge-base", "--is-ancestor", base, revision)
    require(ancestry.returncode != 1,
            "Dispatch SHA predates or diverges from the last successful private-dev promotion.")
    if ancestry.returncode != 0:
        return None
    host = git_result("show", f"{base}:deploy/private-dev/bin/promote-private-dev")
    return base if host.returncode == 0 and FAST_BASE in host.stdout else None


def fast_requirements(base, revision):
    # Compare every change since the last deployed version, including skipped
    # main revisions and deletions. The host independently checks this baseline.
    result = subprocess.run(["bash", "scripts/detect-ci-changes.sh", "--base", base, "--head", revision],
                            capture_output=True, text=True, check=False)
    require(result.returncode == 0, "Could not classify the private-dev deployment range.")
    changes = dict(line.split("=", 1) for line in result.stdout.splitlines())
    require(all(changes.get(key) in ("true", "false") for key in (*FAST_QUALITY, *FAST_SECURITY, "ci")),
            "Incomplete private-dev change classification.")
    if changes["ci"] == "true":
        return None
    quality = ["Detect affected CI areas", "Secret scan before application publication",
               "AMD64 application image", "Publish immutable application image"]
    security = ["Detect affected security areas", "Secret scan"]
    for expected, mapping in ((quality, FAST_QUALITY), (security, FAST_SECURITY)):
        for category, jobs in mapping.items():
            if changes[category] == "true":
                expected.extend(jobs)
    return quality, security


def fast_run(api, revision, workflow, required, expected=None):
    listing = api.request(f"/actions/workflows/{workflow}/runs?head_sha={revision}"
                          "&branch=main&event=push&per_page=100")
    candidates = listing["workflow_runs"]
    require(listing.get("total_count") == len(candidates) <= 1,
            f"{workflow} has ambiguous evidence for the dispatch SHA.")
    if not candidates:
        return None
    candidate = candidates[0]
    identity = str(candidate["id"]), str(candidate["run_attempt"])
    validate_source_run(revision, *identity)
    require(expected is None or identity == expected, f"{workflow} was rerun or replaced; select it again.")
    run = trusted_run(api, identity[0], revision, workflow, identity[1])
    require(run.get("conclusion") not in ("cancelled", "action_required", "startup_failure", "stale", "timed_out"),
            f"{workflow} did not yield usable fast-gate evidence.")
    jobs = run_jobs(api, run["id"])
    pending = False
    for gate in required:
        matches = [job for job in jobs if job.get("name") == gate]
        require(len(matches) <= 1, f"Ambiguous required private-dev check: {gate}")
        if not matches or matches[0].get("status") != "completed":
            require(run.get("status") != "completed", f"Required private-dev check is absent: {gate}")
            pending = True
        else:
            require(matches[0].get("conclusion") == "success", f"Required private-dev check failed: {gate}")
    return None if pending else run


def fast_promotion(api, revision, requirements, *, wait=False, expected_build=None, expected_security=None,
                   clock=time.monotonic, sleep=time.sleep):
    quality, security = requirements
    verify_main_ancestry(api, revision)
    deadline = clock() + CI_WAIT_SECONDS
    while True:
        build = fast_run(api, revision, "build-and-verify.yml", quality, expected_build)
        scan = fast_run(api, revision, "security.yml", security, expected_security)
        if build and scan:
            break
        require(wait and clock() < deadline, "The required private-dev checks are not ready.")
        print("Waiting for required private-dev checks and immutable image publication.", file=sys.stderr)
        sleep(min(CI_POLL_SECONDS, 10))
    digest = verify_publication(api, build, revision)
    build_id = str(build["id"]), str(build["run_attempt"])
    security_id = str(scan["id"]), str(scan["run_attempt"])
    # Reading the reports spans several API calls. Fail closed on any rerun or
    # cancellation before handing off the tuple to the next job.
    require(fast_run(api, revision, "build-and-verify.yml", quality, build_id)
            and fast_run(api, revision, "security.yml", security, security_id),
            "Private-dev evidence changed during discovery.")
    return {"source_revision": revision, "image_digest": digest,
            "source_run_id": build_id[0], "source_run_attempt": build_id[1],
            "security_run_id": security_id[0], "security_run_attempt": security_id[1]}


def verify_environment(api):
    environment = api.request("/environments/dev")
    rules = environment.get("protection_rules", [])
    require(
        all(rule.get("type") == "branch_policy" for rule in rules)
        and environment.get("can_admins_bypass") is False
        and (environment.get("deployment_branch_policy") or {}).get("custom_branch_policies") is True
        and (environment.get("deployment_branch_policy") or {}).get("protected_branches") is False,
        "dev must have no reviewer/wait/custom approval rules, disallow administrator bypass, and restrict deployments to main. Update GitHub Settings > Environments > dev.",
    )
    policies = api.request("/environments/dev/deployment-branch-policies?per_page=100")
    require(
        policies.get("total_count") == 1
        and [(item.get("name"), item.get("type")) for item in policies["branch_policies"]] == [("main", "branch")],
        "dev must allow only the main branch, with no tag policy.",
    )


def contract_hash(entries):
    # Shared Git-entry fingerprint used by the public-production operational tooling.
    return hashlib.sha256(b"\0".join(sorted(entries, key=lambda entry: entry.split(b"\t", 1)[1]))).hexdigest()


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
    parser.add_argument("--receipt", type=Path, help="Validate and summarize a sanitized SSH receipt without network access.")
    parser.add_argument("--discover", action="store_true", help="Derive CI run, current attempt and digest for the exact dispatch revision.")
    parser.add_argument("--revision")
    parser.add_argument("--digest")
    parser.add_argument("--run-id")
    parser.add_argument("--attempt")
    parser.add_argument("--private-dev", action="store_true", help="Use private-dev admission where the deployed baseline supports it.")
    parser.add_argument("--deployment-base", default="", help="Recheck the exact deployed baseline selected by discovery.")
    parser.add_argument("--security-run-id", default="")
    parser.add_argument("--security-run-attempt", default="")
    args = parser.parse_args()
    require(not args.discover or (args.revision and not any((args.digest, args.run_id, args.attempt, args.receipt,
                                                        args.deployment_base, args.security_run_id,
                                                        args.security_run_attempt))),
            "Discovery accepts only the dispatch revision; manual artifact/run overrides are forbidden.")
    if not args.discover:
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
    base = ""
    requirements = None
    if args.private_dev:
        base = (last_deployed_base(api, args.revision) or "") if args.discover else args.deployment_base
        if base:
            require(re.fullmatch(r"[0-9a-f]{40}", base), "Invalid private-dev deployment baseline.")
            requirements = fast_requirements(base, args.revision)
            require(args.discover or requirements is not None, "Private-dev classification changed during promotion.")
    if requirements is not None:
        result = fast_promotion(api, args.revision, requirements, wait=args.discover,
                                expected_build=None if args.discover else (args.run_id, args.attempt),
                                expected_security=None if args.discover else
                                (args.security_run_id, args.security_run_attempt))
        result["deployment_base"] = base
    else:
        if args.discover:
            wait_for_main_ci(api, args.revision)
        result = discover_promotion(api, args.revision)
    if not args.discover:
        require((result["image_digest"], result["source_run_id"], result["source_run_attempt"])
                == (args.digest, args.run_id, args.attempt),
                "The derived publication/run/attempt changed; promotion refused without reselection.")
    print(json.dumps(result))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for name in ("source_revision", "image_digest", "source_run_id", "source_run_attempt",
                         "deployment_base", "security_run_id", "security_run_attempt"):
                if name in result:
                    output.write(f"{name}={result[name]}\n")


if __name__ == "__main__":
    try:
        main()
    except (Refused, OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile) as error:
        # Do not print remote error bodies, URLs, headers or credentials.
        print(str(error) if isinstance(error, Refused) else "Promotion evidence could not be verified.", file=sys.stderr)
        sys.exit(1)
