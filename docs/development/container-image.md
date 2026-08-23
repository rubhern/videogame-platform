# Application container image

- **Status:** Implemented local and CI evidence for issue #27
- **Last verified:** 2026-08-23
- **Application version:** `0.7.0-SNAPSHOT`
- **Delivery decision:** [ADR-0008](../decisions/0008-use-github-actions-and-ghcr-for-initial-delivery.md)
- **Platform design:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

This guide owns the verified build and inspection workflow for the single production
application image. It is not the remote deployment, recovery, or operations runbook
owned by later work.

## Image boundary

The multi-stage `Dockerfile` builds the locked React/Vite frontend with Node.js
24.19.0 and npm 11, embeds the generated assets through Maven's `with-frontend`
profile, and builds the Spring Boot application with Java 25 and the committed Maven
Wrapper. The image-only Maven profile excludes the opt-in development seed while
retaining production migrations. Runtime smoke loads its deterministic fixture
directly into disposable PostgreSQL after migration, so the runtime stage contains
only the production executable JAR and a Java 25 JRE.

Spring Boot serves the frontend and owns the same-origin BFF/API boundary. Explicit
browser routes such as `/games/{slug}` return the SPA entry point. `/api`, `/auth`,
and `/actuator` remain server-owned and are never captured by the SPA fallback.

The image:

- targets `linux/amd64` and `linux/arm64` in one OCI index;
- runs as the dedicated numeric user and group `10001:10001`;
- supports a read-only root filesystem with a temporary `/tmp` mount;
- accepts Spring Boot configuration only at runtime through normal external
  configuration and environment variables;
- contains no local `.env`, source tree, test tree, npm workspace, provider payload,
  or build credential;
- records standard OCI source, revision, version, title, description, URL, and vendor
  labels.

## Build and verify locally

Docker Desktop with Buildx must be available. On an AMD64 machine, Docker must also
have ARM64 emulation registered; CI does this explicitly with the pinned QEMU action.
Run from the repository root:

```bash
bash scripts/validate-container-image.sh
```

The script performs one repository-level workflow:

1. builds a two-platform OCI archive with Buildx;
2. parses its OCI index and requires exactly `linux/amd64` and `linux/arm64`;
3. loads each platform variant and verifies OCI labels, configured user, history,
   final filesystem contents, and absence of development seed/source/test entries
   inside the executable JAR;
4. starts each image with a read-only root filesystem, dropped Linux capabilities,
   `no-new-privileges`, an ephemeral PostgreSQL 18 database, and random runtime-only
   credentials;
5. proves liveness, readiness, packaged frontend, a deep SPA route, the release API,
   anonymous BFF session, and server ownership of `/api`, `/auth`, and `/actuator`;
6. verifies the running process UID and correlates `/actuator/info` with image
   revision/version labels;
7. scans each platform image with the pinned Trivy image for vulnerabilities and
   secrets, failing on unsuppressed high or critical findings; and
8. generates a CycloneDX JSON SBOM for each architecture.

Generated evidence is ignored build output under `target/container-evidence/`:

| Evidence | File |
|---|---|
| Multi-platform OCI archive and checksum | `application-image.oci.tar`, `SHA256SUMS` |
| Immutable index digest and platforms | `image-digest.txt`, `oci-index.json`, `manifest-platforms.txt` |
| Runtime and non-root checks | `runtime-amd64.txt`, `runtime-arm64.txt` |
| Final filesystem and layer history | `filesystem-*.txt`, `history-*.txt` |
| Executable JAR contents | `jar-contents-amd64.txt`, `jar-contents-arm64.txt` |
| Trivy reports | `trivy-*.txt`, `trivy-*.json`, `trivy-version.txt` |
| CycloneDX SBOMs | `sbom-amd64.cdx.json`, `sbom-arm64.cdx.json` |

A clean checkout uses the full Git commit for `org.opencontainers.image.revision`.
A dirty local checkout deliberately uses `<short-sha>-dirty`; CI always passes the
trusted `github.sha` explicitly.

## Trusted publication

Pull requests build, run, inspect, scan, and retain evidence but have read-only
package permissions and never publish. A trusted push to `main` uploads the exact
validated OCI archive between jobs, verifies its checksum, authenticates to GHCR
with the short-lived `GITHUB_TOKEN`, and copies all manifests with Skopeo while
preserving their digests.

The published reference is:

```text
ghcr.io/rubhern/videogame-platform:<full-commit-sha>
```

The workflow then requires the remote digest to equal the locally inspected and
scanned OCI index digest, requires both architectures in the remote manifest, writes
the SHA reference and digest to the job summary, and retains publication metadata.
No `latest` tag is created. Deployment work must select the digest, not rebuild or
resolve a mutable tag.

GHCR package visibility is repository administration. ADR-0008 requires the linked
package to remain public while this public-repository delivery design applies; the
first hosted publication must confirm that setting. No long-lived registry secret is
required.

## Failure policy and scope

Do not remove ARM64, add a scanner ignore, lower the severity gate, or upgrade across
the approved technology baseline merely to obtain a green build. Diagnose the
platform or finding and apply the delivery lifecycle's existing risk process when no
compatible remediation exists.

This workflow does not provision OCI resources, deploy the image, define backup or
restore procedures, implement product features, or claim the complete resource-budget
evidence owned by issue #34.
