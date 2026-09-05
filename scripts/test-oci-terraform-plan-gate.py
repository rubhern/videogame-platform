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
    quota_statement_groups = [
        [
            f"zero compute-core quotas in compartment {compartment}",
            f"set compute-core quota standard-a1-core-count to 2 in compartment {compartment}",
            f"set compute-core quota standard-a1-core-regional-count to 2 in compartment {compartment}",
        ],
        [
            f"zero compute-memory quotas in compartment {compartment}",
            f"set compute-memory quota standard-a1-memory-count to 12 in compartment {compartment}",
            f"set compute-memory quota standard-a1-memory-regional-count to 12 in compartment {compartment}",
        ],
        [
            f"zero compute quotas in compartment {compartment}",
            f"zero compute-management quotas in compartment {compartment}",
            f"zero auto-scaling quotas in compartment {compartment}",
        ],
        [
            f"set block-storage quota total-storage-gb to 150 in compartment {compartment}",
            f"set object-storage quota storage-bytes to 5000000000 in compartment {compartment}",
        ],
        [f"zero kms quota virtual-private-vault-count in compartment {compartment}"],
    ]
    resources = [
        resource(
            "oci_core_instance",
            {
                "shape": "VM.Standard.A1.Flex",
                "shape_config": [{"ocpus": 2, "memory_in_gbs": 12}],
                "create_vnic_details": [{"assign_public_ip": "true"}],
                "source_details": [
                    {
                        "boot_volume_size_in_gbs": "50",
                        "boot_volume_vpus_per_gb": "10",
                    }
                ],
            },
        ),
        resource(
            "oci_core_volume",
            {
                "size_in_gbs": "100",
                "vpus_per_gb": "10",
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
        resource("time_sleep", {"create_duration": "10m"}),
        resource("oci_core_security_list", {"ingress_security_rules": []}),
        resource(
            "oci_core_network_security_group_security_rule", {"direction": "EGRESS"}
        ),
        *[
            resource("oci_limits_quota", {"statements": statements})
            for statements in quota_statement_groups
        ],
        resource("oci_identity_compartment", {"name": compartment}),
        resource(
            "oci_objectstorage_object_lifecycle_policy",
            {
                "rules": [
                    {
                        "target": "objects",
                        "action": "DELETE",
                        "time_amount": "14",
                        "time_unit": "DAYS",
                        "is_enabled": True,
                    },
                    {
                        "target": "previous-object-versions",
                        "action": "DELETE",
                        "time_amount": "14",
                        "time_unit": "DAYS",
                        "is_enabled": True,
                    },
                    {
                        "target": "multipart-uploads",
                        "action": "ABORT",
                        "time_amount": "1",
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
        "oci_identity_dynamic_group",
        "oci_ons_notification_topic",
    )
    resources.extend(resource(resource_type, {}) for resource_type in singleton_placeholders)
    resources.extend(
        [
            resource("oci_identity_policy", {"name": f"{compartment}-runtime"}),
            resource(
                "oci_identity_policy",
                {
                    "name": f"{compartment}-object-storage-lifecycle",
                    "statements": [
                        f"Allow service objectstorage-eu-madrid-3 to manage object-family in compartment {compartment} where any {{request.permission='BUCKET_INSPECT', request.permission='BUCKET_READ', request.permission='OBJECT_INSPECT', request.permission='OBJECT_UPDATE_TIER', request.permission='OBJECT_DELETE', request.permission='OBJECT_VERSION_DELETE'}}"
                    ],
                },
            ),
        ]
    )
    resources.extend(resource("oci_monitoring_alarm", {"resolution": "1m"}) for _ in range(2))
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
        "schema_version": 4,
        "verdict": "PASS",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "official_terms_reviewed_at": datetime.now(timezone.utc).date().isoformat(),
        "official_sources": [
            "https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm",
            "https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm",
            "https://docs.oracle.com/en-us/iaas/Content/ResourceManager/Concepts/premium-jobs.htm",
            "https://docs.oracle.com/en-us/iaas/Content/Quotas/Concepts/resourcequotas_topic-Compute_Quotas.htm",
            "https://docs.oracle.com/en-us/iaas/tools/python/latest/api/core/client/oci.core.ComputeClient.html#create_compute_capacity_report",
        ],
        "account_mode": "always-free",
        "eligibility_basis": "always-free-only",
        "promotional_credits_excluded": True,
        "home_region_verified": True,
        "a1_shape_verified": True,
        "ubuntu_arm_image_verified": True,
        "ordinary_resource_manager_jobs_verified": True,
        "premium_jobs_disabled": True,
        "a1_physical_capacity_verified": True,
        "physical_capacity": {
            "mechanism": "oci-compute-capacity-report",
            "availability_domain": "AmkB:EU-MADRID-3-AD-1",
            "fault_domain_requested": None,
            "instance_shape": "VM.Standard.A1.Flex",
            "instance_shape_config": {"ocpus": 2, "memory_in_gbs": 12},
            "availability_status": "AVAILABLE",
            "available_count": 1,
            "sufficient_for_instance": True,
            "report_time_created": datetime.now(timezone.utc).isoformat(),
        },
        "a1_limit_scopes": {
            "a1_ocpus": {
                "availability_domain": {"used": 0, "available": 24},
                "region": {"used": 0, "available": 16},
            },
            "a1_memory_gb": {
                "availability_domain": {"used": 0, "available": 100},
                "region": {"used": 0, "available": 96},
            },
        },
        "observed_resource_status": {
            "a1_ocpus": {"used": 0, "available": 16},
            "a1_memory_gb": {"used": 0, "available": 96},
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
        MODULE.validate_plan_headroom(plan, evidence)
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

    capacity_request = COLLECT_MODULE.a1_capacity_request()
    assert capacity_request == [
        {
            "instanceShape": "VM.Standard.A1.Flex",
            "instanceShapeConfig": {"ocpus": 2, "memoryInGBs": 12},
        }
    ]
    capacity_payload = {
        "data": {
            "availability-domain": "AmkB:EU-MADRID-3-AD-1",
            "compartment-id": "tenancy",
            "shape-availabilities": [
                {
                    "availability-status": "AVAILABLE",
                    "available-count": 1,
                    "fault-domain": None,
                    "instance-shape": "VM.Standard.A1.Flex",
                    "instance-shape-config": {
                        "memory-in-gbs": 12,
                        "ocpus": 2,
                    },
                }
            ],
            "time-created": datetime.now(timezone.utc).isoformat(),
        }
    }
    capacity = COLLECT_MODULE.parse_a1_capacity_report(
        capacity_payload,
        "tenancy",
        "AmkB:EU-MADRID-3-AD-1",
    )
    assert capacity["sufficient_for_instance"] is True
    assert capacity["fault_domain_requested"] is None
    unavailable_capacity_payload = deepcopy(capacity_payload)
    unavailable_row = unavailable_capacity_payload["data"]["shape-availabilities"][0]
    unavailable_row["availability-status"] = "OUT_OF_HOST_CAPACITY"
    unavailable_row["available-count"] = None
    unavailable_capacity = COLLECT_MODULE.parse_a1_capacity_report(
        unavailable_capacity_payload,
        "tenancy",
        "AmkB:EU-MADRID-3-AD-1",
    )
    assert unavailable_capacity["sufficient_for_instance"] is False
    print("PASS physical-capacity-request      checks exact A1 across all fault domains")

    plan = safe_plan()
    evidence = safe_evidence()
    MODULE.validate_evidence(evidence, datetime.now(timezone.utc))
    with tempfile.NamedTemporaryFile() as plan_file:
        MODULE.validate_actions(Path(plan_file.name), plan, None)
    MODULE.validate_envelope(MODULE.resources_by_type(plan))
    MODULE.validate_plan_headroom(plan, evidence)
    print("PASS safe-plan                     accepted")

    out_of_host_capacity = deepcopy(evidence)
    out_of_host_capacity["a1_physical_capacity_verified"] = False
    out_of_host_capacity["physical_capacity"].update(
        {
            "availability_status": "OUT_OF_HOST_CAPACITY",
            "available_count": None,
            "sufficient_for_instance": False,
        }
    )
    expect_blocked("out-of-host-capacity", plan, out_of_host_capacity)

    pinned_fault_domain = deepcopy(evidence)
    pinned_fault_domain["physical_capacity"]["fault_domain_requested"] = "FAULT-DOMAIN-1"
    expect_blocked("pinned-capacity-fault-domain", plan, pinned_fault_domain)

    paid_shape = deepcopy(plan)
    paid_shape["planned_values"]["root_module"]["resources"][0]["values"]["shape"] = "VM.Standard.E5.Flex"
    expect_blocked("paid-shape", paid_shape, evidence)

    oversized = deepcopy(plan)
    oversized["planned_values"]["root_module"]["resources"][1]["values"]["size_in_gbs"] = "200"
    expect_blocked("oversized-storage", oversized, evidence)

    high_performance_boot = deepcopy(plan)
    high_performance_boot["planned_values"]["root_module"]["resources"][0]["values"][
        "source_details"
    ][0]["boot_volume_vpus_per_gb"] = "20"
    expect_blocked("paid-boot-performance", high_performance_boot, evidence)

    high_performance_data = deepcopy(plan)
    high_performance_data["planned_values"]["root_module"]["resources"][1]["values"][
        "vpus_per_gb"
    ] = "20"
    expect_blocked("paid-data-performance", high_performance_data, evidence)

    ambiguous_number = deepcopy(plan)
    ambiguous_number["planned_values"]["root_module"]["resources"][0]["values"][
        "source_details"
    ][0]["boot_volume_size_in_gbs"] = "50.0"
    expect_blocked("ambiguous-number-format", ambiguous_number, evidence)

    ambiguous_boolean = deepcopy(plan)
    ambiguous_boolean["planned_values"]["root_module"]["resources"][0]["values"][
        "create_vnic_details"
    ][0]["assign_public_ip"] = "True"
    expect_blocked("ambiguous-boolean-format", ambiguous_boolean, evidence)

    invalid_quota_family = deepcopy(plan)
    quota = next(
        item
        for item in invalid_quota_family["planned_values"]["root_module"]["resources"]
        if item["type"] == "oci_limits_quota"
    )
    quota["values"]["statements"].append(
        "zero compute-gpu quotas in compartment videogame-platform-dev"
    )
    expect_blocked("invalid-quota-family", invalid_quota_family, evidence)

    missing_regional_quota = deepcopy(plan)
    quota = next(
        item
        for item in missing_regional_quota["planned_values"]["root_module"]["resources"]
        if item["type"] == "oci_limits_quota"
        and any("standard-a1-core-regional-count" in value for value in item["values"]["statements"])
    )
    quota["values"]["statements"] = [
        value
        for value in quota["values"]["statements"]
        if "standard-a1-core-regional-count" not in value
    ]
    expect_blocked("missing-regional-quota", missing_regional_quota, evidence)

    invalid_quota_wait = deepcopy(plan)
    quota_wait = next(
        item
        for item in invalid_quota_wait["planned_values"]["root_module"]["resources"]
        if item["type"] == "time_sleep"
    )
    quota_wait["values"]["create_duration"] = "1m"
    expect_blocked("short-quota-propagation-wait", invalid_quota_wait, evidence)

    invalid_alarm_resolution = deepcopy(plan)
    alarm = next(
        item
        for item in invalid_alarm_resolution["planned_values"]["root_module"]["resources"]
        if item["type"] == "oci_monitoring_alarm"
    )
    alarm["values"]["resolution"] = "1h"
    expect_blocked("invalid-alarm-resolution", invalid_alarm_resolution, evidence)

    broad_lifecycle_policy = deepcopy(plan)
    policy = next(
        item
        for item in broad_lifecycle_policy["planned_values"]["root_module"]["resources"]
        if item["type"] == "oci_identity_policy"
        and item["values"].get("name") == "videogame-platform-dev-object-storage-lifecycle"
    )
    policy["values"]["statements"] = [
        "Allow service objectstorage-eu-madrid-3 to manage object-family in tenancy"
    ]
    expect_blocked("broad-lifecycle-policy", broad_lifecycle_policy, evidence)

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
    MODULE.validate_plan_headroom(plan, trial)
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

    insufficient_regional = deepcopy(evidence)
    insufficient_regional["a1_limit_scopes"]["a1_ocpus"]["region"]["available"] = 1
    insufficient_regional["observed_resource_status"]["a1_ocpus"]["available"] = 1
    insufficient_regional["always_free_headroom"]["a1_ocpus"] = 1
    expect_blocked("insufficient-regional", plan, insufficient_regional)

    partial_retry = deepcopy(plan)
    volume_change = next(
        change
        for change in partial_retry["resource_changes"]
        if change["type"] == "oci_core_volume"
    )
    volume_change["change"]["actions"] = ["no-op"]
    partial_evidence = deepcopy(evidence)
    partial_evidence["observed_resource_status"]["block_storage_gb"] = {
        "used": 100,
        "available": 900,
    }
    partial_evidence["always_free_headroom"]["block_storage_gb"] = 100
    MODULE.validate_evidence(partial_evidence, datetime.now(timezone.utc))
    MODULE.validate_envelope(MODULE.resources_by_type(partial_retry))
    MODULE.validate_plan_headroom(partial_retry, partial_evidence)
    print("PASS partial-retry-headroom          counts only planned creates")

    print("OCI Terraform plan-gate tests passed.")


if __name__ == "__main__":
    main()
