# ADR-0019: Host private dev on an owner-managed Linux host

- **Status:** Accepted
- **Date:** 2026-09-14
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Supersedes:** [ADR-0005](0005-host-private-dev-on-oci-always-free.md)

## Context

OCI Always Free A1 capacity remained unavailable, and the partially provisioned
resources were torn down as recorded in [#42](https://github.com/rubhern/videogame-platform/issues/42).
The owner selected an existing Lenovo Y520 for private `dev` in
[issue #124](https://github.com/rubhern/videogame-platform/issues/124), which records
manual foundation evidence and its remaining acceptance checks.

## Decision

Use one owner-managed Linux host, initially Ubuntu Server 24.04 LTS on `x86_64`,
with Docker Engine/Compose and owner-only Tailscale administration. Keep existing
hardware, private ingress and the zero-recurring-cost infrastructure/service budget;
no public exposure, paid service or hardware upgrade is authorized. Concise manual
reconstruction belongs to the [platform design](../architecture/deployment/mvp-platform-and-delivery.md).
OCI compute, Vault, Resource Manager and its provisioning gates are no longer
prerequisites for current `dev`. The retired infrastructure and procedures remain
in Git history and #42, not in the active repository.

## Alternatives

Continuing to wait for OCI A1 is rejected for the current baseline. Paid cloud,
additional hosts and orchestration add cost or complexity outside the approved scope.

## Consequences

The owner operates the OS, disk, power, cooling and home-network dependencies;
availability has no guarantee. Existing electricity and connectivity are consumed;
this is not evidence of zero physical operating cost. Initial 8 GB RAM remains until
runtime measurements justify reconsideration. Multi-architecture image delivery and
the single-host application design remain approved. Runtime composition/secrets/
telemetry (#43), deployment (#36) and backup/restore (#44) remain separate work;
foundation evidence proves none of their acceptance criteria.

## Reconsider when

Revisit on measured resource pressure, hardware or network reliability limits,
Tailscale eligibility/terms changes, additional recurring cost, or a changed release
mode. Reopening OCI requires fresh eligibility and cost gates, never a paid fallback.
