# Public release: provider rights, privacy and account data (#274 phase 1)

- **Status:** Phase 1 complete; owner decisions recorded 2026-10-15; IGDB reply pending
- **Research date:** 2026-10-14 (sources); code re-checked 2026-10-15
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
[Q-013](../product/assumptions-and-decisions.md); revocation, backup ceiling and
deletion ledger in the [platform design](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery);
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
| Keycloak database | Username, email, **first and last name (required)**, password hash, verification flag, timestamps; admin-only smoke marker | Until an administrator deletes the user | Login by username; registration open; `verifyEmail=false`; no password policy; brute force off (Keycloak default) |
| Keycloak sessions | SSO session metadata, persisted in the database by default (Keycloak docs) | Idle 30 min, max 10 h | **[U]** IP/device fields shown by "Device activity" not inspected in a real row |
| Keycloak server log | Error events with user ID, username and IP (default logging listener); success events not logged | Private dev: Docker `local` driver, 3 × 10 MiB, no age limit; not sent to Loki | User events are not stored (`eventsEnabled` unset) |
| Keycloak email | Recovery messages | Private dev: Mailpit, 100 messages / 24 h | Production provider unselected |
| Application database | `ratings.rating`: `user_id`, game, value, created/updated time, version token | Until the user deletes the rating | No email/name in the application; aggregates are computed live from this table |
| BFF session | Servlet session with OIDC user and tokens, CSRF token, return context; cookie `vgp_session` (HttpOnly, SameSite=Lax) | 30 min idle, no absolute limit; in memory only, lost on restart | **[I]** ID-token claims probably include profile/email (no client scopes set, so realm defaults apply); not inspected |
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

**[V]** The BFF keeps an in-memory servlet session and configures no OIDC back-channel
logout, no absolute session lifetime and no revalidation against Keycloak. A session
opened before an account is disabled or deleted keeps working while each request
arrives within the 30-minute idle window, and can write ratings under the old `UserId`.

**Requirement (canonical in the platform design):** disabling or deleting an account
stops its new operations on the next request, whatever the session age, without
relying on process-local state.

| Option | Assessment |
|---|---|
| a) Durable revocation check: an identity-owned PostgreSQL row per revoked `UserId`, checked by primary key on every authenticated request; a match invalidates the session and answers as unauthenticated | **[R]** Effective immediately, survives restarts and future replicas, independent of Keycloak availability. Rows live while the account is disabled, or for the absolute session lifetime after deletion; ledger replay restores them after a backup restore |
| b) OIDC back-channel logout from Keycloak | Useful complement; depends on token delivery and a session registry, and emission on administrative disable/delete is **[U]** |
| c) Revalidate with Keycloak on each request | Rejected: couples every request to identity latency and availability |
| d) Absolute BFF session lifetime | **[R]** Required as a bound, insufficient alone |

**[R]** Implement a + d; consider b later. The per-request lookup is a new persistence
path, so phase 2 applies `scalability-by-design`; a local cache may never be the only
source of a revocation.

### 4.2 Deletion ledger: partial failures, retries and restores

**[R]** The ledger is the durable record that makes erasure repeatable:

- **Location:** the owner's protected administration storage, outside the VPS and
  both databases, copied with the reconstruction material. Because the owner runs the
  procedure, no deletion can exist only on a host that might be lost.
- **Entry:** request reference, Keycloak user ID, derived `UserId`, request time,
  per-step outcome and completion time. No email, rating values or reasons.
- **Write-ahead:** the entry is recorded before any mutation.
- **Idempotent steps, in order:** (1) revoke: revocation row, disable the Keycloak
  user, end its Keycloak sessions; (2) delete ratings by `UserId`; (3) delete the
  Keycloak user, "not found" counting as done; (4) verify: no ratings, no user,
  revocation present; (5) mark complete.
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
| R2 | Unverified email registration, no password policy or brute-force protection | Critical | **Blocks accounts**; phase 2 |
| R3 | An open BFF session survives account disable/delete and can recreate ratings | High | **Blocks accounts**; phase 2 |
| R4 | No production email route for verification/recovery | High | **Blocks accounts**; phase 2 |
| R5 | Erasure, ledger and restore replay not implemented or rehearsed | High | **Blocks accounts**; privacy phase |
| R6 | LSSI may require a published postal address and tax ID | High | **Blocks publishing the legal notice** until answered |
| R7 | Translated summaries may breach the no-modification clause | High | Blocks public translations unless IGDB or a legal answer allows them |
| R8 | IGDB scores not confirmed for public display; #279 widens their surfaces | High | Publication blocked until restrictions are verified (D11) |
| R9 | Decorative artworks/screenshots/logos lack clear coverage | Medium | Rights review before launch (D7); covers unaffected |
| R10 | Browser requests to the US-controlled CDN disclose IP and referrer origin | Medium | No-referrer and disclosure before exposure; privacy review open (D8) |
| R11 | Keycloak logs keep failed-login IP and username with no age limit | Medium | 7-day ceiling before accounts (D9) |
| R12 | Issuer URL change would orphan every rating | Medium | Fix the canonical issuer before accounts |
| R13 | Twitch licence revocable at any time | Medium | Contingency runbook; can follow launch |
| R14 | Unrecorded processors (email, backup storage) | Medium | DPAs before personal data reaches them |
| R15 | Bulk scraping of public JSON as third-party sharing | Low | Abuse phase limits; can follow |
| R16 | Rights requests exceed manual capacity | Low | Reassess after launch |

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

