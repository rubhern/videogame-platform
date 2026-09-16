# Private dev runtime and deployment

This directory is the reviewed operator entry point for the owner-managed private
`dev` environment. It contains the runtime prepared by #43 and the owner-triggered
deployment mechanism prepared by #36. Repository validation is not evidence that
either has run successfully on `vgpdev`.

The canonical environment, deployment and failure policy is the
[platform and delivery design](../../docs/architecture/deployment/mvp-platform-and-delivery.md).
The files here own the executable details.

## Boundaries

- `compose.yaml` defines digest-pinned PostgreSQL, a locally optimized Keycloak image
  built from a digest-pinned upstream, bounded OpenTelemetry collection, the
  digest-selected application, a one-shot migration actor and a one-shot browser
  smoke runner. Only PostgreSQL, Keycloak and telemetry start without an explicit
  application/deployment profile or service selection.
- PostgreSQL and telemetry have no host ports. Keycloak and the application bind only
  to IPv4 loopback; Tailscale Serve remains the private HTTPS edge. Management and
  OTLP ports stay inside Docker networks.
- The application runtime receives `videogame_app` credentials and cannot migrate.
  The one-shot migration actor receives only `videogame_app_migrator` credentials,
  joins only the internal data network and exits before application replacement.
  Keycloak keeps its separate database and role.
- `bin/deploy-private-dev` runs only when the owner explicitly invokes it on a host
  named `vgpdev`. It rejects tags and other mutable references, holds one host lock,
  and requires both an immutable GHCR digest and the full source revision expected
  to have produced it. No workflow deploys from `main` automatically.
- The source-revision tag is used only to verify the supplied digest. The script then
  pulls and runs the digest, verifies the selected-platform OCI source/version labels,
  and never derives a candidate on the owner's behalf.
- The prior application remains untouched until the migration actor succeeds. A
  migration failure prevents replacement. Candidate readiness and every smoke check
  are tied to the newly created Compose container; any failure records a failed
  deployment. Automatic rollback, backup/restore and host-loss recovery remain #44.
- Deployment evidence is an atomically updated JSON record outside the checkout. It
  includes timestamps, target/environment, initiator, source revision, immutable
  image, application version, migration version, candidate container ID, completed
  smoke checks, phase and outcome. It contains no credentials or personal data.
- Deployment health never invokes IGDB or requires catalogue synchronization. A
  published empty release page and the approved `CATALOGUE_NOT_READY` state for a
  database with no publication both prove the local read/browser boundary; provider
  image hosts are blocked inside the smoke runner.

## One-time private host preparation

Do not run these commands from a workstation and do not apply them to `vgpdev`
without the owner's explicit host-change decision. Replace `<operator>` and
`<checkout>` only after verifying them on the host.

1. Create the protected runtime configuration and secret files. The protected parent
   directory is the host access boundary; Compose grants containers only the files
   each actor needs.

   ```bash
   sudo groupadd --system --gid 20001 vgp-runtime
   sudo usermod --append --groups vgp-runtime <operator>
   sudo install -d -m 0750 -o root -g vgp-runtime /etc/videogame-platform/dev
   sudo install -m 0640 -o root -g vgp-runtime \
     <checkout>/deploy/private-dev/runtime.env.example \
     /etc/videogame-platform/dev/runtime.env
   sudo <checkout>/deploy/private-dev/bin/prepare-secrets \
     /etc/videogame-platform/dev/secrets vgp-runtime
   sudo install -d -m 0770 -o root -g vgp-runtime \
     /var/lib/videogame-platform/dev/deployment-evidence
   ```

   Start a new login session so group membership applies. Set
   `PRIVATE_DEV_SECRETS_GID` from the real group and replace both example origins with
   the exact `vgpdev` MagicDNS HTTPS origins. Leave the application/source/version
   placeholders unchanged: the deployment command overrides them for one invocation.
   Populate IGDB credentials only for an explicitly initiated catalogue sync. The
   preparation command creates a fixed non-personal smoke username and random password
   in `oidc-smoke-username` and `oidc-smoke-password`; neither value is printed or
   placed in `runtime.env`.

2. Validate the reviewed topology without starting or replacing services:

   ```bash
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

   The application placeholders are accepted only by this static validator. The
   deployment command will reject them. The independent `--telemetry-smoke` mode
   starts a disposable collector and one fixed, non-personal OTLP sender; it does not
   deploy an application:

   ```bash
   bash scripts/validate-private-dev-runtime.sh --telemetry-smoke
   ```

3. Start only the runtime dependencies prepared by #43. Do not enable the application
   or deployment profiles here.

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml pull postgres telemetry
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml build --pull keycloak
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml up --detach postgres keycloak telemetry
   ```

