# VideoGame Platform frontend

The frontend is the client-rendered walking skeleton for the VideoGame Platform
learning MVP. It proves the approved browser stack, contract generation, routing,
state boundaries, testing, and production build without implementing a product
journey ahead of its dedicated issue.

## Current status and scope

- **Status:** Active walking skeleton
- **Runtime for tooling:** Node.js 24 and npm 11
- **Rendering:** Client-rendered React SPA
- **Current UI:** Technical placeholder and not-found route only
- **Current API traffic:** None from the rendered UI
- **Contract source:** `docs/architecture/api/openapi.yaml`

The placeholder is intentionally not release discovery, catalogue search,
authentication, or ratings functionality. Those slices must add their own behaviour,
states, tests, and accessibility evidence when their issues begin.

The approved [technology baseline](../docs/architecture/technology/mvp-technology-baseline.md),
[solution architecture](../docs/architecture/mvp-solution-architecture.md), and
[ADR-0012](../docs/decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md)
remain authoritative.

## Stack

| Concern | Technology | Locked version |
|---|---|---:|
| UI | React and React DOM | 19.2.8 |
| Language | TypeScript strict mode | 5.9.3 |
| Build and development server | Vite | 8.1.5 |
| Routing | React Router | 7.18.2 |
| Server state | TanStack Query | 5.101.4 |
| Styling | Tailwind CSS and Vite plugin | 4.3.3 |
| Contract generation | openapi-typescript | 7.13.0 |
| Typed HTTP transport | openapi-fetch | 0.17.0 |
| Unit and component tests | Vitest and React Testing Library | 4.1.10 / 16.3.2 |
| Browser tests | Playwright with axe-core | 1.62.1 / 4.12.1 |
| Static analysis | ESLint and typescript-eslint | 10.8.0 / 8.66.0 |

Every direct dependency is exact in [`package.json`](package.json), and the root
[`package-lock.json`](../package-lock.json) locks the complete npm workspace graph.

## Repository layout

```text
frontend
├── src
│   ├── app
│   │   ├── app-providers.tsx      # Application-level providers
│   │   ├── app-shell.tsx          # Shared semantic shell
│   │   ├── query-client.ts        # TanStack Query defaults
│   │   └── router.tsx             # Browser routes
│   ├── pages                      # Route-level UI
│   ├── shared/api
│   │   ├── generated/schema.d.ts  # Disposable OpenAPI-generated types
│   │   └── product-api-client.ts  # Internal same-origin typed transport
│   ├── styles/index.css           # Tailwind entry point and global defaults
│   ├── test                       # Shared component-test setup
│   └── main.tsx                   # Browser entry point
├── tests                          # Playwright browser smoke tests
├── eslint.config.js
├── playwright.config.ts
├── vite.config.ts
└── tsconfig.*.json
```

Keep route-level composition in `pages`, reusable UI and utilities in an explicitly
owned shared area, and server operations behind product-facing API functions. Do not
let UI components depend directly on generated operation types or the raw fetch
client.

## Install and verify

Run npm from the repository root. The root is an npm workspace so one lockfile owns
both OpenAPI documentation tooling and the frontend.

Reproduce the dependency tree from a clean checkout:

```bash
npm ci
```

Run the stable frontend gate:

```bash
npm run frontend:verify
```

The gate regenerates the complete OpenAPI types, runs ESLint, executes explicit
`tsc --noEmit` checks for browser and tooling configurations, runs Vitest, creates a
production build, and verifies that Playwright discovers the expected smoke test.

Run the browser test separately through the repository wrapper:

```bash
bash scripts/validate-browser.sh
```

Playwright never retries a failed test. It retains the first failing execution's
trace so diagnostics cannot convert an unreliable check into a passing result. CI
executes this browser command in addition to `frontend:verify` and retains the HTML
report for seven days as diagnostic output only.

The wrapper uses the matching official Playwright 1.62.1 Noble image pinned by digest
and runs as the current user with network access disabled. This avoids changing WSL
system packages and ensures local and CI use the same Node 24/Chromium runtime. Its
equivalent container command is:

```bash
docker run --rm --ipc=host --network none \
  --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$PWD:/work" -w /work \
  mcr.microsoft.com/playwright@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e \
  npm run frontend:test:e2e
```

Focused commands are:

```bash
npm run frontend:generate-api
npm run frontend:typecheck
npm run frontend:test
npm run frontend:build
```

## Local development

Start the backend in one terminal:

```bash
./mvnw -pl backend spring-boot:run
```

Start Vite in another terminal:

```bash
npm run frontend:dev
```

Open `http://localhost:5173`. Vite proxies `/api`, `/auth`, and `/actuator` to
`http://localhost:8080`, preserving the same-origin browser contract during local
development. The proxy does not make those routes frontend-owned.

Vite provides the SPA fallback in development and preview. The packaged backend must
eventually serve `index.html` for browser routes while leaving `/api`, `/auth`, and
operational routes server-owned; that packaging belongs to the dedicated walking-
skeleton image work.

## Routing policy

`createBrowserRouter` owns only browser navigation. The current routes are:

| Route | Purpose |
|---|---|
| `/` | Technical placeholder proving the frontend runtime |
| `*` | Accessible not-found page with keyboard-usable return navigation |

