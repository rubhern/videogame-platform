# ADR-0038: Measure product use with consent-gated Google Analytics 4

- **Status:** Proposed
- **Date:** 2026-11-09
- **Owner:** Ruben Hernandez
- **Scope:** Behavioural product analytics in the browser: the first third-party script the SPA
  executes, its consent boundary, its runtime configuration and its removal
- **Related:** [#330 evaluation](../research/product-analytics.md),
  [#507](https://github.com/rubhern/videogame-platform/issues/507),
  [ADR-0037](0037-align-local-and-private-dev-with-production.md),
  [ADR-0012](0012-use-react-typescript-and-vite-for-the-web-frontend.md)

## Context

Operational telemetry cannot answer the product questions in the
[#330 evaluation](../research/product-analytics.md): whether people try to rate and understand
the per-platform selector, which sections draw interest, whether Mis plataformas is used, how
Destacados and its calendar are browsed, and whether visitors convert and come back. On
2026-11-09 the owner chose Google Analytics 4 over cookieless Umami for its funnels, path
explorations and cohort retention, and accepted the slug of a public game as a page dimension.

Until now the SPA executed only its own code, its document policy admitted no third-party
script, and the approved cookie notice stated that no analytics cookie or consent banner
existed.

## Decision

1. **Opt-in before anything.** GA4 runs only after the visitor accepts a first-party notice
   where rejecting is as easy as accepting. Consent Mode runs in its basic form: before
   acceptance, after rejection and without a choice, no Google script, cookie or request
   exists. Withdrawal from the footer stops measurement at once and removes the `_ga*` cookies
   the site can reach. The choice is stored only in the visitor's browser, for twelve months.
2. **Runtime configuration, not a build input.** One image serves every environment
   (ADR-0037), so the public measurement ID reaches the browser from deployment configuration
   through the public `GET /api/v1/web-analytics` read (`UC-022`). A blank ID turns analytics
   off and keeps the document's Content-Security-Policy closed to Google. Only a deployment with
   an ID admits Google's tag and collection hosts. Local and private `dev` configure no ID.
3. **The application owns what leaves the browser.** Automatic page views and enhanced
   measurement are off. The SPA reports route templates and a closed, typed event dictionary
   with bounded values. It never reports query strings, search terms, scores, game or account
   identifiers, emails or free text, and never sets a user ID. Administration routes are never
   reported. Google Signals and advertising features stay off, cookies are host-only, `Secure`
   and `SameSite=Lax`, and event data is retained for 14 months.
4. **Removable by configuration.** Clearing the measurement ID disables analytics everywhere
   without a release; removing the module, the read and the notice text is one reversible
   change.

## Alternatives considered

- **Umami without cookies.** It needs no banner and keeps data first-party, but answers return
  visits only approximately and adds a service to operate or a hosted account. It remains the
  fallback if consent rates make GA4's ratios unreadable.
- **A build-time measurement ID.** Simpler, but it would bake one environment's ID into the
  image that private `dev` and production share.
- **Google Tag Manager.** It would let tags change outside code review and widen the document
  policy. It is rejected.
- **Consent Mode advanced (cookieless pings before consent).** Its compatibility with the AEPD
  cookie guide is doubtful, and it contacts Google before any choice. It is rejected.

## Consequences

- The privacy policy and cookie notice gain an optional analytics purpose, a processor with an
  international transfer and two optional cookies. They need owner re-approval.
- Google Ireland becomes a processor under Google's data processing terms. The
  [data-protection record](../development/data-protection.md) owns the activity and supplier
  rows.
- Measured totals undercount by design: rejection, missing choices and blockers suppress
  them. Results are trends and ratios, not a census.
- The GA4 property's own settings (retention, signals, enhanced measurement, data sharing,
  internal traffic) are owner operations outside the repository and must match this decision.

## Reconsider when

- After 6–8 weeks of collection, no product question Q1–Q4 has changed or rejected a
  decision. In that case, remove analytics.
- The consent rate is too low for ratios to be readable. In that case, revisit Umami.
- Google's processor terms, the EU–US Data Privacy Framework or the AEPD guidance change
  materially.
- A product need would require a user ID, advertising features or a tag manager.