4. Keep Tailscale Funnel and router forwarding disabled. Confirm the owner-only
   tailnet policy before configuring the two private HTTPS routes:

   ```bash
   tailscale serve --bg --https=443 http://127.0.0.1:8080
   tailscale serve --bg --https=8443 http://127.0.0.1:8180
   tailscale serve status
   tailscale funnel status
   ```

   Verify the Keycloak discovery issuer through port `8443`, database/role ownership,
   restart persistence, loopback-only listeners, router IPv4 forwarding/UPnP state
   and unsolicited public IPv4/IPv6 reachability as described by the platform design
   and #43 evidence. None of those checks is implied by repository validation.

5. After Keycloak and its private HTTPS route are healthy, provision the dedicated
   deployment-smoke account from the protected files:

   ```bash
   deploy/private-dev/bin/provision-oidc-smoke-user \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

   The command is idempotent: it creates the marked, enabled account when absent and
   otherwise rotates its password to the protected value. It refuses an unmarked
   existing username, personal profile fields, direct client roles, or group
   membership. It uses Keycloak's private HTTPS Admin API without putting the admin
   password, smoke password, or access token in arguments, environment metadata, logs,
   realm imports, or Git. Private dev continues to exclude the synthetic local-user
   import used by disposable development tests.

## Owner-triggered deployment

Choose a trusted `main` source revision and review its successful required checks,
image publication summary, scan/SBOM evidence and immutable digest. The two selected
values remain explicit in the command:

```bash
deploy/private-dev/bin/deploy-private-dev \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --image ghcr.io/rubhern/videogame-platform@sha256:<approved-64-hex-digest> \
  --source-revision <approved-full-40-character-main-sha> \
  --initiator rubhern
```

The command performs this exact sequence under one non-blocking host lock:

1. validate that the target is `vgpdev`, the protected runtime configuration renders,
   smoke credentials exist, dependencies are running/healthy, and evidence storage is
   writable;
2. resolve the explicitly supplied source revision's GHCR tag for verification only,
   require its published digest to equal the supplied digest, pull by digest, and
   verify the local image's source, revision and version OCI labels;
3. build the digest-pinned, no-retry Playwright smoke runner before changing the
   database or application;
4. run the selected application image once as `videogame_app_migrator`, apply and
   validate only packaged production Flyway migrations, record the current migration
   version, and stop immediately on failure;
5. force-recreate only the application service with the selected digest, wait for
   candidate health, and verify the created container uses the inspected local image;
6. run the complete deployment smoke against that candidate and the private HTTPS
   boundary, then verify structured correlation/trace evidence and collector receipt;
7. finalize the JSON evidence record as `success`, or as `failure` with the phase that
   stopped. A failed outcome is never changed to success because another container or
   older version happens to answer health checks.

Every normal deployment checks:

- management liveness and readiness;
- `/actuator/info` application version and full source revision;
- the management metric catalogue for HTTP, JVM and JDBC diagnostics;
- `GET /api/v1/releases` returning either a valid local publication page (including
  zero items) or the approved `CATALOGUE_NOT_READY` response when no publication has
  ever existed;
- the corresponding Spanish release, empty, or catalogue-not-ready shell rendering
  in Chromium, with IGDB hosts blocked;
- real Keycloak authorization, an opaque `HttpOnly`, `Secure`, `SameSite=Lax` BFF
  session, absence of browser-stored OAuth material, and CSRF-protected logout;
- W3C trace/correlation propagation in structured application logs; and
- trace receipt by the bounded OpenTelemetry collector.

The smoke deliberately does not rate a game, traverse every screen, run or require
provider synchronization, or claim full MVP acceptance. Catalogue synchronization
stays a separate owner-triggered operation; the complete journey remains #45.

## First real deployment evidence still required

The first approved execution on `vgpdev` must still establish host facts that cannot
be proved in this repository:

- the selected revision's current required GitHub checks and publication evidence are
  accepted by the owner before invoking the command;
- #43 dependency, private HTTPS, secret, role, resource and no-public-ingress checks
  remain valid with the application reservation active;
- the releases API/browser evidence records either a valid local publication (which
  may be empty) or the approved no-publication state; no catalogue synchronization or
  IGDB availability is a deployment prerequisite;
- the dedicated smoke account completes the real private Keycloak flow without any
  admin role or personal data;
- the migration/application/container versions and all smoke/telemetry checks appear
  in the generated evidence record; and
- representative post-deployment CPU, memory, disk, listener and private-access
  observations are recorded without calling this a named release or full MVP
  acceptance.

Do not add rollback, backup/restore or host-loss claims to this procedure. Those
controls and their evidence remain #44.
