# Walking-skeleton compatibility evidence

- **Status:** Partial; issue #34 remains open
- **Last verified:** 2026-08-22
- **Authority:** [Technology baseline compatibility gate](../architecture/technology/mvp-technology-baseline.md#16-walking-skeleton-compatibility-gate)

This record maps implemented, repeatable evidence without claiming that the complete
walking-skeleton gate has passed.

## Evidence contributed by the releases shell

| #34 requirement | Repeatable evidence | Current result |
|---|---|---|
| Complete OpenAPI TypeScript generation, including release-date `oneOf`, and strict type checking | `npm run frontend:verify`; explicit compile-time assertions in `release-date.test.ts` | Covered |
| Product-facing typed same-origin call | `releases-api.test.ts`; real request observed by `packaged-releases.spec.ts` | Covered |
| Packaged browser-to-API-to-PostgreSQL path | `bash scripts/package-application.sh`; `bash scripts/validate-browser.sh` | Covered |
| CI reproduction of this slice | `browser-smoke` job in `quality-gates.yml` | Defined; hosted result follows PR/push |

The browser gate creates a fresh PostgreSQL 18 database, applies production
migrations plus deterministic development seed, starts the combined Spring Boot JAR,
and runs Chromium on the same disposable Docker network. It asserts the real
`GET /api/v1/releases?view=recent&page=1&pageSize=6` response, known rendered data,
keyboard navigation, axe-core results, server-owned route boundaries, and absence of
browser requests to IGDB. The Docker network is internal, preventing provider egress
from both browser and application. The test does not intercept the product API.

## Still required before #34 can pass

- local Keycloak 26.7 OIDC login establishing a BFF session;
- OCI application image build/start evidence for `linux/amd64` and `linux/arm64`;
- inspected application manifest evidence for both architectures;
- an explicit complete-topology CPU and memory budget within 2 OCPU and 12 GB;
- final hosted CI evidence bringing every dependency of #34 together.

Java/Spring/Modulith, PostgreSQL/Flyway, persistence, dependency-image manifests,
observability, and earlier CI evidence remain owned by their existing guides and
issues. No failure in this slice requires a baseline fallback, and no Java,
framework, database, or architecture line changed.
