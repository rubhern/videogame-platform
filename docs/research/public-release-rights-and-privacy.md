# Public release: provider rights, privacy and account data (#274)

- **Status:** Phase 1 complete; owner decisions recorded 2026-10-15; IGDB reply pending.
  Phase 2 increment 1 (production realm) complete with decisions D12–D19; increment 2
  (revocation, session bound, coordinated logout) implemented and proven in disposable
  gates on 2026-10-15, pending owner review and not deployed. Increment 3 (email
  provider) closed on 2026-10-15 with owner decisions E1–E6: Mailjet preferred,
  nothing contracted, real delivery unproven. Increment 4 (canonical issuer and
  browser cookies) implemented and proven with synthetic origins on 2026-10-15,
  pending owner review; public hostnames not chosen
- **Research date:** 2026-10-14 (sources); code re-checked 2026-10-15; production realm
  exercised against disposable Keycloak 26.7.0 on 2026-10-15
- **Source revision:** `cda7185cced99ecc8a6d3aa08c1e96831772f86f` (`main`)
- **Consumer:** Owner review of [#274](https://github.com/rubhern/videogame-platform/issues/274)
  and its identity, privacy-workflow, provider-content and abuse phases

Point-in-time evidence for decisions, not legal advice, a compliance statement or a
permission to use provider content. Nothing was implemented, configured, purchased,
deployed or sent to a provider in this work. It extends, without repeating, the
[#252 investigation](public-production-topology.md#provider-and-image-review), the
[score rights gate](external-quality-signals.md#rights-and-publiccommercial-use) and
[ADR-0001](../decisions/0001-reference-igdb-cover-images.md). Approved decisions live in
their canonical owners: the account baseline in the
[Product Brief](../product/product-brief.md#public-release-and-sustainability) and
[Q-013](../product/assumptions-and-decisions.md); revocation and the management
interface in the [platform design](../architecture/deployment/mvp-platform-and-delivery.md#account-revocation-and-the-management-interface),
backup ceiling and deletion ledger in its [recovery section](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery);
log ceiling in [observability](../development/observability.md#public-production-observability-proposal);
acceptance checks in the [public gates](../development/delivery-lifecycle.md#public-production-gates).

Labels: **[V]** verified in the cited source on the research date or in the code at
the source revision; **[D]** owner decision of 2026-10-15; **[I]** interpretation that
needs legal review before it is relied on; **[U]** unresolved; **[R]** recommendation.

## Sources consulted (2026-10-14)

| Source | Result |
|---|---|
| [IGDB API documentation](https://api-docs.igdb.com/) (getting started, rate limits, images, partnership, FAQ) | Read in full |
| [Twitch Developer Services Agreement](https://legal.twitch.com/en/legal/developer-agreement/), last modified 12/04/2024 | Read in full |
| [Twitch trademark guidelines](https://legal.twitch.com/en/legal/trademark/), last modified 07/11/2018 | Read |
| [Twitch privacy notice](https://legal.twitch.com/en/legal/privacy-notice/), last modified 08/12/2026 | Read (controller, transfers) |
| [IGDB content policy](https://www.igdb.com/content-policy), terms and privacy pages | **Not readable:** bot challenge (HTTP 403); #252's earlier reading is not re-verified |
| [GDPR](https://eur-lex.europa.eu/legal-content/ES/TXT/HTML/?uri=CELEX:32016R0679) | Read; later EUR-Lex requests (case law, Digital Services Act) were blocked |
| [LOPDGDD](https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673), consolidated 27/12/2025 | Articles 7, 11–13 and 32 read |
| [LSSI](https://www.boe.es/buscar/act.php?id=BOE-A-2002-13758), consolidated 23/01/2025 | Articles 10 and 22, annex definition read |
| [AEPD cookie guide](https://www.aepd.es/guias/guia-cookies.pdf), May 2024 | Scope, definitions and exempt cookies read |
| [AEPD breach notification guide](https://www.aepd.es/guias/guia-brechas-seguridad.pdf), June 2021 | Register and 72-hour sections read |
| [netcup DPA documentation](https://www.netcup.com/en/helpcenter/documentation/general/dpa), updated 24 April 2026 | Read |
| [Keycloak server administration](https://www.keycloak.org/docs/latest/server_admin/index.html) and [caching](https://www.keycloak.org/server/caching) guides (26.8 docs; repository pins 26.7.0) | Events, brute force, password policy, account deletion, registration terms, sessions read |
| `images.igdb.com` response for one documented sample image | Observed through Amazon CloudFront, no `Set-Cookie` |

## 1. IGDB and provider rights

### Confirmed terms

- **[V]** IGDB is "free for non-commercial usage under the terms of the Twitch Developer
  Service Agreement"; commercial use goes through a partnership asking for visible,
  static attribution. The FAQ prefers local storage and serving to end users.
- **[V]** The agreement grants a limited, revocable licence to use Program Materials
  (APIs, data, metadata) "solely to develop, test, promote, measure, support, and
  operate Your Services" (II.B.2), terminable "for any reason or no reason" (II.B.3, IX).
- **[V]** Obligations relevant to a public launch: do not remove attribution notices
  (II.B.3); respect rate limits (IV.C); industry-standard protection of end-user
  personal data and **prompt written report of any security incident to
  legal@twitch.tv** (IV.D); accept Twitch crawling/audit (IV.E); no sharing of Twitch
  Data with third parties without permission (VI.C); delete Twitch Data on
  termination or on Twitch's request (VI.F, IX); keep data current and give "a clear
  path to the source" with appropriate attribution (VII.B–C); legally adequate privacy
  notices and assistance with Twitch's own data-subject requests (XIII.B).
- **[V]** Marks may be shown only to attribute the source, unmodified, without implying
  endorsement. The local IGDB mark already follows this
  ([provider asset record](../../frontend/public/assets/providers/README.md)).
- **[V]** Images removed or replaced on IGDB remain on the CDN for 30 days.
- **[D]** The owner sent a query to `support@igdb.com` covering metadata, translations,
  images, scores, attribution, retention and CDN. **Reply pending; no further email
  is to be sent.** Nothing below is treated as authorized by that query.

### Content actually used and its standing

Code facts are from `IgdbQueries`, the catalogue migrations and the frontend.

| Content | What Gameómetro does **[V]** | Standing |
|---|---|---|
| Facts: name, dates, platforms, regions, companies, type/status, Hypes | Stored and served from PostgreSQL | **Supported** by the licence and FAQ. Game titles are IGDB `name` only; no IGDB localized names are requested |
| Genres/modes | Product-curated Spanish labels keyed by IGDB references | **Supported**; labels are product-owned text |
| English summary | Shown, naming source and language, when no translation exists; the API also returns it as the translation's source | Unmodified data; same third-party-text caveat as below |
| Spanish summary | OPUS-MT translation of the IGDB summary ([ADR-0022](../decisions/0022-localize-catalogue-content-during-acquisition.md)), shown credited as a translation | **[U]** VII.A forbids "make modifications"; whether a credited translation is a prohibited modification is unclear. Summaries may also contain third-party text |
| IGDB player score | Shown with source link on discovery cards (#255), the game page and full search results (#279) | **[U]** Not prohibited by any text read, not confirmed for public display |
| Covers | Browser loads `images.igdb.com` (ADR-0001) | **Documented use** of the API; underlying artwork belongs to third parties **[I]** |
| Artworks, screenshots, game logos | Featured frames, same reference mode | Same as covers, but decorative large-format use is less clearly "source" display **[I]** |
| Public JSON (`/releases`, `/featured-releases`, `/games`) | Unauthenticated, paginated, consumed by the SPA | **[I]** Serving end users is the documented purpose; bulk third-party extraction could be read as VI.C sharing. Bounded pages and abuse limits reduce it |
| Retention after termination | Catalogue persists until resynchronized | **[I]** FAQ #5 retention speaks of *partnership* termination; without a signed partnership the agreement's deletion duty governs, which narrows the conflict recorded in the score gate |

**Conclusion [I]:** factual metadata, covers and attribution are within documented
non-commercial use. Machine-translated summaries, large decorative media, external
scores and termination handling are not confirmed. No source grants a copyright
licence for third-party artwork or text, and no EU/Spanish legal assessment of
embedding provider-hosted images was made.

### Third-party requests from the browser

- **[V]** The only third-party origin in the CSP is `https://images.igdb.com`; fonts and
  assets are self-hosted; the frontend uses no `localStorage`, `sessionStorage`,
  `document.cookie` or analytics. Cover attribution links use `rel="noreferrer"`; the
  IGDB score links (discovery, game page) do not, so a click sends the referrer origin.
- **[V]** The CDN is Amazon CloudFront (Madrid edge observed) and set no cookie on the
  sampled image. Every image load discloses IP address and user agent to it.
- **[V]** The application sends `Referrer-Policy: strict-origin-when-cross-origin`, so
  image requests disclose Gameómetro's origin; `<img>` elements set no
  `referrerpolicy`.
- **[V]** Twitch's privacy notice names Twitch Interactive, Inc. (USA) as controller and
  allows processing in the United States; it does not mention the CDN or a transfer
  mechanism. **[U]** Data Privacy Framework participation was not verifiable (the
  participant search is a client-side application).
- **[I]** Under CJEU case law on embedded third-party content (Fashion ID, C-40/17;
  official text not re-read because EUR-Lex blocked the request), the site operator
  can be joint controller for causing that transmission. Direct loading with no
  cookies, no referrer, disclosure and a legitimate-interest basis is a defensible
  minimal position, not a verified one.

### Contingency if the IGDB licence ends

Termination can be immediate (II.B.3, IX) and requires deleting Twitch Data (VI.F).
**[R]** Prepare a short runbook, no code, distinguishing:

| Class | Content | On termination |
|---|---|---|
| Provider data | Facts and titles acquired from IGDB, summaries and their translations, media references, IGDB scores, Hypes, provider IDs/references, sync evidence | Stop using and delete. **[I]** Treat even factual titles/dates as Twitch Data unless re-sourced |
| Product-owned data | `GameId`/`ReleaseId`, Spanish genre/mode labels, approved aliases, reconciliation and curation decisions, fallback assets | Keep |
| User data | Accounts, ratings (`UserId`, `GameId`, value, times) | Keep under the users' control; never deleted because of a provider event |

Minimal sequence: disable synchronization and remove IGDB credentials; close or
restrict the public catalogue and inform users; purge provider data; let users keep
signing in to export or delete their ratings; re-source from another provider or
curation under fresh terms. **[U]** `ratings.rating` references `catalogue.game`, so a
purge needs neutral game rows or a migration, and a rating export without provider
titles is of limited value. Backups expire within 35 days; whether that satisfies a
deletion request is a legal question. Write the runbook alongside #275's incident
procedures; it is not a launch blocker.

## 2. Personal-data inventory (verified at the source revision)

| Store / flow | Data | Retention today | Notes |
|---|---|---|---|
| Keycloak database | Username, email, **first and last name (required)**, password hash, verification flag, timestamps; admin-only smoke marker | Until an administrator deletes the user | Private dev: login by username; registration open; `verifyEmail=false`; no password policy; brute force off (Keycloak default). The production realm keeps only the email (section 7.1) |
| Keycloak sessions | SSO session metadata, persisted in the database by default (Keycloak docs) | Idle 30 min, max 10 h; production realm idle 30 min, max 8 h | **[V]** (phase 2) each persisted row holds the client IP and a device note (IP, OS, browser); paired backups therefore copy the IPs of sessions open at capture time. The brute-force record keeps the last failing IP per account |
| Keycloak server log | Error events with user ID, username and IP (default logging listener); success events not logged | Private dev: Docker `local` driver, 3 × 10 MiB, no age limit; not sent to Loki | User events are not stored (`eventsEnabled` unset). **[V]** (phase 2) the production realm has no listener; replaying a consumed authorization code still logs its ID and session ID at WARN |
| Keycloak email | Verification and recovery messages | Private dev: Mailpit, 100 messages / 24 h | Preferred provider selected (7.3); not contracted |
| Application database | `ratings.rating`: `user_id`, game, value, created/updated time, version token | Until the user deletes the rating | No email/name in the application; aggregates are computed live from this table |
| BFF session | Servlet session with OIDC user and tokens, CSRF token, return context; one session cookie (production `__Host-vgp_session`) | 30 min idle, 8 h absolute (7.2); in memory only, lost on restart | The production client's ID token carries only protocol claims (7.1). Browser cookies and storage: section 7.4 |
| Application logs | Method, route template, status, duration, error code | Private dev: Loki 24 h plus syslog cache | No IP, user ID, email, query string or stack trace by default |
| Traces / metrics | Spans to the collector `debug` exporter only; bounded metrics; aggregate rating views | Traces not retained | Distinct-user count is aggregate only |
| Backups | Encrypted dumps of both databases (all rows above except memory and logs) | Count-based, keep 7 by default; off-host copy is an owner step | Deletion ledger not implemented |
| Browser → IGDB CDN | IP, user agent, referrer origin | Third party's policy | See section 1 |
| Not yet built | Edge access log, rights/support mailbox, production SMTP, off-host backup store | — | Each becomes a processor or retention decision |

`UserId` is `UUID.nameUUIDFromBytes(issuer + "\n" + subject)` **[V]**: pseudonymous,
deterministic and dependent on the **exact issuer URL**. Changing the production issuer
after launch would detach every rating from its account.

## 3. GDPR and Spanish requirements

| Topic | Confirmed (source) | Interpretation **[I]** | Disposition |
|---|---|---|---|
| Applicability | Household exemption covers purely personal or household activity (GDPR 2.2.c) | An open public site is very unlikely to qualify; non-commercial status does not exempt | Treat GDPR/LOPDGDD as applicable |
| Controller and contact | Identity and contact details at collection (GDPR 13.1.a) | LSSI 10 (name, address, email, tax ID) applies to services that are an *economic activity* (annex; AEPD guide uses the same test); a free, ad-free service may fall outside | **[D]** Natural-person controller with dedicated identity and email; LSSI answer before publication |
| Lawful basis | GDPR 6.1 | Account and ratings: contract (6.1.b) with brief terms; security logs/abuse/backups: legitimate interest (6.1.f); breach and request records: legal obligation (6.1.c) | Draft with the legal texts; not consent |
| Information | GDPR 13; layered basic information (LOPDGDD 11) | — | Spanish layered notice; [AEPD model clause guide](https://www.aepd.es/guias/guia-modelo-clausula-informativa.pdf) as template |
| Minimum age | Consent of minors only from 14 in Spain (LOPDGDD 7); GDPR 8 lets states set 13–16 | An adult-only declaration avoids both thresholds; its sufficiency without verification is untested | **[D]** 18+ by explicit declaration; no birth date or identity document |
| Cookies | Consent required except strictly necessary storage (LSSI 22.2); AEPD lists authentication (session-only) and security cookies as exempt, recommends generic information | Keycloak's own cookies not yet inventoried in a browser | No consent banner if the inventory stays strictly necessary; one paragraph of cookie information |
| Retention | Storage limitation (GDPR 5.1.e) | — | **[D]** Section 6.1 (D9) |
| Processors | Written contract (GDPR 28); netcup's control panel generates a DPA, with "no additional fees if our contract generator is used" | Email, backup storage and any edge provider unselected | Conclude DPAs before any personal data; prefer EU processing |
| Transfers | GDPR Chapter V | CDN request to a US-controlled service; email provider later | Disclose; avoid non-EU processors where an EU option fits the budget |
| Records of processing | 250-employee exemption does not apply when processing "no sea ocasional" (GDPR 30.5) | Account processing is continuous, so a record is probably required | One-page record; [AEPD Facilita RGPD](https://www.aepd.es/herramientas/facilita-rgpd) as a free aid |
| DPO / DPIA | GDPR 37, 35 | Neither appears required (no large-scale monitoring or special categories) | Record the reasoning; reassess on profiles, comments or tracking |
| Breaches | Notify AEPD within 72 h unless unlikely to cause risk; document every breach (GDPR 33); inform users when high risk (34); AEPD guide asks for an incident register | Risk scoring per incident | Incident runbook with register, AEPD sede path and the Twitch report |
| Rights | Reply within one month, extendable by two (GDPR 12.3); free of charge (LOPDGDD 12.7); controller bears the proof of reply (12.4); permanent remote access can satisfy access (13.2); portability for contract/consent data (GDPR 20) | — | **[D]** Manual procedures, section 4 |
| Blocking | Controller "obligado a bloquear" data on rectification or deletion, keep for authorities only during limitation periods (LOPDGDD 32) | Whether expiring encrypted backups satisfy it, or a separate blocked copy is needed | Resolve before building any blocked-data store |
| Digital Services Act | Not read (EUR-Lex blocked) | Likely marginal: no public user content, non-remunerated | Recheck before comments, reviews or monetization |

## 4. Rights handling, revocation and deletion

**[D]** Manual, owner-run administrative procedures; no privacy portal at launch.

| Right | Path |
|---|---|
| Access | "Mis puntuaciones" plus the Keycloak account page give permanent remote access; the remaining article 15 information comes from the notice or by email |
| Portability / export | Owner script: Keycloak profile fields plus all ratings for the derived `UserId`, as JSON, sent to the verified account email; the file is deleted after delivery |
| Rectification | Profile in the Keycloak account page; ratings editable in the product |
| Erasure | Ledger-driven owner script, section 4.2 |
| Restriction / objection | Case by case; disabling the account (with revocation, 4.1) is the restriction tool |

Requests are verified from the account's own email or a signed-in session; data is
never sent to another address. Public aggregates and statistics are computed from
`ratings.rating` at read time and public catalogue representations must revalidate
before reuse, so deleting rows needs no recomputation **[V]**. Keycloak's self-service "Delete Account"
stays disabled: it would remove the identity but not the application's ratings **[R]**.

### 4.1 Immediate revocation

**[V]** The BFF keeps an in-memory servlet session on its single replica and configures
no OIDC back-channel logout, no absolute session lifetime and no revalidation against
Keycloak; after sign-in it never calls Keycloak again, and its logout ends only its own
session. A session opened before an account is disabled or deleted keeps working while
each request arrives within the 30-minute idle window, and can write ratings under the
old `UserId`.

**Requirement (canonical in the platform design):** disabling or deleting an account
stops its new operations on the next request, whatever the session age; a maximum
lifetime only bounds exposure. The requirement names no mechanism. Phase 1 added
"without relying on process-local state", which would have excluded acting on the
in-memory sessions themselves; it is withdrawn as a requirement and replaced by this
correctness criterion: revocation reaches every store that can authenticate the
account (Keycloak and every BFF session store), its outcome is verified, and an
unverified outcome fails closed.

Comparison (revised in phase 2, 2026-10-15):

| Option | Correctness | Security | Complexity | Request cost | Maintainability |
|---|---|---|---|---|---|
| a) PostgreSQL revocation row, checked on every authenticated request | Immediate, durable, independent of where sessions live | Fails closed | Table, migration, owner write path, row lifetime, request filter | One indexed query on **every** request carrying a session, catalogue reads included | New persistence path for a few events per month |
| b) Keycloak back-channel logout handled by Spring Security | Immediate only when delivered. **[V]** The BFF never refreshes Keycloak's session, so after 30 idle minutes in Keycloak an active BFF session has no Keycloak session left to notify; delivery is not retried | Signed logout token; internal endpoint | Low: framework and realm attribute | None | Standard, framework-maintained |
| c) Procedure-driven invalidation of the BFF's own sessions through a private management operation | Immediate; acts on the store that holds the session, so the in-memory index shares its fate; returns the count for verification | Write operation confined to the private management port, like catalogue-sync management | Spring Security session registry, one operation | None | Small; follows the session store if it later moves to a shared store |
| d) Revalidate with Keycloak on each request | Immediate | Couples every request to identity availability | Low | One identity call per request | Rejected |
| e) Periodic refresh against Keycloak | Bounded delay, not next request | Detects disabled/deleted users natively | Medium | One call per session per interval | Insufficient alone |
| f) Absolute BFF session lifetime | Bound only | Limits exposure | Trivial | None | Required complement |

**[D]** Implement **c + f** (D18, [ADR-0027](../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md)).
Disabling or deleting is an owner procedure (D10), so the
moment of revocation is known and the procedure can act where the session lives:
(1) disable or delete the Keycloak user and end its Keycloak sessions, so no new
sign-in or pending code exchange succeeds; (2) invalidate every BFF session whose
derived `UserId` matches and record the returned count; (3) repeat (2) expecting zero.
If the operation cannot be reached or fails, restart the application before marking
the step done: its sessions are in memory, so a restart is a correct, if blunt,
fail-closed fallback. A per-request PostgreSQL check is justified only if sessions
become self-contained (no store to act on) or a revocation must originate where the
procedure cannot reach every store; replicas with a shared session store use the
same operation against that store. Back-channel logout (b) remains a later complement
for sign-outs started in Keycloak. Section 7.2 records the implementation of increment 2.

### 4.2 Deletion ledger: partial failures, retries and restores

**[R]** The ledger is the durable record that makes erasure repeatable:

- **Location:** the owner's protected administration storage, outside the VPS and
  both databases, copied with the reconstruction material. Because the owner runs the
  procedure, no deletion can exist only on a host that might be lost.
- **Entry:** request reference, Keycloak user ID, derived `UserId`, request time,
  per-step outcome and completion time. No email, rating values or reasons.
- **Write-ahead:** the entry is recorded before any mutation.
- **Idempotent steps, in order:** (1) revoke (section 4.1): disable the Keycloak user,
  end its Keycloak sessions and invalidate its BFF sessions; (2) delete ratings by
  `UserId`; (3) delete the Keycloak user, "not found" counting as done; (4) verify: no
  ratings, no user, no Keycloak or BFF session; (5) mark complete.
- **Partial failure:** a failure stops the run and leaves the entry incomplete. The
  account is already revoked, so a half-deleted account cannot act (fail closed).
- **Retry:** each run lists incomplete entries and re-executes all steps; repeating a
  step is harmless. Incomplete entries block reopening after a restore and are
  resolved within the one-month reply period.
- **Re-registration:** entries key on immutable identifiers, never on email. A later
  account with the same email has a new subject and is unaffected.
- **Restore:** after restoring either or both databases, before reopening, replay
  **every** ledger entry, not only those after the recovery point, which avoids clock
  and pair-boundary mistakes; then verify and reopen. Isolated rehearsals do the same.
- **Retention [D]:** keep an entry while any backup, off-host copies included, could
  restore the deleted data: at least 35 days after completion. Incomplete entries are
  never purged. Rights-request correspondence is kept only as long as needed to prove
  the reply (LOPDGDD 12.4); that period is **[U]**.
- **Rehearsal:** with synthetic accounts, interrupt the procedure after each step and
  rerun it; restore a backup taken before a completed deletion, replay and prove the
  account and ratings stay gone, an unrelated account is intact, and a re-registered
  email gets a fresh identity.

## 5. Risks

| ID | Risk | Criticity | Status / launch impact |
|---|---|---|---|
| R1 | No privacy notice, terms, adult declaration or contact exists | Critical | **Blocks accounts** |
| R2 | Unverified email registration, no password policy or brute-force protection | Critical | **Blocks accounts**; production realm prepared and tested (7.1), not deployed |
| R3 | An open BFF session survives account disable/delete and can recreate ratings | High | **Blocks accounts** until operated on the production host; mechanism implemented and proven with synthetic accounts (7.2) |
| R4 | No production email route for verification/recovery | High | **Blocks accounts**; preferred provider selected (7.3, E1–E6); account acceptance, professional-use confirmation and delivery proof (#276) pending |
| R5 | Erasure, ledger and restore replay not implemented or rehearsed | High | **Blocks accounts**; privacy phase |
| R6 | LSSI may require a published postal address and tax ID | High | **Blocks publishing the legal notice** until answered |
| R7 | Translated summaries may breach the no-modification clause | High | Blocks public translations unless IGDB or a legal answer allows them |
| R8 | IGDB scores not confirmed for public display; #279 widens their surfaces | High | Publication blocked until restrictions are verified (D11) |
| R9 | Decorative artworks/screenshots/logos lack clear coverage | Medium | Rights review before launch (D7); covers unaffected |
| R10 | Browser requests to the US-controlled CDN disclose IP and referrer origin | Medium | No-referrer and disclosure before exposure; privacy review open (D8) |
| R11 | Keycloak logs keep failed-login IP and username with no age limit | Medium | Production realm logs no events (7.1); 7-day ceiling before accounts (D9) |
| R12 | Issuer URL change would orphan every rating | Medium | Fix the canonical issuer before accounts |
| R13 | Twitch licence revocable at any time | Medium | Contingency runbook; can follow launch |
| R14 | Unrecorded processors (email, backup storage) | Medium | DPAs before personal data reaches them; the preferred email provider's DPA is part of its terms (7.3) |
| R15 | Bulk scraping of public JSON as third-party sharing | Low | Abuse phase limits; can follow |
| R16 | Rights requests exceed manual capacity | Low | Reassess after launch |
| R17 | Registration answers "El email ya existe", disclosing that an address has an account (enumeration); recovery already answers uniformly | Medium | **Blocks launch**; resolve in the abuse phase without a custom Keycloak flow unless one is justified |
| R18 | With no Keycloak event listener (provisional, 7.1) there is no security signal for credential stuffing or registration abuse | Medium | **Blocks launch**; the abuse and observability phase defines sufficient bounded signals without unnecessary personal data |

## 6. Decision status

### 6.1 Approved owner decisions (2026-10-15)

| ID | Decision | Consequence |
|---|---|---|
| D1 | Natural-person controller with a dedicated identity and contact email | LSSI address/tax-ID question answered before publication |
| D2 | AI-assisted research on official sources; a one-off legal consultation only for a material uncertainty that cannot reasonably be resolved | No compliance claim; section 6.3 lists candidates |
| D3 | Adults only (18+) by explicit declaration; no birth date or identity document | Registration terms carry the declaration |
| D4 | Email as sign-in identifier; no required names | Production realm user profile |
| D5 | Query sent to `support@igdb.com`; reply pending; no further email | Record the reply when it arrives |
| D6 | Translations unchanged in private-dev; authorized before exposure, otherwise originals or disabled | Public content switch |
| D7 | Images unchanged in private-dev; decorative-image rights reviewed before launch; approved cover mechanism kept where supported | Public content switch for featured media |
| D8 | Direct IGDB references, no-referrer where appropriate, third-party requests disclosed; privacy review pending | Referrer change and notice text |
| D9 | Accounts and ratings until deletion, save legal obligations; logs ≤ 7 days; backups ≤ 35 days; deletion evidence while restorable backups exist | Platform and observability updated |
| D10 | Manual administrative procedures for access, export, rectification and deletion; no portal | Section 4 |
| D11 | IGDB scores remain a product goal; restrictions verified before publication, neither assumed prohibited nor confirmed | Public score switch until verified |
| D12 | Public realm `gameometro` | The full OIDC issuer (identity HTTPS origin plus `/realms/gameometro`) is fixed before the first real account and never changes, because every rating is keyed by a `UserId` derived from it (R12) |
| D13 | Passwords of 12 to 128 characters, no artificial composition rules | Realm password policy (7.1) |
| D14 | Per-account temporary lockout after five failures, from one minute up to 15 | Realm brute-force policy (7.1) |
| D15 | Keycloak sessions: 30 minutes idle, 8 hours maximum | The BFF has an 8-hour absolute limit from the same authentication (7.2) |
| D16 | Email changes stay a manual administrative procedure | Keycloak's update-email flow is not enabled or extended |
| D17 | Logging out of Gameómetro also ends that browser's Keycloak session with standard OIDC | Implemented (7.2, [ADR-0027](../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md)) |
| D18 | Administrative revocation invalidates BFF sessions directly with standard Spring Security capabilities; no per-request PostgreSQL check, no new dependency | Implemented (7.2, ADR-0027) |
| D19 | Keep Keycloak's native registration declaration (18+ and conditions) | No per-account evidence of the accepted text; no complex customization without a justified need |
| E1–E6 | Transactional email: Mailjet Free preferred and conditional, Scaleway as documented replacement, no card or overage, dedicated sending subdomain, real reply address, account created only with the domain | Section 7.3; nothing contracted |

### 6.2 Implementation requirements for later phases

- **Identity (phase 2):** production realm (email sign-in, no names, verified email,
  18+ declaration, password policy, brute-force lockout, self-delete off, explicit
  event logging, exact origins; prepared in 7.1); verified revocation, absolute BFF
  session lifetime and coordinated logout (7.2); fixed canonical issuer (D12); email
  provider selected with DPA. Reuse #273's recovery.
- **Privacy workflows:** export/erasure scripts with the ledger, restore replay,
  synthetic rehearsal including failures, incident register, Spanish legal texts and
  cookie paragraph, record of processing.
- **Provider content:** public switches for translations, decorative media and IGDB
  scores; no-referrer on provider images and links; licence-termination runbook.
- **Abuse:** registration/login limits, no email enumeration at registration (R17),
  bounded identity security signals (R18) and bounded expensive anonymous reads.

### 6.3 Open legal and provider uncertainties

| Question | Resolution path (D2) |
|---|---|
| Does LSSI 10 apply to a free, ad-free service (address, tax ID)? | Official sources first; consultation candidate if still unclear |
| Is an unverified 18+ declaration sufficient, and is contract the right basis? | Research; consultation candidate |
| Do expiring encrypted backups meet LOPDGDD 32 blocking? | Research; consultation candidate |
| Joint controllership and US transfer for CDN requests; DPF status | Privacy review (D8) |
| Translations, decorative media, scores, public JSON, retention | IGDB reply (D5); otherwise originals/disable |
| Deletion duty vs backups after licence termination | Only if termination happens |
| Digital Services Act relevance | Recheck before user-generated content |
| Keycloak cookies | Inventoried in 7.4: strictly necessary, no consent banner justified **[I]**; the cookie paragraph of the privacy notice remains to be written |

### 6.4 Effective launch blockers

**Before opening accounts:** R1–R5, R11, R12, R14, R17, R18 resolved with evidence; netcup DPA
concluded; incident runbook with AEPD and Twitch reporting; legal notice published
only after R6 is answered.

**Before exposing specific provider content:** translations (R7), IGDB scores (R8)
and decorative media (R9) authorized or switched to originals/off; no-referrer and
CDN disclosure in place (R10).

**Can follow launch:** licence-termination runbook (R13), abuse refinements beyond
the essential limits (R15), self-service rights (R16), DSA recheck.

None of these items is passed by this document.

## 7. Phase 2 plan: production identity

Prioritized; each increment is reviewable alone. Increments 1, 2 and 4 are
implemented; increment 3 is decided (provider preferred, not contracted).

1. **Production realm baseline** (configuration and tests): section 7.1.
2. **Revocation, session bound and coordinated logout** (code): section 7.2.
3. **Email provider selection** (desk research, owner decision): section 7.3. No
   contract without approval; real delivery is proven on the VPS (#276).
4. **Canonical issuer and cookies** (code, gate, inventory): section 7.4.

### 7.1 Increment 1: production realm (2026-10-15)

The [realm import](../../deploy/public-production/keycloak/import/gameometro-realm.json)
is the executable owner, the [platform contract](../architecture/deployment/mvp-platform-and-delivery.md#production-realm-contract)
says how #275 mounts it, and [`validate-production-identity.sh`](../../scripts/validate-production-identity.sh)
proves it: a static contract, then PostgreSQL, Keycloak 26.7.0 over verified HTTPS and
a Mailpit sink that accepts only authenticated STARTTLS, on an internal network with
no published port. Private-dev and local realms, users and data were not touched.
Values are as reviewed here; the import is authoritative.

| Area | Policy | Verified behaviour **[V]** and rationale |
|---|---|---|
| Isolation | Own import directory holding only realm `gameometro`; no users; only runtime placeholders; no local/private-dev file refers to it | The instance holds only `master` and `gameometro`, with no accounts. A distinct realm makes private-dev scripts and the local user import fail closed against production, and fixes the issuer path |
| Registration | Email is the username; the form shows only the email and one checkbox, "Declaro que tengo 18 años o más y acepto las condiciones de uso de Gameómetro." | Without the checkbox nothing is created; submitted names, birth date and password are discarded. With verification on, Keycloak 26.7 creates the account without a password and asks for it only after the emailed link proves ownership, which blocks pre-registration takeover |
| Verification | Required before any sign-in | A new account has no password to sign in with; an unverified account that has one stops at the verification page and receives no code. The link works once from any device; on the registering device it resumes the original authorization to the BFF callback |
| Password | 12 to 128 characters, neither the email nor containing it, no composition rules; Keycloak's default Argon2 hashing | Applies to the first password and to recovery; every rejection leaves the stored credential unchanged |
| Brute force | Per-account temporary lockout after five failures: one minute, growing to at most 15, failures forgotten after 12 hours, never permanent | The lockout reads exactly like a wrong password, other accounts keep signing in, and it expires by itself. Per-IP limits belong to the abuse phase |
| Sessions and tokens | SSO idle 30 min and maximum 8 h, no remember-me; offline sessions bounded the same and unobtainable by the BFF; 5 min access token, 60 s code, 5 min action links (#273); single-use rotated refresh tokens | A replayed code is refused and revokes what it issued |
| Client | Confidential BFF, code flow with S256 PKCE only, exact HTTPS callback and post-logout URI built from the public host, no web origins, no front-channel logout, `basic` scope only | ID token has only protocol claims (no email, name or username) and userinfo only `sub`. Refused: six redirect variants (HTTP, other host, suffix, traversal, query, subdomain), missing or `plain` PKCE, implicit and hybrid, profile/email/offline scopes, password and client-credential grants, a wrong secret |
| Lifecycle | Self-deletion unavailable; email read-only in the account console because Keycloak's update-email action stays disabled | `kc_action=delete_account` returns an error and the account remains; an account-API email change is ignored, so email rectification is an owner procedure (D10) |
| Events and logs | No stored user or admin events and no event listener; the runtime keeps `info` level and no HTTP access log | None of 69 synthetic secrets, addresses, link keys, codes, tokens or the client IP reached the log. Restoring Keycloak's default listener fails the gate with 11 leaked values, including addresses and the IP |
| Recovery | #273's flow and copy unchanged; production overrides only the identifier wording | Same answer for known and unknown email, mail only for the known one; a reset link for an unverified account first forces verification, then sets the password |
| Mail | Authenticated STARTTLS from runtime inputs | The Admin API never returns the SMTP secret |

Product-voice copy for verification, password errors and the verification email was
added to the shared `gameometro` theme as keys that local and private-dev never render;
wording that depends on the email identifier or is legal text lives in the realm.

The owner approved the realm name, password, lockout and session values, owner-run
email changes and the native declaration (D12–D16, D19). The declaration and the short
terms paragraph above it remain drafts: the final conditions, privacy notice and their
links are R1. The event policy is **provisional**: it avoids personal data in logs, and
the abuse and observability phase must still define sufficient security signals (R18).

**Limitations [V]:** Keycloak's registration action stores no record of the accepted
text (D19): the evidence is the enforced flow plus the import version deployed when
the account was created; Keycloak's separate terms page would store a timestamp at the
cost of a second step. Registration answers "El email ya existe" for a taken address
(R17). A disabled account sees Keycloak's formal "La cuenta está
desactivada, contacte con el administrador", pending the public contact (D1). No
breached-password list (needs a maintained list file), no per-IP throttling, no purge
of never-verified accounts. The SMTP password is stored in the Keycloak database and
so in its backups; keeping it in a Keycloak vault, or adding bounded user-event
metrics, needs build options in the production image (#275).

### 7.2 Increment 2: revocation, session bound and coordinated logout (2026-10-15)

Implemented as approved (D15, D17, D18, [ADR-0027](../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md)),
with one refinement for requests in flight; pending owner review and not deployed. No
migration, per-request query or new dependency. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#account-revocation-and-the-management-interface)
owns the resulting guarantees and the management-interface requirements for #275; the
[operations runbook](../development/operations-runbook.md#account-disable-session-revocation-and-rating-deletion)
owns the owner procedure. Code, tests and the OpenAPI contract own the mechanics.

| Area | Implementation | Evidence **[V]** |
|---|---|---|
| All sessions of a `UserId` | Spring Security's session registry, unlimited sessions per user, kept current by container session events. Revocation derives each registered principal's `UserId` from issuer and subject and expires every match; Spring's concurrent-session filter ends it on its next request, which continues anonymously | Two devices of one account and one of another: count 2, repeat 0, the other account and the same subject under another issuer unaffected |
| Fail closed | An authenticated OIDC session absent from the registry, or whose ID token has neither `auth_time` nor `iat`, is ended | Backend tests |
| Management operation | `POST /actuator/sessionrevocation` with `{"userId"}`, created only when the management server has its own port; accepts only canonical derived (version 3) `UserId`s and answers only the count; one `INFO` event with the count and the settled flag | Product port never answers `200`; a browser-initiated call `403`; malformed, upper-case or random UUIDs `400 INVALID_USER_ID`; logs hold no `UserId`, subject or token |
| Requests in flight | Refinement of the plan: waiting longer than the Ratings timeouts bounds the database work but not a request stalled before it, so it guarantees nothing. Personal writes now run through `CurrentUser.changeAsCurrentUser`, which holds a shared lock and rechecks the session; revocation holds it exclusively. A `200` means no write by a revoked session is running or can start; `503 SESSION_REVOCATION_NOT_SETTLED` after 30 seconds means restart | PostgreSQL test: a write held inside the store keeps revocation waiting until it commits, the following deletion removes it and the other device's write gets `401`; a request admitted before revocation but stalled before the lock gets `401` after the deletion; no rating remains |
| Absolute lifetime | Eight hours from the ID token's `auth_time` (`iat` otherwise) on the application clock; activity never extends it; the 30-minute idle timeout is unchanged | Requests at 25 minutes, 4, 7 and 8 hours minus one second keep the session; at 8 hours it is anonymous |
| Coordinated logout | `POST /api/v1/session` stays a CSRF command answering `204`. Spring's OIDC logout handler builds the end-session URL (configured endpoint, ID token hint, exact post-logout URI); a fresh unauthenticated session carries it until the SPA navigates to `/auth/logout`, which consumes it once and redirects. OpenAPI 3.13.0, compatible | Slice tests; real Keycloak: the account's Keycloak sessions drop from two to one, the next sign-in shows the credential form, the other device and other accounts stay signed in, no application cookie remains |
| Keycloak compatibility | Keycloak 26.7.0's end-session endpoint checks only the hint's signature and token type, not its expiry, so logout works long after the 5-minute ID token lifetime. The BFF bound equals the production realm's 8-hour SSO maximum from the same authentication; private-dev's 10-hour maximum outlasts it harmlessly | Bytecode of `LogoutEndpoint` and `TokenManager` in the 26.7.0 image; realm imports |

The real identity gate now also proves, with synthetic accounts: a disabled account's
two devices revoked (2, then 0), a rating write with a still-current CSRF token refused
with `401`, the other account still reading its ratings and a new sign-in refused
(Keycloak `user_disabled`); and a deleted account's open session revoked and anonymous.

**Limitations [V]:** logout lands on the landing page, because Keycloak returns only to
the exact post-logout URI, instead of preserving the public page (UC-004 updated); a
validated path in the OIDC `state` could restore that. The ID token reaches the browser
in the end-session redirect, which RP-initiated logout requires, never in JavaScript or
an API response. If Keycloak is unreachable, logout ends only the BFF session. Personal
writes of every user pause while a revocation waits, normally for milliseconds and 30
seconds at most. A session opened through single sign-on keeps the original
`auth_time`, so it ends eight hours after the credentials were entered. With a fixed
application clock, used only by browser test topologies, the lifetime may never elapse.
The registry and the lock are process-local like the sessions, so a second replica or a
shared session store reopens ADR-0027.

### 7.3 Increment 3: transactional email provider (2026-10-15)

Desk research on official provider pages, consulted on 2026-10-15 and, for Mailjet,
rechecked the same day after the owner's decisions E1–E6. **A preferred provider is
selected; no service is contracted, no account exists and no real mail has been
validated.** No message was sent. The need is the realm's
verification and recovery mail only: low volume, no marketing, Keycloak's built-in
SMTP client with authenticated STARTTLS (7.1), EUR 0–2/month inside the EUR 20
envelope ([cost evidence](public-production-topology.md#cost-evidence-estimate-not-an-order)).
Prices exclude VAT unless stated. Cells marked **[U]** depend on registering,
contracting or a real test, or the official page could not be read.

| Provider | Price and free quota **[V]** | Card / approval | Keycloak SMTP **[V]** | Own domain | Processing and DPA | Spend control | Retention |
|---|---|---|---|---|---|---|---|
| [Scaleway Transactional Email](https://www.scaleway.com/en/pricing/managed-services/) (Scaleway SAS, France) | Essential plan: 300 emails/month per organization free, then EUR 0.25 per 1,000; no monthly fee; billed monthly on emails sent ([FAQ](https://www.scaleway.com/en/docs/transactional-email/faq/)) | Natural or legal person may subscribe ([TEM conditions](https://www-uploads.scaleway.com/Specific_Conditions_Transactionnal_Email_Services_v042026_3f5487a93c.pdf), v. 07/04/2026); "Personal project" account type; ordering requires a valid credit card, EUR 1 authorization refunded ([account creation](https://www.scaleway.com/en/docs/account/how-to/create-an-account/)); identity check only to raise quotas | `smtp.tem.scaleway.com`, STARTTLS on 587/2587, TLS on 465/2465, PLAIN/LOGIN; user = Project ID, password = secret of an IAM API key; encryption mandatory ([SMTP](https://www.scaleway.com/en/docs/transactional-email/reference-content/smtp-configuration/), [IAM keys](https://www.scaleway.com/en/docs/transactional-email/how-to/generate-api-keys-for-tem-with-iam/)) | Required; SPF `include:_spf.tem.scaleway.com`, console-issued DKIM, DMARC, MX or Scaleway blackhole MX ([DNS](https://www.scaleway.com/en/docs/transactional-email/how-to/add-spf-dkim-records-to-your-domain/)); recursive SPF include unsupported | `fr-par` only; FAQ: all TEM data hosted and processed in the EU, no non-EU sub-processor; [DPA](https://www-uploads.scaleway.com/DPA_2024_ENG_b0abb5cc26.pdf) (1 June 2024) "forms an integral part" of the contract, SCCs only for any exceptional transfer | Default quota 10,000 emails/month ([limits](https://www.scaleway.com/en/docs/transactional-email/reference-content/tem-capabilities-and-limits/)); alert at 70% of it; [billing alerts](https://www.scaleway.com/en/docs/billing/how-to/use-billing-alerts/) notify only. No hard EUR cap | Message content "transmitted and then automatically deleted after processing" (conditions 4.3); console activity (recipient, subject, status) filterable up to a year, period not stated **[U]** |
| [Mailjet](https://www.mailjet.com/pricing/) (Sinch Email; EU contracting entity Mailjet SAS, France **[I]**) | Free: 6,000/month, 200/day, "APIs, SMTP Relay, Webhooks"; Starter USD 9/month. Pricing FAQ: no card required for the free plan at sign-up | [Terms of Service](https://www.mailjet.com/legal/terms/) (last revised 01/15/2025): "exclusively reserved for a professional use" and not for minors. Account review: no official criteria; the help centre describes compliance reviews, 7-day limits and blocks (excerpt; page 403) | `in-v3.mailjet.com`, 587 and 2525 with optional TLS, 465 SSL; credentials from the account ([SMTP](https://dev.mailjet.com/docs/smtp-relay/configuration)) | Domain or subdomain verification "strongly recommend[ed]"; Mailjet generates the SPF record and a DKIM key pair shown in the account ([verify domain](https://dev.mailjet.com/docs/email-api/getting-started/verify-your-domain)); DMARC is the owner's record | Hosting on Google Cloud, Germany and Belgium; support, ticketing and billing sub-processors in the USA, including Mailgun Technologies, Atlassian and Zendesk ([sub-processors](https://sinch.com/legal/data-protection-agreement-sub-processors/)). [Sinch DPA](https://sinch.com/legal/terms-and-conditions/other-sinch-terms-conditions/data-protection-agreement/) v8 (29 October 2025) "shall become part of the Agreement" (Terms 4.3), with SCCs and, for Sinch Email, the EU-US DPF | No card, so nothing to charge; daily overflow is queued to the next day and deleted after 3 days (help centre excerpt). The FAQ's over-limit billing describes paid plans; free monthly-cap behaviour **[U]** | Deletion of all personal data within 90 days of account termination (DPA). Retention of message content, recipient addresses, events and statistics during the account's life is **not published [U]**. Sinch may use "message routing data" (hostnames, IPs, timestamps) for its own business purposes (Terms 8) |
| Brevo (Sendinblue, France) | Free: 300 sends/day; free mail "always include the Sent with Brevo sticker" (help centre, via official search excerpt; page returned 403) | **[U]**: only third-party reports of manual activation | Ports 587/2525, 465 with TLS; dedicated SMTP key ([developers](https://developers.brevo.com/docs/smtp-integration)); host not stated there **[U]** | SPF/DKIM/DMARC advertised **[U]** | Pricing, terms and GDPR pages render no readable text; EU hosting, DPA and sub-processors **[U]** | Free plan stops at its limits | Logs and previews kept **indefinitely by default**, configurable 1 month–5 years (help centre excerpt) |
| [Amazon SES](https://aws.amazon.com/ses/pricing/) (AWS) | USD 0.10 per 1,000; new accounts get up to USD 200 Free Tier credits, free plan 6 months | Sandbox: verified recipients only, 200/24 h, 1/s; production access by manual review, first answer within 96 h, may need more information ([sandbox](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html)); card **[U]** | SMTP endpoints in Paris, Frankfurt, Ireland, Stockholm, none in Spain; STARTTLS on 25/587/2587 ([endpoints](https://docs.aws.amazon.com/general/latest/gr/ses.html), [SMTP](https://docs.aws.amazon.com/ses/latest/dg/smtp-connect.html)); IAM-derived SMTP credentials | Domain identity with DKIM; custom MAIL FROM | EU region selectable; Service Terms include the 2021 SCCs ([GDPR centre](https://aws.amazon.com/compliance/gdpr-center/)); US parent | [Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html) alert hours late and do not stop sending; quotas bound volume | **[U]** |
| [Resend](https://resend.com/pricing) (Plus Five Five, Inc., USA) | Free: 3,000/month, 100/day, 30-day data retention; Pro USD 20/month | **[U]** | `smtp.resend.com`, STARTTLS on 25/587/2587, user `resend`, password = API key ([SMTP](https://resend.com/docs/send-with-smtp)) | Yes, 3 domains on free | "Primary processing operations take place in the United States"; account data stored in the US whatever the sending region ([regions](https://resend.com/docs/dashboard/domains/regions)); [DPA](https://resend.com/legal/dpa) 2025-12-31 with SCCs and DPF; deletion within 90 days of termination | Free stops at limits; paid overage USD 0.90/1,000 | 30 days (free) |

Common facts **[V]**: every candidate offers authenticated SMTP with STARTTLS or
implicit TLS, which Keycloak's realm SMTP settings use without an adapter. Not
verified for any: deliverability to Spanish mailbox providers, new-account
throttling in practice and whether the VPS provider lets outbound 587 through.

**Evaluation [I]** (initial, before the owner's decisions): Scaleway was the only
candidate with documented EU-only processing and natural-person customers; Mailjet the
best zero-commitment option; Brevo unverifiable on its own pages, with indefinite log
retention by default; Amazon SES disproportionate to operate with no hard cap; Resend
stores in the United States and its paid tier equals the whole contingency.

#### Owner decisions (2026-10-15)

| ID | Decision | Consequence |
|---|---|---|
| E1 | Preferred provider: **Mailjet Free**, conditional on Mailjet accepting the account and on Keycloak sending through its SMTP relay in practice | Selected, not contracted. The professional-use clause below must be resolved before relying on it |
| E2 | Replacement option: **Scaleway Transactional Email**, documented only | No second integration and never both services contracted. Switching changes only runtime SMTP inputs and DNS |
| E3 | Free plan, no card, no automatic overage charges; sending should stop at the quota rather than cost money | Excludes card-backed pay-as-you-go while Mailjet qualifies. Scaleway, the replacement, needs a card and has no hard EUR cap (worst case about EUR 2.94/month within its default quota): choosing it later reopens E3 |
| E4 | Dedicated sending subdomain, for example `mail.gameometro.es`, subject to the final domain | SPF, DKIM and DMARC of the subdomain stay independent of the contact mailbox (D1) |
| E5 | Replies go to a real, operated contact address; no blackhole mailbox by default; user replies and technical bounces kept apart, bounces diagnosable | `Reply-To` set to the contact address; bounces stay with the provider's return path and are read in its statistics or event webhooks |
| E6 | Create the provider account only when the domain and the public environment are being prepared; domain configuration may precede the VPS purchase with a separate authorization | No account, DNS change or contract now |

#### Mailjet evidence recheck (2026-10-15)

| # | Owner question | Finding | Status |
|---|---|---|---|
| 1 | Free plan | 6,000 emails/month, 200/day, SMTP relay, no card at sign-up. Daily overflow is queued and dropped after 3 days, not billed. Nothing can be charged without a payment method; the free-plan monthly-cap behaviour is not stated | **[V]** confirmed; monthly cap **[U]** |
| 2 | DPA for a natural person on a free account | The Terms incorporate the Sinch DPA without plan distinction; its parties are those of the Agreement. The same Terms reserve the services "exclusively" for professional use | DPA **[V]**; eligibility **conflict, see below** |
| 3 | Storage, processing, transfers | Hosting in Google Cloud Germany and Belgium; US sub-processors for support, ticketing and billing; SCCs and DPF; group-internal transfers allowed. The earlier "exclusively within the European Union" refers to data centres only | **Corrected**: not EU-only processing |
| 4 | Retention | Only the 90-day deletion after termination is published. No published period for message content, recipient addresses, events or statistics; the privacy policy's retention table covers Sinch's own customer records, not mail data. Free plan offers only basic statistics, which says nothing about retention | **[U]**: ask support or observe after sign-up |
| 5 | Mailjet branding on SMTP mail | Help centre: the logo appears only in templates built with the Email Editor or the Subscription Form Builder; removing it is not offered on Free or Starter (excerpt; page 403). Keycloak builds its own messages, so SMTP mail should be unbranded | **[V]** by official excerpt; prove in #276 |
| 6 | Approval, domain, SPF/DKIM/DMARC, bounces | Domain or subdomain verification with Mailjet-generated SPF and DKIM; DMARC is ours; hard bounces and spam reports block the address, pre-blocking exists, the reason is visible in statistics; Webhooks are in the free plan. No published account-approval criteria; reviews and temporary limits exist | Mechanisms **[V]**; approval **[U]** |

**Condition that may invalidate E1:** Mailjet's Terms state "The Services are
exclusively reserved for a professional use". Gameómetro is a free, non-commercial
service run by a natural person. Whether that counts as professional use is an
interpretation **[I]**; if it does not, Mailjet may suspend the account for breach
(Terms 3.4), which would stop verification and recovery mail without warning. This is
not resolved by documentation. Keep E1 only if, at sign-up (E6), Mailjet confirms in
writing that this use is accepted; otherwise move to E2 and decide E3 again. Scaleway's
conditions name natural persons explicitly.

Further differences from the initial assessment: Mailjet is not EU-only (US support
sub-processors under SCCs and DPF, which section 3 asks to disclose) and keeps routing
metadata for its own purposes. Neither invalidates E1 on its own; both belong in the
privacy notice and the record of processing.

**Cost:** EUR 0/month with Mailjet Free; no payment method, so no charge is possible.
The EUR 2.00 allowance stays unused. With Scaleway (E2) expected EUR 0 and at most
about EUR 2.94/month within its default quota, still inside the envelope.

#### Future configuration (not implemented)

1. **Keycloak.** The realm import's runtime inputs stay as they are and #275 supplies
   `KEYCLOAK_SMTP_HOST=in-v3.mailjet.com`, `KEYCLOAK_SMTP_PORT=587`,
   `KEYCLOAK_SMTP_FROM` an address on the sending subdomain (for example
   `no-reply@mail.gameometro.es`), and `KEYCLOAK_SMTP_USER`/`KEYCLOAK_SMTP_PASSWORD`
   the Mailjet SMTP credentials. `auth`, `starttls=true` and `ssl=false` already match;
   2525 is the documented fallback port. E5 needs one **realm import change**, made with
   #275 and covered by the identity gate: `replyTo` (and optionally
   `replyToDisplayName`) set to the contact address from a runtime input. With E2 only the
   host (`smtp.tem.scaleway.com`), user (Project ID) and password (IAM API key secret)
   change.
2. **DNS on the sending subdomain.** The SPF and DKIM records exactly as Mailjet's
   domain page shows them; `_dmarc` starting at `p=none` with `rua` to an owner-read
   mailbox, tightened to `quarantine`/`reject` after clean reports; no blackhole MX.
   Whether Mailjet's return path aligns with the subdomain (SPF alignment) or DMARC
   relies on DKIM alone is **[U]** until the domain page is seen. The contact mailbox's
   own records stay on the apex domain.
3. **Diagnosis (E5).** User replies arrive in the contact mailbox; bounces and blocks
   are read in Mailjet's statistics ("details" column) or through its event webhook if
   a later need justifies one. No webhook or receiving service is planned now.
4. **Account (E6).** Free plan, no card; sub-processor change notifications subscribed
   (DPA, 30 days' notice); the Terms, DPA version and the professional-use answer
   captured with their dates.

#### Launch requirements and pending verification

Carried to the launch proof (#276) and the public gates; none is passed by this
document:

- Mailjet's written acceptance of the use (professional-use clause) and account
  approval; otherwise E2 with a new E3 decision.
- Real delivery of verification and recovery mail from Keycloak through the relay to
  at least two large mailbox providers; SPF, DKIM and DMARC `pass` in received
  headers; no Mailjet branding; replies reaching the contact mailbox; a deliberate
  bounce visible and diagnosable.
- Limits observed: 200/day queueing and the free monthly-cap behaviour.
- Data handling: Mailjet's actual retention of content, addresses, events and
  statistics; the US support transfers and routing-data use disclosed in the privacy
  notice and record of processing; DPA version recorded (R14).
- Outbound 587 or 2525 permitted from the chosen VPS; no SMTP secret or address in
  Keycloak's log. The SMTP secret stays in the Keycloak database and backups (7.1).

### 7.4 Increment 4: canonical issuer and cookies (2026-10-15)

Implemented and proven with synthetic `.test` origins; pending owner review, not
deployed, and the public hostnames are not chosen. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#canonical-issuer-contract)
owns the issuer contract and what #275 must supply; the BFF's startup check, the
read-only [`check-oidc-issuer-contract.py`](../../scripts/check-oidc-issuer-contract.py)
and [`validate-production-identity.sh`](../../scripts/validate-production-identity.sh)
own the mechanics. Nothing local or private-dev changed: the startup check applies only
to the production realm.

| Area | Evidence **[V]** |
|---|---|
| One issuer | Keycloak and the packaged BFF on separate HTTPS hosts, both on the implicit port 443, so the issuer has its production shape. Discovery, every endpoint the BFF uses, email action links and the stored rating's `UserId` all derive from `https://identity.gameometro.test/realms/gameometro` |
| Untrusted headers | With Keycloak's proxy headers trusted, a spoofed `Host`, `X-Forwarded-*` or `Forwarded` leaves discovery unchanged; the BFF's sign-in redirect keeps the canonical endpoint and fixed callback under the same four header sets |
| Inconsistent configuration | Eighteen unit cases refuse non-canonical issuers (HTTP, explicit or other port, trailing slash, upper case, user information, query, fragment, IP, `localhost`, other path), endpoints off the issuer (an internal HTTP back channel included), request-built or foreign return addresses, a shared identity origin and a non-`__Host-` or non-`Secure` cookie. The packaged BFF with a request-built post-logout address exits before serving |
| Redirects | The realm accepts only the exact callback and post-logout address; seven near variants of each (HTTP, other host, suffix host, subdomain, port, extra path, query) are refused without a redirect |
| Session, CSRF, ownership, recovery | Registration with email verification, sign-in, a rating, a second account that cannot see it, logout through Keycloak and #273 recovery all pass through the canonical issuer. A no-CORS `POST /api/v1/session` sent from the identity origin with the browser's cookies is refused and the session survives; one without the CSRF token answers `403` |
| Logs | Neither the Keycloak nor the BFF log contains any of 87 synthetic secrets, addresses, link keys or passwords |

**Cookies and storage [V]** observed in headless Chromium at each stage of
registration, verification, sign-in, logout and recovery. Every cookie is first-party,
host-only (no `Domain`) and `Secure`; Keycloak scopes its own to `/realms/gameometro/`.

| Cookie | Host | Set | Lifetime | `HttpOnly` / `SameSite` | Content and purpose |
|---|---|---|---|---|---|
| `__Host-vgp_session` | Application | When an account entry starts, never while browsing anonymously | Browser session; server 30 min idle, 8 h absolute | Yes / `Lax` | Opaque session identifier; removed at logout |
| `AUTH_SESSION_ID` | Identity | First login or registration page | Browser session | Yes / `None` | Identifier of the sign-in in progress; stays after logout until the browser closes, authenticating nothing |
| `KC_AUTH_SESSION_HASH` | Identity | With `AUTH_SESSION_ID` | 60 s | **No** / `None` | Hash of the sign-in in progress, readable by Keycloak's page scripts |
| `KC_RESTART` | Identity | Recovery request form | Browser session | Yes / `None` | Opaque 1.4 KB value, not a readable token, that lets an interrupted sign-in restart |
| `KEYCLOAK_LOCALE` | Identity | During registration and recovery | Browser session | Yes / `None` | `es`, the language the BFF requests for the forms |
| `KEYCLOAK_IDENTITY` | Identity | After sign-in | Browser session (no remember-me) | Yes / `None` | Keycloak's signed single-sign-on token: issuer, subject, session ID, times; not an OAuth token the application accepts. Removed at logout |
| `KEYCLOAK_SESSION` | Identity | After sign-in | **Persistent, 8 h** (SSO maximum) | **No** / `None` | Hash of the Keycloak session for its session-status scripts, which Gameómetro does not use. Removed at logout |

Findings for the privacy notice:

- Anonymous browsing sets no cookie and uses no storage. `localStorage`,
  `sessionStorage` and IndexedDB stay empty on both origins at every stage; scripts
  on the application origin can read no cookie. No OAuth access, refresh or ID token is
  stored in the browser; the ID token appears only in the end-session redirect (7.2).
- Only the two first-party hosts were contacted. The demonstration catalogue carries no
  provider images, so the IGDB CDN request of section 1 was not exercised here.
- The identity host and the application host share one registrable domain, so they are
  the **same site**: `SameSite=Lax` does not separate them, and the CSRF token, `Origin`
  and Fetch-Metadata checks are the effective protection, now proven across them.
- Every cookie serves authentication, security or the form language of a service the
  user asked for; none is analytic, advertising or third-party. **[I]** They fall under
  the strictly-necessary exemption of the AEPD cookie guidance, so no consent banner is
  justified; the notice must still list them, including the persistent 8-hour
  `KEYCLOAK_SESSION` and the two script-readable Keycloak cookies.

**Limitations.** **[V]** Spring Security 7.1 always calls userinfo, and Keycloak 26.7
rejects at userinfo a token whose issue second precedes its session's start second,
both read from the wall clock. On this host, whose clock steps back several times a
minute, one full gate run failed one sign-in with `invalid_user_info_response`; six
isolated repetitions and the later full runs passed. The cause is inferred from the
Keycloak bytecode and the host's known clock steps, not logged. On a host with a
stable clock it should not occur, and a user would sign in again; skipping userinfo,
which returns only `sub` to this client, would remove the call and is an owner
decision. **[V]** Chromium only; Firefox and Safari were not tested. Keycloak and the
BFF terminated TLS themselves and the browser ignored the test CA, so the edge proxy's
TLS, forwarded-header overwriting, HSTS and unknown-host refusal remain #275's to
prove. The BFF reached Keycloak at the canonical name through a container alias;
how it does so on the host (split DNS or the edge) is **[U]**. An aborted browser
request is logged by the BFF as an `ERROR` with `INTERNAL_ERROR`: pre-existing noise,
not changed here.

**Before the first real account (real host, #275/#276):** fix both hostnames; run
`check-oidc-issuer-contract.py` with the production values through the real edge,
including its unknown-host behaviour; repeat the cookie and storage inventory with the
real hostnames, edge TLS and HSTS; record the issuer in the deployed configuration
fingerprint so a later change is detected rather than silently detaching ratings.

## 8. Dependencies and conflicts

| Issue | Dependency or conflict | Handling |
|---|---|---|
| [#275](https://github.com/rubhern/videogame-platform/issues/275) production runtime | Both need the production Keycloak configuration | #274 owns realm policy and its tests; #275 mounts it with secrets, hostname and proxy settings. Location and mount contract recorded in the [platform design](../architecture/deployment/mvp-platform-and-delivery.md#production-realm-contract) |
| #275 | Paired backups and restore exercise | #275 applies the 35-day ceiling; its restore path calls the ledger replay before reopening (a stub until the privacy phase lands) |
| #275 | Production logging | #275 enforces the 7-day age limit and the Keycloak logging configuration |
| #275 | Same canonical documents (platform design, gates) | Small sequential documentation changes |
| [#279](https://github.com/rubhern/videogame-platform/issues/279) quality signals | Extends IGDB scores to the game page and search (merged on `main`, phase 4 pending) while D11 keeps publication conditional | Keep score rendering in shared components so one public switch covers every surface |
| #279 | No-referrer touches score links in `shared/score/discovery-scores.tsx` and `game-details-content.tsx` | Apply after #279 phase 4 merges, or in one coordinated small change |

## Evidence limits

No legal professional reviewed this document. IGDB's own content, terms and privacy
pages could not be read; Fashion ID and the Digital Services Act were not re-read.
Cookies, storage, ID-token claims and session rows were observed only in the
disposable phase 2 stack, in headless Chromium, with synthetic accounts, synthetic
`.test` origins and a mail sink. No provider was contacted by this work; the owner's IGDB query is
unanswered. Email providers were assessed only from public pages (7.3): Brevo's
pricing, terms and help pages and Mailjet's help centre returned 403 (their content
is cited from official search excerpts), and no account, quota or delivery was
tested. Prices, terms and policies can change after the research date.
Phase 1 and increment 1 changed no versioned artefact. Increment 2 is a compatible
capability: backend reactor `0.38.0-SNAPSHOT`, frontend `0.29.0`, OpenAPI `3.13.0`.
Increment 4 is a compatible enabler: backend reactor `0.39.0-SNAPSHOT`; frontend and
OpenAPI unchanged (one browser test added).
