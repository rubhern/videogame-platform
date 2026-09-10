# VideoGame Platform frontend

The frontend is a client-rendered React/TypeScript SPA for the approved same-origin
BFF/API. It currently renders the complete `UC-001` release-discovery page — recent
and upcoming windows, platform and region filters, pagination, covers, and the
loading, empty, stale, catalogue-not-ready and failure states — the `UC-002`
bounded-catalogue search page, and the accessible `UC-003` public game-detail page.
It also implements the `UC-004` same-origin authentication boundary: an authenticated-only
header account control with CSRF-protected logout, and a minimal game-page rating entry
point that starts Keycloak-hosted authentication and, on return, shows the recovered value
as a pending, non-persisted selection. Personal rating create/update/delete commands,
`Mis puntuaciones`, and provider synchronization UI remain later slices.

## Visual development

Screen and component changes must follow the canonical
[Frontend design guidelines](../docs/development/frontend-design.md).
The Contemporary Catalogue foundation is applied to releases and reused by search,
game details and route fallbacks. `src/shared/ui/` holds the cover and loading patterns already
shared by those experiences; the guide points to the executable visual tokens.

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

Vite serves `http://localhost:5173` and proxies `/api`, `/auth`, and `/actuator` to
the backend. Those paths remain server-owned and must not become client routes.

Production assets are written to ignored `frontend/dist/`. Build the deployable
same-origin JAR with `bash scripts/package-application.sh`; validate the real packaged
browser path with `bash scripts/validate-browser.sh`.

## Structure and ownership

| Path | Responsibility |
|---|---|
| `src/app/` | Providers, router, query client, and application shell |
| `src/features/` | Product capabilities and their API/view-model/UI code |
| `src/pages/` | Route-level composition |
| `src/shared/api/` | Generated contract and product-facing transport boundary |
| `src/shared/catalogue/` | Catalogue presentation shared by release browsing and search |
| `src/shared/ui/` | UI patterns with demonstrated cross-feature reuse |
| `src/styles/` | Global Tailwind entry and shared visual foundations |
| `src/test/` | Shared component-test setup |
| `tests/` | Packaged browser journeys |

Keep feature behaviour close to its feature. Do not create a design system, global
state store, or generic abstraction without demonstrated reuse or ownership value.

## API, routing, and state

[`docs/architecture/api/openapi.yaml`](../docs/architecture/api/openapi.yaml) is the
wire contract. `openapi-typescript` generates
`src/shared/api/generated/schema.d.ts`; never edit it. `openapi-fetch` remains behind
product-facing API functions and hooks so generated transport types do not spread
through components. Follow the [OpenAPI workflow](../docs/development/openapi.md).

React Router owns browser navigation. TanStack Query owns server state, caching,
loading, and invalidation. Component state owns transient local interaction state;
URL parameters own navigable/shareable state when safe. The release page keeps
`view`, `platformId`, `regionId`, `page`, and `pageSize` in the query string so a
filtered result stays shareable. Values outside the contract shape fall back to a
safe default before reaching the API; well-formed but unknown platform and region
identifiers reach the API and produce the explicit unsupported-filter state. OAuth
tokens and personal responses must not be stored in browser storage. Same-origin
requests preserve the BFF cookie and CSRF contract.

The search page restores query and pagination from the URL, hides old placeholder
results during a new request, and maps transport failures to a retryable state.
Only failures carrying a correlation ID display a support reference.

Game links use `/games/{gameId}/{slug}`; the optional slug is descriptive and the
internal ID alone drives the public API read. Details distinguish loading, absence,
catalogue-not-ready, retryable failure, stale/review-required evidence and unavailable
statistics. Platform and region selection updates the displayed release context and
is restored from the URL, without changing game-wide eligibility or aggregate state.
The community score sits beneath the cover; its distribution is retained in the API
but is not rendered. When the game is eligible, the personal-rating panel shows the minimal
`UC-004` rating entry point that begins authentication at the rating boundary and presents a
recovered value as pending, non-persisted state; it never persists a rating. The existing
shell restores main-content focus on navigation. Date precision and direct-provider cover fallback/attribution follow
the same contracts as discovery.

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