Do not add a frontend route under `/api`, `/auth`, or `/actuator`. Authentication
navigation goes to the server-side BFF, not to a client-side token flow.

## State policy

- TanStack Query owns remote server state, caching, retries, invalidation, and
  mutation coordination.
- Component state and small React contexts own transient UI state.
- A separate global state library remains deferred.
- Query retries are disabled by default so command failures are never repeated
  implicitly and unavailable server state is not hidden.
- Session and personal data must not be persisted in local storage or session
  storage.
- OAuth access and refresh tokens must never enter JavaScript-accessible state.

Feature slices may set deliberate query freshness or retry behaviour only when it
matches the operation's HTTP and product semantics.

## OpenAPI and product API boundary

Regenerate types from the complete reviewed OpenAPI 3.1.2 document:

```bash
npm run frontend:generate-api
```

The output is
[`src/shared/api/generated/schema.d.ts`](src/shared/api/generated/schema.d.ts). It is
committed for review and compile-time feedback but remains generated, disposable
source: never edit it manually.

[`product-api-client.ts`](src/shared/api/product-api-client.ts) creates a typed
`openapi-fetch` client with `/api/v1` and `credentials: same-origin`. It is the
low-level transport, not the UI-facing API. Each product slice must wrap only the
operations it owns, map transport failures to explicit product states, and expose
small functions or hooks to React code.

The rendered walking skeleton does not call the product API. Its transport test uses
the typed `/session` path only to prove URL construction and cookie credential policy
without a network request.

## Styling and accessibility

Tailwind CSS is loaded through the official Vite plugin and the CSS-first
`@import "tailwindcss"` entry point. There is no JavaScript Tailwind configuration
until the design system requires one.

The current shell provides:

- Spanish document language and visible Spanish-first copy;
- semantic `header`, `main`, section, heading, list, and link structure;
- keyboard-usable navigation and visible focus treatment;
- responsive spacing and text sizing from 320 pixels upward;
- contrast checked by the Playwright axe-core scan.

Automated checks are a baseline. New interaction must also be reviewed for keyboard
order, focus movement, accessible names, announcements, zoom, responsive behaviour,
and contrast.

## Testing strategy

| Layer | Current evidence |
|---|---|
| Type generation | Complete OpenAPI document generates without error |
| Type checking | Browser and Node/tooling configs pass strict `tsc --noEmit` |
| Static analysis | ESLint recommended, strict TypeScript, Hooks and Fast Refresh rules |
| Component | Placeholder semantics and unknown-route keyboard return |
| API transport | Generated path type, URL construction, and same-origin credentials |
| Browser | Production preview navigation and axe-core accessibility scan |
| Build | Optimized static HTML, CSS, and JavaScript output in `dist` |

Product behaviour requires tests for loading, success, empty, stale, unavailable,
rejection, and relevant accessibility states rather than implementation details.

## Production output

Create static assets with:

```bash
npm run frontend:build
```

Vite writes disposable output to `frontend/dist`, which is ignored by Git. The
current issue proves the production frontend build only; copying these assets into
the Spring Boot jar or final OCI image is intentionally deferred.

## Security and dependency notes

- No secret, token, user identifier, provider payload, or machine-specific URL is
  committed or written to browser storage.
- The UI loads no remote script, font, image, or analytics resource.
- `npm audit --audit-level=high` reports zero known vulnerabilities as of 2026-08-13.
  Issue #24 updated only the locked transitive OpenAPI parser chain from
  `@redocly/openapi-core` 1.34.18 / `js-yaml` 4.3.0 to the compatible patched
  1.34.19 / 4.3.1 versions. Direct package versions and generated/runtime behaviour
  did not change.
- Dependency review blocks newly introduced high/critical advisories, Gitleaks scans
  committed history, CodeQL and SonarQube Cloud scan TypeScript, and Dependabot
  proposes reviewable locked updates. Sonar excludes generated OpenAPI types so
  generated code does not distort ownership or gate results. Never use
  `npm audit fix --force` to cross the approved baseline silently.

## Troubleshooting

| Symptom | Action |
|---|---|
| `npm ci` rejects Node | Select Node.js 24 and npm 11, then start a fresh shell. |
| Generated schema is missing or stale | Run `npm run frontend:generate-api`; never edit it manually. |
| Vite cannot reach the backend | Start Spring Boot on port 8080 or deliberately update the local proxy. |
| A browser route works in Vite but not when packaged | Configure the server SPA fallback without capturing `/api`, `/auth`, or Actuator. |
| Browser wrapper cannot start Docker | Start Docker Desktop and verify `docker info`. |
| Direct `npm run frontend:test:e2e` reports a missing browser library | Use `bash scripts/validate-browser.sh`; do not alter WSL packages just for the smoke gate. |
| TypeScript reports a contract mismatch | Update the product-facing mapping or reviewed OpenAPI source; do not hand-edit generated types. |

## Adding a product slice

1. Start from an approved use case and reviewed OpenAPI operation.
2. Regenerate the complete contract.
3. Add a product-facing API function around the internal typed transport.
4. Use TanStack Query for server state and explicit UI state for transient input.
5. Implement all meaningful user-visible states with accessible semantics.
6. Add focused component, transport, and browser evidence.
7. Update this README and the cross-repository development guide.
8. Run `npm ci`, `npm run frontend:verify`, and applicable backend/contract checks.
