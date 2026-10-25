# Automated off-host backup storage (#276)

- **Status:** Provider/topology, Lite 3 and revised budget approved by owner on 2026-10-25; contracting blocked, implementation not authorized
- **Research date:** 2026-10-25
- **Repository inspected:** `147f7f28c0b637f89314aa997303a0847f1617c3`
- **Consumer:** Owner decision within [#276](https://github.com/rubhern/videogame-platform/issues/276)

Point-in-time comparison and proposed changes only. The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns recovery policy; the [data-protection record](../development/data-protection.md#b-restricted-legal-archive)
owns deletion/archive obligations; the [runtime guide](../../deploy/public-production/README.md#paired-backup-and-recovery-candidate)
and its scripts own mechanics. Those approved sources remain unchanged. The accepted
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
| Total recurring budget increased from EUR 20 to EUR 25/month | Includes VAT, renewal, email, storage, administration and recurring extras. Keep EUR 2 email contingency; no uncapped-overage exception follows |
| OVHcloud S3 Object Storage Standard, 1-AZ Gravelines | Full region, not Local Zone/Swift. Documented single-zone durability limitation explicitly accepted for this topology |
| Plan for 200 GiB actually retained | Approximately EUR 1.72/month including VAT, not purchased capacity, provider quota or enforceable spending cap |
| Fully automated encrypted direct VPS-to-S3 uploads | Compliance retention, least privilege, independent integrity/missed-job verification and tested recovery; not implemented |
| Lenovo optional and expendable | Removed from mandatory production recovery; no required unique copy, key or administrative dependency there |

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

**Contracting blocker:** no enforceable aggregate OVHcloud spending cap is verified.
Public documentation establishes alerts, resource quotas and prepaid credit, not a
hard aggregate charge limit. The existing capped-overage gate remains effective;
provider/budget approval is not risk acceptance. The [specific draft below](#pending-owner-risk-acceptance-if-no-hard-cap-is-available)
is **pending**, not a waiver or purchase authorization.

Only the decisions in the table are approved. The proposed 34-day lock, 12-hour
capture interval, expanded retained-copy count, archive/ledger cloud placement,
GitHub watchdog and weekly-restoration evidence semantics below remain unapproved
operational parameters. Reconcile them with the canonical 35-day and privacy/recovery
requirements before implementation; no implicit policy change is recorded here.

## Evidence and current implementation

**Verified in repository:**

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

### Pending owner risk acceptance if no hard cap is available

**Status: draft only; NOT ACCEPTED.** Owner approval of provider and EUR 25 ceiling
does not approve this exception. First establish whether the selected account can
have a hard aggregate limit covering Standard storage, all versions/multipart objects,
auxiliary buckets and all billable project services, with automatic increases disabled.
Record scope, tax/currency, enforcement delay, limits and recovery/billing behaviour.
A sales assurance or resource-count quota alone is insufficient.

If that route is unavailable, the exact decision requiring a separate owner response is:

> I accept that this OVHcloud PAYG project has no verified hard aggregate spending
> cap. The 200 GiB target and EUR 25 normal recurring budget are operating limits,
> not maximum contractual liability. Credential compromise, retained versions,
> automation/expiry errors or delayed alerts can create charges above EUR 25, with
> no verified financial upper bound; Compliance-locked bytes can remain billable
> after uploads are stopped or keys revoked. I explicitly authorize a scoped
> exception to the capped-overage contracting requirement for this project only,
> subject to the agreed alerts, independent inventory, least privilege, retention
> review and incident response. This does not change the normal recurring ceiling,
> authorize another paid resource, or grant purchase or public-exposure permission.

Before signing, owner must record the actual project/service scope, which uncapped
liability is acceptable (or reject the exception), selected warning/stop thresholds,
incident contact/coverage, revocation procedure, retention-compatible exit plan and
review date. Proposed review: after the first invoice or 30 days, whichever is first,
and on pricing, credential, retention or architecture change. If the owner requires
a guaranteed financial maximum, this uncapped exception cannot meet it. No finite
incident-loss maximum is invented from notification timing.

Only an explicit approval plus matching [contracting-gate](../development/delivery-lifecycle.md#public-production-gates)
record/update could enable this exception. Until then **purchase remains NO-GO**;
no accounts, support requests, payment commitments or project deletion are authorized.

## Proposed topology and retention changes

| Topology | Assessment |
|---|---|
| Lenovo initiates existing restricted pull, verifies ciphertext, uploads to immutable S3 | Closest to the prepared pull workflow and keeps cloud keys off VPS. Adds Lenovo uptime, staging capacity, patching, SSH transport and copy-lag dependencies. Lenovo root still controls upload credentials, local manifests and checks. Only previously locked cloud versions are protected; root can forge future success. Cannot host the authoritative legal/ledger/key material under the approved isolation rule |
| **VPS produces existing encrypted pair and uploads directly to protected S3** | **Owner-selected target; not implemented.** Removes a required machine/transfer hop. Only restricted upload credentials reach VPS; cloud versions, independent receipt verification and owner-held keys provide recovery after either machine is lost. ADR-0033 explicitly revises the former source-with-no-destination-credential rule; exact credential policies remain to validate. It does not protect future captures from a compromised VPS, which the pull topology also cannot prove truthful |

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
  rolling bucket, never bypass or configuration/retention rights. May run on VPS:
  compromise can delete expired versions but cannot erase the Compliance window.
  This residual last-day risk is explicit. It cannot touch archive/ledger buckets.
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

### 35-day boundary and expiry

**Unapproved operational proposal, not part of the 2026-10-25 selection:** keep every automated pair for the bounded rolling window,
rather than applying the existing seven-daily/four-weekly filesystem selector to
immutable objects. A uniform default is easier to enforce than tier-dependent lock
extensions. Preserve the **35-day maximum from the recorded recovery point**.
Do not configure a 35-day upload-relative lock and call it policy-compliant.

Use **34-day Compliance default retention**, admit uploads only within 12 hours of
the recovery point, and schedule version deletion at recovery point +34 days 12 hours,
with hourly retries/alerts and a hard deadline at +35 days. Validate actual timestamps
and provider behaviour before personal data. This gives at least 34 days protected
from data-plane deletion and a bounded cleanup margin. Configure lifecycle expiry of
current/noncurrent versions and abandoned multipart uploads as a safety net, not
proof of exact-time erasure. No legal holds on rolling backups. Refuse late uploads
rather than extending their privacy lifetime; generate a new pair.

Retain exact-version receipts for as long as their pair exists; expire their personal
metadata under the applicable policy. Cloud expiry must enumerate **all versions and
markers**, not just hide current names; verify absence after deletion. Storage failures,
clock errors, lock extension by an owner or delayed provider cleanup are incidents,
not permission to retain indefinitely. Obtain documented handling of provider replicas,
service backups, account closure and deletion completion; neither cloud's lifecycle
provides a verified exact 35-day physical-erasure SLA here.

Preserve the existing last-rehearsed-pair safeguard: require a newer successfully
restored pair before pruning that proof's version set. Alert on day 28 if replacement
is missing; at impending expiry stop normal operation and require the owner's
recovery/privacy incident decision. Refusal must never silently extend storage past
35 days or silently discard the only demonstrated recovery point.

To leave transfer/verification margin under the existing proposed RPO, schedule a pair
**every 12 hours** (satisfies daily capture), completion/remote verification within two
hours, immediate failure notification. Retaining these means up to about **70 pairs**
plus bounded in-flight/version overhead, not twelve. For the approved 200 GiB planning target, budget roughly
2.8 GiB per encrypted pair **before** auxiliary stores/headroom; measure actual sizes.
For constant pair size this is about 5.8× the previous twelve-pair upper bound. A
single daily job would have an RPO of 24 hours **plus** capture/transfer delay; do not
claim strict <=24 hours from a daily timer alone. Recalibrate cadence only with evidence.

### Separate recovery material

The following is a proposed storage placement, not a replacement retention policy.
The [canonical data-protection record](../development/data-protection.md#b-restricted-legal-archive)
continues to govern field profiles, lawful access, review, holds, expiry and replay.

| Material | Proposed independent protection |
|---|---|
| Deletion ledger and current checkpoint | Trusted owner administration storage plus separate encrypted S3 ledger bucket with owner-only publisher. Durable remote version/receipt acknowledgement **before each destructive step**, maintaining current two-copy refusal semantics; daily async copying is insufficient. Use append-only encrypted checkpoints/events and bounded reconciliation, not repeated unbounded full-history uploads. Retention follows the ledger policy, not rolling-backup expiry |
| Restricted legal archive and suppression/destruction/hold proofs | Trusted owner storage plus separate owner-only S3 archive/proof buckets. Preserve signed sealed two-copy acknowledgement before erasure. Choose locks per approved case expiry/review/hold; do not impose a blanket three-year Compliance lock that prevents lawful narrowing/purge. Automate transfer/verification, retain required owner authorization for legal review and destruction |
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

The independent workflow should check hourly for a complete remote pair <=18 hours
old, verify expected versions/size/digest/retention and compare the observer's previous
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
schema, key or recovery-tool changes. The existing attestation requires the **same**
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

## Decision alignment and next bounded slice

**Approved now:** only the infrastructure, total budget, 200 GiB planning target,
Compliance/direct-upload direction and expendable Lenovo boundary recorded above.
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
  billing. A hard aggregate cap, or a separately owner-accepted risk exception, is
  mandatory under the unchanged gate. Do not treat a selected region as an accepted DPA.
- Actual domain/DNS renewal and contact-mailbox charges, Mailjet Free/account terms,
  CI/monitor/administration extras and final combined recurring/initial cash quote.
  The EUR 2 email contingency stays reserved; no fallback service purchase is approved.
- Compatibility of Compliance retention with the 35-day ceiling, all-version expiry,
  ledger durability/replay and legal-archive review/holds; exact cadence, copy counts,
  private storage/key locations and integrity-versus-full-restore evidence parameters
  remain to approve. No silent widening of retention or weakening of two-copy barriers.

**Actions requiring separate owner authorization:** a scoped uncapped-spend exception
and gate revision if needed; then any account/project/credential setup or purchase;
then host installation/deployment/DNS changes and restricted real-host exercises;
and finally public exposure. Documentation approval is none of these authorizations.

**Next minimal implementation slice after explicit implementation approval:** prepare
one bounded S3 publish/exact-version retrieval/integrity/expiry adapter around the
existing four-file encrypted pair, with an independent receipt and missed-run check.
Keep endpoints, buckets and keys as private inputs; do not provision them. Use focused
fake-S3 tests for partial uploads, retries, version masking, denied privileges, byte
bounds and expiry timestamps; do not repeat #275 rehearsals. No new backup engine,
S3 mount, infrastructure or resource-limit increase is needed. Durable ledger/archive
remote-copy barriers and the reviewed scheduler/attestation integration must follow
before any real destructive cleanup or production automation uses the cloud target.

After authorized provider setup, prove effective non-owner permissions, Compliance
shortening/deletion refusal, metadata/default behaviour, old versions and expiry;
then measure capture/upload/verification age and a clean cloud-only empty-host restore
with Lenovo unavailable. Include reconstruction/key recovery, latest ledger replay,
old-session revocation and owner notification. Real-host RPO/RTO remain unmeasured;
source checksums or provider selection do not establish recoverability.

Mandatory before public accounts: effective independent copies/keys and permissions,
retention/deletion barriers, missed-job detection, acceptable billing/terms and tested
cloud-only recovery. Optional future 3-AZ/second provider/paid watchdog work requires
new evidence and approval, not a provider comparison restart here.

Documentation-only: no executable artefact or SemVer bump. #276 remains open for
contracting decisions, implementation and real-host acceptance. No account, credential,
purchase, infrastructure/DNS change, deployment, commit or merge occurred.
