# Private dev runtime

This directory is the reproducible operator entry point for issue #43. It prepares
the approved single-host runtime without deploying an application image, running
migrations, proving backup/restore, or claiming evidence from `vgpdev`.

## Reviewed boundary

- `compose.yaml` starts digest-pinned PostgreSQL, one locally optimized Keycloak image
  built from a digest-pinned upstream, and one bounded OpenTelemetry Collector. The
  `application` profile defines the later deployment boundary but does not select or
  start an image by default.
- PostgreSQL and telemetry have no host ports. Keycloak and the application bind only
  to `127.0.0.1`; Tailscale Serve is the private HTTPS edge. Application and Keycloak
  management ports remain inside Docker networks.
- The application and Keycloak use different databases and login roles. The
  application runtime role cannot perform Flyway migrations; #36 owns the serialized
  migration/deployment sequence. Both local and private dev mount
  `docker/postgres/init/001-create-databases.sh`: that shared executable is the single
  owner of database/role creation, while direct variables versus secret files are
  environment-specific transports.
- Local and private dev also share the single parameterized Keycloak realm at
  `docker/keycloak/import/videogame-platform-realm.json`. The synthetic local test
  user is a separate import file mounted only by the local Compose topology.
- Secrets are individual files below a protected `0750` directory outside the checkout;
  that parent directory is the host access boundary. Files are non-writable `0644` so
  PostgreSQL can read its explicitly granted Compose mounts after dropping groups. The actual runtime
  environment file also stays outside Git because it contains the private tailnet
  name and selected image digest.
- The collector receives OTLP HTTP metrics/traces only on an internal network. Its
  memory, batch and Docker-log retention are bounded, and basic export verbosity does
  not print signal attributes or bodies. `--telemetry-smoke` proves the path with
  exactly one fixed versioned span and one fixed metric, independently of #36.

## Host application and verification

Do not run these commands until the owner approves applying #43 to `vgpdev`. Run them
from a reviewed checkout on that host and record results in issue #43 rather than in
evergreen documentation.

1. Choose an unused system group/GID for runtime-secret readers, create the protected
   configuration location, and copy the non-secret example. Replace `<operator>` and
   `<checkout>` with verified values.

   ```bash
   sudo groupadd --system --gid 20001 vgp-runtime
   sudo usermod --append --groups vgp-runtime <operator>
   sudo install -d -m 0750 -o root -g vgp-runtime /etc/videogame-platform/dev
   sudo install -m 0640 -o root -g vgp-runtime \
     <checkout>/deploy/private-dev/runtime.env.example \
     /etc/videogame-platform/dev/runtime.env
   sudo <checkout>/deploy/private-dev/bin/prepare-secrets \
     /etc/videogame-platform/dev/secrets vgp-runtime
   ```

   Start a new login session so group membership applies. Set
   `PRIVATE_DEV_SECRETS_GID` to `getent group vgp-runtime | cut -d: -f3`; replace both
   example origins with the exact `vgpdev` MagicDNS name. Leave the application digest
   placeholder until #36 selects a validated image. Populate the two IGDB files only
   when approved synchronization is needed; empty files keep it disabled. Never print
   secret files or paste their values into the environment file.

2. Validate the rendered configuration without starting anything. During #43 use a
   syntactically valid non-zero placeholder application digest/version solely because
   Compose validates the profile-gated service too; it is not deployment approval.

   ```bash
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

   Independently validate OTLP receipt with a disposable collector and digest-pinned
   one-shot sender on the internal telemetry network. This publishes no host port,
   submits one span and one metric containing fixed non-personal resource attributes,
   verifies basic logs do not expose their names/version, and removes both containers
   automatically:

   ```bash
   bash scripts/validate-private-dev-runtime.sh --telemetry-smoke
   ```

3. Pull and start only the #43 dependency/telemetry services. Do not enable the
   `application` profile and do not run Flyway or seed data.

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml pull postgres telemetry
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml build --pull keycloak
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml up --detach postgres keycloak telemetry
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml ps
   ```

4. Confirm the owner-only tailnet ACL before configuring private HTTPS. Keep Funnel
   disabled. Configure the product route now even though it will return an unavailable
   upstream response until #36 starts the application.

   ```bash
   tailscale serve --bg --https=443 http://127.0.0.1:8080
   tailscale serve --bg --https=8443 http://127.0.0.1:8180
   tailscale serve status
   tailscale funnel status
   ```

5. From an owner device on the tailnet, inspect the Keycloak discovery document at
   `https://<vgpdev-magicdns>:8443/realms/videogame-platform/.well-known/openid-configuration`.
   Its issuer must equal the configured Keycloak origin. Confirm the admin console is
   reachable only to the owner. Port 443 is not an application acceptance check before
   #36.

6. Verify database ownership/isolation and persistent-volume ownership without
   migrations or application data:

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml exec --user postgres postgres \
     psql --dbname postgres --command \
     "SELECT datname, pg_get_userbyid(datdba) AS owner FROM pg_database WHERE datname IN ('videogame_platform', 'videogame_keycloak') ORDER BY datname;"
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml exec --user postgres postgres \
     stat -c '%U:%G %a %n' /var/lib/postgresql/18/docker
   ```

   Restart the three services and repeat the database query. Then reboot the host once,
   confirm Docker restores them through `unless-stopped`, and repeat it again. Record
   container health, volume ownership and any unsupported restart behavior in #43.

7. Capture idle and representative dependency-load resource evidence twice with a
   recorded observation interval. The live validator is read-only and prints container
   CPU/memory/PID usage, host memory/load/disk, listeners and Tailscale Serve state:

   ```bash
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env --live
   ```

   Keep the current 8 GB RAM unless repeated measurements show real pressure. The
   application reservation is not usage evidence while its profile is stopped.

8. Validate exposure separately for IPv4 and IPv6. On the router, confirm no IPv4
   forwarding/UPnP mapping exists for `443`, `8443`, `8080`, `8180`, `5432`, `4317`,
   `4318`, `8081` or `9000`, and confirm the IPv6 firewall does not allow unsolicited
   ingress to them. From a device outside the tailnet, scan the public IPv4 address
   and every global IPv6 address assigned to `vgpdev`:

   ```bash
   nmap -4 -Pn -p 443,8443,8080,8180,5432,4317,4318,8081,9000 <public-ipv4>
   nmap -6 -Pn -p 443,8443,8080,8180,5432,4317,4318,8081,9000 <global-ipv6>
   ```

   If the host has no global IPv6 address, record `ip -6 address show scope global` as
   that evidence instead of silently skipping IPv6. The live validator prints and
   checks IPv4 and IPv6 listeners independently: product and Keycloak HTTP may bind
   only to IPv4 `127.0.0.1`; PostgreSQL, management and OTLP ports must have neither an
   IPv4 nor IPv6 host listener.

The bounded synthetic check lets #43 accept collector receipt, version propagation at
the OTLP boundary and safe basic logging without #36. After #36 supplies an immutable
image digest and version, re-run static validation, start the `application` profile
through that issue's serialized migration/deployment procedure, and repeat `--live`.
Real application readiness, secure-cookie OIDC behavior and application-produced
telemetry remain #36 evidence, not a prerequisite for #43's telemetry-path criterion.
#44 separately owns backup and restore evidence.