### 6.2 Implementation requirements for later phases

- **Identity (phase 2):** production realm (email sign-in, no names, verified email,
  18+ declaration, password policy, brute-force lockout, self-delete off, explicit
  event logging, exact origins); revocation check and absolute BFF session lifetime;
  fixed canonical issuer; email provider selected with DPA. Reuse #273's recovery.
- **Privacy workflows:** export/erasure scripts with the ledger, restore replay,
  synthetic rehearsal including failures, incident register, Spanish legal texts and
  cookie paragraph, record of processing.
- **Provider content:** public switches for translations, decorative media and IGDB
  scores; no-referrer on provider images and links; licence-termination runbook.
- **Abuse:** registration/login limits and bounded expensive anonymous reads.

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
| Keycloak cookies, ID-token claims and session columns | Technical check in phase 2 |

### 6.4 Effective launch blockers

**Before opening accounts:** R1–R5, R11, R12, R14 resolved with evidence; netcup DPA
concluded; incident runbook with AEPD and Twitch reporting; legal notice published
only after R6 is answered.

**Before exposing specific provider content:** translations (R7), IGDB scores (R8)
and decorative media (R9) authorized or switched to originals/off; no-referrer and
CDN disclosure in place (R10).

**Can follow launch:** licence-termination runbook (R13), abuse refinements beyond
the essential limits (R15), self-service rights (R16), DSA recheck.

None of these items is passed by this document.

## 7. Phase 2 plan: production identity

Prioritized; each increment is reviewable alone. Phase 2 is not started.

1. **Production realm baseline** (configuration and tests): an independent production
   realm template beside the unchanged private-dev realm; email as username, no name
   attributes, verified email, registration terms required (18+ declaration, final
   text later), length-based password policy that rejects username/email, temporary
   brute-force lockout, self-delete disabled, no stored user events and explicit
   logging level, idle/maximum session values, placeholder production origins and
   SMTP from secrets. Validation: static assertions on the realm file plus a
   disposable Keycloak + Mailpit journey proving that an unverified account cannot sign
   in, the declaration is mandatory and no name field exists.
2. **Immediate revocation and BFF session bound** (code): identity-owned revocation
   table and request check (4.1 a), absolute session lifetime, negative tests for
   disabled and deleted accounts with an open session, a restart, and an unaffected
   second account.
3. **Email provider selection** (desk research, owner decision): EU processing, DPA,
   free quota with hard cap, custom-domain SPF/DKIM/DMARC, Keycloak SMTP fit, price
   within the EUR 2/month allowance. No contract without approval; real delivery is
   proven on the VPS (#276).
4. **Canonical issuer and session claims:** fix the production issuer naming rule and
   inspect stored claims/cookies in the validation stack (section 6.3).

## 8. Dependencies and conflicts

| Issue | Dependency or conflict | Handling |
|---|---|---|
| [#275](https://github.com/rubhern/videogame-platform/issues/275) production runtime | Both need the production Keycloak configuration | #274 owns realm policy and its tests; #275 mounts it with secrets, hostname and proxy settings. Agree the file location before either starts |
| #275 | Paired backups and restore exercise | #275 applies the 35-day ceiling; its restore path calls the ledger replay before reopening (a stub until the privacy phase lands) |
| #275 | Production logging | #275 enforces the 7-day age limit and the Keycloak logging configuration |
| #275 | Same canonical documents (platform design, gates) | Small sequential documentation changes |
| [#279](https://github.com/rubhern/videogame-platform/issues/279) quality signals | Extends IGDB scores to the game page and search (merged on `main`, phase 4 pending) while D11 keeps publication conditional | Keep score rendering in shared components so one public switch covers every surface |
| #279 | No-referrer touches score links in `shared/score/discovery-scores.tsx` and `game-details-content.tsx` | Apply after #279 phase 4 merges, or in one coordinated small change |

## Evidence limits

No legal professional reviewed this document. IGDB's own content, terms and privacy
pages could not be read; Fashion ID and the Digital Services Act were not re-read.
Keycloak cookies, ID-token claims and stored session columns were not inspected in a
running stack. No provider was contacted by this work; the owner's IGDB query is
unanswered. Prices, terms and policies can change after the research date.
Documentation-only change: no SemVer increment.
