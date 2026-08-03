# ADR-0005: Host private dev on OCI Always Free

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Related platform:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

## Context

The MVP needs one non-local environment to practise repeatable infrastructure,
private networking, identity, delivery, observability, backup, and recovery. It must
cost 0 EUR recurrently, remain operable by one person, and provide enough capacity for
the application, PostgreSQL, Keycloak, and bounded telemetry.

The platform should teach transferable enterprise infrastructure concepts without
requiring Kubernetes or splitting the modular monolith. Free promotional credits do
not satisfy the requirement because they expire.

Official terms checked on 2026-08-03 show:

- [OCI Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
  include up to 2 Ampere A1 OCPUs, 12 GB memory, 200 GB total block storage,
  20 GB Object Storage, Vault, Monitoring, Logging, and Terraform Resource Manager;
- OCI states that Always Free resources are available for the life of the account,
  subject to limits, capacity, idle reclamation, and terms;
- [Google Cloud Free Tier](https://docs.cloud.google.com/free/docs/free-cloud-features)
  provides one `e2-micro` VM with 30 GB disk in selected US regions, which is too
  constrained for the complete dev topology;
- [AWS EC2 Free Tier](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html)
  is time- or credit-limited for current new accounts and therefore is not a
  persistent zero-cost option;
- [Tailscale Personal](https://tailscale.com/pricing) is free for the current
  non-commercial scope and provides private connectivity; MagicDNS and HTTPS provide
  a stable private name without buying a public domain.

## Decision

Host the persistent private `dev` environment on one OCI Always Free
`VM.Standard.A1.Flex` Ubuntu instance using no more than 2 OCPUs and 12 GB memory.

Use:

- OCI Virtual Cloud Network, network security rules, block storage, Object Storage,
  Vault, Logging, Monitoring/APM allowance, and IAM only within Always Free limits;
- Tailscale Personal, MagicDNS, and HTTPS as the owner-only ingress and administrative
  path; expose no public application, identity, database, telemetry, or SSH port;
- standard OCI containers for the application and Keycloak plus PostgreSQL on the VM;
- Terraform configuration in the repository for reproducible infrastructure; state is
  protected remotely and applies are serialized;
- public GHCR images and GitHub Actions for build and deployment orchestration.

Keep the OCI tenancy on the Always Free account model. Trial-only or paid resources,
automatic scaling, managed PostgreSQL, NAT Gateway, paid DNS, and paid support are not
part of this decision. Before every initial provision or material infrastructure
change, verify that each selected resource is still marked Always Free.

The environment is persistent but rebuildable. Back up irreplaceable PostgreSQL and
Keycloak state to encrypted OCI Object Storage outside the VM. Do not generate
artificial load to avoid Oracle's idle-instance reclamation.

## Alternatives considered

### Google Cloud Compute Engine Free Tier

Google Cloud provides mature enterprise infrastructure and an ongoing free VM, but an
`e2-micro` instance and 30 GB disk do not provide a credible resource envelope for the
application, PostgreSQL, Keycloak, and telemetry together.

### AWS Free Tier

AWS has strong enterprise relevance, but the current EC2 free offer expires or depends
on finite credits. It violates the persistent zero-cost requirement.

### Free application PaaS plus external free services

This reduces VM operation, but typically introduces sleeping services, several vendor
limits, fragmented recovery, and less transferable infrastructure learning. Free tiers
also change independently.

### Local-only WSL/Docker environment

This costs nothing and remains the fallback when OCI capacity is unavailable, but it
does not validate remote IAM, networking, infrastructure state, deployment identity,
or off-machine recovery.

### Kubernetes on a free VM

It increases orchestration learning but consumes scarce CPU/memory and adds failure
surface without multiple independently operated workloads. Container and IaC skills
provide the required learning with lower operational cost.

## Consequences

### Positive

- The selected resource envelope is sufficient for the bounded private environment.
- OCI IAM, networking, Vault, Terraform, logging, monitoring, backup, and ARM64 teach
  current transferable cloud practices.
- Private Tailscale access avoids public exposure and paid DNS while preserving HTTPS.
- OCI images, PostgreSQL, OpenTelemetry, and Terraform limit hosting lock-in.

### Negative

- Ampere A1 is ARM64, so all images and dependencies must support `linux/arm64`.
- Always Free capacity can be unavailable and idle instances may be reclaimed.
- PostgreSQL and Keycloak are self-operated rather than managed services.
- Tailscale is an additional external dependency and its Personal plan is limited to
  non-commercial use.
- No availability or response-time guarantee exists.

## Risks and mitigations

- **Unexpected cost:** remain on Always Free, encode allowed shapes/sizes in Terraform,
  disable scaling, inspect plans, and stop if an eligible resource is unavailable.
- **Reclamation or VM loss:** store backups outside the VM, keep IaC complete, and
  rehearse restoration.
- **Regional capacity:** choose the home region carefully; if A1 is unavailable, wait
  and use local development rather than selecting paid capacity.
- **ARM incompatibility:** build and test `linux/arm64` and preferably `linux/amd64`
  images before remote provisioning.
- **Single-node failure:** accept it for private learning; do not claim high
  availability.
- **Terms change:** review terms before provisioning and at least quarterly while the
  environment exists; trigger a hosting reassessment instead of accepting charges.

## Follow-up actions

- Enable Docker Desktop integration for the Ubuntu WSL distribution and prove the
  local topology first.
- Confirm OCI account eligibility and select a home region only after checking A1
  capacity and current limits.
- Create reviewed Terraform for the compartment, network, VM, storage, IAM, secrets,
  telemetry, and backups.
- Configure a least-privilege Tailscale policy and private HTTPS name.
- Rehearse VM reprovisioning, PostgreSQL/Keycloak restore, and application rollback.
- Revisit this ADR if cost, capacity, reclamation, resource limits, Tailscale terms, or
  release mode changes.
