# ADR-0033: Select Lite 3 and immutable off-host production storage

- **Status:** Accepted
- **Date:** 2026-10-25
- **Owner:** Ruben Hernandez
- **Scope:** Owner-approved infrastructure direction for #276; unbuilt and uncontracted
- **Partially supersedes:** [ADR-0025](0025-host-public-production-on-a-deferred-single-vps.md), only its Lite 2 selection and former budget input; replaces the #274 Lenovo production-storage selection

## Context

ADR-0025 accepted a deferred single-VPS public environment on 2026-10-19. Its
historical rationale remains valid: private/non-commercial development, a modular
monolith with PostgreSQL and Keycloak, Docker Compose, private administration and
separate purchase/exposure gates. The owner now selects a larger advertised VPS
and automated immutable off-host storage. Lenovo has sufficient capacity, but its
unrestricted root/Docker/agent access cannot meet the required privilege isolation.

The [#276 research](../research/public-production-backup-storage.md) records the
provider evidence, cost assumptions, remaining spending-protection blocker and
implementation gaps. The [Product Brief](../product/product-brief.md#public-release-and-sustainability)
owns the revised recurring budget; no actual order or account-specific quote exists.

## Decision

Select netcup VPS Lite 3, x86_64, advertised eight shared vCores, 16 GB RAM and
320 GB SSD, instead of Lite 2. The indicative tax-inclusive price and two-month
minimum term remain subject to the final quote and contractual review. Shared
vCores are not dedicated CPU capacity and advertised resources are not measured
qualification evidence. This selection changes no prepared runtime limit or image.

Select OVHcloud Object Storage Standard in the full 1-AZ Gravelines region. Accept
the documented single-zone limitation for this topology: independent off-VPS storage
does not provide multi-AZ or cross-region protection against datacentre/provider loss.
Plan for 200 GiB actually retained, not purchased capacity or an enforceable cap.

Use fully automated client-encrypted direct uploads from the production VPS through
the S3-compatible API, Object Lock Compliance retention, least-privilege credentials,
independent integrity verification/missed-job detection and tested cloud-only recovery.
This supersedes destination-initiated pull and the rule excluding every destination
credential from the source: the upload identity on VPS must be narrowly restricted;
provider administration, retention/bypass authority and independent recovery keys
remain outside it. Existing locked versions, not truthful future source data, are
the compromise-protection boundary.
Any separate expiry worker's permissions and location require implementation review.

Remove Lenovo from the mandatory production backup/recovery path. An optional copy
there is expendable and must not hold a required unique backup, key, receipt, ledger,
archive or administrative dependency. This does not change private-dev hosting.

The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns recovery requirements; the [privacy record](../development/data-protection.md#b-restricted-legal-archive)
owns separate legal archives, ledgers, independent copies/keys, deletion barriers and
retention. Preserve the 35-day rolling-backup ceiling. At the original 2026-10-25 acceptance, exact lock duration, capture
cadence, copy selection, cloud expiry and restore-evidence semantics remained proposals
requiring compatibility review; the [later amendment](#amendment-2026-10-29-scoped-recovery-policy-approval) records the scoped update; accepting Compliance mode does not approve a 35-day
upload-relative lock, indefinite hold or a research parameter by implication.

## Alternatives and consequences

- Retain mandatory Lenovo pull: rejected because root/Docker/agent access defeats the
  protection boundary and adds a required intermediary to automation/recovery.
- Backblaze B2: researched alternative; not selected. The retained research owns
  price/processor-term differences rather than reopening the comparison here.
- OVHcloud 3-AZ or a second provider: possible later improvement, not selected or
  silently substituted. Their cost and operating need require another owner decision.

Cloud integration is not implemented by the accepted #275 filesystem tools. Extend
only the missing upload/version/verification and durable-copy seams, preserving
capture/reopen fences and synchronous ledger/archive acknowledgement before mutation.
Lenovo permissions, a local byte guard, resource quotas and billing alerts do not
constitute an enforceable aggregate spending cap.

## Contracting and evidence boundary

The owner raises the recurring ceiling through the Product Brief. At acceptance
this ADR did not accept uncapped PAYG liability; the
[amendment below](#amendment-2026-10-25-uncapped-ovhcloud-payg-risk) now records that
scoped exception. The [delivery lifecycle](../development/delivery-lifecycle.md#public-production-gates)
owns the resulting gate. Netcup cancellation/renewal/quote, both providers' applicable
processor terms and all final recurring charges remain to verify.

No purchase, account/credential creation, host/DNS change, implementation, deployment,
public exposure or Git publication is authorized by this ADR. #275's signed
acceptance, immutable image identities and historical evidence remain unchanged;
#276 must establish new cloud/real-host evidence without repeating that campaign.

## Reconsider when

Reopen on unacceptable quote/renewal/processor terms, unaffordable actual retained
bytes, unresolved spending risk, inability to reconcile Compliance expiry with the
35-day ceiling or legal/ledger duties, failed independent recovery, or measured
single-zone/capacity risk. No automatic paid upgrade, provider substitution or
Lenovo fallback is authorized.

## Amendment 2026-10-25: uncapped OVHcloud PAYG risk

Owner decision (#276): **accept the residual risk of uncapped OVHcloud pay-as-you-go
charges** for the selected Object Storage project, because no enforceable aggregate
spending cap is verified. This is a scoped exception to the capped-overage contracting
requirement, not a guarantee that costs stay within EUR 25/month and not a finite
liability bound. It is conditional on bounded operation (local upload/byte guards,
least privilege, no other paid OVHcloud resource), consumption monitoring and alerts,
configured and tested before the first upload. Compliance-locked bytes can remain
billable after uploads stop or keys are revoked; that is inside the accepted risk.

The exception covers OVHcloud Object Storage only. It changes neither the EUR 25
recurring ceiling, the netcup or email terms, nor any other gate, and it authorizes
no purchase, account, credential, bucket, deployment or exposure. The
[#276 research](../research/public-production-backup-storage.md#owner-accepted-uncapped-payg-risk)
holds the proposed thresholds and the open operating parameters.


## Amendment 2026-10-29: scoped recovery policy approval

The [owner decision in #276](https://github.com/rubhern/videogame-platform/issues/276#issuecomment-6483692685)
approves the summarized target policy now recorded by the
[platform recovery policy](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery),
and authorizes local adapter preparation and fake-S3 testing. It grants no operational
acceptance, provider setup, purchase, job installation or deployment. D3, D6 and the
precise D7 operating thresholds remain recommendations pending ratification; no
research table is approved in full by implication. The
[local evidence boundary](../research/public-production-backup-storage.md#local-s3-adapter-evidence)
records what is prepared and what still blocks activation. #275 remains unchanged.
