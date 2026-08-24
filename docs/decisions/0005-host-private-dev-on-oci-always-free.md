# ADR-0005: Host private dev on OCI Always Free

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

One non-local environment is useful for learning repeatable infrastructure, private
networking, delivery, backup and recovery. It must cost 0 EUR recurrently and remain
operable by one person. Promotional credits and paid fallbacks do not meet that
constraint.

## Decision

Subject to a fresh eligibility check before provisioning, use one OCI Always Free
Ampere A1 Ubuntu VM within the approved free CPU, memory and storage limits:

- run application, Keycloak and PostgreSQL as containers on the VM;
- use Tailscale Personal, MagicDNS and HTTPS for owner-only ingress and
  administration; expose no public application, identity, database, telemetry or
  SSH port;
- define infrastructure with Terraform and serialize state changes;
- build multi-architecture OCI images in GitHub Actions and publish them to GHCR;
- keep encrypted backups of irreplaceable PostgreSQL/Keycloak state outside the VM;
- never substitute a trial-only or paid resource when free capacity is unavailable.

The exact topology, recovery and deployment behavior belong to the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md).

## Alternatives considered

- **Other public-cloud free tiers:** rejected because the evaluated persistent
  envelopes were insufficient or time/credit limited.
- **Free PaaS:** rejected because fragmented limits and sleeping services weaken the
  intended infrastructure learning.
- **Local only:** retained as the fallback, but it cannot validate remote IAM,
  networking or recovery.
- **Kubernetes:** rejected as unnecessary overhead for this single-node scope.

## Consequences

The environment exercises transferable VM, IAM, IaC, backup and ARM64 concerns at no
approved recurring cost. Capacity may be unavailable, free terms may change, the
single node has no availability guarantee and PostgreSQL/Keycloak remain
self-operated.

## Reconsider when

Verify current OCI and Tailscale eligibility before initial provisioning and every
material infrastructure change. If cost, capacity, reclamation, terms or release mode
changes, stop and reassess; do not silently accept charges.
