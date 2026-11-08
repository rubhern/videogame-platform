# VideoGame Platform frontend

The frontend is a client-rendered React/TypeScript SPA for the approved same-origin
BFF/API. Its home at `/` (#382) composes previews of the features below. It implements the
complete MVP journey: `UC-001` release discovery, `UC-010` monthly
featured releases,
`UC-002` bounded catalogue search, `UC-003` public game details, the `UC-004`
same-origin authentication boundary (direct BFF/OIDC navigation to hosted identity screens,
anonymous account entry,
an authenticated header account control and CSRF-protected logout that continues through
Keycloak), the
`UC-005`–`UC-007` inline personal rating on the game page, `UC-008`
`/mis-puntuaciones`, the post-MVP `UC-015` personal statistics at
`/mis-puntuaciones/estadisticas`, the `UC-014` preferred platforms (the first-run `/bienvenida`,
`/mis-plataformas` and the **Mis plataformas** switch in Mi cuenta that applies them to
Destacados, the release explorer and the calendar), the post-MVP `UC-016` highlights calendar (**Calendario de destacados**) at
`/lanzamientos/calendario`, the post-MVP `UC-018` player-rating ranking **TOPómetro** at
`/topometro` and, per platform, at `/plataformas/<plataforma>/topometro`, the post-MVP `UC-020`
platform ranking **Próximos destacados** at `/plataformas/<plataforma>/proximos`, and
the operator-only `UC-011`–`UC-013` and historical `UC-017` `/administracion/catalogo`. Behaviour is specified by the
[use cases](../docs/architecture/application/mvp-use-cases.md) and the
[frontend design guidelines](../docs/development/frontend-design.md); this README
owns only how to build, run, and organise it.

## Install and verify

Run from the repository root with Node.js 24 and npm 11:

```bash
npm ci
npm run frontend:verify
```

`frontend:verify` regenerates OpenAPI types, lints, type-checks, runs component tests,
builds production assets, and verifies that Playwright can discover the browser
tests. Exact dependency versions and scripts live in
[`package.json`](package.json) and the root lock file.

## Develop locally

Start the backend on port 8080, then:

```bash
npm run frontend:dev
```

Vite serves `http://localhost:5173` and proxies `/api`, `/auth`, `/login` and `/actuator` to
the backend. Those paths remain server-owned and must not become client routes.
Production assets are written to ignored `frontend/dist/`; build the deployable
same-origin JAR with `bash scripts/package-application.sh` and validate the packaged
browser path with `bash scripts/validate-browser.sh`.

## Public legal pages

`/privacidad`, `/condiciones`, `/aviso-legal` and `/cookies` are anonymous routes in
local, private-dev and production builds, linked from the shared footer alongside
contact mail. The [approved Spanish source](../docs/legal/public-texts-es.md) owns
all public wording. After an approved source change, run
`node scripts/prepare-legal-content.mjs` from the repository root. Build/tests check
the generated safe React data and registration localization in both realm imports.
Only public sections enter the application; editorial metadata and the administrative
template stay in documentation. No cookie banner or privacy-consent control is added.
Text approval and build availability do not authorize a public production deployment;
the [release gates](../docs/development/delivery-lifecycle.md#public-production-gates)
remain separate. Existing realms require explicit operator reconciliation because
startup import does not update them; see [product integration](../docs/development/data-protection.md#publication-and-product-integration).

## Structure and ownership

| Path | Responsibility |
|---|---|
| `src/app/` | Providers, router, query client, and application shell |
| `src/features/` | Product capabilities and their API/view-model/UI code |
| `src/pages/` | Route-level composition |
| `src/shared/api/` | Generated contract and product-facing transport boundary |
| `src/shared/brand/` | Canonical Gameómetro standalone mark and shared lockup SVGs and their inline components |
| `src/shared/catalogue/` | Catalogue presentation shared by release browsing and search |
| `src/shared/score/` | Score temperature bands and the G-meter shared by the game page and personal ratings; the external player score check shared by discovery and the game page; the compact score readings shared by release discovery, search results and personal ratings |
| `src/shared/ui/` | UI patterns with demonstrated cross-feature reuse |
| `src/styles/` | Global Tailwind entry and shared visual foundations |
| `src/test/` | Shared component-test setup |
| `tests/` | Packaged browser journeys |

Keep feature behaviour close to its feature. Do not create a design system, global
state store, or generic abstraction without demonstrated reuse or ownership value.

## Deployed build identity

Each document startup writes one developer-console info message:
`Gameómetro <application-version> — revision <source-sha-prefix>`.
It stays outside React rendering and does not repeat during client navigation.
Packaged builds inject the same application version/source revision used for the
Maven build information and OCI labels; the Dockerfile and packaging script own
that wiring. Standalone Vite uses the frontend package version and
`local-development` unless build metadata is injected. No second version constant,
browser secret or public metadata endpoint is added.

After private-dev promotion, open DevTools **Console**, enable info messages and
reload the application with the network cache disabled. Compare its version and
12-character SHA with the selected publication/deployment receipt. Deployment smoke
checks that message against the candidate's expected metadata. A browser still
holding an earlier document can retain the earlier bundle until reloaded.

## API, routing, and state

[`openapi.yaml`](../docs/architecture/api/openapi.yaml) is the wire contract.
`openapi-typescript` generates `src/shared/api/generated/schema.d.ts`; never edit it.
`openapi-fetch` stays behind product-facing API functions and hooks so generated
transport types do not spread through components. Follow the
[OpenAPI workflow](../docs/development/openapi.md).

React Router owns navigation. TanStack Query owns server state, caching, loading, and
invalidation. Component state owns transient interaction state; URL parameters own
navigable/shareable state when safe (the featured week or month, release view and filters,
search query and page). The canonical featured destination is `/lanzamientos/destacados`; `week`, `month` and
`granularity` keep the existing weekly/monthly URL state. `/` is the home (#382), whose
previews live in `src/features/home/` and keep no URL state of their own (TOPómetro's source
and the calendar's chosen day stay in the preview). A former landing link keeps its destination:
`week`, `month` or `granularity` replace their history entry with Destacados on that query, and
`view=recent|upcoming` links retain their explorer migration; saved game-return state carrying
them resolves the same way, so no former featured link becomes a player-score ranking. A game
opened from the home returns with **Volver al inicio**. The **Lanzamientos** disclosure
links to **Destacados**, **Explorar** (`/lanzamientos`) and **Calendario**
(`/lanzamientos/calendario`); **TOPómetro** (`/topometro`) is a separate entry whose URL holds
only its source (`source=gameometro`), while its loaded pages stay in the document's query
cache for Back and the game page's way back; a platform's TOPómetro names its platform in the path
and keeps the same `source`; a platform's Próximos destacados holds no URL state beyond its
platform. **Plataformas** (`/plataformas`) holds only the
open other platform (`plataforma=<platformId>`); its platform contexts and the three
destinations and shared contextual navigation live in `src/features/platforms/`.
The header's desktop disclosure and compact menu share `app/platform-shortcuts.tsx`; the
[frontend design guidelines](../docs/development/frontend-design.md#shared-platform-navigation)
own their interaction and visual rules. The glossary owns their [product meanings](../docs/product/glossary.md#user-facing-terms). Values outside the contract shape
fall back to a safe default before reaching the API. Personal filters live only in
component state and personal query data is discarded when the page unmounts. OAuth
tokens and personal responses are never stored in browser storage; same-origin
requests preserve the BFF cookie and CSRF contract.

Game links use `/games/{gameId}/{slug}`; the slug is descriptive and the internal ID
alone drives the API read. Personal rating mutations never retry: a conflict re-reads
the personal rating, an authentication or CSRF rejection re-reads the session, and an
ambiguous transport failure keeps the last valid state and offers an explicit re-read.
An anonymous rating press starts authentication through the BFF `/auth/rating-intent`
routes; the single-use recovered value is persisted once on return.

## Accessibility and testing

Every implemented state must be usable with semantic HTML, keyboard navigation,
visible focus, accessible names, and understandable loading/empty/error feedback.
Component tests use Vitest and React Testing Library; Playwright owns complete
packaged journeys and axe checks. Test observable behaviour rather than component
internals.

The project-specific frontend skill and
[ADR-0012](../docs/decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md)
define the approved framework boundary. Next.js, SSR, React Server Components,
another state library, and a component library remain deferred until a concrete need
is approved.
