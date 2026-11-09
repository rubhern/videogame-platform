# Product analytics evaluation (#330)

- **Status:** Investigation complete; owner decision recorded on 2026-11-09 (section 5):
  adopt Google Analytics 4. Implementation is delegated to a separate issue; nothing is
  installed, no cookie is set and no account exists as a result of this evaluation
- **Research date:** 2026-11-09
- **Source revision:** `f92b512` (`main`)
- **Consumer:** Owner decision on [#330](https://github.com/rubhern/videogame-platform/issues/330)
  and the implementation issue that follows it

This is point-in-time evidence on whether Gameómetro needs behavioural product
analytics and which tool fits. Current processing stays owned by the
[data-protection record](../development/data-protection.md). Public wording stays
owned by the [Spanish legal texts](../legal/public-texts-es.md). Operational telemetry
stays owned by [observability](../development/observability.md).

Labels: **[V]** verified in the cited source; **[I]** inference not directly observed;
**[U]** unresolved; **[D]** owner decision, 2026-11-09.

## 1. Product questions

| # | Question | Decision it informs |
|---|---|---|
| Q1 | Do people try to rate, and do they understand the per-platform selector? | Simplify or redesign the rating control (#359) |
| Q2 | Which sections draw interest: TOPómetro, Destacados, Plataformas, Explorar? | Where to invest next; home and header priority |
| Q3 | Is Mis plataformas understood and used? | Keep, promote or simplify preferences (#295) |
| Q4 | Week or month Destacados; is the calendar used; how far do people navigate? | Default period, horizon and calendar investment (#329, #250) |
| Q5 | Do anonymous visitors convert to registered raters, and do they come back? | Registration prompts and retention work |

**[V]** The current Micrometer → Prometheus/Grafana telemetry counts API activity
only. Its [own limits](../development/observability.md) state that it cannot derive
unique visitors, sessions, conversion, retention, most visited Games or navigation.
It answers none of Q1–Q5.

## 2. Minimum event dictionary

Pages are reported as **route templates**, never full URLs: for example,
`/games/:gameId/:slug` becomes `game_detail`. Payloads never contain query strings,
search terms, scores, user identifiers or emails.

| Event | Properties (bounded) | Answers |
|---|---|---|
| `page_view` | `route` (template), `entry_source` (header/home/footer/internal/external) | Q2 |
| `rating_intent` | `authenticated` (bool) | Q1, Q5 |
| `rating_platform_selector` | `action` (open/change), `platform_count` (1/2/3+) | Q1 |
| `rating_saved` | `action` (create/update/delete), `platform_count` | Q1 |
| `rating_abandoned` | `step` (selector/score) | Q1 |
| `my_platforms_saved` | `count_bucket` (0/1/2–3/4+), `origin` (welcome/page) | Q3 |
| `interests_filter_toggle` | `screen`, `state` (on/off) | Q3 |
| `featured_period` | `period` (week/month) | Q4 |
| `featured_navigate` | `direction` (prev/next), `offset` (−3…+3, beyond) | Q4 |
| `calendar_interaction` | `action` (open/day/month_change) | Q4 |
| `featured_card_open` | `period`, `position_bucket` (1–3/4–10/11+) | Q4 |
| `sign_up_start` / `sign_up_return` | none | Q5 |

Optional signals:

- Search result band (0, 1–10, 11+), to spot zero-result searches. The search term is
  never sent.
- Explorar filter *types* used.
- The page a game detail view came from.
- Hits on the `404` route.
- Device class.

**[D]** `game_detail` may carry the public game slug, so that the most-viewed games can
be ranked. The slug is public catalogue data, not personal data, but it is a
high-cardinality analytics dimension.

**[I]** Some things cannot be inferred reliably:

- Repeat *people*. Rejected consent, blocked scripts and new devices all break identity.
- Exact totals. Blockers and consent rejection undercount them.
- The intent behind an abandoned rating.

Read the results as trends and ratios, not as a census.

## 3. Options compared

| | A. Google Analytics 4 | B. Umami (cookieless) |
|---|---|---|
| Cost | Free standard property | Self-hosted: free, but adds a container and a database or schema to the VPS. Cloud Hobby: free with 6-month retention **[V]**; event cap conflicts between sources (10K vs 100K a month) **[U]** |
| Consent and cookies | `_ga*` cookies (about two years): **prior opt-in banner** under the AEPD guide, with rejection as easy as acceptance and later withdrawal | No cookies and no device storage, so no banner is expected **[I]**. GDPR still applies to the transient IP, so the privacy notice still changes |
| Data loss | Consent rejection plus ad blockers | Ad blockers only; smaller when served same-origin |
| Analysis power | Funnels, path explorations, cohort retention, Search Console link, BigQuery export | Events with properties, funnels, journeys and basic retention; weaker person-level analysis |
| Retention | 2 or 14 months for event-level and exploration data; aggregated reports unaffected **[V]** | Self-hosted: operator-defined. Cloud Hobby: 6 months **[V]** |
| Third party and transfer | Google as processor, transfer under the EU–US Data Privacy Framework, terms acceptance | Self-hosted: none. Cloud: EU or US region chosen at sign-up and fixed afterwards **[V]** |
| Performance and CSP | Third-party `gtag` script, heavier | About 2 KB script, which can be served same-origin |
| Operation | Nothing to run; property hardening (signals and ad personalization off, 14-month retention, internal-traffic filter) | Self-hosted: upgrades, backups and monitoring are owner work. Cloud: none |
| Lock-in | Medium (proprietary model) | Low (open source, data in PostgreSQL) |

Discarded options:

- **Plausible:** the cloud service is paid, and self-hosting needs ClickHouse.
- **Matomo:** adds a PHP and MySQL stack.
- **No new tool:** answers none of Q1–Q5.

Changes required by either option:

- An analytics module in the frontend: a consent gate (A only), route-template page
  views, the event dictionary and tests.
- Re-approval of the cookie and privacy texts. The current text says there is no
  analytics cookie and no banner.
- A data-protection inventory row.
- An ADR.
- A minor frontend version bump.

## 4. Recommendation

- **A (GA4)** fits when cohort retention and path exploration (Q5) matter more than
  avoiding a banner and the transfer to Google.
- **B (Umami)** fits when a banner-free, first-party setup matters more. It answers
  Q1–Q4 equally well and Q5 only approximately.

## 5. Owner decision

**[D]** 2026-11-09:

- **Adopt GA4 (option A).**
- Send the public game slug on `game_detail`.
- The findings above are validated as the basis for implementation.

The owner drafts the revised legal texts with agent assistance and approves them
before activation. No external counsel is planned.

### Validation and stop criteria

- After 6–8 weeks, each of Q1–Q4 must have produced or rejected at least one concrete
  product change. Otherwise, remove the tool.
- Review the consent acceptance rate. If it is too low to read ratios, reconsider
  option B.
- **Rollback:** remove the module and the banner, restore the previous legal texts,
  and delete the property and its data.

## Sources

Consulted on 2026-11-09:

- [Umami Cloud FAQ](https://umami.is/docs/cloud/faq): EU or US region, Hobby retention
  of 6 months. The Hobby event cap differs between third-party listings; verify it on
  the official pricing page before relying on it.
- [Google Analytics 360 comparison](https://support.google.com/analytics/answer/11202874):
  standard-property retention of 2 or 14 months.
- [GA4 limits (Analytify)](https://analytify.io/google-analytics-limits/): 50
  event-scoped custom dimensions on a standard property.
- [AEPD cookie guide](https://www.aepd.es/guias/guia-cookies.pdf), May 2024.
