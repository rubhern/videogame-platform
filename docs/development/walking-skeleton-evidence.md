# Walking-skeleton compatibility evidence

- **Status:** Partial; issue #34 remains open
- **Last verified:** 2026-08-23
- **Authority:** [Technology baseline compatibility gate](../architecture/technology/mvp-technology-baseline.md#16-walking-skeleton-compatibility-gate)

This record maps implemented, repeatable evidence without claiming that the complete
walking-skeleton gate has passed.

## Covered compatibility evidence

| #34 requirement | Repeatable evidence | Current result |
|---|---|---|
| Complete OpenAPI TypeScript generation, including release-date `oneOf`, and strict type checking | `npm run frontend:verify`; explicit compile-time assertions in `release-date.test.ts` | Covered |
| Product-facing typed same-origin call | `releases-api.test.ts`; real request observed by `packaged-releases.spec.ts` | Covered |
| Packaged browser-to-API-to-PostgreSQL path | `bash scripts/package-application.sh`; `bash scripts/validate-browser.sh` | Covered |
| CI reproduction of this slice | `browser-smoke` job in `build-and-verify.yml` | Defined; hosted result follows PR/push |
| Real Keycloak 26.7 OIDC login establishing an opaque BFF session | `SessionSecurityIntegrationTest`; `OidcIdTokenValidationTest`; `bash scripts/validate-identity.sh` | Covered locally; hosted result follows PR/push |
| CI reproduction of the identity slice | `identity-compatibility` job in `build-and-verify.yml` | Defined; hosted result follows PR/push |
| One application OCI index for `linux/amd64` and `linux/arm64` | `bash scripts/validate-container-image.sh`; parsed `oci-index.json` and `manifest-platforms.txt` | Covered locally; hosted result follows PR/push |
| AMD64 and ARM64 startup, liveness, readiness, frontend, API/BFF ownership, non-root UID and source metadata | `runtime-amd64.txt`, `runtime-arm64.txt`; ARM64 uses explicit QEMU on AMD64 CI | Covered locally; hosted result follows PR/push |
| Image vulnerability/secret scan and software inventory | Trivy reports and `sbom-*.cdx.json` retained by `container-image` | Covered locally; hosted result follows PR/push |
| Immutable GHCR SHA tag and matching digest | trusted-`main` `publish-container-image` job | Defined; first hosted `main` publication remains required |

The release browser gate creates a fresh PostgreSQL 18 database, applies production
migrations plus deterministic development seed, starts the combined Spring Boot JAR,
and runs Chromium on the same disposable Docker network. It asserts the real
`GET /api/v1/releases?view=recent&page=1&pageSize=6` response, known rendered data,
keyboard navigation, axe-core results, server-owned route boundaries, and absence of
browser requests to IGDB. The Docker network is internal, preventing provider egress
from both browser and application. The test does not intercept the product API.

The identity gate creates a disposable topology per execution using the same
reviewed realm import as local development. Chromium completes the real Keycloak
form and callback, then verifies the minimal session response, opaque HttpOnly
cookie, absence of tokens and browser storage, CSRF rejection, valid logout and
cookie removal. It does not use `page.route`, mock OIDC endpoints, retries, or traces.

## Still required before #34 can pass

- an explicit complete-topology CPU and memory budget within 2 OCPU and 12 GB;
- final hosted CI evidence bringing every dependency of #34 together, including the
  first GHCR publication result.

Java/Spring/Modulith, PostgreSQL/Flyway, persistence, dependency-image manifests,
observability, and earlier CI evidence remain owned by their existing guides and
issues. No failure in this slice requires a baseline fallback, and no Java,
framework, database, or architecture line changed.
