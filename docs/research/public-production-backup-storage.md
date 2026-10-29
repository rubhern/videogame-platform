# Automated off-host backup storage (#276)

- **Status:** Provider/topology approved 2026-10-25; summarized target retention policy and local S3 implementation authorized 2026-10-29; D3/D6/exact D7 values pending; no operational acceptance
- **Research date:** 2026-10-25
- **Repository inspected:** `147f7f28c0b637f89314aa997303a0847f1617c3`
- **Consumer:** Owner decision within [#276](https://github.com/rubhern/videogame-platform/issues/276)

Point-in-time comparison and proposed changes only. The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns recovery policy; the [data-protection record](../development/data-protection.md#b-restricted-legal-archive)
owns deletion/archive obligations; the [runtime guide](../../deploy/public-production/README.md#paired-backup-and-recovery-candidate)
and its scripts own mechanics. The approved sources now record the scoped 2026-10-29 policy approval; the comparison below remains dated research. The accepted
[#275 candidate and evidence](public-production-candidate-acceptance.md) remain frozen;
this research neither reopens their acceptance nor repeats their tests.

## Owner-approved direction and purchase readiness

**Design selection approved; purchase NO-GO pending contracting evidence.** The owner
approved the following on 2026-10-25; [ADR-0033](../decisions/0033-select-lite-3-and-immutable-offhost-storage.md)
records the durable change and its partial supersession of ADR-0025. The
[Product Brief](../product/product-brief.md#public-release-and-sustainability) owns
the current total recurring ceiling.

| Approved decision | Boundary |
|---|---|
| netcup VPS Lite 3 replaces Lite 2 | x86_64, advertised eight shared vCores, 16 GB RAM, 320 GB SSD; indicative EUR 16.95/month including VAT and two-month minimum. Actual quote/contract unverified |
| Total recurring budget increased from EUR 20 to EUR 25/month | Includes VAT, renewal, email, storage, administration and recurring extras. Keep EUR 2 email contingency. A ceiling for normal operation, not a maximum bill |
| OVHcloud S3 Object Storage Standard, 1-AZ Gravelines | Full region, not Local Zone/Swift. Documented single-zone durability limitation explicitly accepted for this topology |
| Plan for 200 GiB actually retained | Approximately EUR 1.72/month including VAT, not purchased capacity, provider quota or enforceable spending cap |
| Fully automated encrypted direct VPS-to-S3 uploads | Compliance retention, least privilege, independent integrity/missed-job verification and tested recovery; not implemented |
| Lenovo optional and expendable | Removed from mandatory production recovery; no required unique copy, key or administrative dependency there |
| Uncapped OVHcloud PAYG residual risk accepted | Scoped exception to the capped-overage gate for OVHcloud Object Storage only, subject to bounded operation, consumption monitoring and alerts. See [below](#owner-accepted-uncapped-payg-risk) |

This supersedes the previous Lenovo pull-based destination decision without asserting
that its prepared #275 commands implement S3. No provider account, paid service or
production state is changed. The earlier provider comparison below is retained
point-in-time evidence; it is not reopened by this alignment.

**Budget planning:** EUR 16.95 VPS + EUR 2 domain allowance + EUR 2 email contingency
+ EUR 1.72 storage = **EUR 22.67/month**, leaving EUR 2.33 against EUR 25. Actual
Mailjet Free costs EUR 0, so the corresponding estimated cash run rate is EUR 20.67
before use of the reserved contingency or unverified extras. Neither total is a
maximum bill; full accounting must include auxiliary retained versions, mailbox,
CI/administration charges and final tax/renewal terms.

**Spending protection:** no enforceable aggregate OVHcloud spending cap is verified.
Public documentation establishes alerts, resource quotas and prepaid credit, not a
hard aggregate charge limit. The owner has [accepted that residual risk](#owner-accepted-uncapped-payg-risk)
as a scoped exception; it is not a purchase authorization.

The table records the 2026-10-25 approval. The later scoped approval and local evidence are recorded [below](#local-s3-adapter-evidence). The [Object Lock retention recommendation](#object-lock-retention-recommendation)
reconciles lock duration, capture cadence, copy count, expiry ownership and
ledger/archive placement with the canonical 35-day and privacy/recovery requirements.
Its full detail was not approved on 2026-10-25. The 2026-10-29 approval covers the summarized policy only; D3, D6 and precise D7 thresholds still await ratification. The watchdog remains uninstalled.

## Evidence and current implementation

**Verified at the 2026-10-25 research baseline; the [local adapter section](#local-s3-adapter-evidence) records subsequent changes:**

| Executable source | Existing capability and integration consequence |
|---|---|
| [`recovery`](../../deploy/public-production/bin/recovery), `backup`, `thaw`, `restore`, `replay`, `reopen` | Paired custom-format dumps encrypted with GPG to a public-only recipient; recorded common recovery point, manifests/checksums, runtime lease, writer/identity fences and refusal on partial state. Successful capture still needs explicit thaw and consumer restart. Reuse these controls |
| `recovery`, `pull` / `copy` | Four-file inventory: two encrypted dumps, manifest and checksums; pinned SSH/read-only source, 1,800-second transfer timeout and caller byte cap. Local filesystem destination only; no S3 upload/download, timer or cloud retention implementation |
| `recovery`, `retain` | Filesystem seven-daily/four-weekly selection plus last fully rehearsed pair, maximum twelve pairs, 512-entry refusal, 35-day ceiling. Not an Object Lock/version cleanup implementation |
| [`verify-offhost.py`](../../deploy/public-production/observability/verify-offhost.py) | Requires a local pair, successful `encrypted-pull` receipt, matching manifest/full restore/deletion-replay evidence and an owner isolation attestation no older than 30 days. Cannot assert cloud verification or Lenovo isolation merely by changing a boolean |
| [`personal-data-ledger`](../../deploy/private-dev/bin/personal-data-ledger) and [`personal_data_archive.py`](../../deploy/private-dev/lib/personal_data_archive.py) | Owner-side two-filesystem-copy durability before destructive steps, archive signing/sealing and replay barriers. An asynchronous S3 sync would not satisfy this contract |

**Owner-supplied evidence, not measured here:** Lenovo has about 805 GiB free but
unrestricted root/Docker/agent administration. It cannot satisfy the approved isolation
condition. A different OS user, `0700`, container or encrypted mount whose key is
accessible to root does not fix that boundary. Automated off-host storage is preferred
to rotating USB media. No host was contacted.

## Provider comparison from official sources

“Verified” below means documented product behaviour, not an authenticated test,
contractual acceptance or an account-specific guarantee.

| Property | Backblaze B2 EU Central | OVHcloud full European regions |
|---|---|---|
| Location | Account chooses region at creation; EU Central stores customer files in Amsterdam; account region cannot subsequently change. Regional storage is not automatic cross-region replication | Standard is listed in French SBG/GRA/RBX, German LIM, Polish WAW and Paris/Milan 3-AZ regions; London is European but outside the EU. Select Gravelines explicitly; exact enabled endpoint/SKU remains to confirm |
| Durability / redundancy | Designed annual durability 99.999999999%; vault shards across servers/drives inside a datacentre. Amsterdam is not a three-AZ service | Standard advertises 11 nines durability. 1-AZ has internal 2N+1 redundancy and server/disk fault protection; datacentre loss can lose data. 3-AZ spans independent power/cooling/network zones and survives one zone outage; neither is cross-region protection |
| Availability | 99.9% monthly SLO with conditional service credits, not a restore-time guarantee | Standard 1-AZ 99.9%, 3-AZ 99.99% advertised SLA; actual applicable contract and exclusions must be retained |

Sources: [B2 regions](https://www.backblaze.com/docs/cloud-storage-data-regions),
[B2 durability](https://help.backblaze.com/hc/en-us/articles/218485257-B2-Resiliency-Durability-and-Availability),
[B2 SLA](https://www.backblaze.com/company/policy/sla),
[OVHcloud region matrix](https://www.ovhcloud.com/en/public-cloud/regions-availability/),
[OVHcloud classes](https://www.ovhcloud.com/en/public-cloud/object-storage/),
[OVHcloud redundancy comparison](https://www.ovhcloud.com/sites/default/files/external_files/ovhcloud-info-object-storage-2025.pdf).

### API, immutability and permissions

| Property | B2 | OVHcloud |
|---|---|---|
| S3 limits | S3 syntax is a subset: bucket-level ACLs, no object ACLs/full tagging. Normal accounts use capability-based application keys; AWS IAM/STS is restricted to enterprise-console customers | Published compatibility matrix distinguishes full Regions from Local Zones. Regions support version listing, retention/legal-hold APIs and encryption configuration; several are absent in Local Zones. Bucket policies and public-access-block writes are not supported in Regions; use user policies and private ACLs |
| Versions | Same-name writes create new versions; hide/delete markers can obscure the original. Recover by recorded version/file ID | Enable versioning; Object Lock creation also enables it. New versions/delete markers must not be mistaken for destruction of a locked original |
| Lock setup | Can enable on a new or eligible existing bucket, irreversibly. Restrictions include source replication/restricted buckets; documented period 1–3,000 days. Default retention applies to subsequent uploads, not existing files | Must enable when creating the bucket; cannot add later or disable. Documented minimum one day, no maximum. Lock-enabled alone does not configure a retention period |
| Governance | A credential with `bypassGovernance` can override protection | `s3:BypassGovernanceRetention` permits bypass. Not sufficient against a privileged administrator |
| Compliance | Protected versions cannot be deleted/shortened by users; authorized retention writers can extend dates | Documentation says no user, including administrators, can delete/modify the protected version or change its mode/duration. Do not assume AWS's extension semantics without testing OVHcloud |
| Legal hold | Separate from time retention, no automatic expiry; authorized hold writer can release it | Likewise independent ON/OFF protection until released. Avoid indefinite holds for rolling backups |
| Least privilege | Restrict key by bucket/prefix/capabilities/expiry; exclude delete, retention, key and bucket administration. `writeFiles` can hide files despite lacking `deleteFiles`: record version IDs | Dedicated non-owner S3 user, scoped resources and explicit denies. User policy evaluation falls back to ACLs; omission of an allow is insufficient for a bucket owner with FULL_CONTROL. Test effective denies rather than copying an AWS policy |

Sources: [B2 S3 scope](https://www.backblaze.com/docs/cloud-storage-s3-compatible-api),
[B2 access-control mapping](https://www.backblaze.com/docs/cloud-storage-map-iam-policies-and-s3-acls-to-backblaze-b2-access-controls),
[B2 versions](https://www.backblaze.com/docs/cloud-storage-file-versions),
[B2 Object Lock](https://www.backblaze.com/docs/cloud-storage-object-lock),
[B2 key capabilities](https://www.backblaze.com/docs/cloud-storage-application-key-capabilities),
[OVHcloud compatibility](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-s3-compliancy),
[OVHcloud Object Lock](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-managing-object-lock),
[OVHcloud IAM](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-identity-and-access-management).

Both protect **existing locked versions**, not truthful future backups, availability
of the account, billing, decryption keys or every administrator action. An attacker
can stop uploads, upload garbage/new versions, hide a good version, exhaust storage
or change future defaults if granted control-plane access. Use immutable unique
pair keys, preserve independently verified version IDs/digests and recover explicit
versions. Keep provider account/MFA/recovery codes outside VPS, Lenovo and agent
sessions. Provider termination, legal orders, non-payment and provider failure are
not covered by an absolute “even the provider cannot delete” claim; confirm account
closure and unpaid-account handling before contracting.

### Encryption and data protection

Keep existing client-side GPG encryption and TLS. B2 offers AES-256 SSE-B2/SSE-C;
OVHcloud offers AES-256 SSE-OMK/SSE-C. Select managed SSE as an additional disk control;
SSE-C adds a second recovery dependency and is unnecessary with GPG. Neither managed
SSE prevents an authorized reader obtaining the stored bytes; neither substitutes
for keeping the GPG private key off the runtime and backup storage.
Sources: [B2 security](https://www.backblaze.com/docs/cloud-storage-security),
[OVHcloud encryption](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-encrypt-your-objects-with-sse-c).

**B2:** the [EEA/EU DPA](https://www.backblaze.com/company/policy/dpa-for-eea-eu-residents)
incorporates SCCs but describes individual customers as joint controllers and applies
controller-to-controller Module One; organizations use processor terms/Module Two.
This is material for this personally operated project: do not silently call a personal
B2 account an accepted Article 28 processor arrangement. Its subprocessor clause also
refers to express data-subject consent. Obtain applicable written clarification or
terms before personal data. The [subprocessor list](https://www.backblaze.com/company/policy/subprocessors)
identifies Interxion Netherlands for EU files, US Datastax/Rookout services for files,
and US recipients for account/support/billing data. Amsterdam file storage does not
mean EU-only processing. Verify necessary remote access, SCC module, safeguards and
actual customer classification; do not assume a current Data Privacy Framework
certification or encryption alone resolves all transfer obligations.

**OVHcloud:** the Spanish [contracts index](https://www.ovhcloud.com/es-es/terms-and-conditions/contracts/)
links the [DPA dated 2025-10-17](https://contract.eu.ovhapis.com/1.0/pdf/OVH_Data_Protection_Agreement-es.pdf)
(OVH Hispano SL as processor) and [subprocessors dated 2025-10-16](https://contract.eu.ovhapis.com/1.0/pdf/OVH_Sub_processors-es.pdf).
The PDFs were fetched and text-extracted locally after the browser could not parse
them. DPA §6 preserves selected location subject to service conditions, permits
necessary remote processing, and requires SCC effectiveness/supplementary-measure
assessment where relevant. Affiliates include EU entities and Canada/UK across
ordinary services; APAC participation has specified service/location conditions.
Third-party processors are identified in service-specific terms. Verify the actual
Spanish contracting entity, incorporated Public Cloud terms and applicable remote
support/subprocessors; EU storage is not a promise of no transfers. Retain those
terms and update the canonical supplier register only after approval. No legal or
provider acceptance is inferred from this research.

## Verified price comparison and budget

Use **total bytes actually retained**, including every version, duplicate, partial
upload, receipt, archive, ledger and reconstruction object. These are not prices
for 50/100/200 GiB of live database multiplied by an unstated retention factor.

[OVHcloud Spain prices](https://www.ovhcloud.com/es-es/public-cloud/prices/): Standard
1-AZ EUR 0.00000972/GiB-hour; 3-AZ first 50 TiB EUR 0.00001917/GiB-hour, excluding VAT.
Published GB/GiB wording is inconsistent in some FAQ examples; use this explicit
GiB tariff and confirm metering on the quote. No fixed capacity subscription is shown;
Standard's product page specifies a one-hour minimum, pricing table no multi-day
minimum. GiB-hour granularity/rounding and any project activation deposit remain
account-specific. Do not mistake a refundable deposit for a monthly service fee.

[B2 current pricing](https://www.backblaze.com/cloud-storage/pricing) is
USD 6.95/TB/30 days, byte-hour metering, first 10 GB free, no minimum size/duration.
Use decimal GB/TB for this estimate (confirm invoice units); 1 GiB = 1.073741824 GB.
Normalize **both** providers to 730 stored hours and Spanish 21% VAT, with
[ECB 2026-10-23 USD/EUR reference](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html)
1 EUR = USD 1.1193. B2 formula before VAT: `GiB × 1.073741824 × 0.00695 × 730/720` USD.
Card conversion fees and exchange movement are excluded, so B2 EUR amounts are estimates.
No fixed monthly minimum is advertised for either selected PAYG route; confirm any
minimum invoice collection, rounding or account-level charge in the actual terms.
The [Spanish Tax Agency](https://sede.agenciatributaria.gob.es/Sede/iva/calculo-iva-repercutido-clientes/tipos-impositivos-iva.html)
confirms the general 21% rate; final invoice treatment remains to verify.

| Total retained GiB | B2 USD net, no allowance | B2 EUR incl. VAT, no allowance | B2 EUR incl. VAT, advertised 10 GB allowance | OVHcloud Standard 1-AZ EUR incl. VAT | OVHcloud Standard 3-AZ EUR incl. VAT |
|---:|---:|---:|---:|---:|---:|
| 50 | 0.3783 | 0.41 | 0.33 | 0.43 | 0.85 |
| 100 | 0.7566 | 0.82 | 0.74 | 0.86 | 1.69 |
| 200 | 1.5132 | 1.64 | 1.56 | 1.72 | 3.39 |

Use the **no-allowance** B2 column for budget decisions. The advertised allowance is
verified, its application to a future account/invoice is not. Amounts vary with actual
hours (744-hour month costs more), rounding and tax treatment. Spanish VAT is a
conservative consumer assumption, not an assertion of reverse-charge eligibility.

| Other charges / retention effects | B2 pay-as-you-go | OVHcloud selected Standard |
|---|---|---|
| Upload and normal S3 API calls | Upload free; Class A/B/C free. Class D event notifications USD 0.004/10,000 after 2,500/day; not proposed | API, upload, download and Standard retrieval shown free for the Spanish route; APAC egress exception is irrelevant to a confirmed Spanish contract |
| Restore | Free up to 3× monthly **average** stored bytes, then USD 0.01/GB. Early restore after little byte-hour accumulation can be billable | No separate Standard retrieval/egress tariff shown; confirm fair-use/contract conditions |
| Minimum/early deletion | No storage-duration or deletion fee; locked and noncurrent bytes remain billed | Standard has no multi-day early deletion penalty. IA/Active/Cold have 30/90/180-day charging floors and retrieval fees; exclude them |
| Immutability | No separate lock fee, but all protected bytes bill until deleted | No separate Object Lock surcharge shown; quote must confirm. All versions count; lock expiry is not deletion |

[B2 transaction tariff](https://www.backblaze.com/cloud-storage/transaction-pricing)
confirms free A/B/C but retains an obsolete USD 0.005/GB storage footnote, conflicting
with its main pricing page. Use USD 6.95 for planning and require invoice confirmation;
old Class B/C price articles are not current tariffs. For scale, 200 GiB of **chargeable**
B2 restore traffic would be USD 2.1475 net, about EUR 2.32 with assumed VAT, additional
to storage; this is not an automatic charge for every 200 GiB restore.

### Complete recurring total with Lite 3

The prior [specific Lite 3 G12.5s listing](https://www.netcup.com/en/server/vps/vps-lite-3-g12.5s-iv-2m)
evidence showed EUR 16.66 with German 19% VAT, two-month term/billing, zero setup and
IPv4+IPv6. The owner-approved indicative **EUR 16.95/month including VAT** is a
planning input, not a final quote. Selection is now approved; stock, exact SKU,
architecture/location, cancellation notice/deadline, minimum commitment, billing,
renewal price, traffic restrictions and actual Spanish invoice remain unverified.
The older G12s overview price is not this selected candidate's contractual quote.

| Recurring item | Planning EUR/month incl. assumed VAT | Evidence / unresolved amount |
|---|---:|---|
| Selected VPS Lite 3 | 16.95 | Indicative, uncontracted; about EUR 33.90 initial two-month cash commitment before final quote |
| Domain/DNS and any bundled contact mailbox | 2.00 | Preserve existing renewal allowance; first-year EUR 9.67 payment is not renewal evidence. Confirm mailbox inclusions/additional charges |
| Transactional email contingency | 2.00 | Reserved, not contracted; selected Mailjet Free actual EUR 0. No automatic paid fallback |
| Selected Standard 1-AZ storage, 200 GiB total | 1.72 | Quoted public tariff estimate, 730 hours. Includes all retained versions and auxiliary storage within the planning target; no cap or prepaid capacity |
| Existing software/hardware/telemetry/administration | 0.00 identified incremental subscription | No paid managed/admin tier approved. Verify account/CI, contact mailbox and any operating extras; unknown charges are not proven zero |
| **Reserved planning total** | **22.67** | **EUR 2.33 headroom within owner-approved EUR 25 ceiling** |
| **Estimated cash run rate with Mailjet Free** | **20.67** | Before reserved email spend and unverified extras; contingency still belongs in planning |

At 744 stored hours, the same 200 GiB storage costs about EUR 1.75 and the reserved
total is **EUR 22.70**, leaving EUR 2.30. Price/tax/size changes or retained auxiliary
copies consume that margin; it is not protection against runaway PAYG charges.

For comparison with the retained tariff evidence, all totals below include the
**same EUR 2 email contingency** and EUR 2 domain allowance; alternatives are not
new selections or authorization to substitute providers.

| Stored GiB | Reserved total with B2, no free allowance | Reserved total with OVHcloud 1-AZ | Reserved total with OVHcloud 3-AZ |
|---:|---:|---:|---:|
| 50 | EUR 21.36 | EUR 21.38 | EUR 21.80 |
| 100 | EUR 21.77 | EUR 21.81 | EUR 22.64 |
| 200 | EUR 22.59 | **EUR 22.67** | EUR 24.34 |

### Spending protections and their limits

Official documentation reviewed on 2026-10-25, without account access or provider
contact. **No public proof of a hard aggregate stored-byte/spending cap was found**;
that does not prove a bespoke account restriction is impossible. Obtain a written
account-specific answer before claiming one exists.

| Available protection | Verified documentation | What it does not establish |
|---|---|---|
| Usage/forecast and threshold email | [Public Cloud billing](https://docs.ovhcloud.com/en/guides/public-cloud/cross-functional/analyze-billing) exposes current usage, forecasts and email when predicted usage exceeds a configured threshold | No documented upload rejection, charge stop or upper bound. Delayed forecasts/notification/response can miss rapid growth |
| Project resource quotas | [Interface guide](https://docs.ovhcloud.com/en/guides/public-cloud/cross-functional/public-cloud-interface-walk-me) describes resource limits and automatic quota increases; [quota guide](https://docs.ovhcloud.com/en/guides/public-cloud/cross-functional/increasing-public-cloud-quota) concerns resource quotas | Not a verified aggregate Object Storage GiB/euro quota. Automatic increases cannot be treated as a fixed budget guard |
| S3 technical limits | [Object Storage limits](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-limitations) lists bucket/object/version/multipart limits; object count per bucket is unlimited and request rates are soft | Per-object/version/bucket-count limits do not bound total stored bytes or ongoing locked-version charges |
| Cloud credit/vouchers | [Adding credit](https://docs.ovhcloud.com/en/guides/account-and-service-management/managing-billing-payments-and-services/add-cloud-credit-to-project) says credit is used first, then other payment methods pay the outstanding balance | Prepayment is not a stop-on-depletion cap. No trial/credit is budget protection |
| Removing payment or manual renewal | [Billing FAQ](https://docs.ovhcloud.com/en/guides/account-and-service-management/managing-billing-payments-and-services/faq-billing) excludes Public Cloud projects from manual renewal; stopping all billing requires project deletion | Deliberate non-payment/suspension is not a cap, erases no incurred liability and threatens independent recovery; project deletion is not a safe automatic protection for locked backups |
| Local upload/retention guards and least privilege | Proposed bounded uploader, inventory, credential rotation/revocation and alerts reduce normal/operator risk | Compromised upload credentials can bypass local guards and create many locked versions; revocation stops future access but already-retained bytes remain billed |

Proposed operating controls, **not configured/approved thresholds**: early retained-byte
warning at 180 GiB, block an ordinary new batch projected to exceed the 200 GiB
planning target, forecast alerts at EUR 2 and EUR 3 including VAT of storage cost
(translate correctly if the console is ex-VAT), independent hourly inventory and
failure alerts, and immediate owner key-revocation/incident escalation. Check all
buckets, versions, multipart uploads and auxiliary stores; do not auto-delete
protected history or a legal/ledger store to force the budget green. Limits in an
uploader and notifications are compensating controls only, never a provider cap.

### Owner-accepted uncapped PAYG risk

**Owner decision, 2026-10-25 (#276): accepted.** No hard aggregate cap covering
Standard storage, all versions and multipart objects and auxiliary buckets is
verified. The owner explicitly accepts the residual risk of uncapped OVHcloud PAYG
charges as a scoped exception to the capped-overage contracting requirement. The
[ADR-0033 amendment](../decisions/0033-select-lite-3-and-immutable-offhost-storage.md#amendment-2026-10-25-uncapped-ovhcloud-payg-risk)
records the decision and the [contracting gate](../development/delivery-lifecycle.md#public-production-gates)
its effect.

| Boundary | Content |
|---|---|
| Scope | OVHcloud Public Cloud Object Storage for this project only: rolling, ledger, archive/proof and reconstruction buckets. No other paid OVHcloud resource |
| Accepted | The 200 GiB target and EUR 25 ceiling are operating limits, not maximum liability. Credential compromise, retained versions, automation or expiry errors and delayed alerts can produce charges above EUR 25 with no verified upper bound. Compliance-locked bytes stay billable after uploads stop or keys are revoked |
| Conditions | Bounded operation, consumption monitoring and alerts configured and tested before the first upload: local byte guard, least-privilege credentials, independent inventory, forecast alerts, key revocation and incident escalation |
| Not changed | Recurring ceiling, other services' capped-overage requirement, purchase/account/exposure authorizations, retention policy |

Proposed operating values, **not yet owner-set** ([D7](#owner-decisions-before-bucket-creation)):
the thresholds above, review after the first invoice or 30 days (whichever is first),
and on any pricing, credential, retention or architecture change. No finite
incident-loss maximum is derived from notification timing. Deliberate non-payment or
project deletion is never an automatic cost control for locked backups.

## Proposed topology and retention changes

| Topology | Assessment |
|---|---|
| Lenovo initiates existing restricted pull, verifies ciphertext, uploads to immutable S3 | Closest to the prepared pull workflow and keeps cloud keys off VPS. Adds Lenovo uptime, staging capacity, patching, SSH transport and copy-lag dependencies. Lenovo root still controls upload credentials, local manifests and checks. Only previously locked cloud versions are protected; root can forge future success. Cannot host the authoritative legal/ledger/key material under the approved isolation rule |
| **VPS produces existing encrypted pair and uploads directly to protected S3** | **Owner-selected target; automation not activated.** Removes a required machine/transfer hop. Only restricted upload credentials reach VPS; cloud versions, independent receipt verification and owner-held keys provide recovery after either machine is lost. ADR-0033 explicitly revises the former source-with-no-destination-credential rule; exact credential policies remain to validate. It does not protect future captures from a compromised VPS, which the pull topology also cannot prove truthful |

Run the existing capture as a restricted host backup actor with its public key only;
no upload/admin key in application/Keycloak containers, build jobs, Docker metadata,
logs or Git. The observer receives its separately restricted read credential only.
Use a small S3 CLI adapter around the existing four-file inventory, not a new backup
engine. Upload unique opaque pair prefixes; publish a completion receipt last, naming
**all version IDs, sizes, recovery point and manifest SHA-256**. An independent reader
fetches the exact versions and verifies SHA-256; ETag is not a general content hash.
Partial/multipart uploads are never successes. Retry bounded parts with deadlines,
byte limits and concurrency one; leave capture failures fenced for owner action.
Automate the reviewed successful thaw/start/smoke sequence; never reopen on a failed
capture or remove a fence to make a scheduler green. A transport failure after safe
thaw alerts/retries without falsely claiming off-host success.

### Identity separation

- **Uploader on VPS:** dedicated non-owner S3 user, only required PUT/multipart actions
  to rolling-backup prefix. Explicitly deny GET/list of unrelated stores, DELETE,
  bypass, retention/hold changes, ACL/policy/bucket/configuration and key creation.
  Prove that explicit shorter/absent retention headers cannot defeat the default.
- **Expiry worker:** separate scoped credential, only list/delete versions in the
  rolling bucket, never bypass or configuration/retention rights. Compromise can
  delete only versions whose lock has ended. The
  [recommendation](#credentials-verification-and-cleanup-ownership) places it off the
  VPS so that expiry continues after host loss. It cannot touch archive/ledger buckets.
- **Independent observer:** read-only list/GET/retention-inspection credential in a
  protected scheduled workflow on the existing GitHub Actions service, running trusted
  default-branch code only, never PR inputs; no GPG private key. Verify account minutes
  and a no-paid-overage limit first. This is a small watchdog, not a backup engine.
- **Owner recovery/admin:** separately held read-version access, provider MFA/recovery
  codes, configuration authority and GPG private key. Never place these on VPS or
  Lenovo. Use a trusted owner administration context without agent/root access to
  keys. Its actual location and independent key recovery remain launch evidence.

Provider account control also protects the existing OVHcloud domain: dedicated
object users/project reduce data-plane blast radius but do not isolate a stolen
owner account from DNS/billing. Keep management sessions and automation keys separate,
protect recovery email independently of the VPS, and verify MFA/break-glass access.

### Separate recovery material

The following is a proposed storage placement, not a replacement retention policy.
The [canonical data-protection record](../development/data-protection.md#b-restricted-legal-archive)
continues to govern field profiles, lawful access, review, holds, expiry and replay.

| Material | Proposed independent protection |
|---|---|
| Deletion ledger and current checkpoint | Trusted owner administration storage plus separate encrypted S3 ledger bucket with owner-only publisher. Durable remote version/receipt acknowledgement **before each destructive step**, maintaining current two-copy refusal semantics; daily async copying is insufficient. Use append-only encrypted checkpoints/events and bounded reconciliation, not repeated unbounded full-history uploads. Retention follows the ledger policy, not rolling-backup expiry; [recommended lock](#separate-stores-and-replay) |
| Restricted legal archive and suppression/destruction/hold proofs | Trusted owner storage plus separate owner-only S3 archive/proof buckets. Preserve signed sealed two-copy acknowledgement before erasure. No Object Lock retention: case-specific narrowing/purge must take effect when decided ([recommendation](#separate-stores-and-replay)). Automate transfer/verification, retain required owner authorization for legal review and destruction |
| Pair manifests and verification/recovery receipts | Cloud locked exact versions plus independent observer receipts in protected owner storage, with monotonic checkpoint/version history. Never accept a mutable `latest.json` as sole evidence. Sanitized monitoring output must not include personal IDs/secrets |
| Reconstruction bundle | Independently encrypted production config/secrets, trusted source and OCI digest/provenance receipts, bootstrap inputs and remote-access recovery instructions; off both runtime hosts. Protected owner copy plus separate S3 prefix inaccessible to uploader/pruner; test image availability or a bounded retained image copy |
| Decryption/signing/reconstruction-unlock keys | Owner-controlled independent recovery storage separate from ciphertext and provider login; protected second key/recovery copy and tested passphrase/MFA recovery. No private key on VPS, Lenovo, Actions or in the bundle it unlocks |

Two buckets in the same account are **not** two independent copies. For ledger and
archives, owner storage is copy one and S3 is copy two, with no Lenovo mount. Actual
owner-storage/agent isolation must be demonstrated. Existing ledger/archive commands
accept filesystem paths: add the smallest explicit S3 durable-copy barrier/retrieval
adapter and focused interrupted-step tests before using this placement. Do not
claim it already works or weaken pre-delete confirmation to make cloud sync fit.
Automatic unverified-account cleanup must use this same barrier and stop when the
independent ledger/archive copy cannot be acknowledged.

### Detection and recovery

The independent workflow should check hourly for a complete remote pair within the
[recommended age thresholds](#monitoring-and-failure-handling), verify expected versions/size/digest/retention and compare the observer's previous
checkpoint. Alert immediately on failed attempts, unexpected versions/permissions,
lock changes, cap pressure or expiry backlog; missing uploads are detected without
trusting a VPS-provided success flag. Escalate at age 24 hours. Keep the runtime's
existing private Grafana/Telegram notices as a second route. A successful hash check
is transport integrity, not successful decryption/restore or truthful source data.
Hash each newly published version set once by streaming it; subsequent hourly checks
inspect metadata and continuity, rather than downloading the entire retained history.
Bound pagination/object counts and stop/alert on overflow. Keep sanitized observer
receipts in private immutable-per-run Actions artefacts for the applicable bounded
period and copy accepted recovery proofs to protected owner storage; no plaintext
dumps or secrets enter Actions artefacts. Confirm actual artefact access/expiry.

Send the observer's daily heartbeat to the owner's existing private channel and
configure independent failed-workflow notifications; test missed-schedule/disabled-job
and delivery failures. GitHub scheduling and Telegram are best effort and neither
can prove its own total outage. Owner must accept/check the daily heartbeat as the
minimum dead-man control; a paid external watchdog is optional, not assumed. Do not
forge a new full-recovery receipt on each upload. Adapt the attestation contract to
distinguish recent remote integrity from the latest matching **actually restored**
pair; rehearse a replacement before its 35-day expiry.

Propose a weekly owner-side full restore and an additional restore after capture,
schema, key or recovery-tool changes ([D5](#owner-decisions-before-bucket-creation)). The existing attestation requires the **same**
pair to have a successful full rehearsal: it cannot continuously report a fresh
RPO from new uploads with only last week's restore receipt. Owner must approve the
separate integrity-age and full-restore-age evidence contract before implementation.
If “last verified pair” continues to mean “individually fully restored within 24
hours”, weekly rehearsal does not meet it: daily owner-side restores are required,
or that RPO claim remains unproven. No unattended decryption key is proposed to hide
this limitation.

After **complete VPS loss**, use a trusted owner machine and retained credentials to
read exact cloud versions, reconstruct an empty replacement from trusted digests and
protected inputs, restore through the existing owner-side SSH Docker context, reconcile
the current independently protected ledger and archive proofs, replay deletions,
invalidate stale sessions and verify identity/rating consistency before reopening.
After **Lenovo compromise**, revoke any Lenovo access, rebuild it separately and
perform the same cloud-only recovery from a clean owner context; the proposed design
places no recovery key, admin authority or required unique copy on Lenovo. Old
locked versions survive compromised VPS upload/expiry credentials; treat captures
since compromise as suspect. Detect/respond before the protected history expires.

Propose acceptance of **RPO <=24 hours** for the last independently verified recoverable
pair and **RTO <=48 hours from detection**, as already proposed by the platform owner.
Neither is measured or an SLA. Record capture, quiescence, upload, remote hash,
decryption, fresh-host bootstrap, image retrieval, restore, ledger replay and journey
times at measured production-sized byte counts. A two-hour transfer budget for a
200 GiB pair alone needs about 239 Mbit/s before overhead; total 200 GiB history is
not necessarily one pair. Include provision time, bandwidth limits, owner availability,
key recovery and restart fencing in the timed RTO.

## Object Lock retention recommendation

**Historical recommendation dated 2026-10-25; subsequently approved only to the extent stated in the [2026-10-29 implementation boundary](#local-s3-adapter-evidence).**
Until approval, the [platform design](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
and the [data-protection record](../development/data-protection.md#b-restricted-legal-archive)
keep their current wording. This section supersedes the earlier 34-day-lock,
12-hour-admission proposal. Repository facts come from `deploy/public-production/bin/recovery`
(`backup` records `recoveryPoint` after the write fence; `retain` is a filesystem
selector) and `verify-offhost.py` (accepts only an `encrypted-pull` receipt).

### Provider semantics

Verified means stated in current OVHcloud documentation, read 2026-10-25 without
account access. The S3 API reference is AWS's documentation, used only where
OVHcloud is silent; it is never evidence of OVHcloud behaviour.

| Topic | OVHcloud documentation (verified) | Undocumented; provider test |
|---|---|---|
| Enablement | Object Lock is enabled at bucket creation only, irreversibly, and needs versioning | None |
| Compliance | No user, including administrators, can delete or modify the protected version during retention; "its retention mode and duration cannot be changed" | Whether a later retain-until date (extension) is accepted. The S3 reference lets any `s3:PutObjectRetention` holder extend it, so assume extension is possible ([P3](#tests-that-validate-the-semantics)) |
| Default vs per-object | A bucket default in days or years applies to every new object; per-object retention is an absolute `RetainUntilDate`; minimum one day, no maximum | When the default starts and whether it rounds. The S3 reference adds the duration to the version's creation timestamp; for multipart, initiation or completion is unknown ([P1](#tests-that-validate-the-semantics)) |
| Deletes | A simple DELETE returns 200 and adds a delete marker; the version stays protected. DELETE by version ID during retention returns 403. A version-ID delete is permanent and irreversible, with no marker | Physical reclamation time after a permanent delete; time until the version leaves listings ([P4](#tests-that-validate-the-semantics)) |
| Legal hold | Same protection without an expiry, until removed by an `s3:PutObjectLegalHold` holder; independent of retention | None |
| Lifecycle | In a versioned bucket, Expiration adds a delete marker; `NoncurrentDays` counts from when a version stopped being current; `ExpiredObjectDeleteMarker` and `AbortIncompleteMultipartUpload` exist. "Most rules are applied within 24 hours", sometimes later, and billing continues meanwhile | Lifecycle on locked versions. The S3 reference never deletes a locked version ([P6](#tests-that-validate-the-semantics)) |
| Billing | Each version is a full billed object; no Object Lock surcharge is shown | Locked, noncurrent and incomplete-multipart bytes in actual usage ([P7](#tests-that-validate-the-semantics)) |
| IAM | User policies only (no bucket policies); explicit Deny wins; the bucket owner falls back to a FULL_CONTROL ACL. Listed actions include `PutObjectRetention`, `PutObjectLegalHold`, `BypassGovernanceRetention`, `ListBucketVersions`, `AbortMultipartUpload`, lock and lifecycle configuration. Only `aws:SourceIp` is a documented condition key | Version-level delete/read actions are not listed separately; whether a PUT carrying retention or legal-hold headers needs those permissions ([P2](#tests-that-validate-the-semantics), [P5](#tests-that-validate-the-semantics)) |

Sources: [Object Lock](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-managing-object-lock),
[versioning](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-versioning),
[lifecycle](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-bucket-lifecycle),
[IAM](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-identity-and-access-management),
[compatibility](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-s3-compliancy);
S3 reference: [AWS Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html).

### Can a strict 35-day maximum be guaranteed?

**Not unconditionally, and not by any storage design.**

1. Object Lock sets a minimum lifetime, never a maximum. Nothing deletes a version
   when its lock ends; a separate request must succeed, and a provider outage, a
   revoked credential or a lost worker host can stop it.
2. Lifecycle cannot enforce the ceiling. It is asynchronous ("within 24 hours", or
   later) and, for versioned objects, needs two steps: a marker, then noncurrent expiry.
3. After an accepted version-ID delete, physical reclamation is internal to the
   provider. The client can verify only logical absence. This is the same limit
   the data-protection record already accepts for SSD unlink.
4. Compliance makes an error permanent. A version locked past recovery point plus
   35 days cannot be removed by anyone until its lock ends. Late uploads, date
   rounding, an extension, a legal hold or a wrong default all cause this.

What design **can** guarantee: every version's lock ends at least 18 hours before
recovery point plus 35 days, and no automation credential can lengthen it. What only
operation can guarantee: the deletion request succeeds inside that window. The
filesystem selector has the same property today, because `retain` deletes only
when it runs.

The real contradiction is therefore narrow. Compliance retention and the ceiling are
compatible only if lock + maximum upload lag + date rounding + cleanup window fits
inside 35 days. A literal "no byte exists anywhere after 35 days" requirement cannot
be met by any third-party storage, with or without a lock.

**Smallest defensible adjustment ([D1](#owner-decisions-before-bucket-creation)):**
define how the existing ceiling is measured for provider storage. Every version is
deleted by exact version ID, and its absence verified, no later than recovery point
plus 35 days. Provider-internal reclamation follows the processor's deletion terms.
A missed deadline is a recorded retention incident with continued deletion attempts,
never permission to keep. The 35-day figure and its recovery-point anchor are unchanged.

### Recommended policy

| Parameter | Value | Reason |
|---|---|---|
| Anchor | Manifest `recoveryPoint` (VPS clock, after the fence) | Already recorded; the ceiling is measured from it |
| Capture cadence | Every 12 hours at two fixed UTC slots; the owner picks low-traffic slots (proposal: 02:30 and 14:30 UTC) | The newest verified pair is normally at most 14 hours old; one missed run is detectable before the proposed 24-hour RPO lapses |
| Upload admission | All objects of a pair, receipt last, complete within **6 hours** of `recoveryPoint`. The uploader refuses to start or continue after that. An abandoned pair is never re-uploaded; the next capture replaces it | Bounds every lock start. Shorter than the cadence, so attempts never overlap |
| Immutable protection | Bucket default **COMPLIANCE, 33 days**; no per-object retention, no legal holds, no retention rights for any automation | At least 33 days of protection from upload. Lock ends by `recoveryPoint` + 33 d 6 h (+24 h if P1 shows next-day rounding) |
| Cleanup window | From lock end to `recoveryPoint` + 35 d: **at least 42 hours**, or **at least 18 hours** with next-day rounding | Covers a worker or provider outage of roughly a day |
| Deletion | Hourly: delete every version and delete marker whose provider-reported retain-until has passed and that has no legal hold, by version ID, then verify absence | Exact-version, idempotent; never the bypass header |
| Lifecycle (backstop only) | `AbortIncompleteMultipartUpload` 1 day; `ExpiredObjectDeleteMarker`; current-version Expiration 34 days plus `NoncurrentDays` 1 | Cleans orphans and bounds a dead worker. It is not the ceiling mechanism and may finish after day 35 |
| Copies | Keep every pair until its lock ends: about **67 pairs**, plus at most one in flight. No 7-daily/4-weekly selection in the cloud | A uniform Compliance lock cannot prune early. Same ceiling, more copies. Budget about 2.9 GiB per pair before auxiliary stores |
| Clock | NTP-synchronised VPS. The uploader refuses when VPS and provider `Date` differ by more than 60 s. The worker decides from the provider's retain-until and `Date` | Margins are hours, skew is bounded to seconds |

**34 days rejected.** With a 6-hour admission window, the lock ends by `recoveryPoint`
+ 34 d 6 h. That leaves 18 hours if timestamps are exact, and **minus 6 hours**
(a ceiling breach) under next-day rounding. With the earlier 12-hour admission window
the margins are 12 hours and minus 12 hours. One extra day of protection is not worth
a weekend-outage margin of hours. Keep 33 days even if P1 proves exact timestamps.

### Timeline

```text
Normal (D = day of capture, times UTC)
D0  02:30  fence, capture; recoveryPoint = D0 02:30
D0  02:45  last object + receipt uploaded (LastModified); thaw
D0 ~04:30  observer streams exact versions once, SHA-256 matches manifest
D0  14:30  next capture
D33 02:45  provider retain-until (upload + 33 d) passes
D33 03:00  worker deletes every version of the pair by version ID; absence verified
D35 02:30  ceiling. Unused margin: 47.5 h

Captured yesterday, uploaded today
D0  14:30  capture; upload stalls
D0  20:30  admission window closes: abort multipart, record failure, alert
D1  02:30  next capture replaces it; the stalled pair is never uploaded.
           Uploading it on D1 would lock it until D34 + upload time, so it is refused
Latest admissible: complete at D0 20:29 -> lock ends D33 20:29 -> 42 h margin

Retry inside the window
Each attempt writes new keys pairs/<recoveryPoint>/<backupId>/<attempt>/...
Completed objects of a failed attempt are ordinary locked versions inside the
same bounds; the worker deletes them by enumerating all versions, not by receipt.
The observer alerts on any key with more than one version.

Interrupted cleanup
D33 03:00  worker fails (host or provider down)
D33 05:00  warning: due version not deleted for 2 h
D34 14:30  critical: recoveryPoint + 34 d 12 h; owner runs the same delete-only
           command manually from the owner context
D35 02:30  deadline missed: retention incident recorded; hourly retries continue

Lock longer than expected (rounding, extension, legal hold, wrong default)
Observer sees retain-until > LastModified + 33 d + 5 min, mode != COMPLIANCE,
hold ON or a changed default -> critical immediately: revoke the uploader, record a
retention incident. The version cannot be removed until its lock ends, which is
why P1-P3 must pass before any personal data.
```

### Credentials, verification and cleanup ownership

This replaces the expiry-worker placement in [identity separation](#identity-separation).

| Actor | Location | Allowed | Explicitly denied |
|---|---|---|---|
| Uploader | VPS backup actor | PUT and multipart create/part/complete/abort under `pairs/` in the rolling bucket | Every read, list and delete; retention, legal hold, bypass; lock, lifecycle, ACL and versioning configuration; other buckets |
| Observer | Independent protected scheduled workflow, trusted default-branch code | List versions; read exact versions, retention and holds; read lock and lifecycle configuration | Every write and delete |
| Expiry worker | Same independent environment as the observer, separate job and secret; the owner can run it manually | List versions, read retention and holds, delete by version ID in the rolling bucket only | PUT, retention, legal hold, bypass, configuration, other buckets |
| Owner administration | Trusted owner context | Bucket creation, lock and lifecycle configuration, credential issuance, recovery reads | Routine use; any legal hold on the rolling bucket |

The VPS keeps at most the last uploaded pair locally and removes it after the next
pair uploads. The uploader cannot read, so a compromised VPS cannot re-upload history
it no longer holds. It can upload garbage or fresh data, but only with the default
lock, which expires normally. A compromised worker can delete only versions whose
lock has already ended. The worker runs off the VPS because host loss is precisely
when expiry must continue. A worker on the VPS would stop while the host is rebuilt.

### Monitoring and failure handling

| Signal | Warning | Critical / action |
|---|---|---|
| Newest integrity-verified pair age | > 14 h | > 24 h; owner triggers a manual capture |
| Upload attempt failed or window closed | Immediately | Second consecutive failure |
| Due version not deleted | Due + 2 h | `recoveryPoint` + 34 d 12 h: manual deletion. At + 35 d: retention incident under the [incident procedure](../development/data-protection.md#security-incident-and-personal-data-breach-procedure); retries continue |
| Lock invariant (mode, retain-until window, hold, upload lag, versions per key, unknown prefix, changed default or lifecycle) | None | Immediately; revoke the uploader |
| Newest fully restored pair age | > 8 d | > 14 d; rehearse before day 28 |
| Retained bytes and forecast cost | 180 GiB; forecast EUR 2 | Refuse a new pair projected above 200 GiB (alert, never delete); forecast EUR 3 |
| Observer heartbeat | Daily | Missing heartbeat |

A missed expiry deadline is a retention incident: the data stays encrypted to the
owner's key and its confidentiality is unchanged. The owner assesses notification
duties under the incident procedure; this research does not presume the outcome.

**Expiry never waits for a rehearsal ([D3](#owner-decisions-before-bucket-creation)).**
Today `retain` refuses all pruning when the pinned rehearsed pair is missing or
35 days old. Under Compliance storage, that refusal would breach the ceiling. In the
cloud, the pinned pair is deleted on schedule, and the full-restore-age alerts leave
about three weeks to rehearse a newer one. If none exists at deletion, record a
recovery-evidence incident and rehearse at once. Integrity-verified pairs remain
recoverable, but unproven.

**Recovery point.** Normally, the newest integrity-verified pair is at most 12 h +
capture/upload/verify time (target 2 h) = 14 h old. One missed run raises that to
about 26 h, until a manual capture or the next slot. The proposed RPO of 24 hours
holds only with a manual capture within about 10 h of the alert. Point-in-time
history spans about 33 days at 12-hour granularity. RTO is unaffected: recovery
reads exact version IDs.

### Separate stores and replay

| Store | Object Lock | Compatibility with the existing policy |
|---|---|---|
| Rolling pairs and receipts | COMPLIANCE default, 33 days | As above |
| Deletion-ledger cloud copy (append-only events and checkpoints) | Separate bucket and owner-only publisher; COMPLIANCE default **36 days**. The driver waits for PUT success and a version ID before each destructive step | The ledger must live while any restorable copy exists and at least 35 days after completion. A lock is a minimum, so 36 days fits, and incomplete entries can be kept longer. Superseded checkpoint versions can outlive a purged entry by up to 36 days ([D6](#owner-decisions-before-bucket-creation)) |
| Legal archive payloads, manifests, suppression/destruction/hold proofs | Separate bucket(s) created **without** Object Lock; versioning; owner-only credentials; signed sealing; independent owner-storage copy | Review can narrow fields and destruction must take effect when its lawful term ends. Any Compliance term would block that, and Governance mode is not approved. A bucket created without a lock can never gain one |
| Reconstruction bundle | Separate bucket without Object Lock, inaccessible to uploader and worker; no personal data | Secrets rotate; the owner copy is primary |
| Decryption, signing and unlock keys | Never stored in OVHcloud | Owner-held independent storage with a tested second copy, unchanged |

**Replay check.** Take a backup with recovery point `t0` and an erasure completed at
`t1 > t0`. The backup exists until at most `t0 + 35 d`; the erasure's ledger entry
exists until at least `t1 + 35 d`. Every restorable backup therefore meets a ledger
that contains each later erasure, and incomplete entries are never purged. Two buckets
in one account are still not two independent copies: owner storage remains copy one
for the ledger and the archive.

### Compatibility assessment

| Existing contract | Owner | Result |
|---|---|---|
| Rolling copies at most 35 days from the recovery point | Data protection, platform | Compatible under D1 |
| Upload-relative lock leaves room for lag and deletion; expiry is not deletion | Data protection | Satisfied: 33 d + 6 h + rounding leaves an 18–42 h window; the worker deletes and verifies |
| No indefinite legal holds; no clock reset by re-upload | Data protection | Satisfied: no automation holds hold rights; re-upload is refused and the observer detects it |
| Ledger while any restorable copy exists, at least 35 days after completion | Data protection | Compatible (36-day minimum lock) |
| Synchronous pre-mutation ledger/archive durability, two independent copies | Data protection | Compatible only if the adapter waits for the version-ID acknowledgement. Asynchronous sync stays forbidden |
| Case-specific archive narrowing, holds and destruction | Data protection | Compatible only without Object Lock on archive and proof buckets |
| Seven daily plus four weekly copies, at most twelve pairs | Runtime `retain` | **Incompatible** with a uniform lock. Replaced in the cloud by about 67 pairs (D2); kept for optional filesystem copies |
| Keep the last rehearsed pair before pruning | Runtime `retain` | **Conflicts** with the ceiling under Compliance; resolved by D3 |
| Proposed RPO <=24 h for the last verified pair; RTO <=48 h | Platform (proposal) | Achievable with 12-hour cadence and the D5 evidence contract; RTO unaffected |
| Off-host attestation from an `encrypted-pull` receipt | `verify-offhost.py` | Needs a cloud receipt kind and separate integrity and full-restore ages |

### Tests that validate the semantics

**Local, after implementation approval.** Use an in-process fake S3 client in the
existing `scripts/test-production-recovery.py` style. Emulator behaviour never
substitutes for the provider tests.

| Test | Proves |
|---|---|
| L1 | Admission refuses to start or continue after `recoveryPoint` + 6 h and on more than 60 s clock skew |
| L2 | Unique attempt keys; a second version on one key fails the observer |
| L3 | An interrupted multipart upload is aborted; partial is never success; the receipt is last and lists every version ID, size and SHA-256 |
| L4 | The worker selects only expired, unheld versions and markers; deletes by version ID; verifies absence; never sends the bypass header; bounded pagination alerts on overflow |
| L5 | Alert ladder at due + 2 h, + 34 d 12 h and + 35 d with a frozen clock |
| L6 | Observer invariants: mode, retain-until window, hold, upload lag, unknown prefix, configuration drift |
| L7 | Deletion does not wait for the rehearsal pin; full-restore-age alerts fire |
| L8 | A ledger destructive step refuses on a missing or timed-out version acknowledgement, with crashes at each boundary |
| L9 | A new pair projected above 200 GiB is refused without deleting anything |

**Provider tests.** Use a disposable lock-enabled test bucket in Gravelines, synthetic
non-personal bytes and a 1-day default, after the separately authorized account setup.
P1–P5 block activation.

| Test | Proves |
|---|---|
| P1 | Default retention start and rounding for single PUT and for multipart (initiate, wait, complete) |
| P2 | Uploader PUTs carrying retain-until (later and earlier) or legal-hold headers are refused or ignored; effective retain-until equals the default; `PutObjectRetention` on an existing version is refused |
| P3 | Owner shortening of Compliance retention is refused; extension behaviour recorded |
| P4 | Version-ID DELETE returns 403 before expiry; a simple DELETE adds a marker; after expiry the version-ID DELETE succeeds and listing and GET show absence, with the time taken |
| P5 | Effective denies for the uploader, observer and worker, including ACL fallback, the bypass header and configuration writes |
| P6 | Lifecycle never removes a locked version; measured delay afterwards; incomplete multipart abort |
| P7 | Locked, noncurrent and multipart bytes appear in usage; the forecast alert fires |
| P8 | Provider `Date` against NTP time |

If P2 fails (the uploader can set a lock or a hold), this bucket design is **NO-GO**.
A compromised VPS could then lock personal data past the ceiling.

### Original implementation proposal (2026-10-25)

- `deploy/public-production/bin/recovery`: add `publish` (upload the existing
  four-file pair plus a receipt under the admission window and unique attempt
  keys), `fetch` (exact-version download into the existing `verify`/`restore`
  path) and `expire` (delete-only worker). Keep `retain` unchanged for filesystem
  copies; it is not used on the cloud store. Pin the S3 client through the existing
  dependency manifests at implementation review.
- `deploy/public-production/observability/verify-offhost.py`: accept the cloud and
  observer receipts, and report integrity age and full-restore age separately (D5).
- `deploy/private-dev/bin/personal-data-ledger` and `personal_data_archive.py`: an S3
  durable-copy barrier that waits for the version ID, and a lock-free archive adapter.
- Later slice: the observer/worker scheduled workflow and the host capture timer.
  No OpenAPI, Postman, migration or image change.

### Owner decisions before bucket creation

Object Lock is fixed when a bucket is created, so these decisions precede creation.

| ID | Decision | Recommendation |
|---|---|---|
| D1 | How the 35-day ceiling is measured for provider storage | Exact-version deletion verified by recovery point + 35 d; provider reclamation per processor terms; a missed deadline is an incident |
| D2 | Retention parameters | 12-hour cadence, 6-hour admission, 33-day Compliance default, keep all pairs (about 67); no cloud daily/weekly selection |
| D3 | Expiry versus the rehearsed-pair pin | Expiry wins; alert ladder on full-restore age |
| D4 | Expiry-worker location | Independent scheduled environment with a delete-only credential, plus a manual owner fallback; not the VPS |
| D5 | Restore-evidence contract | Integrity-verified pair at most 24 h old, plus a full restore at most 8 days old with the same tooling, format and key |
| D6 | Ledger and archive locks | Ledger bucket COMPLIANCE 36 days; archive, proof and reconstruction buckets created without Object Lock |
| D7 | PAYG operating values | 180/200 GiB, forecast EUR 2/3, review after the first invoice or 30 days |

**Verdict: CONDITIONAL GO.** Approve D1–D7 as the target policy. Activation stays
blocked until P1–P5 pass on a test bucket. If P1 shows rounding beyond one day, or
P2 or P5 fail, the policy returns for revision before any personal data.

## Decision alignment and next bounded slice

**Approved on 2026-10-25:** the infrastructure, total budget, 200 GiB planning target,
Compliance/direct-upload direction, expendable Lenovo boundary and scoped uncapped
OVHcloud PAYG risk recorded above.
ADR-0033 partially supersedes ADR-0025 without rewriting its rationale. Canonical
Product Brief/platform/privacy records are aligned in this documentation change;
#275 acceptance, source/image identities and historical evidence remain unchanged.

**Contracting evidence still required:**

- Netcup actual Lite 3 SKU/location/architecture, stock, quote with applicable VAT,
  two-month minimum/billing commitment, cancellation notice/deadline, renewal,
  traffic/abuse restrictions, DPA/entity/subprocessors and support/deletion terms.
- OVHcloud Spanish contracting entity, exact full-region Gravelines endpoint/Standard
  offer, VAT/GiB-hour rounding/minimum/account charges, applicable DPA/service terms,
  remote support/subprocessors/transfers, closure/deletion/retrieval and lock-related
  billing. The accepted PAYG exception replaces the hard-cap requirement for this
  service only. Do not treat a selected region as an accepted DPA.
- Actual domain/DNS renewal and contact-mailbox charges, Mailjet Free/account terms,
  CI/monitor/administration extras and final combined recurring/initial cash quote.
  The EUR 2 email contingency stays reserved; no fallback service purchase is approved.
- Ratification of outstanding [D3, D6 and exact D7 values](#owner-decisions-before-bucket-creation)
  before any bucket is created; private storage and key locations.
  No silent widening of retention or weakening of two-copy barriers.

**Actions requiring separate owner authorization:** any account/project/credential
setup or purchase, including the provider-test bucket;
then host installation/deployment/DNS changes and restricted real-host exercises;
and finally public exposure. Documentation approval is none of these authorizations.

**Original next slice (authorized locally on 2026-10-29; see [evidence](#local-s3-adapter-evidence)):** prepare
one bounded S3 publish/exact-version retrieval/integrity/expiry adapter around the
existing four-file encrypted pair, with an independent receipt and missed-run check.
Keep endpoints, buckets and keys as private inputs; do not provision them. Use focused
fake-S3 tests [L1–L9](#tests-that-validate-the-semantics); do not repeat #275 rehearsals. No new backup engine,
S3 mount, infrastructure or resource-limit increase is needed. Durable ledger/archive
remote-copy barriers and the reviewed scheduler/attestation integration must follow
before any real destructive cleanup or production automation uses the cloud target.

After authorized provider setup, run provider tests [P1–P8](#tests-that-validate-the-semantics);
then measure capture/upload/verification age and a clean cloud-only empty-host restore
with Lenovo unavailable. Include reconstruction/key recovery, latest ledger replay,
old-session revocation and owner notification. Real-host RPO/RTO remain unmeasured;
source checksums or provider selection do not establish recoverability.

Mandatory before public accounts: effective independent copies/keys and permissions,
retention/deletion barriers, missed-job detection, acceptable billing/terms and tested
cloud-only recovery. Optional future 3-AZ/second provider/paid watchdog work requires
new evidence and approval, not a provider comparison restart here.

**Historical 2026-10-25 research change:** documentation-only, no executable artefact or SemVer bump. #276 remains open for
contracting decisions, implementation and real-host acceptance. No account, credential,
purchase, infrastructure/DNS change, deployment, commit or merge occurred.


## Local S3 adapter evidence

**Prepared locally for #276, 2026-10-29; no provider or production acceptance.**
The [owner approval](https://github.com/rubhern/videogame-platform/issues/276#issuecomment-6483692685)
authorizes this bounded adapter and fake-S3 tests. It approves the summarized target
policy: 12-hour capture, 33-day Compliance, verified exact-version deletion within
35 days, integrity age at most 24 hours, full restore at least every 8 days,
independent expiry actor, off-VPS recovery keys and spend monitoring within the
approved planning budget. The [platform policy](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
and [privacy record](../development/data-protection.md#b-restricted-legal-archive)
own those guarantees. D3, D6 and D7's precise values remain recommendations.
The runtime [S3 preparation guide](../../deploy/public-production/README.md#s3-transport-preparation-276)
owns commands, protected inputs and limitations.

[`test-production-recovery-s3.py`](../../scripts/test-production-recovery-s3.py)
uses an in-process fake and frozen clock. It reuses the existing pair verifier;
synthetic bytes do not establish encryption or provider enforcement.

| Reference | Local guarantee and limit |
|---|---|
| L1 | Refuses admission at six hours, future recovery points, stale capacity evidence and clock skew; rechecks between parts/objects and after completion. An already accepted late write cannot be undone; no success receipt is issued |
| L2 | Unique attempt prefixes; observer detects multiple versions; fetch reads the recorded version even when another exists |
| L3 | Receipt last, binds four exact version IDs/sizes/SHA-256; multipart failure attempts abort; missing acknowledgement and corruption never publish a complete local destination. Process death or failed abort leaves an orphan for later reconciliation |
| L4 | Bounded version/marker inventory and expiry selection demonstrated; **planning only**. Exact-version DELETE, post-delete absence and bypass rejection remain unimplemented and unproven |
| L5 | Frozen-clock alerts at due + 2 hours, recovery point + 34 days 12 hours and + 35 days; no automatic notification channel installed |
| L6 | Observer refuses mode/hold/retention/configuration drift, unknown keys and missing versions; streams exact ciphertext versions through the existing checksum verifier |
| L7 | Integrity and restore ages are separate; missed-copy and restore-age alerts demonstrated. Pin override remains unimplemented pending D3; no deletion occurs |
| L8 | Not implemented: synchronous remote ledger/archive durability and crash-boundary tests remain a separate required slice. Pair version-acknowledgement tests do not satisfy this barrier |
| L9 | Caller-specified projected byte budget enforced using fresh protected observer evidence, without deletion. No fixed 200 GiB enforcement decision: exact D7 values remain pending. Concurrent attempts, incomplete multipart bytes and account-wide consumption still require operational reconciliation |

P1–P8 remain entirely unexecuted. SDK and fake behaviour do not establish OVHcloud
semantics, effective IAM/ACL denies, provider clocks, physical deletion, lifecycle,
billing or monitored consumption. P1–P5 block activation; retain P6–P8 as qualification
requirements. No cloud-only restore, achieved RPO/RTO, independent environment/key
isolation, observer heartbeat delivery, real missed-job notification or cloud
ledger/archive barriers are proven. `verify-offhost.py` retains its accepted
filesystem-only attestation contract; S3 observation cannot set its green recovery
flag. Scheduler/attestation integration and real deletion require a later reviewed
slice, ratification of pending decisions and separate provider authorization.

The optional adapter starts its own operational version at `0.1.0` (new compatible
capability); application, frontend, candidate #275 and migration versions are unchanged.
#276 remains open. No account, bucket, credential, deployment, job, commit or publication
is created by this increment.
