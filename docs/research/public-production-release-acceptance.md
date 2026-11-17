# Public production release acceptance — #276

- **Status:** Point-in-time evidence; public since 2026-11-08 by owner go; owner-accepted and signed 2026-11-08
- **Scope:** Real-host qualification of the netcup production VPS and the public opening of `gameometro.com`
- **Consumer:** Owner acceptance and closure of [#276](https://github.com/rubhern/videogame-platform/issues/276)

This record owns the frozen #276 evidence assessment, not operating policy. The
[release gates](../development/delivery-lifecycle.md#public-production-gates), the
[platform target](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target),
the [runtime guide](../../deploy/public-production/README.md) and the
[operations runbook](../development/operations-runbook.md#private-production-maintenance-plan)
remain canonical. The [#275 candidate acceptance](public-production-candidate-acceptance.md)
and the [backup-storage research](public-production-backup-storage.md) keep their own evidence.
Raw receipts stay in protected owner storage outside Git; the values below are sanitized.

## Release identity

| Identity | Value |
|---|---|
| Source revision | `20311c8241172f33d80298d11d1a8d133d8aba59` (trusted-main build `42392274108`, Security `42392274161`) |
| Application and migration | `ghcr.io/rubhern/videogame-platform@sha256:a67503fc495fa745e9123bfa48cac91647629c352fa26c87b2dc360abf891aea` |
| Keycloak | `…-keycloak@sha256:bdd2cc23df12ec3c80b3e86cc54afecdad7b6a9e5228af15b8d4f3ee42c0e3fd` |
| Caddy | `…-caddy@sha256:293e231f1f269c1b223fd88a3186e415d0e5f5d859ce8df799b28a62a8a6baf5` |
| Catalogue localizer | `…-catalogue-localizer@sha256:3b110ba7008d261c8116f6ec0582b6d42386a7deed0385b5c23f04ee70fc34bb` |
| Operational fingerprint | `9c32c06b6e04fa561f9c7e24da4e7b3704c61d384853242e11ff85d1b1e063c2` |
| Upgrade plan | `aa7ab38c1cd56696ab2974a6e7507eb3416518e658c12ae2d3c82d8e81022b44`, from `bdc483d`; images only, Flyway `20261106.130000` (rank 39), no realm delta |
| Pre-upgrade pair | `eb9cf227142a4f7d97c2ffa05dcb2dc2`, captured fenced 2026-11-07 18:31 UTC |
| Public edge | `62.83.73.32`, A records only, Let's Encrypt for `gameometro.com` and `auth.gameometro.com` |

Each host step ran under a disposable, expiring launcher with exact sudo argv. The plan hash was
pinned once and never replaced. The previous bundle, pair and receipts are preserved, and no schema
rollback was used.

## Owner authorizations

| Date (UTC) | Decision |
|---|---|
| 2026-10-25 | Lite 3, budget ceiling and scoped uncapped-OVHcloud exception ([ADR-0033](../decisions/0033-select-lite-3-and-immutable-offhost-storage.md)) |
| 2026-11-07 | End-to-end #276 execution; existing production S3 identities reused; [single-account L8 risk](../decisions/0033-select-lite-3-and-immutable-offhost-storage.md) accepted; phase 5 with registration closed |
| 2026-11-07 | DNS A records changed by the owner after the upgrade; netcup firewall opens 80/443 only; SSH stays closed to the Internet, Tailscale only |
| 2026-11-08 | Scheduled backup activation, then public registration "when everything is ready" |

## Gate matrix

| Gate | Real-host evidence | State |
|---|---|---|
| Source/release | Exact identities above; trusted-main checks green; staged bundle verified; `--verify` showed 10 services with none degraded | Passed |
| HTTPS/isolation | External check from outside the VPS: certificates on both names (89 days), 308 redirects, catalogue 200, personal route 401, issuer contract, closed admin console and master realm, management and database ports closed on IPv4, SSH not reachable | Passed on IPv4. IPv6 by host evidence: the only public IPv6 listener is SSH, and the netcup implicit incoming drop covers every protocol and address family. No external IPv6 probe was available |
| Abuse | Per-source search zone returns 429 with and without forged `X-Forwarded-For`/`X-Real-Ip`; the source address is preserved through Docker NAT | Passed |
| Accounts/privacy | Registration closed during qualification (Keycloak "registro no está permitido"), then opened with the reviewed actor, which changes only `registrationAllowed`; owner verified real email delivery and password recovery | Passed |
| Capacity | 9 requests/s for 180 s on one source, mixing catalogue, rankings, game and shell routes: 1,571 × 200 and 49 × 429 (per-source quota). p95 from WSL including network: 243–651 ms. Host: 8 vCPU about 9% busy, steal 0, 81% memory available, 4% disk used, no swap, no OOM | Passed at this load; aggregate overload covered only by CI scenarios |
| Backup/recovery | Fenced pair `eb9cf227…` was pulled through the restricted reader, published to `gameometro-prod-backups` and fetched by the recovery reader. It was then restored in isolation from the cloud copy in 39 s, and the upgrade consumed those receipts. The scheduled cycle is active: supervised run captured 2026-11-08 09:22 and uploaded 09:24:48 UTC, timers at 02:30 and 14:30 UTC, reconcile every 10 min | Passed. Cloud-only reconstruction ran 2026-11-08 on the owner's local engine. Newest pair `19bf415d…` (10:37 UTC) was found by the observer and fetched by the recovery reader, and the empty ledger was rebuilt from both copies. The tool then restored (37 s), replayed, reopened and recorded readiness: 112 s from restore to readiness, about 0.93 GB of databases. Published as `RESTORE_EVIDENCE` |
| Independent supervision | `backup-watch` enabled. With adapter `0.2.2` the observer verified the newest pair and expiry planned 0 candidates. Telegram delivery confirmed by the owner | Passed; `restore-missing` cleared by the reconstruction evidence |
| Deletion durability (L8) | Two-copy ledger and archive on separate OVHcloud projects and regions, checked against the real provider with synthetic data. The empty production ledger was recorded in both locked copies on 2026-11-08 and rebuilt with `ledger-fetch` | Passed; production cleanup not activated |
| Observability/operation | Private Grafana and Telegram routing; ingress `--revert` and registration closing available | Passed; drills below |

## Failure drills — 2026-11-08

| Drill | Result |
|---|---|
| Host reboot while a scheduled capture held the fence | Shutdown and boot took about 4.5 minutes in total. Reconcile ran two minutes after boot and reopened the product with no owner action. The journal shows `idle` with `capture-failed`, and no owner-required state |
| Uploader refused by the provider | The capture completed, and the product reopened after about 2.5 minutes. The upload failed, and a retry was scheduled at the 15-minute backoff. After the credential was restored, reconcile's second attempt uploaded the same pair at 10:57:29 UTC, and the journal returned to `idle` |

## Actual recurring cost

From the owner's invoices, VAT included:

| Item | Invoice | Monthly equivalent |
|---|---|---|
| netcup VPS Lite 3 with IPv4 (IPv6 free) | `nc-5758620`: EUR 33.88 for two months in advance | EUR 16.94 |
| `gameometro.com` registration, DNS zone | OVHcloud `ES4489776`: EUR 9.67 per year | EUR 0.81 |
| Zimbra Starter mailbox | Same invoice: free for the first year (gift), EUR 4.36 per year from 2027-10-15 | EUR 0.00, then EUR 0.36 |
| OVHcloud Object Storage | Pay as you go; about 0.5 GB retained on 2026-11-08 | Cents; bounded by the D7 ceiling |
| Mailjet | Free plan | EUR 0.00 |

Total: about EUR 17.75 per month now and EUR 18.11 from October 2027, within the EUR 25 ceiling.
Object storage stays uncapped under the owner's accepted exception.

## Defects found on the real host

Each defect was fixed with a regression test and, where the host was affected, applied there:

- Maintenance `expose-pair` compared the file SHA with the rendered-configuration hash (launcher only).
- The S3 inventory refused botocore's echoed `EncodingType=url` (adapter `0.2.1`).
- The scheduled units could not start `gpg-agent` under `ProtectSystem=strict` (keyring write path).
- OVHcloud answers `NoSuchObjectLockConfiguration` for never-held versions (adapter `0.2.2`).

## Remaining before closure

None. The owner accepted the IPv6 host-side evidence on 2026-11-08: neither owner network has IPv6,
Docker publishes 80/443 on the IPv4 address only, the only public IPv6 listener is SSH, and the
netcup implicit incoming drop covers every protocol and family.

Cloud-only reconstruction and the failure drills are done. Reconstruction repeats about every 30 days
(owner policy), prompted by the observer's Telegram message.

## Limitations proposed for acceptance

- Best effort, single VPS, no high availability.
- About 2.5 minutes without service at each scheduled capture, twice a day, with no maintenance page.
- Ledger and archive copies share one OVHcloud account (accepted L8 residual risk).
- IPv4-only serving until IPv6 is qualified.
- Application admission limits remain experimental values.

## Owner acceptance record

Prepared and **signed** 2026-11-08. Signing accepts the public release at the identities above,
the evidence states in the gate matrix and the limitations listed. No item remains open.

| Acceptance item | Owner decision |
|---|---|
| Release identity and traceability | Accepted |
| Gate evidence as recorded | Accepted |
| Limitations above | Accepted |
| IPv6 by host evidence | Accepted |
