# Public production: hosting and release-gate investigation (#252)

- **Status:** Investigation complete; design and work decomposition awaiting owner review
- **Research date:** 2026-10-14
- **Source revision:** `c0bcc1e46cff47e03db596c70320b3c1e65b1587` (updated `origin/main`)
- **Consumer:** Owner review of #252 and decomposition of epic #251

This is point-in-time evidence, not an operations procedure or a permission to
purchase/expose anything. The [Product Brief](../product/product-brief.md#public-release-and-sustainability)
owns release mode; [platform design](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns the technical target; [delivery lifecycle](../development/delivery-lifecycle.md#public-production-gates)
owns the gates; [ADR-0025](../decisions/0025-host-public-production-on-a-deferred-single-vps.md)
proposes the durable architecture decision.

## Conclusion and evidence boundary

The owner selected **netcup VPS Lite 2, x86_64, 8 GB RAM**, conditionally on
availability, final price and measured capacity. The owner also selected open,
non-commercial, best-effort operation, a EUR 20/month total recurring ceiling,
deferred purchase until a production candidate, and continued local/CI development
with temporary private-dev integration. Architecture research can close: the remaining
uncertainty is a bounded release/host qualification, not an open provider-selection
exercise. The technical details below remain proposals for review.

No server inspection, load test, configuration change, contract, DNS change or
purchase was performed. Existing main evidence is used; unrelated working-tree
changes in the original checkout are excluded.

### Options compared

| Criterion | Separate Compose runtime on current Lenovo | Separate owner-managed hardware | Selected netcup VPS |
|---|---|---|---|
| Availability/evidence | Existing private integration host | No suitable available host identified; do not assume one | Listed product, availability must be checked at purchase |
| CPU/RAM | i7-7700HQ, 4 physical/8 logical cores, ~7.63 GiB RAM, 4 GiB swap; one-runtime #85 results | Unknown until hardware inventory/measurement | Advertised 4 shared vCore, 8 GB RAM; x86 catalogue; no measured equivalence to Lenovo |
| Disk/network | SSD/ext4; no measured two-runtime storage budget or public uplink/ingress reliability | Disk/uplink/power unknown | Advertised 160 GB SSD, 750 Mbit/s interface; traffic policy below; real I/O/latency unmeasured |
| Capacity/headroom | Healthy normal 10–12 req/s windows; degradation at 15; no dual-runtime experiment | No evidence | Proposed budget in platform design; qualification mandatory |
| Failure/security radius | Same kernel, Docker daemon, disk, power and home uplink; Compose separation is not host isolation | Separate host but potentially same power/router/home network | Separate from private-dev/home; all production components share VPS failure/security radius |
| Public edge/management | Requires a new authorized public ingress/DNS/TLS design; existing owner-only Tailscale Serve is not public production | Same home-ingress problem unless independently hosted | One HTTPS proxy; private administration; IPv4/IPv6 host and Docker filtering both required |
| Database/identity/secrets | Must duplicate databases/roles/realm/secrets; never share private data | Independent stack required | One PG server with separate databases/roles; independent production realm/secrets |
| Recovery | Existing rehearsal patterns reusable; off-host backup still necessary | Rebuild hardware/network plus restore | Rebuild VPS plus restore; provider snapshots do not cover account/host loss |
| Telemetry/delivery | Existing patterns, additional stack competes for resources | Same portable patterns, new host burden | Reuse bounded metrics and immutable digests; no public management access |
| Recurring/worst-case cost | No new subscription, but electricity/connection already consumed; outage/upgrade cost not quantified | Unpriced acquisition, energy, network, replacement; not a proven zero-cost option | Estimate below; add-ons and renewal must fit total ceiling |
| Maintenance | Lowest hardware novelty, highest coexistence coupling | More physical lifecycle responsibility | Owner maintains Linux/Docker/data/identity; supplier owns hardware |
| Decision | Rejected for first public launch | Not an evidenced alternative worth pursuing now | Owner-selected; purchase and exposure remain gated |

### Capacity evidence, assumptions and stop conditions

The [final #85 report](performance-baseline/remote-harness.md) and its linked JSON
are numerical authority: synthetic growth fixture 50,000 Games / 200,000 Releases /
500,000 Ratings; 12 req/s for five minutes passed, 10 req/s during bounded fixture
sync passed, 15 req/s saturated the ten-connection application pool and caused errors.
Recovery at 5 req/s succeeded on the same instance. Only two authenticated users
were concurrent. Browser assets/CDN, mass new-game import/localization and continuous
production traffic were not established. Broad/deep search is a known expensive path.

The private app, PG and localizer quotas already sum to 4.5 CPU / 3 GiB memory;
these are ceilings, not simultaneously consumed cores or total host consumption.
Identity, telemetry, kernel, cache and jobs add work. Doubling this stack cannot be
declared safe on an 8 GB Lenovo from #85. Its shared kernel/daemon failure radius
exists regardless of resource limits, and no public home-network test was authorized.
This supports rejecting coexistence without another remote experiment.

No registered-user count predicts request rate. Catalogue, rating/identity, history,
WAL, model and telemetry growth remain independent axes. Continue bounded SQL pages
and deterministic unique tie-breakers, database constraints and one fenced sync;
do not introduce in-memory catalogue processing or per-request provider calls.
No query, pool, index, cache or concurrency implementation changes in this spike.
Existing ETags apply to public representations; personal/session responses are not
shared-cacheable. Future horizontal application capacity still needs an explicit
session/identity/runtime assessment; a single-host launch does not claim HA.

Use the existing harness with a disposable fixture/isolated project on the VPS
before real accounts: normal growth reads at 5 then 10 req/s, five-minute reference,
two authenticated journeys, bounded synchronization and a backup observation, and
recovery at 5 req/s. Separately test a cold model and a bounded new-game sample;
never load-test IGDB. Proposed stop criteria reuse #85's <1% errors/drops, >=95%
scheduled delivery and p95 <=1 s, plus no OOM, sustained swap thrashing or persistent
pool pending. Record CPU steal/throttling, JVM/PG/Keycloak/host memory, filesystem
growth and recovery. Sample each activity and their realistic overlaps; do not run
all resource-heavy jobs concurrently by default. These are qualification criteria,
not a public SLO. If failed, keep closed, diagnose the narrow reproducer and decide
whether to reduce safe admission/workload, change the design or abandon the plan;
there is no automatic upgrade authorization.

### Cost evidence (estimate, not an order)

The [netcup product listing](https://www.netcup.com/de/server/vps/vps-lite-2-g12.5s-3m)
showed EUR 9.50/month including German 19% VAT, no setup fee, three-month minimum
term and quarterly billing with IPv4 + IPv6. Assuming the same net price and Spanish
21% VAT yields **EUR 9.66/month**, approximately EUR 28.98 per quarter. Checkout,
stock, location, renewal and cancellation terms must be rechecked. It lists a
temporary 100 Mbit/s throttle when the preceding 24-hour average exceeds 100 Mbit/s.
Do not interpret advertised interface speed as guaranteed application throughput.

| Recurring item | Planning allowance, EUR/month | Evidence / condition |
|---|---:|---|
| Selected VPS, IPv4 + IPv6, estimated Spanish VAT | 9.66 | Above listing; final checkout unresolved |
| Domain and DNS, annual renewal amortized | 2.00 | `gameometro.com` at OVHcloud, registrar and DNS ([names](../architecture/deployment/mvp-platform-and-delivery.md#public-domain-and-names)): first 12 months EUR 9.67 including VAT paid upfront (about EUR 0.81/month). Renewal tariff unverified, so the allowance stays until it is read in OVHcloud; the same applies to any bundled mailbox |
| Encrypted off-host backup destination | 2.00 | Allowance; existing independent owner storage may cost zero; prove capacity/access/copy cadence |
| Transactional recovery/verification email | 2.00 | Allowance; [preferred free plan](public-release-rights-and-privacy.md#73-increment-3-transactional-email-provider-2026-10-15) costs EUR 0 with no payment method; the documented replacement at most about EUR 2.94; nothing contracted, delivery unproven |
| Total planned | **15.66** | Leaves EUR 4.34 contingency within EUR 20; not a service quote |

Compose, reverse proxy, PostgreSQL, Keycloak and reused local telemetry require no
paid managed tier. Private-dev continues on existing hardware, without a new paid
subscription; account for any paid eligibility change. The EUR 20 limit includes
VAT, renewals, addresses, storage, backup, email, DNS and all recurring add-ons;
annual/quarterly invoices are amortized without concealing their payment/commitment.
Do not enable Plesk, managed DB, extra disks, paid monitoring or auto-upgrade.
Record actual terms and the maximum foreseeable bill, not merely a promotional
starting price. If any required service has an uncapped overage, obtain a hard cap
or reject it. Owner review of the actual total/commitment precedes purchase.

## Provider and image review

Primary sources reviewed: [IGDB API documentation](https://api-docs.igdb.com/#getting-started),
[FAQ](https://api-docs.igdb.com/#faq),
[image reference](https://api-docs.igdb.com/#images),
[Twitch Developer Services Agreement](https://legal.twitch.com/en/legal/developer-agreement/),
and [IGDB content/image policy](https://www.igdb.com/content-policy).

IGDB describes free non-commercial API use under the Twitch agreement and explicitly
supports local storage/serving to end users. Its commercial FAQ requires a partnership
path for monetized products; “free” is not unconditional commercial permission.
It documents CDN image URLs, 4 API requests/s and up to 8 open requests. These
support the proposed public non-commercial metadata use; no source makes public
visibility alone require a paid API tier. Preserve the existing lower pacing,
bounded retry/page/window budgets and application-only credentials.

**Limit:** metadata access and a documented image URL do not establish a universal
copyright licence for every artwork. IGDB's upload policy includes fair-use material;
that US concept cannot be presumed a permission for all Spanish/EU uses. The Twitch
agreement requires lawful use and respect for third-party rights. Conclusion:
**metadata route supported; public media acceptance conditional**. This is a
specific launch gate, not an unresolved hosting choice.

Before exposure the owner must record a terms/rights assessment covering normalized
metadata, localized derivative text, covers, artworks, screenshots and logos, and
the existing detail-page attribution/source-link placement. If the documents cannot
resolve a material permission/attribution ambiguity, obtain IGDB clarification through
an owner-authorized contact; no message was sent here. Disable disputed references
and use product-owned fallback while unresolved. Do not silently proxy/cache/copy
binaries or expose a raw data dump/provider API. No external-score use is approved.
Monetization (including ads/affiliate/sponsorship), changed terms or broader reuse
reopens this gate independently of infrastructure spend.

Direct browser CDN requests disclose the visitor's IP/user agent and potentially
referrer to a third party. Audit actual requests/cookies and destinations, retain
the allowlist/CSP and choose a no-referrer production policy for those requests.
Explain the CDN and any international processing in the privacy information.
Fallback works offline; CDN/IGDB failure must not block local product readiness.
[ADR-0001](../decisions/0001-reference-igdb-cover-images.md) remains the image
mechanics owner; no public rights clearance is claimed by this investigation.

## Security, privacy and operation review

Public exposure introduces account farming, credential attacks, expensive anonymous
search and disk/log exhaustion. HTTPS and container limits alone do not address
these. The platform target describes isolation, secrets and deployment controls;
the following findings become release acceptance work.

### Identity and abuse findings from the repository

The [shared realm](../../docker/keycloak/import/videogame-platform-realm.json) enables
registration/password reset, disables email verification, requires email plus first
and last names, and contains no SMTP or brute-force protection configuration. Local
or private login evidence does not prove email ownership/recovery or safe open signup.
Prepare a production-specific reviewed realm policy without changing private-dev:
minimize profile data (names are not needed to rate), enable verified-email registration,
prove recovery/email delivery, password policy, session expiry and brute-force protection.
If an eligible email route cannot fit the budget, keep open account launch blocked
rather than displaying a broken reset flow. Do not host a new mail server.

Preserve confidential-client OIDC, exact HTTPS redirect/logout URLs, opaque secure
HttpOnly cookies, CSRF/same-origin checks and principal-derived ownership. Test two
accounts and forged identity/forwarded headers through the proxy, not just direct API
calls. Rate/admission limits for expensive reads and signup/login/writes need bounded
state/expiry and explicit overload behaviour; do not assume core Caddy provides a
rate-limiter. Prefer controls in the current app/Keycloak before adding a plugin or
service. A bounded single-instance limiter may shed load, but must never be the
sole durable owner/uniqueness/authorization guarantee. Security restrictions need
IPv4 and IPv6 evidence plus Docker-published-port review, not only host firewall rules.

### Privacy obligations and minimal delivery

Use [GDPR](https://eur-lex.europa.eu/legal-content/ENG/ALL/?uri=celex%3A32016R0679)
as the primary legal baseline. Public best-effort/non-commercial operation does
not itself remove obligations. The owner must establish purpose/lawful basis,
minimum data, retention, information and rights procedures, processor agreements,
security and breach response (Articles 5/6/12–22/28/32–34). Assess applicability of
Spanish service information and children's access rather than assume an exemption.
Before accounts open, approve who the controller is, a private contact route, age
policy and public Spanish information without publishing private personal details
in the repository.

Prepare a small data inventory: Keycloak profile/credentials, internal principal
mapping and active ratings, session/security metadata, support requests, off-host
encrypted backups and third-party CDN/email flows. No public user profiles or
individual-owner rankings follow from public launch. Avoid optional tracking.
Document and rehearse access/export, rectification, account/rating deletion across
Keycloak/application and aggregate consistency, and expiry of deleted data in
backups. A manual authenticated owner procedure is sufficient initially; no privacy
portal or new API is needed merely for this spike. Prevent restored backups from
resurrecting deleted accounts/ratings before reopening service.

[AEPD cookie guidance](https://www.aepd.es/guias/guia-cookies.pdf) distinguishes
strictly necessary cookies from non-exempt tracking that requires prior consent.
Inventory real cookies/storage first; session/security-only use does not justify
adding a generic consent platform. Review CDN behaviour rather than treating every
external image as either consent-free or automatically consent-required.
[Netcup's DPA documentation](https://www.netcup.com/en/helpcenter/documentation/general/dpa)
offers a standard agreement without extra fee; review region/subprocessors and
conclude the applicable agreement before storing personal production data. Do the
same assessment for chosen email/backup providers; EU compute alone is insufficient.

Retention, redaction and diagnostic bounds belong to
[observability](../development/observability.md#public-production-observability-proposal).
Production identity/client credentials and real personal data never enter CI fixtures,
images or the repository. Technical account/recovery/email/privacy paths need their
own meaningful tests during implementation.

### Best-effort operation

One owner, no commercial SLA, no guaranteed uptime or instant support. Publish that
expectation and a usable private contact route. It permits downtime, not unbounded
retention or knowingly unsafe accounts. Have a demonstrated way to disable signup,
stop synchronization, close public ingress and preserve protected data during an
incident. Assess breach notification obligations promptly; do not treat owner
availability as a waiver. Review OS/Docker/image security updates regularly and
critical advisories urgently, with schema-compatible rollback/forward recovery.
No staffed support system, status SaaS, HA or on-call tooling is warranted now.

## Evidence split and minimal epic decomposition

The [gate matrix](../development/delivery-lifecycle.md#public-production-gates)
is the canonical split between preparation without a server, procurement and
mandatory real-VPS acceptance. VPS I/O/CPU steal, provider networking/firewall,
public ACME renewal, independent off-host recovery, real email delivery and reboot
behaviour cannot be certified by local/CI doubles. Purchase is conditional
qualification after a candidate exists; successful qualification precedes exposure.

The following are **draft work packages for owner review**, not created issues.
#252 is the architecture investigation; #251 remains the parent delivery epic.
Keep only the next bounded package Ready; split further only if implementation
reveals an independently reviewable boundary. #269 already owns alert work; reuse
its relevant result instead of creating duplicate alerts work.

| Order / draft package | Scope and acceptance | Dependency / gate |
|---|---|---|
| 1. Prepare public-use and account readiness | Record provider/media assessment; Spanish privacy/contact/age and retention policy; profile minimization, verified signup/recovery and rights procedure; bounded abuse controls with tests. No server purchase or DNS. | Review #252/ADR proposal; media or identity ambiguity blocks open release |
| 2. Prepare portable production candidate | Versioned Compose/proxy configuration and independent secrets/realm; digest-based dependency/app delivery, migration lock, bounded telemetry, encrypted backup/restore and documented manual recovery. Local/CI exercises use synthetic data; candidate digest/current-main checks and release review recorded. | Package 1 decisions; satisfies pre-purchase gate. Reuse private-dev patterns, not its data or host credentials |
| 3. Qualify selected VPS and accept public release | Owner checks quote/budget/terms and explicitly purchases; establish private administration, deploy candidate, measure capacity, prove recovery/network/ACME/email/reboot and external journey; owner authorizes opening only after evidence. | Both prior packages; purchase gate then real-VPS exposure gate |

The third package is the bounded host/deployment walking skeleton, not a request for
Terraform, a provisioner, blue/green fleet or new environment per stage. Record
new-host evidence in the operations runbook when executed. After first public
acceptance, owner reviews retiring private-dev; no decommission occurs in #252.

## Acceptance disposition for #252

| Issue criterion | Investigation result |
|---|---|
| Compare hosting with capacity/cost/operability/security | Options and conditional cost evidence above |
| Evaluate coexistence with resource/blast-radius evidence | Single-runtime #85, missing dual-runtime proof and shared kernel/disk/network explicitly assessed; rejected without touching host |
| Research public non-commercial provider/licensing | Primary-source review above; metadata supported, media/derivative/attribution assessment is bounded launch work |
| Explicit security/privacy/ingress gates | Findings above plus canonical platform and lifecycle gate matrix |
| Select topology or bounded missing-evidence action | Owner-selected netcup class; proposed single-host topology; actual-VPS qualification after candidate |
| Decompose public epic after selection | Three draft packages above; owner review before any new issue |

This closes the investigation scope, not the issue/epic or launch gates. Owner diff
review, fresh documentation CI after publication, ADR acceptance and implementation
remain outstanding. Documentation-only change: no executable SemVer increment.
