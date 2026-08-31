#!/usr/bin/env python3
"""Collect read-only OCI account evidence immediately before a plan job."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any


OFFICIAL_SOURCES = [
    "https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm",
    "https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm",
    "https://docs.oracle.com/en-us/iaas/Content/Compute/References/computeshapes.htm",
    "https://docs.oracle.com/en-us/iaas/Content/ResourceManager/Concepts/resourcemanager.htm",
    "https://docs.oracle.com/en-us/iaas/Content/ResourceManager/Concepts/premium-jobs.htm",
    "https://docs.oracle.com/en-us/iaas/Content/ResourceManager/Reference/terraformversions.htm",
]
ALWAYS_FREE_LIMITS = {
    "a1_ocpus": 2,
    "a1_memory_gb": 12,
    "block_storage_gb": 200,
    "object_storage_bytes": 20_000_000_000,
    "ordinary_resource_manager_jobs": 2,
}
SUPPORTED_ACCOUNT_MODES = ("always-free", "trial", "pay-as-you-go")


class EvidenceError(Exception):
    pass


def oci_json(*arguments: str) -> Any:
    command = ["oci", *arguments, "--output", "json"]
    completed = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        diagnostic = completed.stderr.strip().splitlines()
        summary = diagnostic[-1] if diagnostic else "unknown OCI CLI failure"
        raise EvidenceError(f"read-only command failed ({' '.join(command[:4])} ...): {summary}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise EvidenceError("OCI CLI returned invalid JSON") from error


def data_value(payload: Any, key: str) -> Any:
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), dict):
        raise EvidenceError(f"OCI response has no data object for {key}")
    return payload["data"].get(key)


def limit_status(
    tenancy_ocid: str,
    service_name: str,
    limit_name: str,
    availability_domain: str | None = None,
) -> dict[str, float]:
    arguments = [
        "limits",
        "resource-availability",
        "get",
        "--compartment-id",
        tenancy_ocid,
        "--service-name",
        service_name,
        "--limit-name",
        limit_name,
    ]
    if availability_domain:
        arguments.extend(["--availability-domain", availability_domain])
    payload = oci_json(*arguments)
    available = data_value(payload, "fractional-availability")
    if not isinstance(available, (int, float)):
        available = data_value(payload, "available")
    used = data_value(payload, "fractional-usage")
    if not isinstance(used, (int, float)):
        used = data_value(payload, "used")
    if (
        not isinstance(available, (int, float))
        or isinstance(available, bool)
        or available < 0
        or not isinstance(used, (int, float))
        or isinstance(used, bool)
        or used < 0
    ):
        raise EvidenceError(f"OCI did not report non-negative usage and availability for {limit_name}")
    return {"available": float(available), "used": float(used)}


def calculate_always_free_headroom(
    resource_status: dict[str, dict[str, float]],
) -> dict[str, float]:
    headroom: dict[str, float] = {}
    for name in ("a1_ocpus", "a1_memory_gb", "block_storage_gb", "object_storage_bytes"):
        status = resource_status.get(name)
        if not isinstance(status, dict):
            raise EvidenceError(f"OCI status for {name} is missing")
        used = status.get("used")
        available = status.get("available")
        if (
            not isinstance(used, (int, float))
            or isinstance(used, bool)
            or used < 0
            or not isinstance(available, (int, float))
            or isinstance(available, bool)
            or available < 0
        ):
            raise EvidenceError(f"OCI status for {name} must contain non-negative numbers")
        permanent_remaining = max(0.0, float(ALWAYS_FREE_LIMITS[name]) - float(used))
        headroom[name] = min(float(available), permanent_remaining)
    return headroom


def premium_jobs_are_disabled(status: dict[str, float]) -> bool:
    return status.get("used") == 0 and status.get("available") == 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Runs read-only OCI CLI calls and writes local, ignored evidence."
    )
    parser.add_argument("--tenancy-ocid", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--availability-domain", required=True)
    parser.add_argument("--image-ocid", required=True)
    parser.add_argument(
        "--account-mode",
        required=True,
        choices=SUPPORTED_ACCOUNT_MODES,
        help="Explicit owner attestation; OCI exposes no reliable API field for this classification.",
    )
    parser.add_argument(
        "--official-terms-reviewed-on",
        required=True,
        help="UTC date (YYYY-MM-DD) on which the owner reviewed the official sources.",
    )
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def collect(args: argparse.Namespace) -> dict[str, Any]:
    current_date = datetime.now(timezone.utc).date().isoformat()
    if args.official_terms_reviewed_on != current_date:
        raise EvidenceError("official OCI terms must be reviewed again on the UTC preflight date")
    tenancy = oci_json("iam", "tenancy", "get", "--tenancy-id", args.tenancy_ocid)
    home_region_key = data_value(tenancy, "home-region-key")
    regions = oci_json("iam", "region", "list")
    region_rows = regions.get("data", []) if isinstance(regions, dict) else []
    home_regions = [
        row.get("name")
        for row in region_rows
        if isinstance(row, dict) and row.get("key") == home_region_key
    ]
    home_region_verified = home_regions == [args.region]

    shapes = oci_json(
        "compute",
        "shape",
        "list",
        "--compartment-id",
        args.tenancy_ocid,
        "--availability-domain",
        args.availability_domain,
        "--all",
    )
    shape_rows = shapes.get("data", []) if isinstance(shapes, dict) else []
    a1_shape_verified = any(
        isinstance(row, dict)
        and row.get("shape") == "VM.Standard.A1.Flex"
        and row.get("is-flexible") is True
        for row in shape_rows
    )

    image = oci_json("compute", "image", "get", "--image-id", args.image_ocid)
    image_data = image.get("data", {}) if isinstance(image, dict) else {}
    compatibility = oci_json(
        "compute",
        "image-shape-compatibility-entry",
        "list",
        "--image-id",
        args.image_ocid,
        "--all",
    )
    compatibility_rows = compatibility.get("data", []) if isinstance(compatibility, dict) else []
    ubuntu_arm_image_verified = (
        isinstance(image_data, dict)
        and "ubuntu" in str(image_data.get("operating-system", "")).lower()
        and any(
            isinstance(row, dict) and row.get("shape") == "VM.Standard.A1.Flex"
            for row in compatibility_rows
        )
    )

    resource_status = {
        "a1_ocpus": limit_status(
            args.tenancy_ocid,
            "compute",
            "standard-a1-core-count",
            args.availability_domain,
        ),
        "a1_memory_gb": limit_status(
            args.tenancy_ocid,
            "compute",
            "standard-a1-memory-count",
            args.availability_domain,
        ),
        "block_storage_gb": limit_status(
            args.tenancy_ocid,
            "block-storage",
            "total-storage-gb",
            args.availability_domain,
        ),
        "object_storage_bytes": limit_status(
            args.tenancy_ocid,
            "object-storage",
            "storage-bytes",
        ),
    }
    always_free_headroom = calculate_always_free_headroom(resource_status)
    ordinary_jobs = limit_status(
        args.tenancy_ocid,
        "resource-manager",
        "concurrent-job-count",
    )
    premium_jobs = limit_status(
        args.tenancy_ocid,
        "resource-manager",
        "premium-job-submission-count",
    )
    ordinary_job_headroom = min(
        ordinary_jobs["available"],
        max(
            0.0,
            ALWAYS_FREE_LIMITS["ordinary_resource_manager_jobs"] - ordinary_jobs["used"],
        ),
    )
    premium_jobs_disabled = premium_jobs_are_disabled(premium_jobs)

    checks = {
        "account_mode": args.account_mode,
        "eligibility_basis": "always-free-only",
        "promotional_credits_excluded": True,
        "home_region_verified": home_region_verified,
        "a1_shape_verified": a1_shape_verified,
        "ubuntu_arm_image_verified": ubuntu_arm_image_verified,
        "ordinary_resource_manager_jobs_verified": ordinary_job_headroom >= 1,
        "premium_jobs_disabled": premium_jobs_disabled,
        "observed_resource_status": resource_status,
        "resource_manager_job_status": {
            "ordinary": ordinary_jobs,
            "premium": premium_jobs,
        },
        "always_free_headroom": always_free_headroom,
    }
    pass_result = (
        args.account_mode in SUPPORTED_ACCOUNT_MODES
        and home_region_verified
        and a1_shape_verified
        and ubuntu_arm_image_verified
        and ordinary_job_headroom >= 1
        and premium_jobs_disabled
        and always_free_headroom["a1_ocpus"] >= 2
        and always_free_headroom["a1_memory_gb"] >= 12
        and always_free_headroom["block_storage_gb"] >= 150
        and always_free_headroom["object_storage_bytes"] >= 5_000_000_000
    )
    return {
        "schema_version": 2,
        "verdict": "PASS" if pass_result else "BLOCKED",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "official_terms_reviewed_at": args.official_terms_reviewed_on,
        "official_sources": OFFICIAL_SOURCES,
        "host_capacity_proven": False,
        "host_capacity_note": (
            "OCI exposes A1 service-limit headroom but does not guarantee physical host "
            "capacity before launch; #42 must stop on out-of-host-capacity without fallback."
        ),
        **checks,
    }


def main() -> int:
    args = parse_args()
    if not shutil_which("oci"):
        print("OCI CLI is required for the live read-only preflight.", file=sys.stderr)
        return 1
    os.environ["OCI_CLI_REGION"] = args.region
    try:
        evidence = collect(args)
    except EvidenceError as error:
        evidence = {
            "schema_version": 2,
            "verdict": "BLOCKED",
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "official_terms_reviewed_at": args.official_terms_reviewed_on,
            "official_sources": OFFICIAL_SOURCES,
            "failure": str(error),
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"OCI preflight verdict: {evidence['verdict']}")
    print(f"Local evidence: {args.output}")
    return 0 if evidence["verdict"] == "PASS" else 1


def shutil_which(command: str) -> str | None:
    # Kept local to avoid importing subprocess-unrelated helpers into the evidence data.
    from shutil import which

    return which(command)


if __name__ == "__main__":
    raise SystemExit(main())
