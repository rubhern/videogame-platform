# Browser session lifetime and remember-me (#361)

- **Status:** Investigation complete; owner decision recorded on 2026-10-25 (section 5);
  implementation delegated to [#374](https://github.com/rubhern/videogame-platform/issues/374).
  Nothing configured, implemented or deployed
- **Research date:** 2026-10-24
- **Source revision:** `9cee33dfd2460213ea3e89711dd91bdc41405c81` (`main`), the revision
  private-dev was running on the research date
- **Consumer:** Owner decision on [#361](https://github.com/rubhern/videogame-platform/issues/361);
  production identity policy in [#274](https://github.com/rubhern/videogame-platform/issues/274)
  and account-entry UX in [#162](https://github.com/rubhern/videogame-platform/issues/162)

Point-in-time evidence on how long a Gameómetro browser stays signed in, and whether a
"keep me signed in" option fits the approved same-origin BFF. It extends the
[#274 revocation and session-bound evidence](public-release-rights-and-privacy.md#72-increment-2-revocation-session-bound-and-coordinated-logout-2026-10-15)
and its [cookie inventory](public-release-rights-and-privacy.md#74-increment-4-canonical-issuer-and-cookies-2026-10-15)
without repeating them. Current guarantees stay owned by
[ADR-0027](../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md) and the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#account-revocation-and-the-management-interface);
configuration values stay owned by the files cited below.

Labels: **[V]** verified in the cited source or reproduced as described; **[I]**
inference not directly observed; **[U]** unresolved; **[R]** recommendation made before
the decision; **[D]** owner decision, 2026-10-25.

## 1. Four separate lifetimes

A signed-in browser depends on four mechanisms that expire independently. None of them
alone tells how long a user stays signed in.

| Mechanism | Where it lives | What renews it | What ends it |
|---|---|---|---|
| BFF session | Servlet session in the application's memory, one replica | Requests carrying the session cookie (observed for page loads and `/api/v1/session`) | 30 min without requests, 8 h after the ID token's `auth_time`, logout, revocation, application restart |
| Application cookie `vgp_session` / `__Host-vgp_session` | Browser, `HttpOnly`, `SameSite=Lax`, no `Max-Age` | Nothing; it only carries the session identifier | Browser close (unless the browser restores its session), logout |
| Keycloak SSO session | Keycloak, persisted in its database | Only a browser visit to Keycloak: sign-in or silent sign-in | 30 min without such a visit, 8 h maximum, logout, admin sign-out |
| SSO cookie `KEYCLOAK_IDENTITY` | Browser, identity host | Re-issued on each Keycloak visit | Browser close (session cookie without remember-me), logout |

**[V]** The BFF requests only the `openid` scope and stores the authorized client,
including the refresh token, in the HTTP session
([`IdentitySecurityConfiguration`](../../backend/src/main/java/com/videogameplatform/identity/configuration/IdentitySecurityConfiguration.java),
[`application-oidc.yaml`](../../backend/src/main/resources/application-oidc.yaml)). Nothing
reads or refreshes those tokens after sign-in: identity comes from the ID token's issuer
and subject. Refresh-token lifetime and rotation therefore play no part in how long a
user stays signed in; the tokens disappear with the session.

Because the BFF never contacts Keycloak during use, an actively used Gameómetro session
outlives its own Keycloak SSO session. The only way back into the application without
credentials is a *silent sign-in*: the user starts account entry and Keycloak
recognises a still-valid SSO cookie.

## 2. Effective configuration

| Setting | Local (repository import) | Private-dev, live | Production (prepared, not deployed) |
|---|---|---|---|
| BFF idle | 30 min | 30 min (default; no override in the running container) | 30 min |
| BFF absolute, from `auth_time` | 8 h | 8 h | 8 h |
| Application cookie | `vgp_session`, not `Secure`, session cookie | `__Host-vgp_session`, `Secure`, session cookie | Same as private-dev |
| Server session persistence | None, in memory | None | None |
| SSO Session Idle | 1800 s | 1800 s | 1800 s |
| SSO Session Max | 36000 s (10 h) | 28800 s (8 h) | 28800 s (8 h) |
| Remember Me | Off | Off | Off; remember-me idle/max 0 (inherit) |
| Client session idle/max | Not set (inherit) | 0 (inherit) | 0 (inherit) |
| Access token | 300 s | 300 s | 300 s |
| Refresh-token rotation | Not set (off) | On, reuse 0 | On, reuse 0 |
| Offline sessions | Keycloak default | Idle 1800 s, max 28800 s | Idle 1800 s, max 28800 s |

Sources **[V]**: BFF values in [`application.yaml`](../../backend/src/main/resources/application.yaml)
(`server.servlet.session.*`, `identity.session.absolute-timeout`) and
[`backend/.env.example`](../../backend/.env.example); production cookie overrides in
[`deploy/public-production/compose.yaml`](../../deploy/public-production/compose.yaml) and
[`deploy/private-dev/compose.yaml`](../../deploy/private-dev/compose.yaml); realm values in
[`videogame-platform-realm.json`](../../docker/keycloak/import/videogame-platform-realm.json)
and [`gameometro-realm.json`](../../deploy/public-production/keycloak/import/gameometro-realm.json).
Private-dev values were read on 2026-10-24 with one read-only SQL transaction on the
Keycloak database and `docker inspect` of the running application (image revision
`9cee33d`, no `APPLICATION_SESSION_*` timeout variable); no secret was read and nothing
was changed. Private-dev differs from the local import because
[`configure-keycloak-identity`](../../deploy/private-dev/bin/configure-keycloak-identity)
applied the production session subset to the existing realm.

## 3. Reproduction

**Method.** A disposable topology derived from
[`validate-identity.sh`](../../scripts/validate-identity.sh): same pinned PostgreSQL,
Keycloak 26.7.4 image with the Gameómetro theme (built from the shared Dockerfile at this
revision), packaged application from this revision, internal Docker network and generated secrets, one synthetic account per
scenario, headless Chromium (Playwright). To observe expiry in minutes, the timeouts
were scaled only for these throwaway containers: BFF idle 120 s and absolute 360 s;
Keycloak values set per phase through its Admin API. "Browser restart" is emulated by a
new browser context that keeps only persistent cookies; "session restore" by one that
keeps every cookie. Each scenario starts account entry with `/auth/login/keycloak` and
records whether Keycloak showed the credential form or returned silently. The harness
was a scratch tool and is not part of the repository.

**Results [V]** (seconds after the scenario's own sign-in; Phase 1 uses Keycloak idle
120 s / max 360 s, aligned like production):

| # | Scenario | Observed |
|---|---|---|
| 1 | Cookies after sign-in | `vgp_session` session cookie, `HttpOnly`, `Lax`; `KEYCLOAK_IDENTITY` session cookie whose signed payload expires at SSO max; `KEYCLOAK_SESSION` persistent until SSO max. `document.cookie`, `localStorage` and `sessionStorage` empty on the application origin |
| 2 | Active use, a request every 45 s | Signed in through 357 s, anonymous at 405 s (absolute bound). Keycloak listed no SSO session from about 130 s on, while the BFF session stayed valid. Sign-in afterwards showed the form |
| 3 | Inactive 150 s (beyond BFF idle) | Anonymous; sign-in showed the form, because the SSO session had idled out too |
| 4 | Inactive 270 s | Anonymous; form |
| 5 | Browser restart at 30 s | Only `KEYCLOAK_SESSION` and a short sign-in hash survived; anonymous; form |
| 6 | Browser restart with session restore | Still signed in at 31 s; anonymous after 200 s of inactivity (server idle enforced) |
| 7 | Logout at 60 s | `204`, Keycloak session ended, `KEYCLOAK_IDENTITY` and `KEYCLOAK_SESSION` removed; form on next sign-in; another account's browser stayed signed in |
| 8 | New private window | Anonymous; form |
| 9 | Keycloak restart (SSO idle 600 s) | BFF session unaffected; the SSO session survived the restart: after dropping the application cookie, sign-in was silent |
| 10 | Application restart | Every BFF session lost (anonymous); silent sign-in while the SSO session was valid |
| 11 | SSO max longer than the BFF bound (SSO 600 s / 1200 s) | Silent sign-ins at 150 s and 300 s restored the session; at 400 s the silent sign-in completed but the new BFF session was ended on its first request: the user stays anonymous after every attempt |
| 12 | Remember Me enabled (idle 900 s / max 1200 s; normal 120 s / 360 s) | The Gameómetro theme renders Keycloak's native, unchecked "Seguir conectado". With it, `KEYCLOAK_IDENTITY` became persistent for the remember-me max and a persistent `KEYCLOAK_REMEMBER_ME` appeared with a 365-day lifetime (its value was not inspected). After a browser restart the user was anonymous, and sign-in was silent at 30 s and 300 s. Without it, a browser restart required the form |
| 13 | Remember Me beyond the BFF bound (420 s) | Silent sign-in completed, the user stayed anonymous, as in 11. An anonymous session has no CSRF token and the interface offers no logout, so the harness's logout command answered `403`: Gameómetro can no longer end that Keycloak session. **[I]** Every further sign-in loops the same way until the remember-me session expires or the browser's identity cookies are deleted; that end was not observed |
| 14 | Logout of a remember-me session | `204`; SSO session ended; `KEYCLOAK_IDENTITY`, `KEYCLOAK_SESSION` and `KEYCLOAK_REMEMBER_ME` removed; next sign-in, and sign-in after a browser restart, showed the form with no username prefilled |

**What this means at real values [I]** (the logic of scenarios 2–4 and 10–11, not
measured at 30 minutes or 8 hours):

- Within one browser session, a user stays signed in while there is a request at least
  every 30 minutes, for at most 8 hours from entering credentials.
- After 30 idle minutes, the BFF has expired and so has the SSO session: idle time is
  counted from the last request for the BFF and from the last Keycloak visit for SSO,
  which is never later. Credentials are needed again.
- Closing the browser normally ends both session cookies; credentials are needed again.
  A browser that restores its session keeps the user signed in only within the same
  server-side bounds.
- A deployment or restart of the application signs everyone out; users who signed in
  within the last 30 minutes come back silently, others need credentials. Restarting
  Keycloak signs nobody out.
- Silent sign-in keeps the original `auth_time`. In production and private-dev, SSO max
  equals the BFF bound, so scenario 11 cannot happen; the local import's 10-hour SSO max
  could reproduce it only after a silent sign-in late in the session.

**Limitations.** Scaled timeouts, not 30 minutes or 8 hours. Headless Chromium only, and
browser restart and session restore were emulated through cookie persistence, not by
restarting a real browser; Firefox, Safari and mobile browsers were not tested. HTTP
origins, so Keycloak set its cookies `SameSite=Lax` instead of the `None; Secure` that
the HTTPS inventory records. Keycloak documents a two-minute window during which an
idle session is still active; at these values the session was gone shortly after the
configured idle time and sign-in at 150 s needed the form, so the window was not
observable here **[U]** at real values. This host's wall clock steps backwards several
times a minute; elapsed times were driven by monotonic timers, but the timestamps above
and Keycloak's session ages are wall-clock values and read slightly short. No
private-dev browser session was exercised. Keycloak was 26.7.4; `main` moved to 26.7.5
after the research revision, and the scenarios were not repeated on it **[U]**.

## 4. Options

Official documentation consulted on 2026-10-24: the
[Keycloak Server Administration Guide](https://www.keycloak.org/docs/latest/server_admin/index.html)
(Remember Me; *Session and token timeouts*: remember-me idle and max fall back to the
normal values when not greater than zero, client-session values fall back to SSO values,
a two-minute idle window, refresh-token revocation), the
[Keycloak 26.0.0 release notes](https://www.keycloak.org/2024/10/keycloak-2600-released.html)
(persistent user sessions on by default), the Spring Boot
[application properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)
(`server.servlet.session.timeout`, `.cookie.max-age`, `.persistent`), Spring Security
[Remember-Me](https://docs.spring.io/spring-security/reference/servlet/authentication/rememberme.html),
the [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
(15–30 min idle for low-risk applications, absolute bound, persistent cookies only when
needed) and [NIST SP 800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html)
(overall reauthentication timeout of no more than 30 days at AAL1, where no inactivity
timeout is required; 24 hours and 1 hour of inactivity at AAL2). External guidance is advisory input only.

| Option | UX | Security and shared devices | Logout and revocation | Complexity and operations |
|---|---|---|---|---|
| A. Keep current | Credentials after every browser close, 30 idle minutes, 8 h, or application deploy (unless signed in within 30 min) | Strongest; nothing outlives the browser except `KEYCLOAK_SESSION`, which authenticates nothing | Proven (ADR-0027) | None |
| B. Longer bounds, session cookies (for example 2 h idle, 12 h max on both sides) | Fewer prompts within one browser session; nothing gained across restarts or deploys | Longer exposure on an unattended open browser | Unchanged | Configuration, realm alignment, legal cookie table |
| C. Keycloak Remember Me, opt-in, with a BFF adjustment | After closing the browser, deploys or idle expiry, "Iniciar sesión" returns silently while the remember-me session lasts | Opt-in and unchecked by default; shared-device risk limited to users who tick it; reauthentication bounded by the remember-me max | Logout already ends it (14); owner revocation already ends Keycloak sessions first | Realm setting and alignment; small BFF change (below); privacy and cookie texts |
| C0. Keycloak Remember Me without the BFF adjustment | As C until 8 h after credentials, then locked out (13) | — | The application cannot log the user out (13) | **Rejected** |
| D. Persistent BFF cookie (`server.servlet.session.cookie.max-age`) with persistent server sessions (Spring Session JDBC) | Seamless across browser restarts and deploys | Tokens and security context at rest in PostgreSQL; a global cookie age is not opt-in without custom code | Revocation must act on the shared store; ADR-0027 reopens | New dependency, schema, serialization, cleanup; contradicts "sessions only in memory" |
| D0. Persistent BFF cookie, in-memory sessions | Survives browser restart only until idle expiry or the next deploy | Not opt-in | Unchanged | **Rejected**: promises persistence it cannot keep |
| E. Spring Security Remember-Me or offline tokens | Seamless | A second, application-owned credential | Needs its own revocation | Remember-Me needs a `UserDetailsService` and is a second authentication mechanism; offline tokens add a stored refresh credential. **Rejected** by the issue constraints |

**The adjustment C needs.** The BFF bound runs from the ID token's `auth_time`, and a
silent sign-in keeps it, so with any SSO lifetime longer than 8 hours every silent
sign-in after that point produces a session that ends immediately (11, 13). The
recommendation (section 5) was to count the 8-hour BFF bound from the creation of the
BFF session instead, and cap the time since `auth_time` at the longest SSO lifetime the
realm allows (the remember-me maximum). The 8-hour bound then acts as a periodic renewal against Keycloak,
which should also refuse disabled or deleted accounts (**[I]**, validated in section 6),
while Keycloak alone bounds how long
credentials stay valid. Without remember-me this can at most allow a session to outlast
8 hours from credentials when a silent sign-in happens late in the SSO session, which in
practice only an application restart within 30 minutes of a Keycloak visit allows
**[I]**. If that edge must be closed, a protocol mapper exposing the remember-me state
could bound each session exactly; it adds a realm and token contract. The owner decision
closes the edge: ordinary sessions keep the `auth_time` bound, so the BFF must tell a
remembered authentication from an ordinary one by a server-trusted signal. This
investigation did not establish that signal **[U]**. ADR-0027's "eight hours matching
Keycloak's maximum session" would need an amendment either way.

## 5. Recommendation and owner decision

**Recommendation [R]** (2026-10-24): keep private-dev unchanged; for production, offer
opt-in Keycloak Remember Me with remember-me SSO idle 7 days and max 30 days, normal
Keycloak limits unchanged, and a BFF bound of 8 hours from session creation capped at
30 days from `auth_time` for **every** session; reject B, C0, D, D0 and E; keep automatic
silent sign-in (`prompt=none`) out of scope.

**Decision [D]** (owner, 2026-10-25), an approved target policy, not an authorization to
change the current implementation:

- Private-dev keeps its current configuration.
- Production offers an optional "Seguir conectado" through Keycloak's native Remember Me,
  unchecked by default.
- Ordinary sessions keep today's limits: BFF 30 minutes idle and 8 hours from the
  original authentication (`auth_time`); Keycloak SSO 30 minutes idle, 8 hours maximum.
- Remembered sessions: Keycloak SSO 7 days idle and 30 days maximum; each BFF session
  30 minutes idle and 8 hours from its creation, with an overall cap of 30 days from the
  original `auth_time`.

The decision differs from the recommendation in one point: the creation-based BFF bound
applies only to remembered sessions, so ordinary sessions are not weakened by the edge
described in section 4. The canonical owners record the target without presenting it as
implemented: the [production realm contract](../architecture/deployment/mvp-platform-and-delivery.md#production-realm-contract)
holds the bounds and the [Product Brief](../product/product-brief.md#public-release-and-sustainability)
the account capability. ADR-0027 and the platform design's description of effective BFF
behaviour stay unchanged until #374 proves the design.

## 6. Delegated to #374

[#374](https://github.com/rubhern/videogame-platform/issues/374) owns feasibility, the
ADR-0027 amendment, implementation and its acceptance criteria. Evidence from this
record that it must answer:

- **Mode distinction [U]:** a server-trusted way for the BFF to tell a remembered
  authentication from an ordinary one, without trusting a browser-supplied flag; if none
  is proportionate, #374 returns an alternative to the owner.
- **Late silent sign-in (11, 13):** with remember-me, a silent sign-in more than 8 hours
  after `auth_time` must yield a usable session, and logout must work even after the BFF
  session has expired, without a `403` or a silent sign-in loop.
- **Logout and revocation (7, 14):** RP-initiated logout already ends a remember-me SSO
  session and clears `KEYCLOAK_IDENTITY`, `KEYCLOAK_SESSION` and `KEYCLOAK_REMEMBER_ME`;
  revocation already ends Keycloak sessions before invalidating BFF sessions; a disabled
  account refusing a remembered silent sign-in is still unverified **[I]**.
- **Cookies and texts:** remember-me makes `KEYCLOAK_IDENTITY` persistent and adds the
  365-day `KEYCLOAK_REMEMBER_ME`; the cookie inventory and the
  [Spanish legal drafts](../legal/public-texts-es.md) describe only today's cookies and
  must change with the implementation.
- **Evidence gaps (section 3):** real 30-minute, 8-hour, 7-day and 30-day boundaries,
  HTTPS `SameSite=None; Secure` cookies, real browser restarts, Firefox, Safari and
  mobile browsers, Keycloak's two-minute idle window and Keycloak 26.7.5.

Not delegated and left as observed: the local import keeps its 10-hour SSO maximum, the
only configuration in which scenario 11 can appear without remember-me.
