# VideoGame Platform frontend

The frontend is a client-rendered React/TypeScript SPA for the approved same-origin
BFF/API. It implements the complete MVP journey: `UC-001` release discovery,
`UC-002` bounded catalogue search, `UC-003` public game details, the `UC-004`
same-origin authentication boundary (an authenticated-only header account control
with CSRF-protected logout; authentication starts only from the rating control), the
`UC-005`–`UC-007` inline personal rating on the game page, and `UC-008`
`/mis-puntuaciones`. Behaviour is specified by the
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

Vite serves `http://localhost:5173` and proxies `/api`, `/auth`, and `/actuator` to
the backend. Those paths remain server-owned and must not become client routes.
Production assets are written to ignored `frontend/dist/`; build the deployable
same-origin JAR with `bash scripts/package-application.sh` and validate the packaged
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

[`openapi.yaml`](../docs/architecture/api/openapi.yaml) is the wire contract.
`openapi-typescript` generates `src/shared/api/generated/schema.d.ts`; never edit it.
`openapi-fetch` stays behind product-facing API functions and hooks so generated
transport types do not spread through components. Follow the
[OpenAPI workflow](../docs/development/openapi.md).

React Router owns navigation. TanStack Query owns server state, caching, loading, and
invalidation. Component state owns transient interaction state; URL parameters own
navigable/shareable state when safe (release view and filters, search query and
page, the selected platform/region on a game page). Values outside the contract shape
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
