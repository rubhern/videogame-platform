# Frontend development

- **Status:** Active minimal product shell
- **Last verified:** 2026-08-23
- **Runtime:** Node.js 24 and npm 11
- **Stack:** React 19.2, TypeScript strict mode, Vite 8.1
- **Technical reference:** [Frontend README](../../frontend/README.md)
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)

## Supported boundary

The current frontend is the smallest client-rendered product slice for the approved
stack. It proves React, routing, TanStack Query, Tailwind CSS, complete OpenAPI type
generation, a release-specific typed same-origin API, explicit UI states, component
tests, and a production build embedded in the application JAR.

The browser evidence runs Chromium against the packaged application and a fresh
PostgreSQL 18 database. It observes the real release response, renders deterministic
seed data, scans the shell with axe-core, and follows the game link by keyboard. It
remains skeleton evidence rather than the final release-discovery experience.

## Stable commands

Run from the repository root:

```bash
npm ci
npm run frontend:verify
bash scripts/package-application.sh
bash scripts/validate-browser.sh
bash scripts/validate-container-image.sh
```

For local development:

```bash
./mvnw -pl backend spring-boot:run
npm run frontend:dev
```

The second command starts Vite at `http://localhost:5173` and proxies server-owned
paths to Spring Boot on port 8080.

The npm commands regenerate OpenAPI types, run static analysis, strict type checks,
component tests and the production build. The package command creates the combined
Spring Boot JAR. The browser wrapper runs that JAR, PostgreSQL and the pinned official
Playwright image in one disposable network. The
[frontend README](../../frontend/README.md) records the exact container boundary.

See the [frontend technical reference](../../frontend/README.md) for architecture,
directory ownership, API generation, state, routing, accessibility, testing,
security advisories, build output, and troubleshooting.

## Current limitations

- Only the fixed, bounded recent page is visible; filters, pagination controls and
  final stale-data presentation remain deferred.
- `/games/:slug` is an explicit navigation placeholder, not game details.
- Login controls, authenticated product UI, ratings, and provider synchronization
  remain separate work. The
  server-owned BFF session compatibility proof is covered by the identity guide and
  its real-browser gate.
- Full responsive polish and critical journey coverage belong to later feature work.
