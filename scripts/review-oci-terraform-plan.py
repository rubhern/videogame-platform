#!/usr/bin/env python3
"""Fail closed unless an OCI Terraform plan matches the reviewed free envelope."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sys
from typing import Any, Iterable


ALLOWED_RESOURCE_TYPES = {
    "oci_core_instance",
    "oci_core_internet_gateway",
    "oci_core_network_security_group",
    "oci_core_network_security_group_security_rule",
    "oci_core_route_table",
    "oci_core_security_list",
    "oci_core_subnet",
    "oci_core_vcn",
    "oci_core_volume",
    "oci_core_volume_attachment",
    "oci_identity_compartment",
    "oci_identity_dynamic_group",
    "oci_identity_policy",
    "oci_kms_key",
    "oci_kms_vault",
    "oci_limits_quota",
    "oci_monitoring_alarm",
    "oci_objectstorage_bucket",
    "oci_objectstorage_object_lifecycle_policy",
    "oci_ons_notification_topic",
}
REQUIRED_SHAPE = "VM.Standard.A1.Flex"
MAX_OCPUS = 2
MAX_MEMORY_GB = 12
MAX_BLOCK_STORAGE_GB = 150
MAX_OBJECT_STORAGE_BYTES = 5_000_000_000
BALANCED_BLOCK_VOLUME_VPUS_PER_GB = 10
MAX_EVIDENCE_AGE = timedelta(hours=24)
ALWAYS_FREE_LIMITS = {
    "a1_ocpus": 2,
    "a1_memory_gb": 12,
    "block_storage_gb": 200,
    "object_storage_bytes": 20_000_000_000,
}


class GateFailure(Exception):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GateFailure(f"cannot read JSON from {path}: {error}") from error
    if not isinstance(value, dict):
        raise GateFailure(f"{path} must contain a JSON object")
    return value


def parse_timestamp(value: Any) -> datetime:
    if not isinstance(value, str):
        raise GateFailure("eligibility evidence must contain generated_at")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise GateFailure("eligibility evidence generated_at is not ISO-8601") from error
    if parsed.tzinfo is None:
        raise GateFailure("eligibility evidence generated_at must include a timezone")
    return parsed.astimezone(timezone.utc)


def validate_evidence(evidence: dict[str, Any], now: datetime) -> None:
    if evidence.get("schema_version") != 2:
        raise GateFailure("eligibility evidence schema version is unsupported")
    if evidence.get("verdict") != "PASS":
        raise GateFailure("live OCI eligibility evidence did not pass")
    if evidence.get("account_mode") not in {"always-free", "pay-as-you-go", "trial"}:
        raise GateFailure("unknown or unverified OCI account mode is prohibited")
    if evidence.get("eligibility_basis") != "always-free-only":
        raise GateFailure("eligibility evidence must use the permanent Always Free envelope")
    if evidence.get("promotional_credits_excluded") is not True:
        raise GateFailure("promotional credits must not count as eligibility or budget")
    generated_at = parse_timestamp(evidence.get("generated_at"))
    if evidence.get("official_terms_reviewed_at") != generated_at.date().isoformat():
        raise GateFailure("official OCI terms were not recorded on the evidence date")
    sources = evidence.get("official_sources")
    required_source_fragments = (
        "FreeTier/freetier.htm",
        "freetier_topic-Always_Free_Resources",
        "ResourceManager/Concepts/premium-jobs.htm",
    )
    if not isinstance(sources, list) or any(
        not any(isinstance(source, str) and fragment in source for source in sources)
        for fragment in required_source_fragments
    ):
        raise GateFailure("required official OCI Free Trial, Always Free, or premium-jobs source is absent")
    age = now - generated_at
    if age < timedelta(0) or age > MAX_EVIDENCE_AGE:
        raise GateFailure("live OCI eligibility evidence must be no more than 24 hours old")

    required_flags = (
        "home_region_verified",
        "a1_shape_verified",
        "ubuntu_arm_image_verified",
        "ordinary_resource_manager_jobs_verified",
        "premium_jobs_disabled",
    )
    missing = [name for name in required_flags if evidence.get(name) is not True]
    if missing:
        raise GateFailure("eligibility evidence is missing passed controls: " + ", ".join(missing))

    headroom = evidence.get("always_free_headroom")
    if not isinstance(headroom, dict):
        raise GateFailure("eligibility evidence is missing permanent Always Free headroom")
    observed_status = evidence.get("observed_resource_status")
    if not isinstance(observed_status, dict):
        raise GateFailure("eligibility evidence is missing observed resource status")
    minimums = {
        "a1_ocpus": MAX_OCPUS,
        "a1_memory_gb": MAX_MEMORY_GB,
        "block_storage_gb": MAX_BLOCK_STORAGE_GB,
        "object_storage_bytes": MAX_OBJECT_STORAGE_BYTES,
    }
    for key, minimum in minimums.items():
        value = headroom.get(key)
        status = observed_status.get(key)
        if not isinstance(status, dict):
            raise GateFailure(f"eligibility evidence is missing observed {key} status")
        used = status.get("used")
        available = status.get("available")
        if (
            not isinstance(value, (int, float))
            or isinstance(value, bool)
            or not isinstance(used, (int, float))
            or isinstance(used, bool)
            or used < 0
            or not isinstance(available, (int, float))
            or isinstance(available, bool)
            or available < 0
        ):
            raise GateFailure(f"observed {key} status must contain non-negative numeric values")
        permanent_headroom = min(available, max(0, ALWAYS_FREE_LIMITS[key] - used))
        if value != permanent_headroom:
            raise GateFailure(f"verified {key} headroom is not derived from the permanent envelope")
        if value < minimum:
            raise GateFailure(f"insufficient verified {key} headroom for the reviewed plan")

    job_status = evidence.get("resource_manager_job_status")
    if not isinstance(job_status, dict):
        raise GateFailure("eligibility evidence is missing Resource Manager job status")
    ordinary_jobs = job_status.get("ordinary")
    premium_jobs = job_status.get("premium")
    if not isinstance(ordinary_jobs, dict) or not isinstance(premium_jobs, dict):
        raise GateFailure("Resource Manager ordinary and premium job status must be present")
    for name, status in (("ordinary", ordinary_jobs), ("premium", premium_jobs)):
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
            raise GateFailure(f"Resource Manager {name} job status must contain non-negative numbers")
    ordinary_headroom = min(
        ordinary_jobs["available"],
        max(0, 2 - ordinary_jobs["used"]),
    )
    if ordinary_headroom < 1:
        raise GateFailure("no ordinary Resource Manager job remains within the permanent free limit")
    if premium_jobs["used"] != 0 or premium_jobs["available"] != 0:
        raise GateFailure("effective premium Resource Manager job capacity must be zero")


def iter_planned_resources(module: dict[str, Any]) -> Iterable[dict[str, Any]]:
    for resource in module.get("resources", []):
        if isinstance(resource, dict):
            yield resource
    for child in module.get("child_modules", []):
        if isinstance(child, dict):
            yield from iter_planned_resources(child)


def resources_by_type(plan: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    root = plan.get("planned_values", {}).get("root_module")
    if not isinstance(root, dict):
        raise GateFailure("plan JSON has no planned_values.root_module")
    result: dict[str, list[dict[str, Any]]] = {}
    for resource in iter_planned_resources(root):
        resource_type = resource.get("type")
        if isinstance(resource_type, str):
            result.setdefault(resource_type, []).append(resource)
    return result


def validate_actions(
    plan_path: Path,
    plan: dict[str, Any],
    approval_path: Path | None,
) -> None:
    destructive_addresses: list[str] = []
    for change in plan.get("resource_changes", []):
        if not isinstance(change, dict):
            continue
        resource_type = change.get("type")
        if resource_type not in ALLOWED_RESOURCE_TYPES:
            raise GateFailure(f"unapproved or potentially paid resource type: {resource_type}")
        actions = change.get("change", {}).get("actions", [])
        if "delete" in actions:
            destructive_addresses.append(str(change.get("address", "unknown")))

    if not destructive_addresses:
        return
    if approval_path is None:
        raise GateFailure(
            "destructive plan requires a separate owner approval bound to this exact plan: "
            + ", ".join(sorted(destructive_addresses))
        )
    approval = load_json(approval_path)
    digest = hashlib.sha256(plan_path.read_bytes()).hexdigest()
    if approval.get("owner_approved") is not True or approval.get("plan_sha256") != digest:
        raise GateFailure("destructive approval is absent or does not match the exact plan JSON")


def only_values(resources: list[dict[str, Any]], resource_type: str) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    for resource in resources:
        value = resource.get("values")
        if not isinstance(value, dict):
            raise GateFailure(f"{resource_type} has no inspectable planned values")
        values.append(value)
    return values


def validate_envelope(resources: dict[str, list[dict[str, Any]]]) -> None:
    unapproved = sorted(set(resources) - ALLOWED_RESOURCE_TYPES)
    if unapproved:
        raise GateFailure("plan contains unapproved resource types: " + ", ".join(unapproved))

    expected_counts = {
        "oci_core_instance": 1,
        "oci_core_internet_gateway": 1,
        "oci_core_network_security_group": 1,
        "oci_core_network_security_group_security_rule": 1,
        "oci_core_route_table": 1,
        "oci_core_security_list": 1,
        "oci_core_subnet": 1,
        "oci_core_vcn": 1,
        "oci_core_volume": 1,
        "oci_core_volume_attachment": 1,
        "oci_identity_compartment": 1,
        "oci_identity_dynamic_group": 1,
        "oci_identity_policy": 1,
        "oci_kms_key": 1,
        "oci_kms_vault": 1,
        "oci_limits_quota": 5,
        "oci_monitoring_alarm": 2,
        "oci_objectstorage_bucket": 1,
        "oci_objectstorage_object_lifecycle_policy": 1,
        "oci_ons_notification_topic": 1,
    }
    for resource_type, expected_count in expected_counts.items():
        actual_count = len(resources.get(resource_type, []))
        if actual_count != expected_count:
            raise GateFailure(
                f"{resource_type} count is {actual_count}; expected {expected_count}"
            )

    instances = only_values(resources.get("oci_core_instance", []), "oci_core_instance")
    if len(instances) != 1:
        raise GateFailure("plan must contain exactly one compute instance")
    instance = instances[0]
    if instance.get("shape") != REQUIRED_SHAPE:
        raise GateFailure("compute shape is not the approved Ampere A1 shape")
    shape_config = instance.get("shape_config")
    if not isinstance(shape_config, list) or len(shape_config) != 1:
        raise GateFailure("A1 shape_config must be fully known in the plan")
    if shape_config[0].get("ocpus") != MAX_OCPUS:
        raise GateFailure("A1 OCPU allocation is not exactly 2")
    if shape_config[0].get("memory_in_gbs") != MAX_MEMORY_GB:
        raise GateFailure("A1 memory allocation is not exactly 12 GB")
    vnics = instance.get("create_vnic_details")
    if not isinstance(vnics, list) or len(vnics) != 1 or vnics[0].get("assign_public_ip") is not True:
        raise GateFailure("the reviewed egress-only ephemeral public IP topology changed")
    source_details = instance.get("source_details")
    if not isinstance(source_details, list) or len(source_details) != 1:
        raise GateFailure("boot-volume configuration must be fully known")
    if source_details[0].get("boot_volume_size_in_gbs") != 50:
        raise GateFailure("boot volume must be exactly 50 GB")
    if (
        source_details[0].get("boot_volume_vpus_per_gb")
        != BALANCED_BLOCK_VOLUME_VPUS_PER_GB
    ):
        raise GateFailure("boot volume performance must remain at 10 VPUs/GB")

    volumes = only_values(resources.get("oci_core_volume", []), "oci_core_volume")
    if len(volumes) != 1 or volumes[0].get("size_in_gbs") != 100:
        raise GateFailure("plan must contain exactly one 100 GB data volume")
    if volumes[0].get("vpus_per_gb") != BALANCED_BLOCK_VOLUME_VPUS_PER_GB:
        raise GateFailure("data volume performance must remain at 10 VPUs/GB")
    if volumes[0].get("is_auto_tune_enabled") is not False:
        raise GateFailure("block-volume performance auto-tuning is prohibited")
    total_block = source_details[0]["boot_volume_size_in_gbs"] + volumes[0]["size_in_gbs"]
    if total_block != MAX_BLOCK_STORAGE_GB:
        raise GateFailure("combined boot and data block storage must be exactly 150 GB")

    buckets = only_values(resources.get("oci_objectstorage_bucket", []), "oci_objectstorage_bucket")
    if len(buckets) != 1:
        raise GateFailure("plan must contain exactly one Object Storage bucket")
    bucket = buckets[0]
    expected_bucket = {
        "access_type": "NoPublicAccess",
        "auto_tiering": "Disabled",
        "storage_tier": "Standard",
        "versioning": "Enabled",
    }
    for key, expected in expected_bucket.items():
        if bucket.get(key) != expected:
            raise GateFailure(f"backup bucket {key} must remain {expected}")

    lifecycle_values = only_values(
        resources.get("oci_objectstorage_object_lifecycle_policy", []),
        "oci_objectstorage_object_lifecycle_policy",
    )[0]
    lifecycle_rules = lifecycle_values.get("rules")
    if not isinstance(lifecycle_rules, list):
        raise GateFailure("backup lifecycle rules must be fully known")
    actual_lifecycle = {
        (
            rule.get("target"),
            rule.get("action"),
            rule.get("time_amount"),
            rule.get("time_unit"),
            rule.get("is_enabled"),
        )
        for rule in lifecycle_rules
        if isinstance(rule, dict)
    }
    expected_lifecycle = {
        ("objects", "DELETE", 14, "DAYS", True),
        ("previous-object-versions", "DELETE", 14, "DAYS", True),
        ("multipart-uploads", "ABORT", 1, "DAYS", True),
    }
    if actual_lifecycle != expected_lifecycle:
        raise GateFailure("backup lifecycle must delete current/previous backups and abort uploads")

    vaults = only_values(resources.get("oci_kms_vault", []), "oci_kms_vault")
    keys = only_values(resources.get("oci_kms_key", []), "oci_kms_key")
    if len(vaults) != 1 or vaults[0].get("vault_type") != "DEFAULT":
        raise GateFailure("exactly one non-private DEFAULT vault is required")
    if len(keys) != 1 or keys[0].get("protection_mode") != "SOFTWARE":
        raise GateFailure("exactly one Always Free software-protected key is required")

    security_lists = only_values(resources.get("oci_core_security_list", []), "oci_core_security_list")
    if len(security_lists) != 1 or security_lists[0].get("ingress_security_rules") not in (None, []):
        raise GateFailure("public ingress in the subnet security list is prohibited")
    nsg_rules = only_values(
        resources.get("oci_core_network_security_group_security_rule", []),
        "oci_core_network_security_group_security_rule",
    )
    if not nsg_rules or any(rule.get("direction") != "EGRESS" for rule in nsg_rules):
        raise GateFailure("network security groups may contain egress rules only")

    quota_values = only_values(resources.get("oci_limits_quota", []), "oci_limits_quota")
    statements = {
        statement
        for quota in quota_values
        for statement in quota.get("statements", [])
        if isinstance(statement, str)
    }
    required_fragments = (
        "standard-a1-core-count to 2",
        "standard-a1-memory-count to 12",
        "total-storage-gb to 150",
        f"storage-bytes to {MAX_OBJECT_STORAGE_BYTES}",
        "zero auto-scaling quotas",
        "zero kms quota virtual-private-vault-count",
    )
    for fragment in required_fragments:
        if not any(fragment in statement for statement in statements):
            raise GateFailure(f"required hard quota is absent: {fragment}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-json", type=Path, required=True)
    parser.add_argument("--eligibility-evidence", type=Path, required=True)
    parser.add_argument("--destructive-approval", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        plan = load_json(args.plan_json)
        evidence = load_json(args.eligibility_evidence)
        validate_evidence(evidence, datetime.now(timezone.utc))
        validate_actions(args.plan_json, plan, args.destructive_approval)
        validate_envelope(resources_by_type(plan))
    except GateFailure as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 1
    print("PASS: live eligibility and structural zero-cost Terraform plan gates passed.")
    print("No apply is authorized; owner review of the complete plan remains mandatory.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
