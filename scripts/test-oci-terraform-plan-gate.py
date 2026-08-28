#!/usr/bin/env python3
"""Behavior tests for the fail-closed OCI plan reviewer."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import importlib.util
from pathlib import Path
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "review_oci_plan", ROOT / "scripts" / "review-oci-terraform-plan.py"
)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

COLLECT_SPEC = importlib.util.spec_from_file_location(
    "collect_oci_evidence", ROOT / "scripts" / "collect-oci-free-tier-evidence.py"
)
assert COLLECT_SPEC and COLLECT_SPEC.loader
COLLECT_MODULE = importlib.util.module_from_spec(COLLECT_SPEC)
COLLECT_SPEC.loader.exec_module(COLLECT_MODULE)


def resource(resource_type: str, values: dict) -> dict:
    return {"type": resource_type, "values": values}


def safe_plan() -> dict:
    compartment = "videogame-platform-dev"
    quota_fragments = [
        f"set compute-core quota standard-a1-core-count to 2 in compartment {compartment}",
        f"set compute-memory quota standard-a1-memory-count to 12 in compartment {compartment}",
        f"set block-storage quota total-storage-gb to 150 in compartment {compartment}",
        f"set object-storage quota storage-bytes to 5000000000 in compartment {compartment}",
        f"zero auto-scaling quotas in compartment {compartment}",
        f"zero kms quota virtual-private-vault-count in compartment {compartment}",
    ]
    resources = [
        resource(
            "oci_core_instance",
            {
                "shape": "VM.Standard.A1.Flex",
                "shape_config": [{"ocpus": 2, "memory_in_gbs": 12}],
                "create_vnic_details": [{"assign_public_ip": True}],
                "source_details": [
                    {
                        "boot_volume_size_in_gbs": 50,
                        "boot_volume_vpus_per_gb": 10,
                    }
                ],
            },
        ),
        resource(
            "oci_core_volume",
            {
                "size_in_gbs": 100,
                "vpus_per_gb": 10,
                "is_auto_tune_enabled": False,
            },
        ),
        resource(
            "oci_objectstorage_bucket",
            {
                "access_type": "NoPublicAccess",
                "auto_tiering": "Disabled",
                "storage_tier": "Standard",
                "versioning": "Enabled",
            },
        ),
        resource("oci_kms_vault", {"vault_type": "DEFAULT"}),
        resource("oci_kms_key", {"protection_mode": "SOFTWARE"}),
        resource("oci_core_security_list", {"ingress_security_rules": []}),
        resource(
            "oci_core_network_security_group_security_rule", {"direction": "EGRESS"}
        ),
        resource("oci_limits_quota", {"statements": quota_fragments}),
        resource("oci_limits_quota", {"statements": []}),
        resource("oci_limits_quota", {"statements": []}),
        resource("oci_limits_quota", {"statements": []}),
        resource("oci_limits_quota", {"statements": []}),
        resource(
            "oci_objectstorage_object_lifecycle_policy",
            {
                "rules": [
                    {
                        "target": "objects",
                        "action": "DELETE",
                        "time_amount": 14,
                        "time_unit": "DAYS",
                        "is_enabled": True,
                    },
                    {
                        "target": "previous-object-versions",
                        "action": "DELETE",
                        "time_amount": 14,
                        "time_unit": "DAYS",
                        "is_enabled": True,
                    },
                    {
                        "target": "multipart-uploads",
                        "action": "ABORT",
                        "time_amount": 1,
                        "time_unit": "DAYS",
                        "is_enabled": True,
                    },
                ]
            },
        ),
    ]
    singleton_placeholders = (
        "oci_core_internet_gateway",
        "oci_core_network_security_group",
        "oci_core_route_table",
        "oci_core_subnet",
        "oci_core_vcn",
        "oci_core_volume_attachment",
        "oci_identity_compartment",
        "oci_identity_dynamic_group",
        "oci_identity_policy",
        "oci_ons_notification_topic",
    )
    resources.extend(resource(resource_type, {}) for resource_type in singleton_placeholders)
    resources.extend(resource("oci_monitoring_alarm", {}) for _ in range(2))
    changes = [
        {
            "address": f"test.{item['type']}",
            "type": item["type"],
            "change": {"actions": ["create"]},
        }
        for item in resources
    ]
    return {
        "planned_values": {"root_module": {"resources": resources}},
        "resource_changes": changes,
    }


def safe_evidence() -> dict:
    return {
        "schema_version": 2,
        "verdict": "PASS",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "official_terms_reviewed_at": datetime.now(timezone.utc).date().isoformat(),
        "official_sources": [
            "https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm",
            "https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm",
            "https://docs.oracle.com/en-us/iaas/Content/ResourceManager/Concepts/premium-jobs.htm",
        ],
        "account_mode": "always-free",
        "eligibility_basis": "always-free-only",
        "promotional_credits_excluded": True,
        "home_region_verified": True,
        "a1_shape_verified": True,
        "ubuntu_arm_image_verified": True,
        "ordinary_resource_manager_jobs_verified": True,
        "premium_jobs_disabled": True,
        "observed_resource_status": {
            "a1_ocpus": {"used": 0, "available": 24},
            "a1_memory_gb": {"used": 0, "available": 100},
            "block_storage_gb": {"used": 50, "available": 950},
            "object_storage_bytes": {
                "used": 15_000_000_000,
                "available": 100_000_000_000,
            },
        },
        "resource_manager_job_status": {
            "ordinary": {"used": 0, "available": 5},
            "premium": {"used": 0, "available": 0},
        },
        "always_free_headroom": {
            "a1_ocpus": 2,
            "a1_memory_gb": 12,
            "block_storage_gb": 150,
            "object_storage_bytes": 5_000_000_000,
        },
    }


def expect_blocked(name: str, plan: dict, evidence: dict) -> None:
    try:
        MODULE.validate_evidence(evidence, datetime.now(timezone.utc))
        with tempfile.NamedTemporaryFile() as plan_file:
            MODULE.validate_actions(Path(plan_file.name), plan, None)
        MODULE.validate_envelope(MODULE.resources_by_type(plan))
    except MODULE.GateFailure:
        print(f"PASS {name:<30} blocked")
        return
    raise AssertionError(f"{name} was not blocked")


def main() -> None:
    assert "trial" in COLLECT_MODULE.SUPPORTED_ACCOUNT_MODES
    assert MODULE.ALWAYS_FREE_LIMITS == {
        key: COLLECT_MODULE.ALWAYS_FREE_LIMITS[key] for key in MODULE.ALWAYS_FREE_LIMITS
    }
    permanent_headroom = COLLECT_MODULE.calculate_always_free_headroom(
        {
            "a1_ocpus": {"used": 0, "available": 24},
            "a1_memory_gb": {"used": 0, "available": 100},
            "block_storage_gb": {"used": 50, "available": 950},
            "object_storage_bytes": {"used": 15_000_000_000, "available": 100_000_000_000},
        }
    )
    assert permanent_headroom == {
        "a1_ocpus": 2.0,
        "a1_memory_gb": 12.0,
        "block_storage_gb": 150.0,
        "object_storage_bytes": 5_000_000_000.0,
    }
    assert COLLECT_MODULE.premium_jobs_are_disabled({"used": 0.0, "available": 0.0})
    assert not COLLECT_MODULE.premium_jobs_are_disabled({"used": 1.0, "available": 0.0})
    print("PASS permanent-free-headroom         ignores promotional capacity")
    print("PASS consumed-premium-capacity       blocked")

    plan = safe_plan()
    evidence = safe_evidence()
    MODULE.validate_evidence(evidence, datetime.now(timezone.utc))
    with tempfile.NamedTemporaryFile() as plan_file:
        MODULE.validate_actions(Path(plan_file.name), plan, None)
    MODULE.validate_envelope(MODULE.resources_by_type(plan))
    print("PASS safe-plan                     accepted")

    paid_shape = deepcopy(plan)
    paid_shape["planned_values"]["root_module"]["resources"][0]["values"]["shape"] = "VM.Standard.E5.Flex"
    expect_blocked("paid-shape", paid_shape, evidence)

    oversized = deepcopy(plan)
    oversized["planned_values"]["root_module"]["resources"][1]["values"]["size_in_gbs"] = 200
    expect_blocked("oversized-storage", oversized, evidence)

    high_performance_boot = deepcopy(plan)
    high_performance_boot["planned_values"]["root_module"]["resources"][0]["values"][
        "source_details"
    ][0]["boot_volume_vpus_per_gb"] = 20
    expect_blocked("paid-boot-performance", high_performance_boot, evidence)

    high_performance_data = deepcopy(plan)
    high_performance_data["planned_values"]["root_module"]["resources"][1]["values"][
        "vpus_per_gb"
    ] = 20
    expect_blocked("paid-data-performance", high_performance_data, evidence)

    autoscaling = deepcopy(plan)
    autoscaling["resource_changes"].append(
        {
            "address": "oci_autoscaling_auto_scaling_configuration.test",
            "type": "oci_autoscaling_auto_scaling_configuration",
            "change": {"actions": ["create"]},
        }
    )
    expect_blocked("autoscaling-resource", autoscaling, evidence)

    destructive = deepcopy(plan)
    destructive["resource_changes"][0]["change"]["actions"] = ["delete", "create"]
    expect_blocked("unapproved-destruction", destructive, evidence)

    trial = deepcopy(evidence)
    trial["account_mode"] = "trial"
    MODULE.validate_evidence(trial, datetime.now(timezone.utc))
    MODULE.validate_envelope(MODULE.resources_by_type(plan))
    print("PASS trial-always-free-plan         accepted")

    trial_credit_budget = deepcopy(trial)
    trial_credit_budget["promotional_credits_excluded"] = False
    expect_blocked("trial-credit-budget", plan, trial_credit_budget)

    promotional_headroom = deepcopy(trial)
    promotional_headroom["always_free_headroom"]["a1_ocpus"] = 4
    expect_blocked("trial-promotional-headroom", plan, promotional_headroom)

    unknown_account = deepcopy(evidence)
    unknown_account["account_mode"] = "unknown"
    expect_blocked("unknown-account", plan, unknown_account)

    trial_paid_shape = deepcopy(paid_shape)
    expect_blocked("trial-paid-shape", trial_paid_shape, trial)

    premium_jobs = deepcopy(trial)
    premium_jobs["resource_manager_job_status"]["premium"] = {
        "used": 1,
        "available": 0,
    }
    expect_blocked("consumed-premium-job", plan, premium_jobs)

    insufficient = deepcopy(evidence)
    insufficient["observed_resource_status"]["a1_ocpus"]["available"] = 1
    insufficient["always_free_headroom"]["a1_ocpus"] = 1
    expect_blocked("insufficient-headroom", plan, insufficient)

    print("OCI Terraform plan-gate tests passed.")


if __name__ == "__main__":
    main()
