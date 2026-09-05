# Private OCI Terraform workflow

This procedure owns the safe operator workflow for the approved private OCI `dev`
stack. The [platform design](../architecture/deployment/mvp-platform-and-delivery.md)
owns topology and behaviour; Terraform and its validation scripts own executable
resource details and limits. Provisioning remains owned by issue #42.

## Safety boundary

The stack is deliberately fail-closed. It permits only one Ampere A1 instance in the
tenancy home region, no public ingress, bounded block/Object Storage, a non-private
Vault, least-privilege instance-principal access, and bounded alarms. It must never
substitute another shape, paid or trial-only service, NAT/load balancer, instance
pool, autoscaling resource, or expanded storage when free capacity is unavailable.

OCI currently documents Free Tier as two independent offers: a time-limited Free Trial
with promotional credits and Always Free resources that remain available after the
trial. The credits may permit paid resources temporarily and therefore never count as
project eligibility, headroom, or budget. Recheck the [official Free Tier semantics](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm),
the [Always Free resource page](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm),
the [Compute Capacity Report API](https://docs.oracle.com/en-us/iaas/tools/python/latest/api/core/client/oci.core.ComputeClient.html#create_compute_capacity_report),
the [premium-jobs billing rules](https://docs.oracle.com/en-us/iaas/Content/ResourceManager/Concepts/premium-jobs.htm),
and the [Resource Manager Terraform version matrix](https://docs.oracle.com/en-us/iaas/Content/ResourceManager/Reference/terraformversions.htm)
immediately before every plan.

The permanent envelope is 1,500 A1 OCPU hours and 9,000 GB hours per month,
equivalent to 2 OCPU and 12 GB RAM continuously, plus 200 GB combined boot/block
storage, 20 GB combined Object Storage, software-protected Vault keys, non-private
Vault secrets, and ordinary Resource Manager jobs within their Always Free limit.

## State and concurrency

Use one OCI Resource Manager stack as the only state owner. Resource Manager stores
the remote state, locks it, and allows only one job at a time for the stack. Restrict
`ORM_JOB_READ`, because it grants access to state, logs, and configuration. Do not run
a local plan or apply against local state, copy state into Git, or configure an
alternative S3-compatible backend. HashiCorp does not guarantee S3 locking against
non-AWS implementations, whereas Resource Manager supplies native OCI state locking.

Creating the Resource Manager stack is a provisioning action and therefore belongs
to #42. At that point, select Terraform 1.5.7, upload the contents of
`infrastructure/terraform` without `.terraform/`, and confirm that premium jobs are
disabled. The stack and plan/apply permissions must remain owner-only. Never enter a
secret payload, secret OCID, private key, token, or notification endpoint as a
Resource Manager variable. Terraform creates only an unsubscribed notification topic;
protected subscription endpoints and Vault secret payloads are configured later,
outside Terraform.

## Required pre-plan evidence

Install and authenticate the OCI CLI without placing its config or key under the
repository. Copy only the non-secret identifiers from
`infrastructure/terraform/terraform.tfvars.example` into ignored local/Resource
Manager variables. Then run the non-provisioning preflight:

```bash
python3 scripts/collect-oci-free-tier-evidence.py \
  --tenancy-ocid "$OCI_TENANCY_OCID" \
  --region "$OCI_HOME_REGION" \
  --availability-domain "$OCI_A1_AVAILABILITY_DOMAIN" \
  --image-ocid "$OCI_UBUNTU_ARM64_IMAGE_OCID" \
  --account-mode "$OCI_ACCOUNT_MODE" \
  --official-terms-reviewed-on "$(date -u +%F)" \
  --output infrastructure/terraform/evidence/preflight.json
```

Set `OCI_ACCOUNT_MODE` to exactly `always-free`, `trial`, or `pay-as-you-go` as an
owner attestation. A trial account is valid, but it receives no extra project budget:
the preflight caps reported availability at the permanent Always Free allowance minus
observed tenancy usage. It therefore respects a lower real availability while
ignoring any larger trial capacity or remaining promotional credit. Evidence expires
after 24 hours. The plan reviewer compares that current headroom with only the
resources the exact plan still creates, so a safe continuation after a partial apply
does not double-count resources already present in protected state.

The preflight also checks home region, A1 shape/image compatibility, both
availability-domain and regional A1 limit headroom, at least one
ordinary Resource Manager job within the permanent free concurrency limit, and zero
effective premium-job capacity. It checks both premium usage and remaining capacity,
so a consumed premium job with `available == 0` is still blocked. Separately from
those quota and Always Free checks, it requests an ephemeral OCI Compute Capacity
Report for exactly one `VM.Standard.A1.Flex` with 2 OCPU and 12 GB RAM. The request
omits Fault Domain so OCI checks the whole selected Availability Domain. Only
`AVAILABLE` with an `available-count` of at least one passes. The report provisions
and reserves nothing; the preflight performs no infrastructure create, update,
delete, plan, or apply operation.

OCI does not expose a reliable account-mode classification, so the account mode
remains an explicit owner attestation. A capacity report is a point-in-time signal,
not a reservation or guarantee that capacity will remain until apply. A negative,
missing, malformed, or failed report blocks plan creation. `OUT_OF_HOST_CAPACITY` in
the report or at launch means stop until the same exact A1 configuration reports
available again; it never authorizes a paid fallback or a narrower Fault Domain.

## Plan and owner review

1. Run `bash scripts/validate-terraform.sh`. Format, initialization without a
   backend, validation, and adversarial tests of the OCI-specific security and cost
   plan policy must pass. This policy, rather than a generic configuration scan, is
   the executable security and cost authority for OCI resources.
2. Create a **plan job only** in the protected Resource Manager stack. Do not create
   an apply or destroy job. Download that job's Terraform plan to the ignored
   `infrastructure/terraform/evidence/` directory and convert it with the pinned CLI:

   ```bash
   terraform show -json evidence/dev.tfplan >evidence/dev.tfplan.json
   ```

3. Bind the plan to fresh live evidence and run the fail-closed reviewer:

   ```bash
   python3 scripts/review-oci-terraform-plan.py \
     --plan-json infrastructure/terraform/evidence/dev.tfplan.json \
     --eligibility-evidence infrastructure/terraform/evidence/preflight.json
   ```

4. Inspect the complete human-readable Resource Manager plan and confirm all of the
   following before #42: only allowlisted resources appear; the image is an official
   Always Free-eligible Ubuntu ARM64 image; all resources are in the verified home
   region/compartment; no amount, tier, performance setting, ingress rule, public
   output, secret payload, trial-only feature, load balancer, NAT gateway, pool, or
   autoscaling facility has appeared; the five quota policies are created before
   a ten-minute, provider-local propagation wait and dependent capacity; and every
   unknown value is understood.
5. Treat plan JSON, binary plans, state, logs, and evidence as protected local data.
   Do not publish or attach them to a public issue or CI artifact.

Any `delete` or replacement action is blocked. If a destructive change is genuinely
required later, the owner must review the recovery path and create a separate ignored
JSON approval containing `"owner_approved": true` and the SHA-256 of the exact plan
JSON, then pass it with `--destructive-approval`. A changed plan invalidates that
approval. This mechanism does not authorize an apply; #42 still requires an explicit
owner go/no-go and must apply the reviewed Resource Manager plan job rather than
replanning silently.

## What the zero-cost gate can prove

Terraform variable validation and OCI compartment quotas hard-cap both the
availability-domain and regional A1 envelopes at 2 A1
OCPU, 12 GB A1 memory, 150 GB boot/block storage, and 5 GB Object Storage; paid
compute families, GPUs, compute management/autoscaling and virtual private Vaults are
zeroed. A provider-local ten-minute wait accounts for OCI's documented quota-policy
propagation delay before dependent capacity is requested. The plan reviewer
additionally allowlists resource types and exact free
settings, including the 10 VPUs/GB balanced performance level for both block volumes.
The live preflight proves that permanent Always Free headroom, after current tenancy
usage and without promotional credits, covers the plan at that time. Independently,
the Compute Capacity Report proves that OCI reported at least one matching A1 host at
the preflight instant; it does not reserve that host. The same plan
allowlist and exact resource limits apply to trial, Always Free, and paid accounts.

This cannot make Oracle's external terms immutable, reserve scarce A1 capacity, or
prevent unrelated resources elsewhere in the tenancy from consuming a shared free
allowance. If official terms, account mode, home region, current usage, provider
behaviour, the plan, or any unknown cost cannot be reconciled, the verdict is
`BLOCKED` and no provisioning may occur.
