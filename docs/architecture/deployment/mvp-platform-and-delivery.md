# Learning MVP platform and delivery design

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Private, zero-recurring-cost learning platform

This document owns environment purpose, deployment topology, artefact/configuration/
secret behaviour, migration/backup/recovery, and technical delivery. The
[delivery lifecycle](../../development/delivery-lifecycle.md) owns human workflow,
gates, review, acceptance, and release.

## Environments and topology

| Environment  | Purpose                                                                        | Data/access                                     |
|--------------|--------------------------------------------------------------------------------|-------------------------------------------------|
| `local`      | Development and focused proofs on supported WSL2                               | Disposable/seeded; loopback only                |
| `test`       | Per-job automated evidence                                                     | Generated fixtures; CI only                     |
| `dev`        | Persistent private integration, HTTPS, identity, delivery, telemetry, recovery | Non-sensitive learning data; owner tailnet only |
| `production` | Deferred/prohibited                                                            | Undefined                                       |

The current `dev` foundation is one owner-managed private Linux host: Lenovo Y520 (`vgpdev`), Ubuntu Server 24.04 LTS
`x86_64`, existing 8 GB RAM and SSD, with Docker
Engine and Compose. Owner administration uses key-based OpenSSH over Tailscale;
Tailscale runs on Windows and the host, not WSL. Ethernet uses a router DHCP
reservation; addressing and tailnet identifiers stay outside canonical documents.
[ADR-0019](../../decisions/0019-host-private-dev-on-an-owner-managed-linux-host.md)
records the hosting decision; [#124](https://github.com/rubhern/videogame-platform/issues/124)
owns measured evidence and outstanding host acceptance.

The approved future runtime remains the non-root application container, Keycloak,
PostgreSQL and bounded telemetry on this host. Private HTTPS and runtime/secrets/
telemetry setup belong to #43, application deployment to #36, and backup/restore to
#44; these are not established by the foundation. Tailscale does not replace
Keycloak/product authorization. No public application, identity, database, telemetry
or SSH ingress is allowed, and router port forwarding must remain disabled.

The superseded OCI infrastructure and procedures were removed from the active
repository; Git history and [#42](https://github.com/rubhern/videogame-platform/issues/42)
retain the abandoned path and teardown evidence. No paid or trial-only substitute is
authorized. Public production, HA, staging, Kubernetes, distributed components,
automatic broad sync and paid managed services remain deferred.

## Host foundation reconstruction

These are rebuild instructions, not an installation log or proof of a completed
rebuild. Record outcomes and exceptions in #124; retain machine-specific addressing
and credentials privately.

1. Review existing data with the owner before erasing the target SSD. Check disk
   identity/SMART health, then clean-install Ubuntu Server 24.04 LTS `amd64` with
   OpenSSH and a non-root sudo user. Apply supported package updates. Prefer local
   SSD boot over PXE/network boot in firmware.
2. Allocate the intended SSD space to the root filesystem. If the installer leaves
   free extents in LVM, inspect `lsblk -f`, `sudo lvs` and `sudo vgs`, then extend the
   verified root LV and filesystem with `sudo lvextend -r -l +100%FREE <root-lv>`.
   Skip when the VG has no free extents; verify with `df -h /` and `sudo vgs`.
3. Connect Ethernet and reserve its address in the router. Install the owner's SSH
   public key and verify a second SSH session before disabling password/root login.
   Install [Tailscale on Linux](https://tailscale.com/docs/install/linux) and Windows,
   enroll both in the owner's private tailnet, and verify OpenSSH from Windows or WSL
   through Windows Tailscale. Keep access restricted to the owner. Check router
   forwarding/UPnP mappings and effective host/router IPv4 and IPv6 ingress rules;
   confirm administrative access is unavailable from outside the tailnet/LAN.
4. Configure a `/etc/systemd/logind.conf.d/` drop-in with `[Login]` and
   `HandleLidSwitch=ignore`, `HandleLidSwitchExternalPower=ignore`,
   `HandleLidSwitchDocked=ignore` and `IdleAction=ignore`. Mask `sleep.target`,
   `suspend.target`, `hibernate.target` and `hybrid-sleep.target` with
   `sudo systemctl mask`. Reboot to apply and test closed-lid SSH/Tailscale access.
   Check firmware power-restoration options where available; record unsupported
   automatic restart or an untested power-loss path instead of assuming recovery.
5. Follow the supported [Docker Ubuntu apt installation](https://docs.docker.com/engine/install/ubuntu/)
   for Engine and the Compose plugin. Enable Docker and Tailscale at boot with
   `sudo systemctl enable --now docker tailscaled`. After reboot check
   `systemctl is-active docker tailscaled`, `sudo docker info`,
   `sudo docker run --rm hello-world` and `sudo docker compose version`.
   Do not start the repository application/dependency Compose stack here.
6. Install `smartmontools` and `lm-sensors` from Ubuntu packages. Record OS/architecture,
   `uptime`, `free -h`, `lsblk`, `sudo vgs`, `sudo lvs`, SMART health for the verified
   SSD (`sudo smartctl -a <disk>`) and `sensors`. Observe normal headless idle operation
   over a recorded interval and inspect `systemctl --failed` and boot errors with
   `journalctl -b -p err`. A single idle snapshot does not prove sustained stability
   or workload capacity; keep untested acceptance checks open.

The following delivery/runtime/recovery policies govern later slices; they do not
claim those capabilities are configured on the current host.

## Artefact and delivery

One immutable multi-architecture OCI image contains the compiled frontend, BFF/API,
and modular monolith. It runs non-root, contains no environment configuration,
secrets, raw provider data, personal data, dev seed, or copied provider images, and
is identified by commit SHA and content digest rather than `latest`.

GitHub Actions validates pull requests. Trusted `main` builds/scans the same index,
produces SBOM/provenance evidence, and publishes to GHCR. Pull requests receive no
provider/deployment secrets and never publish/deploy. Deployment promotes an already
validated digest through a protected manual `dev` boundary.

Technical sequence:

```text
select digest -> validate private target/secrets/recovery -> serialized migrations
-> replace application -> readiness -> smoke/accessibility -> accept or recover
```

Concurrent deployments are prohibited. A failed new version is not successful merely
because the old one remains healthy. Deployment success is not product acceptance.

## Configuration and secrets

Configuration is injected at runtime; `.env.example` files document local names and
safe defaults. Missing security-critical configuration fails clearly. Private-host
secrets must use a protected runtime source, remain independently rotatable and
least-privileged, and stay out of Git, images, frontend code, URLs, logs, screenshots
and CI artifacts. The concrete source and injection mechanism belong to #43; OCI
Vault and Terraform state are not current-host requirements.

## PostgreSQL, migrations, and recovery

One server hosts separate application and Keycloak databases/roles. Business modules
retain logical table ownership. A dedicated actor runs immutable forward Flyway
migrations before application replacement. Destructive changes use expand/contract
and explicit recovery; application rollback is allowed only while schema compatible.

Back up irreplaceable ratings, identity mapping/configuration, and product editorial
outside the host, encrypted and within the zero-cost constraint. Record environment, time,
PostgreSQL/schema/application version; retain only useful backups; prove isolated
restore after setup and material changes. Catalogue provider data may be resynced,
but personal/identity/editorial state is not assumed disposable.

## Health, observability, privacy, and failure

Liveness reports process viability. Readiness proves supported local-data behaviour
and required local dependencies; IGDB/CDN/telemetry outages do not make the app
unready. Health never reveals topology or secrets. Telemetry uses bounded labels,
replaceable OpenTelemetry-compatible export, minimal retention, and no personal data
or credentials.

Catalogue synchronization is one internal management-port command, never a public
request or scheduled job. PostgreSQL enforces one active run; an abandoned worker is
fenced before a successor can write. Run history is retained in bounded quantity,
independently of current catalogue state.
[ADR-0017](../../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
owns the date interval, in-call paging and partial-failure decisions; the
[backend guide](../../../backend/README.md) owns invocation and migration prerequisites.
Credentials come from backend secret configuration and are absent when disabled.
Automatic scheduling remains deferred.

| Failure                       | Required behaviour                                                                                           |
|-------------------------------|--------------------------------------------------------------------------------------------------------------|
| IGDB unavailable/rate-limited | Continue local reads; sync records failure                                                                   |
| Cover CDN unavailable         | Use product fallback; keep game visible                                                                      |
| No valid catalogue            | `CATALOGUE_NOT_READY`; no request-path provider call                                                         |
| Migration failure             | Do not activate new application                                                                              |
| Readiness/smoke failure       | Keep/redeploy prior compatible image or forward-fix                                                          |
| Backup failure                | Report recoverability failure; do not claim release success                                                  |
| Host loss                     | Rebuild foundation; restore durable state and verify the journey through the recovery procedure owned by #44 |

Standard OCI container images, PostgreSQL logical backups, OpenTelemetry and private
ingress preserve portability. Hosting reconsideration triggers belong to ADR-0019.
