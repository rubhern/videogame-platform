# Data protection: processing record and operating procedures

- **Status:** Owner-approved policy/design; local implementation prepared for review; public acceptance pending.
- **Owner:** Rubén Hernández López, natural-person controller; legal-name publication explicitly authorized.
- **Consumer:** Sole operator handling rights, suppliers, incidents and future public opening.

This is the maintained operational record for data protection. The
[Product Brief](../product/product-brief.md#public-release-and-sustainability) owns
account/product decisions, the [approved Spanish texts](../legal/public-texts-es.md) own public
copy, the [platform design](../architecture/deployment/mvp-platform-and-delivery.md)
owns topology/recovery, and [observability](observability.md) owns logging policy.
The [research](../research/public-release-rights-and-privacy.md#9-legal-and-operational-increment-2026-10-17)
provides legal evidence and uncertainty. This record is proportionate preparation,
not evidence of implemented production safeguards or blanket legal approval.

## Record of processing activities

Common controller/contact: **Rubén Hernández López**, natural person;
`hola@gameometro.com`. The owner authorizes this legal name, not a home address or NIF.
The initial service has no economic activity: no advertising, affiliation,
sponsorship, donations, sales or professional promotion. The owner adopts the
interpretation that LSSI article 10 address/NIF publication does not initially apply;
this is a reasoned interpretation of the economic-activity scope, not legal certainty.
Reassess before any change to those facts, monetization or contrary authoritative
interpretation. GDPR controller identification still applies. No appointed DPO or
representative is asserted. Proposed assessment: no large-scale systematic monitoring
or special-category purpose currently requiring a DPO/DPIA; owner must confirm the
screening, including whether small public rating aggregates enable inference. Reassess
on actual scale, public profiles, sensitive data, minors, tracking or changed purposes.
Continuous account processing is not occasional; do not rely on the small-organization
exception to dispense with this register. Keep actual contracts and identifiers in
restricted owner storage, not the repository/portal.

Subjects: adult account holders, anonymous visitors and people contacting the operator.
No intentional collection of special categories or children's data. An unexpected
underage account or sensitive support attachment triggers case-specific restriction,
minimization and review, rather than routine ID collection.

| Activity / subjects | Data and purpose | Proposed legal basis / recipients | Erasure or retention criterion |
|---|---|---|---|
| Account and identity / account holders | Email/username, verification status, internal subject, creation time, password hash; establish/verify/recover identity | Service contract, GDPR 6(1)(b); operator, selected VPS and transactional mail processors | Owner-approved future policy: never-verified accounts expire at 30 days from creation; verified accounts/ratings until erasure regardless of inactivity. Locally prepared, not scheduled or host-qualified; see design A and implementation evidence |
| Session and access security / account holders | Keycloak persisted session ID, start/last-access, IP and device notes; last failed IP in brute-force state; BFF in-memory session, protocol claims, tokens/CSRF and pending intent | Necessary authentication under contract; abuse/security under 6(1)(f), subject to balancing; operator and VPS | Idle/absolute session policy per executable realm/BFF; restore copies also hold live identity sessions; failure-state lifecycle must be verified on real runtime |
| Personal ratings / account holders | Derived UserId, GameId, 1–10 value or, post-MVP (#359), the platforms the user says they played on with optional 1–10 scores there, times and concurrency version; retrieval/edit/delete and public mean/count/distribution and per-platform means/counts | Contract; operator/VPS, public receives aggregates only | Until individual rating/account erasure; played platforms go with their rating, and exports include them; deletion reflected by current DB reads and cache revalidation. Small groups, including a platform scored by few users, are not asserted anonymous |
| Preferred platforms / account holders | Derived UserId, chosen platform IDs, whether and when a choice was last saved (#295); narrow release discovery at the user's request | Contract; operator/VPS; never sent to Keycloak, telemetry, logs or public reads, only to the user's own browser | Until the user clears or replaces them, or account erasure, which deletes them with the ratings; included in exports |
| Transactional delivery / account holders, including pending accounts | Email, verification/recovery message and action link, delivery/bounce/routing metadata | Contract for requested mail; security under legitimate interest; Mailjet Free selected, receiving mailbox providers | Free statistics/campaigns: 90 days; contacts/templates: account lifetime according to the [current help page](https://documentation.mailjet.com/hc/en-us/articles/360042755094-How-long-are-my-statistics-kept). SMTP content/recipient/bounce/routing retention still requires verification; link expiry is not message erasure. No marketing/tracking purpose authorized; verify actual messages do not add tracking |
| Support and rights / correspondents | Address, necessary correspondence, request reference, identity-check outcome, response proof | Ordinary service support: contract or legitimate interest for non-account queries; rights: legal obligation 6(1)(c) | Export files deleted after verified delivery; support closed then minimized; suppression proof, blocked source data and deletion ledger remain separate; blocked-data initial term 3 years with 12-month review and case-specific legal holds; no indefinite mailbox retention |
| Self-service deletion admission / account holders | Principal-derived UserId, subject, opaque request reference, receipt hash, pending/completed status and times; fence access and reconcile the independently recorded erasure | Rights compliance; controller and existing hosting/administration boundary only; no profile, rating values or credentials in the intent | Pending until safely resolved; completed intent reviewed with the deletion ledger while restorable identity state exists, at least 35 days. Receipt authorization expires after 24 hours; owner review/purge remains required |
| Recovery and deletion replay / account holders | Encrypted paired PostgreSQL dumps, live identity-session IPs at capture, credentials/SMTP secret; minimal deletion ledger with issuer/subject/UserId, times/outcomes | Legitimate interest in recovery; rights-compliance evidence under legal obligation; operator and owner-selected future OVHcloud off-host storage, subject to processor terms and independent access/keys | All backup copies ≤35 days; ledger while any restorable copy exists, at least 35 days after completion; incomplete entries never purged; resolve rather than indefinitely neglect them |
| Operation and incident response / visitors and accounts | Necessary route/status/timing/error diagnostics and aggregate metrics; exceptional incident evidence | Legitimate interest in security; breach records/notifications under legal obligation; operator, relevant processors, AEPD/affected people when required | Routine logs ≤7 days; bounded aggregate telemetry under observability policy; incident evidence separate and restricted; reasoned legal schedule/holds, not an automatic extension of routine logs |
| External image delivery / visitors | IP, browser headers/user agent, origin referrer to images.igdb.com / CloudFront to display catalogue artwork | Legitimate interest proposed, **not established**; Twitch/CDN roles and applicable transfer mechanism unresolved | Third-party retention unknown; no false 7-day/35-day promise. Complete necessity/balancing and role/transfer assessment before privacy acceptance |

General safeguards proposed/partly prepared: same-origin BFF, principal-derived
ownership/CSRF, verified email, revocation, minimum profile, private management,
protected files, encrypted backups, independent deletion ledger, bounded diagnostics
and no ordinary personal-event logging. Commands and tested limits remain with their
executable/canonical owners. Production application, PostgreSQL and Keycloak are
self-hosted software; they are not three SaaS processors. PostgreSQL contains both
product and identity databases, under independent roles, on the future VPS.

### Legal-basis and blocking review

Before approval, document purpose, necessity, less intrusive alternatives, user
expectations, possible harm and safeguards for each legitimate-interest purpose.
Recovery/security protect users; that rationale does not automatically establish
necessity for sending every visitor's IP to a decorative-image CDN. Compare local
product-owned fallback as an alternative in the legal assessment without implementing
a content switch. Record the objection route and residual-risk judgment. Contract
covers only objectively necessary account/rating operations, not arbitrary optional
processing. Privacy acknowledgement is not consent to everything.

**Owner decision, phase 1 (2026-10-17):** blocked source data has an initial
**three-year** term from blocking, reviewed every **12 months**. This is not a
universal limitation period. LOPDGDD 72–74 use different administrative periods;
applicable claims, proceedings and interrupted periods can last longer, including
Civil Code 1964(2)'s five-year general personal-action period where applicable.
Ordinary cases use the once-reviewed fixed profiles below; exceptional cases
identify relevant liability, necessary fields, start date and expiry;
retain beyond three years only with a specific obligation/hold and next review.
A missing exceptional-hold classification or overdue annual review blocks archive
purge and alerts the owner, not ordinary erasure under a valid fixed profile; it does not justify retaining every dataset indefinitely. Clear obsolete
holds and destroy each copy when its lawful term ends. The
[research](../research/public-release-rights-and-privacy.md#10-owner-consolidation-and-phase-1-design)
owns the legal evidence; [design B](#b-restricted-legal-archive) owns the proposed
technical mechanism; local implementation and outstanding host evidence are recorded below.

Blocked data is unavailable for ordinary operation or visibility and reserved for
lawful authority/claim handling. A recovery backup is not that archive. LOPDGDD
32(3)'s secure-copy conditions require authenticity, date and integrity evidence;
owner policy alone does not prove technical compliance. No full credential export,
indefinite account archive or replacement deletion engine is approved. The minimal
administrative suppression proof, the blocked-data payload and the deletion ledger
have different purposes, fields and expiry criteria; none is a substitute for another.

## Supplier and transfer register

A public DPA page is not evidence that the right agreement is concluded for the
owner's actual account/service. For each actual recipient privately retain entity,
service/order, role, locations, subprocessors, contract/DPA version and acceptance,
security/incident contacts, deletion/return terms and transfer evidence. No contracts
are concluded by this document.

| Service | Role and data boundary | Evidence required before public data |
|---|---|---|
| Keycloak and PostgreSQL | Self-hosted software under the controller; neither project receives data simply because the software runs | Verify no additional managed support/telemetry/export recipients. Host access belongs in the VPS agreement |
| netcup VPS Lite 3 (selected, not purchased) | Proposed processor for DBs, memory, disk, host diagnostics and provider support | Correct entity, selected region, signed/generated DPA and subprocessor/access locations, deletion/backup/support terms, transfer safeguards if any. No assumed EU-only processing from advertised server location |
| OVHcloud domain/DNS | Registrar/provider can be independent controller for owner registration/billing data; not automatically processor for every visitor | Review actual registration/DNS service and roles, public registration-data exposure and international registrar/registry flows. Do not publish owner billing data in this repo |
| OVHcloud receiving mailbox, if confirmed | Processor for rights/support correspondence, attachments and delivered exports; also provider's own account/security purposes | Owner confirms mailbox product/entity, DPA, region/subprocessors/remote access, mailbox/deleted-item/backup retention and MFA/access. DNS MX observations alone do not identify the contract. Forwarding to personal mail adds another recipient requiring review |
| Mailjet / Sinch transactional SMTP | Processor for message content/recipient and delivery; potentially independent purposes for routing/account data | Owner keeps Mailjet Free for transactional mail and accepts the professional-use contractual uncertainty without further clarification requests (superseding E1's written-acceptance condition). This is not vendor permission. Actual contracting entity/incorporated DPA, content/event/recipient retention and deletion controls, US support/group subprocessors and applicable safeguards remain to be verified; no automatic fallback purchase |
| OVHcloud Object Storage and independent recovery material (selected, not contracted) | Processor for encrypted paired backups and their minimal metadata: Standard, 1-AZ Gravelines, direct automated uploads under the [platform decision](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery). Lenovo removed from the mandatory path; independent ledger/archive copies and keys remain required | Verify actual entity, incorporated DPA/service terms, region, subprocessors/remote access/transfers, retention/deletion/account-closure behaviour and MFA. #276 must prove cloud-only recovery and effective isolation. Encryption does not remove processor duties. Separate ledger/archive placement remains a protected operating input, not an automatic extension of rolling backup storage |
| IGDB/Twitch and image CDN/CloudFront | Recipient of browser IP/headers and origin; do not assume a controller's own CDN is Gameómetro's direct processor | Determine legal roles for collection/transmission, any joint-controller allocation, applicable privacy terms, recipient/retention and Chapter V assessment. EU edge location, a no-cookie sample and owner IP-rights risk acceptance prove none of these |

Check GDPR Chapter V for each transfer or remote access outside the EEA. Record an
applicable adequacy decision and actual recipient/scope (including current DPF
certification if relied on), or correct SCC parties/modules plus the necessary
transfer assessment and supplementary measures. Supplier claims alone do not prove
coverage. No recurring-transfer derogation or forced visitor consent is assumed.
Public notice must identify transfers/safeguards and how to obtain them. Published
Free statistics/campaigns retention is now verified at 90 days; other SMTP retention
and the owner's actual processor/transfer conditions still require confirmation. The Mailjet
DPA's group-level DPF/SCC clauses are available evidence, not a completed recipient
verification. CDN mechanism and controllership remain open; an owner risk decision
cannot waive statutory safeguards.

## Rights request procedure

Use `hola@gameometro.com`; keep the mailbox private, access-controlled and regularly
checked. Proposed operator cadence: each working day, with coverage/alerts for legal
deadlines and incidents; approve an achievable arrangement before opening. Do not
promise staffed or immediate support.

1. Assign an opaque request reference and record received time, right, account lookup,
   identity-check outcome, deadline, actions, response date and delivery proof in
   restricted owner storage. Keep personal correspondence outside Git, Issues and
   telemetry. Acknowledge using the [Spanish template](../legal/public-texts-es.md#mensaje-de-confirmación-de-solicitud-de-derechos).
2. Verify control using the existing verified address or authenticated account and an
   appropriate fresh challenge. A matching `From` header alone can be spoofed and is
   insufficient. Never trust a supplied subject/UserId. Locate the subject in the
   private Admin Console. Request additional evidence only on reasonable doubt;
   avoid routine DNI copies and delete unnecessary material. Lost mailbox access
   needs a documented proportional alternative; do not disclose account existence to
   an unverified correspondent. Explain inability to identify under GDPR 11/12 when
   applicable; preserve the right to complain.
3. For access/portability use the existing
   [export procedure](operations-runbook.md#export-access-and-portability). Check
   the recipient and one-account scope before delivery; protect the transfer (for
   example encrypted attachment with the key through a separately verified channel),
   then delete the temporary export. Never put an export or action link in an Issue.
   Include article 15 information from the notice/register beyond the JSON; protect
   other people's rights. Do not claim the export includes password hashes, tokens
   or historical sessions not available from the tool.
4. Ratings can be corrected/deleted in the product. For email rectification use the
   private Keycloak administration process (D16), verify control of the new address,
   retain the same subject/issuer and revoke current Keycloak/BFF sessions before
   allowing re-entry. Confirm the verified state deliberately: do not bypass proof
   by just setting an email and `emailVerified=true`. No automatic change-email
   command is implemented; rehearse the exact
   admin flow synthetically before using it with public accounts.
5. Self-service export and automatic erasure are functionally implemented in #272,
   pending owner review and production operating qualification. The protected runner
   automatically claims persisted requests and reuses #274 without manual fulfillment;
   local/disposable functional evidence does not establish physical independence. The principal/session is the authorization source; a recent
   OIDC authentication and CSRF protect deliberate deletion. The supported JSON covers
   current account fields, direct/per-platform ratings and preferred-platform timestamps;
   article 15 supplementary identity/session information remains available administratively.
   See the [prepared sequence](operations-runbook.md#self-service-account-deletion).
   Owner decision (2026-10-29): private-dev self-service uses an explicitly gated policy,
   current verification and PostgreSQL retry state, without independent ledger/archive/backup
   requirements; it cannot certify production guarantees. Its activation and restoration limits
   are owned by the linked runbook. In production, the durable access intent is separate from the independent deletion ledger, blocked
   source archive and suppression proof. Pending work is retained until resolved;
   completed local intents retain the subject fence while backups/identity state could
   permit resurrection, and are reviewed with the deletion ledger (at least 35 days).
   Receipt hashes expire after 24 hours; no personal content or credential is stored in
   the intent. This prepared change authorizes no legal publication or production rollout.
   For administrative account erasure run the existing
   [ledger-driven procedure](operations-runbook.md#erasure), including its two copies,
   revocation, rating deletion, identity deletion and verification. Partial failure
   stays incomplete and fail-closed; retry and verify before replying that it is done.
   Inform recipients of rectification/erasure/restriction when GDPR 19 requires it,
   recording any impossibility/disproportionate-effort justification. Restore always
   [replays erasures before reopening](operations-runbook.md#restore-replay-before-reopening).
6. For restriction/objection assess the specific grounds; use the existing
   [revocation procedure](operations-runbook.md#restriction-without-erasure) to stop
   access. Disabling sign-in alone does **not** stop stored ratings contributing to
   public aggregates or other processing. If restriction requires stopping that use,
   keep the affected public service closed until it can be honored; preserve data
   securely instead of deleting a restricted record. No per-row restriction flag
   exists. Legal withholding/blocking decisions need a stated reason, scope and
   expiry; communicate any refusal and complaint route.
7. Respond without undue delay and within one month of receipt. If justified by
   complexity/number, notify extension and reason within that month (at most two
   additional months). Do not assume identity questions reset the clock. Free by
   default; exceptional refusal/fee needs evidence and legal justification. Record
   completion or reasons and AEPD/judicial remedy, plus response proof. Review and
   purge proof under its separately approved limitation-based retention schedule; do
   not treat the archive's three-year default as a universal correspondence period.

## Security incident and personal-data breach procedure

Prepared owner-run procedure; exercise with synthetic data and verify actual contacts
before opening. Covers confidentiality, integrity and availability, including a lost
backup/key, wrongly delivered export, compromised email or unauthorized rating access.
An outage can be a breach even without exfiltration. Use the operations runbook for
runtime commands; no mass real mail or live host mutation is authorized here.

1. **Record and assess immediately.** Open a private incident record, appoint the
   owner as lead, record detection and awareness times in UTC, reporter, facts versus
   hypotheses, systems, data/subjects/approximate counts, impact and evidence. Record
   when there is reasonable certainty personal data was affected: the authority
   notification clock starts at awareness, not when the investigation ends. Preserve
   minimal protected evidence, not a public dump of tokens, emails or database rows.
2. **Contain.** Revoke compromised credentials and sessions; restrict affected ingress,
   signup, mail or sync as appropriate, up to temporary closure of public Gameómetro.
   Keep private administration and clean backups available. Preserve necessary
   evidence before cleanup when safe; do not delay containment to perfect it. Work
   with affected suppliers through private support/security channels; verify contacts
   from the actual contract. Do not restore a known-compromised image or reopen with
   deleted accounts resurrected.
3. **Evaluate risk continuously.** Consider type/amount/identifiability, sensitive
   content, credentials/action links/session exposure, recipients, malicious intent,
   duration, affected vulnerable people, loss/alteration and realistic harms (takeover,
   phishing, inference, loss of rights). Encryption reduces risk only if keys and
   configuration remained safe. Unknown scope is not evidence of low risk. Document
   reasoning and updates; seek specialist advice when needed without missing deadlines.
4. **Notify when required.** Notify the competent authority (normally AEPD for this
   proposed Spanish operation) unless unlikely to risk people's rights/freedoms,
   without undue delay and where feasible within **72 hours of awareness**, including
   weekends/holidays. Use the official
   [AEPD breach notification route](https://www.aepd.es/derechos-y-deberes/cumple-tus-deberes/medidas-de-cumplimiento/brechas-de-datos-personales-notificacion)
   and current electronic form. If incomplete, submit available facts then supplement;
   explain a delay. Provide nature/categories/approximate counts, owner contact,
   likely consequences and containment/remedy. Privately retain submission receipts.
5. **Inform affected people** without undue delay when high risk, in clear Spanish,
   describing what happened, contact, likely impact, protective steps and remedies.
   Assess/document article 34 exceptions rather than assuming encryption excuses all
   notice. Do not wait for an AEPD reply or use the 72-hour authority window as the
   user-notification deadline. Use an uncompromised verified channel, never disclose
   the recipient list. If notification delivery is itself affected, document the
   alternative and seek authority guidance.
6. **Check separate contractual duties.** The Twitch agreement reviewed in #274
   contains prompt written security-incident reporting to `legal@twitch.tv`; assess
   applicability independently of GDPR risk thresholds. Other suppliers may impose
   separate reporting/cooperation requirements. Prepare minimal notices and obtain
   owner dispatch promptly: this procedure does not authorize an agent to send
   provider/user/authority messages. The ban on a new IGDB licensing inquiry remains;
   it does not erase an applicable mandatory security-incident notification duty.
7. **Recover and learn.** Eradicate the cause, patch/rotate, verify isolation and
   integrity, restore into restricted operation, replay all deletion-ledger entries,
   verify sessions/ownership/mail and obtain owner reopening approval. Record actual
   recovery, root cause, notification updates, measures/owners/due dates and closure.
   Every personal-data breach is documented, including a justified decision not to
   notify. Ordinary logs expire under policy; necessary incident evidence is isolated
   with purpose, access and a reviewed expiry rather than silently extending all logs.

Private register template (no live personal data in this page): reference; reporter;
detection/awareness times; breach nature and systems; data/subject categories and
estimated numbers; facts/uncertainty; consequences; likelihood/severity rationale;
containment and recovery timeline; suppliers; authority/user/contract notices or
non-notification reasons; receipts; lead; next review; closure and evidence-expiry
basis. Proposed desktop exercise: a misdelivered synthetic export plus loss of an
encrypted backup with/without the key. Check deadline calculation, staged notice and
erasure replay. This exercise and host response channels are not yet proven.

## Publication and product integration

The owner approved all five [public Spanish text sections](../legal/public-texts-es.md)
on 2026-10-29 and authorized their integration in local, private-dev and production
configurations. The four anonymous React routes (`/privacidad`, `/condiciones`,
`/aviso-legal`, `/cookies`) and relative same-origin footer links, including contact
mail, are available in every application build. Only public section bodies are
rendered; approval metadata, the administrative template and editorial checklist
remain internal documentation. Existing semantic layout, responsive table scrolling
and navigation are reused without dependencies or a cookie banner.

The canonical generator also supplies the basic registration information and privacy/
terms links in Keycloak realm localization before submission. Local registration uses
the existing native adult/terms action; the production flow and private-dev alignment
retain it. Privacy is information, never an additional compulsory consent. D19's lack
of a per-account accepted-text record remains accepted. Links use the configured
application origin (not incoming Host headers); footer routes remain relative.

Startup realm import does not overwrite existing realms. After review, an operator
must explicitly reconcile existing private-dev identity through its snapshot-backed
`configure-keycloak-identity` command, which takes the application origin from the
protected runtime configuration. Disposable/local reconciliation with `--origin` can
set `--application-origin` (default `http://localhost:8080`). Session-only alignment
does not update legal localization. Existing local realms require an explicit reset
or reviewed administrative update; no reset or live realm change is part of this slice.

Text approval, page availability in a build, and actual public production go-live are
separate decisions. Approval accepts the editorial operating premises; it supplies no
independent evidence of a deployed host, actual account/order contracts, transfers,
SMTP, cookies, retention or recovery. Those checks, provider purchase and exposure
remain governed by [public-production gates](delivery-lifecycle.md#public-production-gates)
and #276. No public deployment or existing private-dev retirement is authorized here.

Remaining evidence belongs to the existing release work, not another wording approval:

| Boundary | Owner and preserved decision |
|---|---|
| Actual Mailjet/Sinch entity/DPA/retention/transfers, OVHcloud account/order and CDN safeguards | #276 actual-contract acceptance; no further Mailjet eligibility or IGDB permission inquiry is introduced. Accepted contractual/content risks remain distinct from demonstrated rights or privacy assurances |
| Cloud isolation, independent encrypted backup/legal-archive and ledger copies, separate keys and recovery | #276 qualifies the approved target against #275 preparation; no provisioning or purchase is authorized by this integration |
| Identity/proxy/abuse/mail/security channels and actual cookie/storage/CDN behaviour | #276 supplies deployed evidence; editorial approval is not a host test |
| Effective public-release date and exposure | Separate owner decision under the public-production gates |

The frontend gains public build availability as a compatible MINOR capability.
The backend reactor also gains a compatible MINOR capability for the four explicit
SPA forward mappings required on direct navigation and reload. OpenAPI and the
documentation portal artefact version are unchanged; identity configuration/scripts
have no independent version.

## Phase 1 design for approval

**Approved A/B/C design (2026-10-17); phase 2 prepared locally.** The owner accepts
the reduced ordinary source profiles and their documented legal uncertainty.
Public integration is now approved; actual exposure still requires the separate
[release gates](delivery-lifecycle.md#public-production-gates).
The specification below remains the approved design; executable commands own exact
mechanics and the [implementation evidence](#local-implementation-and-acceptance)
distinguishes preparation from real operation. No timer, host change or deployment.

### Existing seams and scope

Extend `deploy/private-dev/bin/personal-data` and `personal-data-ledger`, retaining
Keycloak Admin API access, exact `(issuer, subject)` identity, Ratings SQL and private
BFF session revocation. The former currently has private-dev realm/database/project
constants; production reuse requires explicit validated runtime selection, matching
realm/issuer/project and refusal of mixed configurations, not pointing a private-dev
command at production by accident. Preserve current private-dev behavior and tests.
Do not introduce a public endpoint, Spring scheduler, module, identity extension,
new database, service or library. Runtime selection and host scheduling/mounts remain
separately qualified under #275/#276.

New administrative modes are prepared within those tools: select expired pending
accounts, cleanup with a dry-run default, produce a restricted archive snapshot,
verify/seal archive copies, review/purge archives and bounded retry. The [runbook](operations-runbook.md#privacy-phase-2-administration) owns CLI usage. Reuse erasure steps and replay rather than creating
a second implementation; add pre-deletion archival checks at their orchestration seam.

### A. Thirty-day unverified-account cleanup

**Eligibility:** production realm `gameometro`, local human account, positive valid
`createdTimestamp <= runStartedAtUTC - 30×24 hours`, and `emailVerified == false`
on the fresh pre-deletion read. Verified accounts are excluded regardless of
inactivity. Do not select by email, last sign-in, required-action presence alone,
client session lifetime or a similarly named private-dev account. Service/admin
accounts, federation and unknown creation dates fail closed for review. Email
rectification of a previously verified account must verify the new address before
committing the change; it must not turn that account into a pending signup. No new
"ever verified" history is needed with that invariant; imports/migrations or a future
flow that resets verification require explicit classification before cleanup.

**Bounded discovery:** use the pinned Keycloak Admin API's native
`emailVerified=false`, `createdBefore=<UTC epoch milliseconds>`, `first=0`,
`max=100`, `briefRepresentation=true`. Both age and verification filters run in
Keycloak/PostgreSQL; do not load all users and filter them in Python. The pinned JPA
provider orders by realm-unique username; that unique key supplies total ordering
within the fixed realm. No undocumented caller-supplied sort is assumed. Prove
filters/ordering against the actual pinned runtime and mixed recent/old users before
phase-2 acceptance; refuse unsupported storage providers rather than invent an
application scan. Do not query or migrate Keycloak tables from the product.

Take at most **one page of 100 per run**; the next run starts at offset zero.
Deleted rows leave the result set, so incrementing offsets after deletion would skip
accounts. Under the fence below, realm-unique usernames order the frozen page. If
an exceptional account does not match a profile, skip/report that subject and
continue eligible ordinary accounts in the same page; do not stop them merely to
obtain per-account classification. Abort on shared storage, identity-boundary or
uncertain-operation failures. A final `max=1` probe reports remaining work; do not
loop on the same exceptional first page. Persistent/non-decreasing backlog or a
page filled by exceptions requires operator attention and is never called complete.
This bounds the initial mechanism without an exception queue or all-user scan.

**Proposed operating budgets:** daily owner-side invocation from the protected
administration context, at most 100 deletions, 120 seconds and one remote operation
at a time per run; page memory ≤100 representations with a 1 MiB response cap,
20-second HTTP timeout and no unbounded automatic retries. Stop before starting a
new account when the run budget is exhausted; if one is in an uncertain step, keep
its recovery state and reconcile before further destructive work. These are proposed
safety ceilings, not measured VPS capacity or a promise of exact-to-the-second
expiry. Ordinary scheduling can delay cleanup by up to the next daily run; missed
runs/backlog must alert the owner, not silently extend retention indefinitely.
#275/#276 must qualify DB/query behavior and adjust cadence/budget with evidence,
without broad scans or extra infrastructure. No-cache Admin reads; SQL plan/latency
and real job-duration evidence are still phase-2/host work. The ledger now streams JSONL history with
selection/folding for the active subject/batch and deadline/byte guards,
reusing its format and two-copy reconciliation. Existing unbounded `retry`/`entries`
must not be called repeatedly by the timer and presented as bounded. A large-ledger
fixture must prove memory follows the active batch, not total historical accounts;
interrupted tails/divergent copies must still refuse. Do not redesign the erasure
engine or add a new database to obtain this administrative iterator.

**Recommended change: probe first, maintain only when work exists.**

| Option | Interruption / complexity | Correctness and dependency |
|---|---|---|
| Previous: open identity maintenance every daily run, then discover | Interrupts registration/login/recovery even with no expired pending account; same drain machinery | Safe only with real deny/drain, not because a timer holds a local lock |
| Recommended: bounded read-only probe first, window only for candidates | Empty run makes one `max=1` filtered Admin read and no proxy change; nonempty run uses the same short global identity window | Re-read after deny/drain eliminates probe-to-verification races; no new service, extension or per-account proxy policy |
| Candidate-specific verification blocking / unguarded GET-disable-delete | May avoid global login disruption, but distinguishing all verification paths or proving atomicity adds native-flow/version coupling | Not selected: Admin read/update/delete is not an atomic check-and-delete; disabling an account is not proof that an in-flight verification cannot finish |

The #275 controller must coordinate the protected maintenance-operation lock with
owner identity changes and restore/deployment; the existing ledger lock excludes
concurrent erasure commands during execution. Read-only probes are not erasure locks.
Read-only daily discovery does not change the proxy. If no eligible account and no
previously committed operation needing this fence exists, finish with **no identity
interruption**. A positive probe is only a hint, never a saved decision to delete.
Preflight archive keys/storage/two-copy availability before requesting the window to
avoid predictable failed-maintenance attempts. Re-probe if those checks are slow;
if candidates disappear, do not open a window.

Only then temporarily deny new public identity requests and drain existing Keycloak
requests. Re-read a bounded page and each subject under that fence. If everyone
verified before the drain completed, delete nothing and end maintenance. During a
nonempty window the simplest safe routing rule covers the public identity host:
registration, verification/action links, login and recovery briefly return a safe
retryable maintenance response; already authenticated BFF sessions and catalogue
reads continue. Avoid daily unconditional closure and token-inspecting/per-user
proxy rules. Frequent candidates would still produce frequent short interruptions;
measure backlog/window frequency and duration rather than claiming disruption-free
cleanup. The 120-second budget limits admission of new work, not a guaranteed drain
or recovery time. Reuse one window for the bounded batch, not one reload per account.

**Actual #275/#276 proxy contract:** atomically activate/revert the intended identity
maintenance rule, retain private Admin access, deny alternate/direct upstream entry,
and prove all earlier verification mutations settled before eligibility is read.
A successful configuration reload, an arbitrary sleep or a local mutex does not
prove upstream requests drained. Preserve the prior route configuration; release the
window after the batch, or after a failure once no administrative mutation is still
running/ambiguous. An individual incomplete erasure remains disabled for existing
retry; it need not indefinitely close login for everyone. If in-flight work cannot
be accounted for, escalate privately instead of silently reopening or claiming a
successful cleanup. Until the real proxy offers tested deny/drain/release evidence,
allow only an owner-controlled equivalent maintenance exercise; unattended cleanup
refuses. No extra proxy service, Spring flag or Keycloak extension is proposed.

Inside that fence, re-read each subject and check eligibility before recording the
erasure. If verification completed before the drain, exclude it with no deletion
entry or archive. Persist the existing requested event to both deletion-ledger copies
before mutation; run existing revocation, then B's snapshot/seal before deleting
ratings or identity, and existing verification/completion. A purge selected just
before a deadline cannot erase a newly verified user. Once a valid erasure starts,
retry addresses that immutable subject but must re-enter the same fence and recheck
its recorded cutoff, verification, administrative-role exclusion and holds before
mutation. A newly verified/protected account stops for owner reconciliation, without
deletion or automatic re-enabling. Reopening identity entry after the run requires
confirmed drain completion and no ambiguous identity mutation still running.

**Failure/idempotency:** archive/copy/key/eligibility failures prevent destructive
steps. Once revoked, partial failure stays disabled and incomplete; retry reconciles
that same operation and sealed archive, not a new snapshot of partly deleted data.
Missing identity before any recorded request is not a successful cleanup and gets
review; missing identity during recorded replay/retry follows existing idempotent
steps. Never mark complete from a timeout. A new account with the same email has a
new subject and is unaffected by the old ledger. A deleted account's pending links
are unusable; it may register again. No extra warning email or quota-consuming
reminder is proposed. Users can verify until the protected cleanup actually starts.

**Restore:** retain existing replay of every recorded erasure before reopening, even
when the backup predates cleanup. Replay performs deletion/verification only; it never
recaptures expired legal archives or resets legal retention. Completed erasures and
legacy entries keep deletion-only replay. An incomplete profiled erasure requires
its original first capture, keys and protected stores; if missing, stop before
consuming the last source. An incomplete cleanup also rechecks verification,
admin exclusion, cutoff and holds; conflicts stop reopening for owner reconciliation.
Then, with ingress still
restricted, run the current-time 30-day selection to catch accounts that have since
aged out, using the same pre-delete checks and ledger. Do not inherit a stale run
cutoff or use an archive as account recovery. Preserve the backup boundary and no
verified-account inactivity purge.

### B. Restricted legal archive

Three separate artefacts, never one combined database/record:

| Artefact | Minimal content / purpose | Retention and access |
|---|---|---|
| Suppression proof | Opaque request/operation reference, request/cause and completion times, identity-check outcome, verified step outcomes and counts, policy/tool version, reply/delivery proof when relevant; no copy of the account's ratings or credential store | Restricted administrative evidence, separately from source data. Proposed initial review at 12 months and three-year retention from closure where justified for demonstrating compliance; longer specific claims require a documented hold. Not an owner-approved universal evidentiary period |
| Blocked source data | Allowlisted pre-delete source fields under the fixed ordinary profile or an explicit scoped claim; encrypted payload plus signed minimal manifest | Three years initially from blocking, 12-month review; longer applicable duties/holds override routine expiry; owner-only lawful access, no operational reuse |
| Deletion ledger | Existing subject/derived UserId, reference, timestamps and step outcomes; optional opaque archive receipt/hash reference only, no archived payload or email | Existing replay purpose while any backup could restore data, at least 35 days after completion; incomplete never purged. No automatic three-year extension merely because the legal archive exists |

#### Default field profiles and exceptions

**Owner-approved initial policy:** adopt two closed ordinary profiles as policy once,
then select them mechanically from verified facts. A manual legal classification is
not required for each normal cleanup or user deletion. The approved profile version
supplies fields, purpose, three-year initial expiry and annual review automatically.
Do not interpret missing per-case paperwork as "archive everything" or as a reason
to stall every scheduled erasure.

Common encrypted linkage: exact issuer/subject/derived UserId, opaque operation
reference, blocking time and profile version. The separate manifest holds hashes,
expiry/review and any explicit hold reference, not an account export. Ordinary
payloads are small identity records, not the general `export` output.

| Case / profile | Blocked source fields by default | Not retained as ordinary blocked source | Automatic selection / reason |
|---|---|---|---|
| Never-verified signup, `pending-signup-v1` | Common linkage; the submitted email once (username is the same value), creation time, `emailVerified=false` | Rating values, profile attributes/names, passwords/hashes, session/IP/device/event history, message bodies or action links | Valid local pending human account, age ≥30 days, re-read unverified under fence and no ratings. Minimal source identity facts for potential responsibilities arising from collecting an address/retaining/removing a pending registration; an unverified email is not proof its owner registered |
| Verified account erased on verified user request, `requested-account-v1` | Common linkage; account email once, creation time and `emailVerified=true` | Individual GameIds, rating values and per-rating dates; names/attributes, credentials and sessions; no full user export | Request already authenticated by existing rights procedure, expected account state and no explicit exceptional hold. Minimal identity source record for responsibilities in account processing; suppression/request-response proof and deleted-rating count stay in their separate administrative record |
| Concrete claim, proceeding or identified liability, `case-hold-v1` | Ordinary record plus **only** named relevant source rows/fields, e.g. disputed GameId/value (or per-platform scores, #359)/creation-update times or necessary correspondence | Unrelated ratings/accounts and generic logs; no blanket whole-account snapshot | Owner records case reference, legal ground, particular fields/rows, necessity, permitted recipient/access, accrual/expiry and next review before deletion. Longer retention only as specifically justified; no unlimited "possible future dispute" hold |

The ordinary email is encrypted blocked source data, not a mailing/contact list;
keeping it is a proposed account-processing liability assessment, not proof that
three years is legally required for every address. No ordinary duplicate username,
raw sessions, tracking data or catalogue media are needed. The ordinary verified
profile intentionally does not preserve every personal rating: its values are not
needed to prove that the erasure procedure ran; use the separate proof/counts for
that purpose. A specific dispute about a rating can justify those particular rows
under the exceptional profile. A rating hash alone is not anonymous data or a
replacement that silently authorizes retaining the full rating collection.

**Article 32 basis and uncertainty:** LOPDGDD 32 requires blocking on relevant
rectification/erasure, prevents ordinary treatment/visibility and confines reservation
to possible responsibilities during their limitation periods. A known complaint is
not a prerequisite for all blocking. Therefore both ordinary profiles retain a small
source record; neither a ledger nor a suppression receipt alone is asserted to
satisfy blocking. Conversely, neither the statute nor the AEPD material consulted
expressly approves Gameómetro's particular reduced field lists or a categorical
exemption for unverified signups. Omitting ordinary rating rows is a **proposed
necessity/minimization interpretation**, not a demonstrated statutory safe harbour.
The owner accepts this initial policy and legal uncertainty; if applicable legal
evidence requires different source fields, amend the fixed template rather
than delegating that judgment to each scheduled run. Detailed sources and limits:
[research, section 11](../research/public-release-rights-and-privacy.md#11-proportionate-ab-adjustment-after-owner-review).

A recorded exceptional hold is looked up by immutable subject through a bounded
protected per-case file/manifest alongside the archive, not a new database. If none
exists and ordinary facts match, use the default without human intervention. A
missing template/version, corrupt hold manifest or missing independent archive copy
is a genuine safety failure; it is different from the normal absence of a hold.
Unexpected ratings on a never-verified account, unknown verification history or a
non-user-initiated verified-account removal is outside these profiles: isolate and
report that case rather than force it into the pending-signup profile. It does not
require manual classification of unrelated ordinary accounts; report bounded skipped
exceptions/backlog and never claim the full sweep completed if they remain. After
account revocation those unexpected rating counts are stable and the anomaly can be
resolved without accidentally archiving/deleting them as an ordinary signup.

Never copy passwords/hashes, credentials, OAuth/action/CSRF tokens, cookies, SMTP or
admin secrets or a whole Keycloak export into either ordinary profile. A specific
security investigation is a separately justified evidence purpose, not an automatic
expansion of routine legal blocking. The three-year initial term, twelve-month
review and longer applicable legal holds remain unchanged for both profiles.

**Capture and seal:** after existing verified revocation and before the first
`delete-ratings`/`delete-identity`, request a new allowlisted snapshot from the same
runtime adapter. Do not save the general `export` result and remove fields afterward:
it contains sessions and arbitrary attributes and currently aggregates all of a
user's ratings. Reuse identity derivation/access and SQL connection handling, but
project the selected closed profile only. Ordinary profiles capture no rating rows.
If an explicit exceptional case justifies ratings, fetch by `(user_id, game_id)`
keyset, `game_id > lastGameId ORDER BY game_id LIMIT 100`, a uniquely ordered existing
key; stream encrypted chunks, bounded bytes and statement/overall deadlines. No
in-memory full rating list or user-collection sort. Session revocation plus exclusive
administrative mutation control stabilizes the source until capture completes.
Record row counts/digests and verify completeness before any delete. Phase 2 must
check the indexed plan on real PostgreSQL and a large per-user fixture; none is
claimed measured now. Proposed limit 20 MiB per account: exceeding it aborts safely
for a separately approved bounded continuation, never a partial successful archive.

Plaintext exists only in bounded process buffers/streams or a protected tmpfs during
capture; encrypt to the owner's existing GPG public-key mechanism before persistent
storage/transit. No decryption key on the VPS, no plaintext temporary export, no
sensitive stdout/logs, and no secrets in command arguments. Keep two durable,
independently stored copies of encrypted chunks and manifest; verify their identical
hashes, GPG integrity and decryptability in a protected off-VPS context before sealing.
Sign the manifest (opaque reference, UTC capture time, schema/profile version, chunk counts,
ciphertext hashes, expiry/review) with an owner-controlled GPG signing key held off
VPS; key provisioning if needed is a #275 prerequisite. SHA-256 alone detects changes
but proves neither authorship nor trusted time; an owner signature and recorded time
are evidence, not a qualified timestamp service.

Atomic no-overwrite files (`0600`, directories `0700`) and fsync-before-acknowledgement
follow existing patterns. A separate private archive receipt links the operation to
its sealed two-copy snapshot; the deletion ledger may reference that receipt but
contains no source data. A crash after sealing reuses the same verified snapshot;
a crash before sealing cannot reach destructive steps. A hash/signature mismatch,
missing second copy or unconfirmed capture stops the erasure; retry reconciles
staged/sealed files, preserving the first valid capture and never overwriting it with
an empty post-delete view. Replay bypasses fresh capture on purpose. An incomplete
profiled operation must recover/verify its original first capture before replay may
mutate the runtime; completed and legacy operations do not acquire a new archive
requirement or retention period.
Explicit legacy entries predating archive rollout are replayable without inventing
an unavailable pre-deletion snapshot. Existing pending operations are reviewed before
enabling the new pre-delete requirement.

**Storage/isolation:** the [platform recovery decision](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
and [ADR-0033](../decisions/0033-select-lite-3-and-immutable-offhost-storage.md) supersede
the 2026-10-17 Lenovo destination decision. Lenovo is optional/expendable and cannot
hold a required unique copy, key or administrative dependency. Rolling cloud backups,
restricted legal archives, deletion ledgers and suppression/destruction/hold proofs
remain distinct stores and access scopes. Their mandatory independent copies and
private locations must be established before destructive operations; two directories
or buckets sharing one failure/administration domain do not suffice.

The no-agent/root-equivalent-access requirement remains for protected owner-side
administration and keys. `0700` alone does not protect against root/Docker access.
Private decryption/signing keys stay off VPS and Lenovo, separate from ciphertext;
ordinary agents/processes cannot decrypt. Provider selection does not approve the
research's particular owner-storage/S3 archive placement or an asynchronous-copy
substitute for the existing synchronous pre-delete ledger/archive barriers.

**Object Lock compatibility gate:** rolling copies remain limited to 35 days from
the recovery point. Upload-relative Compliance retention must leave room for actual
upload lag and deletion of every version; expiry is not deletion. Do not enable
indefinite legal holds on rolling data or reset the clock by reuploading old backups.
Legal archives have case-specific review/hold/purge duties: a blanket immutable term
may prevent lawful narrowing/destruction and is not approved here. Ledger copies must
remain available/current while any restorable data exists and obey their own schedule;
rolling expiry must never prune them. The owner's 2026-10-29 target policy measures
the unchanged ceiling as verified exact-version deletion by recovery point + 35 days;
provider-internal reclamation follows accepted processor terms. A missed deadline is
a retention incident, with continued deletion attempts. The
[platform recovery policy](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns the approved cadence, lock and evidence ages. Provider-side enforcement and
actual deletion remain launch gates. D3, D6 and the precise D7 thresholds remain
recommendations; no ledger/archive lock policy or rehearsal-pin override is inferred.
No real host change is made.

**Review, expiry and destruction:** each sealed case has its own signed expiry,
next-review date and field-level schedule. The operator reviews at least every twelve
months and on a claim/authority notice, records legal holds and releases with grounds,
scope and next review; three-year expiry never blindly overrides a longer applicable
liability or an active proceeding. A review can narrow fields where lawful and must
not extend everything automatically. Proposed daily purge is dry-run first, bounded
to 100 manifests, requires valid current review/hold state on both copies and explicit
operator authorization of the expired selection; failures retain the affected case
and alert without declaring complete. Process due-date buckets/streamed manifests,
not all decrypted payloads in memory; before implementation prove bounded candidate
selection and independent-copy recovery using representative archive counts.

Destroy payload/chunks, manifest copies and any staged files across both independent
stores and their snapshots/caches, then retain only a minimal destruction proof
(reference, time, outcome, legal basis and no source fields) on its own schedule.
Do not include legal archives in the rolling application backups; that would defeat
expiry. Disable unbounded filesystem snapshots for this store or prove their purging.
On SSDs/uncontrolled media, unlink is not proof of physical erasure; use encryption
and a verified media/key disposal plan. The shared long-lived backup key cannot be
destroyed per case: do not claim cryptographic erasure of one archive while copies
remain decryptable with it. Confirm actual recoverability/deletion behavior in
#275/#276. Restoring an archive store must reconcile destruction proofs/holds and
reapply expiry before it becomes accessible; it never restores data to the product.

### C. Minimum React and Keycloak legal integration

**Implemented integration; actual public deployment separately gated.**
The [publication and product integration](#publication-and-product-integration)
section owns current availability, registration reconciliation and release boundaries.
The four destinations use the existing shared shell and safe generated Spanish text
model, with semantic headings/tables and no legal-content image, tracking, storage,
credentials, additional libraries or authentication requirement. No product API or
OpenAPI change is required for these SPA routes. The existing static fallback
controller explicitly forwards the four legal paths on direct navigation and reload.
Registration preserves the native adult/terms enforcement,
verification, recovery and return intent; no copied registration form, new required
action or consent database is introduced.

### Focused phase-2 evidence required

| Boundary | Acceptance evidence; local coverage and remaining gates are distinguished below |
|---|---|
| Cleanup selection | Real Keycloak/PostgreSQL: exact age boundary in milliseconds, future/missing dates, server-side age/verification filters, >one page with mixed ages, ties, verified inactive exclusions, unknown provider/service accounts, bounded response/deadline/backlog and no skipped rows from deletion |
| Races and faults | Empty probe causes no maintenance; candidates disappearing before/after drain survive; verification before drain survives; in-flight verification is drained; bypass/maintenance failure refuses deletion; duplicate timer/manual jobs excluded; ledger-copy/archive-copy outage, timeout and crashes at every pre-delete/delete boundary; re-registration same email unaffected |
| Archive security/purge | Automatic pending/requested profiles with no per-account classification, ordinary rating-row exclusion, scoped exceptional holds, genuine anomaly isolation; explicit field allowlist and absence of credentials/sessions/arbitrary attributes; large per-user keyset SQL/plan and byte cap, encrypted-only persistence, permissions/path/symlink refusal, signature/ciphertext tampering, two-copy sealing/retry, annual review/three-year boundary, legal holds and destruction across copies |
| Restoration | Erasure replay excludes deleted users before opening, no archive recapture or retention reset; newly aged-out pending accounts processed; verified/unrelated users intact; destroyed legal payloads do not resurrect after archive-storage recovery |
| Product notices | React route/footer keyboard/direct navigation and approved Spanish text only; no internal checklist, missing fields or added cookies; Keycloak basic information/links and existing adult validation, register/verify/recover/return journey remain intact |

The following section records focused phase-2 evidence and its limits; full
affected-area CI and actual proxy/VPS/cloud qualification remain separate gates.

## Local implementation and acceptance

**Prepared locally, pending review and CI (2026-10-17).** A/B/C and the minimum
ordinary profiles and final Spanish copy are owner-approved. Approval does not
demonstrate legal compliance or authorize scheduling, host mutation or opening.
The [runbook](operations-runbook.md#privacy-phase-2-administration) owns administrative
commands; executable sources own exact fields, limits and adapter arguments.

A reuses native Keycloak filtered reads and the existing revocation/deletion/replay
steps. The fresh source read excludes effective `realm-management` roles, including
composite/group inheritance, as well as service/federated accounts. A second probe
after key preflight avoids maintenance for candidates that have already disappeared.
Cleanup intent records an optional `cleanupCutoffMs` in the existing requested event;
older private-dev ledger entries retain their format and retry/replay behavior.
Cleanup retries require the #275 adapter and the original cutoff, reuse first capture,
and stop on new verification or a particular hold. Missing-cutoff staged cleanup
entries require owner reconciliation, never guessed eligibility. Replay still applies
committed erasures with the application stopped and never recaptures legal source.
 An empty probe changes no maintenance state. Execution requires a trusted
#275 deny/drain adapter, signed/encrypted two-copy archive preflight, separate proof
and configured protected holds stores. Admission stops at 100 accounts or 120 seconds;
a started account is completed or left recoverable before release. This is not a
hard 120-second interruption SLA. Exceptions/backlog are reported; a shared failure
stops the run. No timer is installed. The
[#275 adapter](../../deploy/public-production/README.md#identity-maintenance) serializes
owner identity changes/restores/deployments through the recovery lease and settles
management requests before releasing the fence; a local ledger lock cannot prove those
properties. It is locally exercised; its real-host drain acceptance belongs to #276.

B uses `pending-signup-v1`, `requested-account-v1` and `case-hold-v1`. Ordinary
profiles persist issuer/subject/UserId linkage, email once, account creation and
verification state, block/reference and expiry/review metadata. They exclude ordinary
individual ratings, credentials, sessions, names and arbitrary attributes. Particular
claims may name up to 100 relevant GameIds and their value/creation/update times,
with case reference, reason and optional justified longer expiry. Wider evidence
requires a reviewed profile change; the tool never captures a whole account export
by default. The absence of a per-subject hold file is ordinary; the holds directory
itself must be present and protected. Policy-level legal uncertainty remains as
recorded above; no legal classification is demanded for every ordinary erasure.

Ciphertext and signed manifests must agree across two separate roots before destructive
steps. Creating a case fsyncs its parent directory in both roots before acknowledging
sealing; review staging files or inconsistent signed review copies block verification
and purge pending private reconciliation. Retry reuses the first capture; replay never recaptures it. Separate minimal
suppression proofs contain no source identity fields. Archive verification, annual
review, extension/release and explicit single-reference purge are available. Purge
has a dry-run default and refuses early expiry, overdue review or an active hold.
Signed destruction proofs prevent restored ciphertext from being resealed; a purge
can remove restored matching copies again. Half-written unsigned destruction intent
or inconsistent review copies stop for owner reconciliation; do not bypass signatures
or manufacture missing review evidence. The tool does not enumerate/schedule all
archives or erase filesystem snapshots, holds correspondence or arbitrary media.
Physical independence, disposal, clocks, key protection and recovery remain host gates.
The private-dev selector refuses a production realm; unknown state of a named
application container is an error rather than evidence that it is stopped. Production
mutations require an explicit named application container even when stopped; #275
must qualify its restore-time creation/state proof. Explicit
production container/origin/issuer pairing remains an unqualified #275/#276 boundary,
not something proven by issuer-string validation alone. Host diagnostics and SQL
errors are not relayed into ledger/log output; archive-review output excludes private
hold grounds/history. Ledger UUIDs remain pseudonymous personal data: the protections
and limited replay retention apply even though emails and source fields are excluded.

The deletion ledger keeps its JSONL format and two-copy prefix reconciliation. Reads
stream with a 128 MiB history ceiling and 8 KiB event ceiling. Offline entry iteration
uses repeated subject scans with a 120-second scan guard, excluding time spent by
the caller on separately bounded host steps; it is deliberately not an indexed database
and may refuse large histories. Review measured runtime before
scheduling; no claim of constant-time full-history traversal is made.

C generates safe text blocks and realm localization from the
[approved Spanish source](../legal/public-texts-es.md), excluding internal editorial
material. See [product integration](#publication-and-product-integration) for current
availability and operator/release boundaries. Packaged same-origin navigation and
actual deployed realm/mail/storage/cookie/CDN acceptance remain distinct from local
component and disposable-provider evidence.

| Work | #274 local result | Remaining gate |
|---|---|---|
| A: 30-day pending signup cleanup | Bounded probe, conditional maintenance protocol, fresh verification guard, existing deletion/ledger and failure recovery | CI real native filters; #275 [prepared production transport and local evidence](../../deploy/public-production/README.md#identity-maintenance), pending final acceptance; #276 measured deployed race/drain/backlog and scheduling acceptance before any real timer |
| B: minimum blocked archive | Automatic profiles, scoped claims, encrypted signed two-copy sealing, separate proof, review/hold/expiry/purge and restored-copy refusal | #275 protected storage/keys and independent copies; #276 cloud/owner-storage isolation, actual recovery/snapshot/media disposal and longer applicable obligations |
| C: public information | Approved public sections in every build, shared footer and generated native registration notice | Owner code review and CI; explicit existing-realm reconciliation; #276 actual realm/mail/storage/cookie/CDN evidence and public exposure decision |
| Rights and incidents | Register, rights/breach procedures and existing export/deletion/replay retained | #276 actual contacts, channels, backup replay and response exercise |

Focused synthetic evidence: real-GPG archive lifecycle and existing ledger fault tests;
10,000-account streamed lookup/reconciliation fixture (observed peak traced memory below 155 KiB);
16 frontend component/routing/account tests; lint/type checks/build; production-realm
static guard; four development pages at desktop/mobile widths with no legal-content
axe violations. A disposable PostgreSQL 18.4 fixture with equivalent Ratings primary
key and 5,000 rows executes the exceptional query, returns 100 then 50 rows after a
cursor, and shows Limit/Sort/Bitmap Heap/Bitmap Index plan nodes. It does not prove
full Flyway/runtime topology or native Keycloak drain behavior. Full disposable
Keycloak/PostgreSQL/BFF validation remains CI; its scenario now includes native
candidate exclusion/inclusion and archive projection checks. No current-commit CI
result exists before owner-authorized commit/push.

The synthetic GPG fixture pins only its disposable keyring clock because this WSL
clock showed backward steps; a negative control still rejects encryption before key
creation. An initial clock-related encryption failure stopped before capture/delete.
Production key/signature clock checks are unchanged and a trustworthy host clock is
still required. No real personal data, archive destination or Lenovo state was used.
