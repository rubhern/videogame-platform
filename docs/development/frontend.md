# Frontend development

- **Status:** Active initial skeleton
- **Last verified:** 2026-08-07
- **Runtime:** Node.js 24 and npm 11
- **Stack:** React 19.2, TypeScript strict mode, Vite 8.1
- **Technical reference:** [Frontend README](../../frontend/README.md)
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)

## Supported boundary

The current frontend is the smallest client-rendered foundation for the approved
stack. It proves React, routing, TanStack Query, Tailwind CSS, complete OpenAPI type
generation, typed same-origin transport, static analysis, component tests,
accessibility smoke coverage, and the production build.

It intentionally implements no product API call or journey. The visible page is a
technical placeholder, not release discovery or another MVP capability.

## Stable commands

Run from the repository root:

```bash
npm ci
npm run frontend:verify
bash scripts/validate-browser.sh
```

For local development:

```bash
./mvnw -pl backend spring-boot:run
npm run frontend:dev
```

The second command starts Vite at `http://localhost:5173` and proxies server-owned
paths to Spring Boot on port 8080.

The npm commands run static analysis, generated-type compilation, component tests
and the production build. The repository browser wrapper runs the real smoke test
with the pinned official Playwright image, including its Node 24 and Chromium runtime,
without changing workstation packages. The
[frontend README](../../frontend/README.md) records the exact container boundary.

See the [frontend technical reference](../../frontend/README.md) for architecture,
directory ownership, API generation, state, routing, accessibility, testing,
security advisories, build output, and troubleshooting.

## Current limitations

- The backend does not yet serve or package `frontend/dist`.
- No product endpoint is invoked by the UI.
- Browser-route fallback is proven through Vite, not through the packaged server.
- Full responsive product states and critical journey coverage belong to later
  feature and compatibility issues.
