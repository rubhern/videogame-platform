# Private dev runtime and deployment

This directory is the reviewed operator entry point for the owner-managed private
`dev` host (`vgpdev`). The
[platform and delivery design](../../docs/architecture/deployment/mvp-platform-and-delivery.md)
owns the policy; the files here own the executable details; the
[operations runbook](../../docs/development/operations-runbook.md) records which
procedures have been proven on the host, their evidence boundary, and day-two
operations (synchronization on `dev`, incident handling).

Local use of the same provisioned metrics stack is documented in
[local setup](../../docs/development/local-setup.md#local-metrics-and-logs).
`compose.observability.yaml` owns shared metrics service definitions; this directory's
`compose.yaml` and the root local overlay own environment-specific wiring.

## Boundaries

- `compose.yaml` defines digest-pinned PostgreSQL, a locally optimized Keycloak image
  built from a digest-pinned upstream, the Mailpit recovery mail sink, the bounded
  OpenTelemetry collector, Prometheus and Grafana, the
  digest-selected application, a one-shot migration actor and a one-shot browser
  smoke runner. PostgreSQL, Keycloak, Mailpit, observability and catalogue-localizer start without an explicit
  profile or service selection; the application runtime receives `videogame_app`
  credentials and cannot migrate, and the migration actor receives only
  `videogame_app_migrator` credentials on the internal data network.
- `bin/deploy-private-dev` runs only when the owner invokes it on a host named
  `vgpdev`, rejects tags and other mutable references, holds one host lock, and
  requires both an immutable GHCR digest and the full source revision expected to
  have produced it. The prior application is untouched until the migration actor
  succeeds; every readiness and smoke check is tied to the newly created container.
- Deployment evidence is an atomically updated JSON record outside the checkout
  (timestamps, target, initiator, source revision, image, application and migration
  versions, candidate container ID, completed smoke checks, phase, outcome), with no
  credentials or personal data.

## Owner connection from WSL

The owner's WSL environment defines `videogame-platform-lenovo` in `~/.ssh/config`.
Use that alias for the existing key-authenticated private connection; no previously
opened SSH session is required. Do not copy the configuration, private addressing,
keys or credentials into Git or operational evidence.

Check connectivity and the existing Docker permissions without changing the host:

```bash
ssh -o BatchMode=yes videogame-platform-lenovo 'hostname'
ssh -o BatchMode=yes videogame-platform-lenovo \
  'docker version --format "{{.Server.Version}}"; docker compose version; docker ps --format "{{.Names}}\t{{.Status}}"'
```

Docker and Compose are available without `sudo` through this owner connection.
`BatchMode=yes` refuses interactive credential prompts; a failed connection is a
connectivity/authentication problem, not a reason to print or replace secrets.
This owner connection is separate from the restricted Actions promotion account.
Keep the existing private ingress and internal-service boundaries unchanged.

## Host foundation

These are rebuild instructions for the owner-managed host, not an installation log.
Record outcomes and exceptions in the issue that tracks the host; keep addressing and
credentials private.

1. Review existing data with the owner before erasing the target SSD. Check disk
   identity/SMART health, then clean-install Ubuntu Server 24.04 LTS `amd64` with
   OpenSSH and a non-root sudo user; apply package updates and prefer local SSD boot
   in firmware.
2. Allocate the SSD to the root filesystem. If the installer leaves free LVM extents
   (`lsblk -f`, `sudo lvs`, `sudo vgs`), extend the root LV with
   `sudo lvextend -r -l +100%FREE <root-lv>` and verify with `df -h /`.
3. Connect Ethernet with a router DHCP reservation. Install the owner's SSH public
   key, verify a second session, then disable password/root login. Install
   [Tailscale](https://tailscale.com/docs/install/linux) on the host and on Windows
   (not WSL), enroll both in the owner-only tailnet and verify SSH through it. Confirm
   router forwarding/UPnP is off and that administrative access is unreachable from
   outside the tailnet/LAN over IPv4 and IPv6.
4. Keep a closed laptop lid running: a `/etc/systemd/logind.conf.d/` drop-in with
   `HandleLidSwitch=ignore`, `HandleLidSwitchExternalPower=ignore`,
   `HandleLidSwitchDocked=ignore`, `IdleAction=ignore`, and
   `sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`.
   Reboot and test closed-lid access. Record firmware power-restoration support
   instead of assuming recovery after power loss.
5. Install Docker Engine and the Compose plugin from the
   [official apt repository](https://docs.docker.com/engine/install/ubuntu/), then
   `sudo systemctl enable --now docker tailscaled`. After a reboot check
   `systemctl is-active docker tailscaled`, `sudo docker info` and
   `sudo docker compose version`. Do not start the repository stack yet.
6. Install `smartmontools` and `lm-sensors`; record OS/architecture, `free -h`,
   `lsblk`, LVM state, SMART health and `sensors`, and inspect `systemctl --failed`
   and `journalctl -b -p err`. A single idle snapshot does not prove workload
   capacity.

## One-time private host preparation

Do not run these commands from a workstation and do not apply them to `vgpdev`
without the owner's explicit host-change decision. Replace `<operator>` and
`<checkout>` only after verifying them on the host. The host needs Bash, Python 3,
Docker Engine and the Compose plugin; deployment smoke JavaScript runs inside the
dedicated Playwright container, so Node.js is not a host prerequisite.

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

2. Validate the reviewed topology without starting or replacing services, and run
   the independent metrics path check (a disposable metrics stack and bounded synthetic
   OTLP sender; no application is deployed):

   ```bash
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env
   bash scripts/validate-private-dev-runtime.sh --telemetry-smoke
   ```

   The application placeholders are accepted only by the static validator; the
   deployment command rejects them.

3. Install the immutable model outside the checkout using the
   [model procedure](../../backend/README.md#catalogue-translation-runtime-and-models)
   and set its directory in protected runtime.env. Start only the runtime dependencies;
   do not enable the application or deployment profiles here. Existing hosts use the
   [targeted localization migration](#one-time-migration-to-the-standard-runtime)
   instead of repeating this full initial startup.

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml pull postgres telemetry prometheus grafana alloy loki
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml build --pull keycloak catalogue-localizer
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml up --detach --wait postgres keycloak telemetry prometheus grafana alloy loki catalogue-localizer
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
   restart persistence, loopback-only listeners, router forwarding/UPnP state and
   unsolicited public IPv4/IPv6 reachability. None of those checks is implied by
   repository validation.

5. After Keycloak and its private HTTPS route are healthy, provision the dedicated
   deployment-smoke account from the protected files:

   ```bash
   deploy/private-dev/bin/provision-oidc-smoke-user \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

   The command is idempotent: it declares the admin-only marker in the realm User
   Profile when missing, then creates the marked, enabled account when absent or
   rotates its protected password. It refuses an unmarked existing username, any
   profile other than its fixed synthetic values, direct client roles, or group
   membership, and never places a password or token in arguments, environment
   metadata, logs, realm imports, or Git. If an earlier attempt left an unmarked
   `vgp-deployment-smoke` account, confirm through the private Admin Console that it
   is the failed non-personal account, delete that one account, and rerun the command;
   never add the marker to adopt it.

## Gameómetro identity theme

Post-MVP (#162), the Keycloak image packages `docker/keycloak/themes/gameometro`
and copies the canonical frontend brand, fonts and night artwork at build time.
Both Compose build contexts are the repository root. The
[frontend design guidelines](../../docs/development/frontend-design.md) own its visual
rules; inherited Keycloak templates retain credential handling and account-flow logic.

After owner review, build/recreate the Keycloak service from the reviewed checkout.
Startup import skips an existing realm: in the private Admin Console, select
`gameometro` as its Login theme, `Gameómetro` as its display name, set the
supported/default locale to Spanish and enable
Forgot password in Login settings. Do not delete identity data or re-import the realm
to apply a theme. Recreate the service to clear its theme cache after changing assets.
To roll back, select the previous built-in login theme and restore the previous realm
settings; no product schema migration is involved.

Password-recovery email goes through the [Mailpit mail sink](#password-recovery-mail-sink).

## Password-recovery mail sink

Post-MVP (#273), Keycloak's native reset-credentials flow emails its single-use link
to Mailpit, which captures every message and delivers nothing outward. It remains the
default transport; real delivery is the separate opt-in described in
[identity policy and real account mail](#identity-policy-and-real-account-mail). The
[platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#private-dev-runtime-boundary)
owns the boundary; `compose.mail.yaml`, `compose.yaml` and the realm import own the
mechanics. No credential, provider or paid service is involved, and real delivery to
users stays with the [public release gates](../../docs/development/delivery-lifecycle.md#public-production-gates).

### Reviewed rollout

Run only after owner review, merge and separate approval to operate on `vgpdev`. The
change adds one service and recreates only Keycloak, which is briefly unavailable for
sign-in; the application, its BFF sessions, PostgreSQL and every other service keep
their containers. The realm, its accounts and their credentials are not re-imported.

1. Install the reviewed merged revision and hold the deployment lock exactly as in
   [step 1 of the localization migration](#one-time-migration-to-the-standard-runtime),
   then validate and record the runtime inventory:

   ```bash
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env
   compose=(docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file /opt/videogame-platform/deploy/private-dev/compose.yaml --profile application)
   "${compose[@]}" ps --all --format json \
     >/var/lib/videogame-platform/dev/mail-runtime-before.json
   ```

2. Start Mailpit, then rebuild Keycloak (its image gains the Spanish recovery email
   copy) and recreate only that service so it joins the mail network:

   ```bash
   "${compose[@]}" pull mailpit
   "${compose[@]}" up --detach --no-deps --wait mailpit
   "${compose[@]}" build --pull keycloak
   "${compose[@]}" up --detach --no-deps --force-recreate --wait --wait-timeout 300 keycloak
   ```

3. Apply the realm import's SMTP target, sender, `gameometro` email theme and
   recovery switch to the existing realm. The command authenticates through the
   private HTTPS origin with the protected admin secret, changes only those settings,
   rereads them and prints no credential. It is idempotent; `--check` only reports:

   ```bash
   python3 deploy/private-dev/bin/configure-keycloak-mail \
     --private-dev-env-file /etc/videogame-platform/dev/runtime.env
   python3 deploy/private-dev/bin/configure-keycloak-mail \
     --private-dev-env-file /etc/videogame-platform/dev/runtime.env --check
   "${compose[@]}" ps --all --format json \
     >/var/lib/videogame-platform/dev/mail-runtime-after.json
   ```

   Compare both inventories: only Keycloak may have a new container ID and Mailpit is
   the only new service. Then perform the
   [applied-runtime acknowledgement](#acknowledge-the-applied-runtime-contract) and
   release the lock as in the localization migration's step 4.

### Owner access and acceptance

Mailpit's UI binds only to host IPv4 loopback and has no Tailscale Serve route or
login of its own: the owner SSH session is its access control. From WSL, open the
tunnel over the existing [owner connection](#owner-connection-from-wsl), adding the
Grafana forward when both are needed:

```bash
ssh -N -L 127.0.0.1:8025:127.0.0.1:8025 videogame-platform-lenovo
```

Browse to `http://127.0.0.1:8025` from WSL or Windows (WSL forwards loopback ports to
Windows by default). Close the tunnel with Ctrl+C when finished. SMTP (1025) and POP3
have no host listener; only Keycloak shares Mailpit's network.

Accept the rollout with real evidence recorded in #273:

1. `bash scripts/validate-private-dev-runtime.sh --env-file <runtime.env> --live`
   passes, including Mailpit health and its loopback-only UI listener.
2. With a dedicated synthetic account created through normal registration (never the
   deployment-smoke account or a personal one), request a reset from the private
   sign-in, open the captured email through the tunnel, complete the new password and
   sign in again. An unknown username shows the same message and captures nothing.
3. After a host reboot, Mailpit is healthy again under its restart policy, previous
   messages are gone by design, and a new request is captured.

### Diagnosis, cleanup and rollback

```bash
"${compose[@]}" ps mailpit keycloak
"${compose[@]}" logs --since 30m mailpit keycloak | grep -E 'smtpd|KC-SERVICES0026'
"${compose[@]}" exec mailpit /mailpit readyz
python3 deploy/private-dev/bin/configure-keycloak-mail \
  --private-dev-env-file /etc/videogame-platform/dev/runtime.env --check
```

`KC-SERVICES0026: Failed to send password reset email` with no Mailpit `smtpd` entry
means Keycloak cannot reach the sink: check that Mailpit is healthy and Keycloak was
recreated onto the `mail` network. While sending fails, an existing username gets
Keycloak's send-error message and an unknown one the usual confirmation, so restore
Mailpit promptly. Mailpit logs the recipient and subject of each message, within the
bounded local Docker logs; it never logs the link.

Messages hold live reset links until they expire. Mailpit keeps at most 100 for 24
hours in container memory; delete them sooner with **Delete all** in the UI,
`curl -X DELETE http://127.0.0.1:8025/api/v1/messages` through the tunnel, or
`"${compose[@]}" restart mailpit`.

To withdraw recovery, first turn off **Forgot password** in the Admin Console's realm
Login settings, so the entry point disappears instead of failing per account, then
stop Mailpit. A full rollback also clears the realm Email host and recreates Keycloak
from the previous reviewed checkout. No account, session or product data changes in
either direction.

## Identity policy and real account mail

Post-MVP (#274), the existing `videogame-platform` realm can apply the reviewed
production identity behaviour and send real account mail through the approved
transactional provider (Mailjet Free). The
[production realm import](../public-production/keycloak/import/gameometro-realm.json)
is the single policy source; the
[platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#private-dev-runtime-boundary)
owns the boundary; `bin/configure-keycloak-identity` and `bin/configure-keycloak-mail`
own the mechanics, and `scripts/validate-private-dev-identity.sh` proves both against a
disposable copy of the realm. No container, image or Compose service changes.

The identity command applies, through the Admin API, the production settings for
email sign-in, the email-only user profile, the mandatory 18+ registration
declaration, email verification, the password policy, temporary brute-force lockout,
session and link lifetimes, the Spanish messages, no self-deletion or email change, and
no stored or logged user events. It never touches the realm name, the issuer, the BFF
client, `sslRequired` or any account's ID, credentials or address, so ratings stay
attached. Startup import still ignores the existing realm, and local development and
CI keep the shared import's policy.

Effects on existing accounts, all reported by `--check` before anything changes:

- Every account signs in with its email. Username sign-in keeps working until a
  profile update through the Admin Console or account console makes Keycloak store
  the email as the username; verification, recovery and sign-in do not.
- Names are no longer requested or shown. Stored values are kept, not deleted, and
  reappear on rollback.
- Existing passwords keep working; the policy applies when a password is set.
- Unverified accounts with a reserved address (`.test`, `.example`, `.invalid`,
  `.localhost`, `example.com/net/org`), which can never receive mail, are marked
  verified, and the snapshot records their IDs. Any other unverified account receives
  a verification email at its next sign-in: list those first.
- The deployment-smoke account signs in by `vgp-deployment-smoke@example.invalid`.
- Keycloak sessions end after 8 hours instead of 10, matching the BFF limit.
- Review any account the check reports without an email before the rollout; the
  validation gate does not exercise that case.
- Failed sign-ins no longer write the username and client IP to Keycloak's log; the
  registration metrics come from a global listener and continue.

### Protected relay inputs

Do this once on `vgpdev`, before the rollout. The Mailjet SMTP username is the API
key and the password its secret key; never paste them into a chat, an issue, a
command argument or shell history. Rerun the idempotent preparation, which keeps
every existing secret and adds the two optional files empty:

```bash
sudo /opt/videogame-platform/deploy/private-dev/bin/prepare-secrets \
  /etc/videogame-platform/dev/secrets vgp-runtime
for name in keycloak-smtp-username keycloak-smtp-password; do
  sudo bash -c 'read -rsp "$1: " value; echo; printf "%s\n" "$value" \
    | install -m 0644 -o root -g vgp-runtime /dev/stdin "/etc/videogame-platform/dev/secrets/$1"' _ "$name"
done
sudoedit /etc/videogame-platform/dev/runtime.env
```

In runtime.env add the four `PRIVATE_DEV_SMTP_RELAY_*` entries exactly as in
[`runtime.env.example`](runtime.env.example). No container reads these files: the mail
command passes them once to Keycloak over the private HTTPS origin, and Keycloak keeps
the password in its database, and therefore in the encrypted backups, as production
does. Neither command prints a credential. To rotate the credential, replace both files
the same way and repeat rollout step 5, which always rewrites the relay settings.

### Reviewed rollout

Run only after owner review, merge and separate approval to operate on `vgpdev`.
Keycloak stays up and no container is recreated; the change takes effect on the next
request.

1. Take and verify an [encrypted backup](#encrypted-backup-with-integrity-and-retention):
   it is the last-resort recovery of the Keycloak database. Then install the reviewed
   merged revision and hold the deployment lock exactly as in
   [step 1 of the localization migration](#one-time-migration-to-the-standard-runtime),
   and create the snapshot directory:

   ```bash
   install -d -m 0700 -o root -g root /var/lib/videogame-platform/dev/identity-snapshots
   identity=(python3 deploy/private-dev/bin/configure-keycloak-identity \
     --private-dev-env-file /etc/videogame-platform/dev/runtime.env)
   account_mail=(python3 deploy/private-dev/bin/configure-keycloak-mail \
     --private-dev-env-file /etc/videogame-platform/dev/runtime.env)
   ```

2. Review the drift and the account inventory. `--list-unverified` prints, on this
   terminal only, the username and address of each unverified account with a
   deliverable address:

   ```bash
   "${identity[@]}" --check || true
   "${identity[@]}" --list-unverified
   ```

   Each listed address receives a verification email at that account's next sign-in.
   Keep an address only if the owner controls it. For one the owner does not control,
   decide in the Admin Console before continuing: disable the account, or delete it if
   it is a throwaway synthetic account. Do not mark an address verified that nobody
   verified.

3. Apply the policy. The command first writes a `0600` snapshot of every setting it
   changes, then applies and rereads them; a repeated run changes nothing:

   ```bash
   "${identity[@]}" --snapshot-dir /var/lib/videogame-platform/dev/identity-snapshots
   "${identity[@]}" --check
   ```

4. Switch the deployment-smoke account to its email identifier and reprovision it.
   The file is not secret but stays with the other protected inputs:

   ```bash
   printf '%s\n' vgp-deployment-smoke@example.invalid \
     | install -m 0644 -o root -g vgp-runtime /dev/stdin \
       /etc/videogame-platform/dev/secrets/oidc-smoke-username
   python3 deploy/private-dev/bin/provision-oidc-smoke-user \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

5. Select the real relay and check it. Selecting it is the explicit opt-in; Mailpit
   keeps running for the other transport:

   ```bash
   "${account_mail[@]}" --transport relay
   "${account_mail[@]}" --transport relay --check
   ```

6. Run the [manual acceptance](#manual-acceptance-with-controlled-addresses), then
   release the lock and leave the root session as in step 4 of the localization
   migration. The installed contract changes with this revision, so acknowledge it
   with the [applied-runtime acknowledgement](#acknowledge-the-applied-runtime-contract)
   before releasing the lock. The next application promotion runs the deployment
   smoke with the email identifier.

### Manual acceptance with controlled addresses

Send real mail only to mailboxes the owner controls, and only as many as the check
needs: the journey below sends two messages against the 200-per-day free quota.
Plus addressing (`name+tag@`), where the mailbox provider supports it, gives distinct
accounts without new mailboxes. Automated tests never use the relay. Record the
outcome in #274 without addresses, message IDs or headers that identify a person.

1. **Registration.** In a private browser window open the private application, choose
   to sign in and then to register. Only the email and the 18+ declaration are asked
   for; submitting without the declaration is refused and creates nothing.
2. **Verification.** The verification email arrives from
   `no-reply@mail.gameometro.com` with `Reply-To: hola@gameometro.com`; check that it
   carries no provider branding and that the mailbox's original-message view reports
   SPF, DKIM and DMARC `pass`. Open the link within five minutes, choose a password
   (one under 12 characters is refused) and confirm that the application signs you in.
3. **Sign-in and logout.** Sign out, which also ends the Keycloak session, and sign in
   again with the email. An existing account still signs in with its email and old
   password, and one wrong password shows the same message as an unknown account.
4. **Recovery.** Use *¿Has olvidado tu contraseña?* with the new account's email: the
   confirmation is the same as for an unknown address, the email arrives through the
   relay, and its link sets a new password. The old password then fails.
5. **Reply.** Reply to one message and confirm the reply reaches the contact mailbox.
6. **Ratings.** With the new account, create, edit and delete a rating on a game page
   and check *Mis puntuaciones* after each step; an existing account still sees its
   earlier ratings, and neither account sees the other's.
7. **Revocation.** Sign the new account in on two browsers, then follow the
   [revocation procedure](../../docs/development/operations-runbook.md#personal-data-requests-and-account-revocation)
   with it: both sessions become anonymous on their next request, a repeat answers
   zero and a new sign-in is refused. Finish with its deletion steps, then record the
   run in #274 as the runbook asks; it is the first revocation operated on a host.

Do not test lockout with the owner's real account: five failures lock it for at least
a minute. Mailjet's statistics show sent, bounced and blocked messages.

### Rollback

Each part reverts independently. Neither direction changes an account ID, so ratings
stay attached.

- **Mail only.** Select Mailpit again; this removes the relay host and credentials
  from the realm. Then empty the secret files, and revoke the key in Mailjet if it may
  have been exposed:

  ```bash
  "${account_mail[@]}"
  "${account_mail[@]}" --check
  sudo truncate -s 0 /etc/videogame-platform/dev/secrets/keycloak-smtp-username \
    /etc/videogame-platform/dev/secrets/keycloak-smtp-password
  ```

- **Identity policy.** Usually together with the mail rollback, restore the
  **earliest** snapshot of the rollout, which reverts every managed setting, the user profile, the
  registration flow, the messages and the reserved accounts' verified flag:

  ```bash
  "${identity[@]}" --restore /var/lib/videogame-platform/dev/identity-snapshots/<earliest>.json
  ```

  Set `oidc-smoke-username` back to `vgp-deployment-smoke` and reprovision. If the
  smoke account's stored username became its email meanwhile, reprovisioning reports
  a conflict: keep the email identifier instead. Accounts registered while the policy
  applied, and any whose stored username became its email, keep signing in with the
  email as their username.
- **Last resort.** Restore the Keycloak database from the backup taken in step 1,
  following [Backup, restore, rollback and host-loss recovery](#backup-restore-rollback-and-host-loss-recovery).
  This loses every identity change made after it, including new accounts.

Diagnosis: `KC-SERVICES0026` or `Failed to send email` in Keycloak's log with the
relay selected means the relay refused or was unreachable. Check the secret files'
values in Mailjet, outbound port 587 from the host and, if it is blocked, the
documented fallback `PRIVATE_DEV_SMTP_RELAY_PORT=2525` followed by step 5 again.
Without event logging, read lockouts in the Admin Console under the user's details.

## Catalogue operator role

Post-MVP (#247, implemented; awaiting owner review): catalogue administration answers only a
session signed in with the BFF client's `catalogue-operator` role
([ADR-0028](../../docs/decisions/0028-authorize-catalogue-operators-with-a-bff-client-role.md)).
The realm import declares the role and its ID-token mapper, but startup import never changes
the existing realm, so `bin/configure-keycloak-operator` aligns them exactly as that
import declares them; the production realm test proves the production import matches. It is additive: accounts, credentials, other clients and the
identity policy are untouched, so it needs no snapshot. Without the role nobody can read the
administration API; the application fails closed. Since #248 (implemented; awaiting owner
review) the same role also lets its session request a bounded catalogue synchronization, so
a grant authorizes provider acquisition runs as well as reads.

Run it only after owner review, merge and separate approval to operate on `vgpdev`, from the
installed merged revision, as root:

```bash
operator=(python3 deploy/private-dev/bin/configure-keycloak-operator \
  --private-dev-env-file /etc/videogame-platform/dev/runtime.env)
"${operator[@]}" --check || true
"${operator[@]}"
"${operator[@]}" --check
"${operator[@]}" --grant 'owner-account-username'
"${operator[@]}" --list-holders
```

The username is the account's exact Keycloak username (its email under the email
identifier policy). A grant applies from that account's next sign-in, so sign out and in
again, then open **Mi cuenta → Panel de administrador**. `--revoke` removes the role from
new sessions only; to end access at once, also revoke the account's sessions with the
[revocation procedure](../../docs/development/operations-runbook.md#personal-data-requests-and-account-revocation). Locally, run the same command with
`--origin http://localhost:8180 --admin-username <local admin> --admin-password-file /dev/stdin`
against an existing local realm; a freshly imported local realm already declares the role.

## Personal-data requests

Post-MVP (#274). The
[operations runbook](../../docs/development/operations-runbook.md#personal-data-requests-and-account-revocation)
owns the owner procedure: verifying the requester, export, erasure, retry, restriction
and the restore replay. `bin/personal-data` and `bin/personal-data-ledger` own the
mechanics, and `scripts/validate-personal-data.sh` proves them. No container, port or
route changes for the live project.

The ledger driver runs on the owner's workstation; each step reaches the host over the
[owner connection](#owner-connection-from-wsl) and runs as root from the installed
checkout, which reads the protected admin secret and reaches PostgreSQL and the
application through `docker exec`. The ledger has two copies, both private: the working
copy on the workstation and one on storage independent of it, such as the owner's
external drive that already holds the off-host backup copies and the backup key
material. `erase` and `retry` refuse to start unless both are present:

```bash
ledger=~/vgp-admin/private-dev/deletion-ledger.jsonl                 # 0700 directory, never on vgpdev
ledger_copy=<independent-storage>/vgp-admin/private-dev/deletion-ledger.jsonl   # 0700 directory
host='ssh -o BatchMode=yes videogame-platform-lenovo sudo -n /opt/videogame-platform/deploy/private-dev/bin/personal-data --private-dev-env-file /etc/videogame-platform/dev/runtime.env'
pd=(deploy/private-dev/bin/personal-data-ledger --ledger "$ledger" --ledger-copy "$ledger_copy" --host-command "$host")
"${pd[@]}" status
```

Keep the private-dev ledger separate from any future production ledger. The host command
refuses to run anywhere but `vgpdev`.

### Manual validation with synthetic accounts

Run only after review, merge, installation of the reviewed checkout with its
[contract acknowledgement](#acknowledge-the-applied-runtime-contract) and separate
approval; act **only on accounts created for this check**. Real accounts, the
deployment-smoke account and the owner's own account are never targets, and nothing
below deploys or changes configuration.

1. Create the two ledger directories (`install -d -m 0700 …`) on the workstation and on
   the independent storage.
2. In the Admin Console, create two accounts with reserved, undeliverable addresses
   (for example `pd-a@example.invalid` and `pd-b@example.invalid`), email verified, and
   a password under **Credentials**. Record their Keycloak user IDs and check each
   address again before every command below.
3. Sign account A in on two browsers and account B on a third; rate one game with each
   and check *Mis puntuaciones*.
4. Take and verify an [encrypted backup](#encrypted-backup-with-integrity-and-retention):
   it now holds both accounts and ratings.
5. Export A with `"${pd[@]}" export --request PD-CHECK-1 --keycloak-user-id <A>
   --output ~/vgp-admin/private-dev/export-PD-CHECK-1.json`. Confirm it holds only A's
   address, sessions (times and IP only) and rating, then delete the file.
6. Erase A with `"${pd[@]}" erase --request PD-CHECK-2 --keycloak-user-id <A>`. Both of
   A's browsers become anonymous on their next request, B's stays signed in, A can no
   longer sign in, and `status` shows the entry complete in both copies (`cmp` them).
   Repeat the same `erase`: it changes nothing. Run it once without `--ledger-copy`: it
   is refused before anything changes.
7. Restore the step 4 backup into the isolated project **without** a ledger, as in
   [isolated restore](#isolated-restore), and confirm that A came back:

   ```bash
   rehearsal=(docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml --file deploy/private-dev/compose.restore-rehearsal.yaml \
     --project-name vgp-restore-rehearsal)
   "${rehearsal[@]}" exec -T postgres gosu postgres psql -d videogame_keycloak -tAc \
     "SELECT count(*) FROM user_entity WHERE id = '<A>'"   # 1
   ```

8. Start only that project's Keycloak and replay from the workstation; A is gone again
   (the same query answers `0`), B and its rating remain:

   ```bash
   "${rehearsal[@]}" up --detach --no-deps --wait --wait-timeout 300 keycloak
   rehearsal_host="$host --project vgp-restore-rehearsal --keycloak-origin http://127.0.0.1:8190"
   deploy/private-dev/bin/personal-data-ledger --ledger "$ledger" --ledger-copy "$ledger_copy" \
     --host-command "$rehearsal_host" replay --receipt ~/vgp-admin/private-dev/replay-rehearsal-$(date +%F).json
   ```

9. Repeat step 7 **with** a [ledger copy on the host](#ledger-copy-for-a-restore)
   (`--deletion-ledger`): the restore replays by itself, its evidence contains the
   receipt, and A is absent. Tear the rehearsal down with
   `"${rehearsal[@]}" down --volumes` and remove the host copy.
10. Check that host-loss recovery refuses the live host: run the
    [host-loss command](#host-loss-recovery) with the step 4 backup. It must answer that
    the host holds containers or volumes of the project, and
    `/var/lib/videogame-platform/dev/deployment-evidence/DELETION-REPLAY-PENDING` must
    not exist afterwards.
11. Erase B the same way, and record the outcome in #274 without addresses or IDs.

Rollback: nothing to undo in the live runtime. The commands change no configuration, an
erased synthetic account is meant to stay erased, and the rehearsal project is removed in
step 9. Keep the private-dev ledger for 35 days like any other.

## Metrics dashboards

The [observability guide](../../docs/development/observability.md#private-dev-dashboards)
owns dashboard interpretation and gaps; Compose, Collector, Prometheus and Grafana
files here own all configuration. No dashboard UI setup is required.

For an existing host, rerun `bin/prepare-secrets` with the protected directory and
runtime group above; it preserves populated files and creates separate Grafana admin
and database-reader secrets. Roll out the reviewed application migrations before
starting the new SQL panels, then provision the view-only role from the checkout:

```bash
bash scripts/provision-grafana-reader.sh --private-dev /etc/videogame-platform/dev/runtime.env
```

Validate, pull and start `telemetry prometheus grafana` using the dependency commands
above, alongside the reviewed application image that supplies the new instrumentation.
Recreate those metrics services with `--no-deps --force-recreate` when their mounted
Collector/Prometheus/datasource configuration changes; a normal `up` does not reload
that configuration. Dashboard JSON alone is watched by Grafana’s file provider.
Keep the named volumes so metric history and Grafana state survive.
The role procedure is idempotent and aligns the database password with its protected
file; restart Grafana after rotating that file. Do not print either secret or put it
in `runtime.env`, command arguments or a URL. Grafana reads its admin secret at first
database initialization; replacing that file does not rotate the stored admin password.
SQL unavailable before migration/role setup means unavailable panels, not empty data.
To recover, restore prior Grafana provisioning and stop using the reader; the additive
views can remain until a separately reviewed forward removal. Product data is unchanged.

From the owner's workstation, open an SSH tunnel over the existing private path:

```bash
ssh -N -L 127.0.0.1:3000:127.0.0.1:3000 vgpdev
```

Open `http://127.0.0.1:3000`, sign in as `owner` using the protected Grafana admin
secret, and select the provisioned **VideoGame Platform** folder. The loopback HTTP hop is
inside the SSH tunnel; do not add a public bind, Tailscale Serve route or Funnel.

Run `validate-private-dev-runtime.sh --telemetry-smoke` for disposable configuration and a PostgreSQL read-model fixture,
OTLP handoff, authenticated datasource/panel queries, anonymous denial, clean
provisioning and recreation/persistence checks. Synthetic samples never go into the
live project, even when an environment file is supplied. Run the existing validator
with `--env-file <protected-runtime.env> --live` after real product activity and at
least one synchronization run for read-only host checks and a resource snapshot.
It requires a finite result in each dashboard category; absent synchronization data
is an evidence gap, not an instruction to fabricate it. Allow several export/scrape
intervals for rates. Capture another resource snapshot during normal browsing and
an owner-triggered bounded sync, as well as at idle.

For host persistence evidence, note a query timestamp and result, recreate only
Prometheus and Grafana with `up --detach --no-deps --force-recreate prometheus grafana`,
and query the original timestamp through Grafana again. Do not use `down --volumes`:
the same project also owns irreplaceable PostgreSQL data. To recover from a metrics
configuration failure, stop only Prometheus/Grafana and restore the prior Collector
configuration; product readiness and requests must continue. Reapply the reviewed
configuration to resume telemetry. Metrics volumes are disposable but intentional
history deletion needs an explicit target review. Leave PostgreSQL volumes alone.

Host acceptance also checks loopback access from the owner workstation, no OTLP,
Collector handoff or Prometheus IPv4/IPv6 listener, representative real-data panels,
retained samples after recreation, and continued readiness/product reads during a
bounded metrics-stack outage. Record measured idle/load CPU, memory and PIDs plus
metrics-volume disk usage in #158; the repository smoke does not prove host capacity.

## Private-dev alerts

The [observability inventory](../../docs/development/observability.md#private-dev-alerts)
owns operational rule semantics, provisional thresholds and delivery limits. Grafana
loads file-provisioned rules and a rendered notification configuration at startup.
No UI creation is required. Only private-dev enables evaluation; delivery defaults
off. Resource ceilings, retention and private listeners remain unchanged.

Focused validation from the worktree (local Unix Docker engine only):

```bash
python3 deploy/private-dev/grafana/check-alerts.py --rules
python3 deploy/private-dev/grafana/check-alerts.py --smoke
python3 deploy/private-dev/grafana/check-notifications.py --smoke
npm ci --ignore-scripts --no-audit --no-fund
python3 deploy/private-dev/grafana/check-product-growth.py
bash scripts/validate-private-dev-runtime.sh
```

`--rules` runs original expressions/pending periods through Promtool and checks the
base/optional scrape configuration. Alert smoke starts disposable Collector,
Prometheus and Grafana with generated fake credentials and separate volumes. Pool
samples exercise the original firing/resolved lifecycle. Separate disposable
zero-pending clones test native No Data/Error/KeepLast/recovery and native datasource
instances; those accelerated tests do not establish live-host timing. Recreation
checks rule UIDs/expressions and all five dashboards. Only the temporary Grafana HTTP
port is published, on random IPv4 loopback; Prometheus/OTLP publish nothing.
The `--telemetry-smoke` gate covers synthetic rating counter translation/dashboard SQL
checks; it does not include alert smoke, which runs independently as listed above.

Notification smoke provisions the real native Telegram integration but pins
`api.telegram.org` to an internal HTTPS fixture with a disposable trusted certificate.
It never uses runtime secrets or real Telegram DNS/delivery. It accelerates only test
policy/config-poll timings, tests disabled/enabled/product routing, English firing
and recovery messages with dashboard links, silent registration resolutions,
deduplication, API failure, timeout redaction, later delivery cycles and recreation,
including disabling only the product route. Keycloak proof uses the pinned 26.7 base image, a disposable realm/H2
volume, existing Mailpit test sink and the matching locked Playwright dependency. It
checks actual event names/tags, rejected registration, two successful registrations
and verifications, imported-account exclusion and reset after restart. H2 is a test
fixture, not a change to private-dev's PostgreSQL identity database. All helpers remove
only their random test projects/volumes. No real-host or delivery guarantee follows.

### Reviewed alert rollout and rollback

**No Lenovo configuration or real Telegram message was changed/sent by this work.**
After owner diff review, publication and separate host authorization, install the
reviewed checkout under the existing deployment lock using the
[installed-revision procedure](#one-time-migration-to-the-standard-runtime).
Run static validation, record service identities/headroom, and rerun `bin/prepare-secrets`
with the existing protected directory/group to add the two **empty optional** Telegram
files without replacing any credential. Empty files are valid with delivery disabled.
Recreate **only Grafana**, retaining its named volume:

```bash
docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  --file deploy/private-dev/compose.yaml up --detach --no-deps --force-recreate grafana
```

For the operational package alone, application/database/Collector/identity/localizer
restarts are unnecessary. The separable growth package below needs its own application
promotion and targeted Prometheus/Keycloak recreation. Record that distinction when
acknowledging the [applied runtime contract](#acknowledge-the-applied-runtime-contract);
do not acknowledge unapplied service changes or run project-wide `up`/`down`.

Through the existing SSH tunnel, verify five operational rules plus the separate
informational registration rule in **Alerting → Alert rules**, evaluation health,
five dashboards, existing metrics/log/SQL datasources and muted empty routes when
flags are false. Idle HTTP/sync/product rules can have no instances. Allow normal
OTLP exports and a scrape before interpreting telemetry; compare private readiness
and service identities. Record real-host evaluation/resource/persistence evidence on
#269. Do not inject synthetic metrics, exhaust pools or interrupt live services to
trigger an alert. Startup reconciles stable UIDs; removing a file alone does not
remove persisted rules. A future removal must provision `deleteRules` by UID.

For rollback, restore the prior reviewed Grafana definition with evaluation disabled,
recreate only Grafana and retain its volume. Confirm dashboards/product readiness.
Rules may remain stored while evaluation is disabled. Never delete telemetry or
application volumes, and never acknowledge a failed rollout.

### Telegram setup and delivery

This is an **owner-executed later procedure**, requiring separate authorization for
real sends. Native Grafana needs only outgoing HTTPS to `api.telegram.org`: no SMTP,
inbound webhook, public Grafana, Funnel, router forwarding, new account integration
or notification service is deployed here. Messages contain fixed operational/activity text and private links,
without user/request information.

1. In Telegram, open the official verified **@BotFather**, use `/newbot`, and keep the
   issued token private. Open the new bot's private chat and send `/start`. Do not use
   third-party chat-ID/token websites or paste credentials into chat, issue comments,
   shell command arguments/history or Compose environment.
2. On the authorized host, use a protected root editor (without swap/backup files) to
   place the token into the existing protected file
   `/etc/videogame-platform/dev/secrets/grafana-telegram-bot-token`. Retain the
   root/runtime-group ownership and 0644 file mode established by `prepare-secrets`;
   the protected 0750 parent is the host access boundary and only Grafana is granted
   these mounts. Do not move secrets under the checkout.
3. Capture the private chat ID using the official `getUpdates` API. The following
   optional root-only snippet reads the token from its file, keeps response identities
   in memory and writes the single private `/start` chat ID without printing it. It
   fails closed for zero/multiple candidate chats; resolve the intended chat privately.
   Do not use `curl -v`, shell tracing, or a token-bearing URL in argv/browser history.

   ```bash
   sudo python3 - <<'PY_TELEGRAM'
   import json
   from pathlib import Path
   import urllib.request
   base = Path('/etc/videogame-platform/dev/secrets')
   try:
       token = (base / 'grafana-telegram-bot-token').read_text().strip()
       with urllib.request.urlopen('https://api.telegram.org/bot' + token + '/getUpdates', timeout=20) as response:
           updates = json.load(response)
       candidates = {item['message']['chat']['id'] for item in updates.get('result', [])
                     if item.get('message', {}).get('text') == '/start'
                     and item['message']['chat'].get('type') == 'private'}
       if not updates.get('ok') or len(candidates) != 1:
           raise ValueError()
       (base / 'grafana-telegram-chat-id').write_text(str(candidates.pop()) + '\n')
   except Exception:
       raise SystemExit('Chat capture failed; values withheld. Check the private bot/chat configuration.') from None
   print('Chat ID saved; no values printed.')
   PY_TELEGRAM
   ```

4. Keep `GRAFANA_PRODUCT_NOTIFICATIONS_ENABLED=false` initially. Explicitly set
   `GRAFANA_TELEGRAM_ENABLED=true` in the protected runtime env, preserving all other
   values. Set `PRIVATE_DEV_GRAFANA_ORIGIN` to the existing approved private access
   origin (default loopback tunnel); this sets message links, not listeners or ingress.
   Do not publish Grafana to make a phone link work. Recreate only Grafana under the
   lock, verify file contact-point/policy ownership and unmuted operational routes,
   then use Grafana's contact-point test **only with explicit real-send approval**.
   A test message proves connectivity, not a live operational firing/resolved cycle.
5. Product delivery needs the additional explicit flag
   `GRAFANA_PRODUCT_NOTIFICATIONS_ENABLED=true` and the growth source below. It
   requires the master Telegram flag; enabling it alone refuses startup. It adds a
   separate informational contact point with the same protected values and resolved
   notifications disabled, so a closing registration window sends nothing. Registration
   notices are best-effort window activity, not one notice per account. No lifetime
   milestone messages exist. Verify the product route separately before enabling it.

Missing/malformed credentials fail startup with a fixed redacted error when enabled;
disabled startup reads neither value. Rendering uses non-exported shell variables and
builtin `printf`, then removes them. The single resulting policy/contact-point file
lives in Grafana's existing tmpfs with 0700/0600 permissions. The bot token is encrypted
in Grafana's existing persistent database; the chat ID is an ordinary administrator-
readable contact setting. Protect the database/volume backups and administrators too.
Never dump rendered provisioning or contact-point exports into diagnostics/artifacts.
Keep the fixed info-level notifier logs; native debug logging can expose URL tokens.
English plain-text templates avoid Telegram markup failures and sensitive error bodies.

To disable, set both flags false and recreate only Grafana; routes become muted/empty.
To stop only registration notices, set the product flag false and recreate only
Grafana; operational delivery continues and the product route becomes muted/empty.
A closed-loopback HTTPS proxy setting in the private-dev wrapper blocks native sends
immediately, including the interval before Grafana reconciles the previous persisted
policy. No proxy service is installed; existing internal HTTP datasources stay direct.
Previously stored contact points can remain encrypted in the database but are no
longer referenced once provisioning reconciles. For compromise or full revocation, revoke/regenerate
the token via BotFather (`/revoke` or its bot-management flow) first, replace the
protected file privately, and recreate Grafana only after choosing whether to enable
again. Rotation reprovisions the same contact UIDs; disabling is not token revocation.
Retain backup access controls and do not delete the Grafana volume as a cleanup shortcut.
A successful send may have an ambiguous acknowledgement: do not promise duplicate-free
manual retries or exactly-once delivery.

### Separable product-growth rollout

This package is independently reviewable: committed Ratings counters/backend minor
version, one growth dashboard, optional Keycloak/Prometheus overlay and an informational
registration-window rule. It changes no production realm, verification policy, BFF
contract or database schema. [Growth semantics and durable follow-up](../../docs/development/observability.md#product-growth-observations)
own the supported/unsupported indicators.

The private Compose build selects event-metrics support before `start --optimized`;
the shared Dockerfile defaults it off for local and production-identity consumers.
Base Compose limits collection to the supported events and realm tag. The overlay
only connects the management scrape. A runtime-only enable flag cannot change a
previously optimized image. Rebuilding is mandatory for the correction; the former
`start-dev` proof did not establish compatibility. One bounded private image avoids
a second variant and its additional build/rollback combinations. Base operation
retains its identity policy and keeps management isolated from Prometheus until opt-in.

Before host changes, run the focused optimized-image and notification-mock checks
above, review the complete diff, and obtain approval for the exact targeted rollout.
Use the reviewed published revision and the existing clean-checkout/installed-revision
procedure, holding the deployment lock. Use `umask 0022` for checkout/build inputs so
container users can read mounted configuration; protected secret directories retain
their existing restrictive ownership/modes. Record service IDs, free memory/disk,
the old immutable Keycloak image ID and previous source/runtime acknowledgement.
Do not print runtime secrets or publish rendered Compose configuration.

Build the reviewed Keycloak image while the existing container remains running:

```bash
docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  --file deploy/private-dev/compose.yaml build keycloak
```

Review the overlay-rendered configuration privately and recreate **only Keycloak
and Prometheus** with the base plus optional file:

```bash
docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  --file deploy/private-dev/compose.yaml \
  --file deploy/private-dev/compose.product-growth.yaml \
  up --detach --no-deps --no-build --force-recreate \
  --wait --wait-timeout 180 keycloak prometheus
```

Retain all volumes. Keycloak's brief restart needs a maintenance window. This attaches
its existing internal management metrics to the telemetry network and selects only
successful register/verification observations. No management port is published. The
base Prometheus config also retains the new Ratings counter family; restarting it
activates that allowlist without changing retention. Keep ordinary application
promotion on **base Compose**: its trusted entrypoint rejects application containers
created with arbitrary overlays. The targeted overlay above creates no application
container, and normal promotion leaves persistent services in place. Future targeted
Keycloak/Prometheus maintenance must include this overlay to retain collection; base
recreation intentionally rolls it back. Do not acknowledge only Git while affected
runtime changes remain unapplied.

Verify `keycloak-product` is up and exposes only the expected realm/event/empty-error
series, with no personal tags. A configured healthy source without an event is zero;
an absent source is unsupported/no data. Do not create real accounts solely to force
an event without separate approval. Check Ratings activity after normal committed
commands and the existing SQL active total; no thresholds or Hikari settings change.
Check private OIDC discovery, normal login and password recovery after recreation;
record the observed identity interruption and idle/normal resource use. Synthetic
host registrations need separate owner approval. Apply the protected runtime
acknowledgement only after the selected configuration, image and checks actually pass.
The existing Ratings capability needs no application promotion for this correction.

To load changed registration-rule annotations/message templates, obtain approval
for a separate targeted Grafana recreation as well. Preserve its named volume,
current HTTPS origin and any installed HTTPS ingress overlay. Do not enable either
Telegram flag without protected credentials and explicit real-send authorization.
The native TLS mock proves routing, not real Telegram delivery.

For rollback, restore the previous reviewed checkout and immutable Keycloak image
using a protected Compose image override, then recreate only Keycloak/Prometheus
with **base Compose**, `--no-build` and the captured old image. Preserve every
PostgreSQL and telemetry volume. Verify discovery/login/recovery and the original
application readiness before restoring the prior runtime acknowledgement; a failed
rollout must never be acknowledged. Removing the growth overlay also removes its
scrape, so the dashboard may return to **No data**. Historical samples can remain.
If Grafana was separately recreated, restore its prior reviewed provisioning and
HTTPS overlay with its volume retained. Keep normal application rollback/promotion
separate; never use project-wide `down`, volume removal or realm reinitialization.

## Log aggregation and exploration

Post-MVP #159 is prepared in the repository; rollout/acceptance on `vgpdev` is not
implied. Review [ADR-0021](../../docs/decisions/0021-collect-private-dev-logs-with-alloy-and-loki.md)
and the [platform storage/capacity policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md)
before changing the host. [Observability](../../docs/development/observability.md#private-dev-log-exploration)
owns log interpretation and queries. Compose, Alloy, Loki and provisioning files
own executable settings. No new secret, paid resource or external service is needed.

First run the static runtime validator and the independent logging smoke:

```bash
bash scripts/validate-private-dev-runtime.sh --env-file /etc/videogame-platform/dev/runtime.env
python3 scripts/private-dev-logs-check.py --smoke
```

The smoke generates its own project, credentials, volumes, loopback UDP port and
structured fixtures. It never consumes the host runtime file or starts application,
identity or database services. It proves configuration and synthetic behaviour only.
Do not run concurrent heavy validation/deployment work on the constrained host.

### Reviewed rollout

1. Check the storage/capacity stop thresholds before pulling or starting anything.
   Review `free -h`, `df -h /`, `docker stats --no-stream` and `docker system df -v`.
   Keep the existing application digest and full source revision: this configuration
   change needs no new product image or migration.
2. From the reviewed checkout, pull/start only logging and recreate Grafana to load
   its additional datasource; the shared metrics datasource and dashboards remain:

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml pull alloy loki
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml up --detach --no-deps alloy loki grafana
   ```

3. The application logging driver changes only when its container is recreated.
   Use the existing owner-triggered deployment procedure with the **same reviewed
   deployed digest and matching source revision**, or defer collection until the
   next approved deployment. This does not authorize selecting a different image.
   That controlled recreation runs the established migration/readiness/smoke and
   evidence checks; do not recreate with runtime placeholders. Earlier local logs
   are not backfilled and disappear with the removed application container.
4. Use the existing Grafana SSH tunnel, select **Platform logs** in Explore and
   search a known normal request correlation ID. Inspect the unmodified ECS body,
   WARN/ERROR/event searches and the two indexed labels. Complete JSON only is
   aggregated; inspect plain pre-logger output with Docker. Keep DEBUG disabled. Check
   `docker compose ... logs application` still supplies bounded local inspection.
5. Run `validate-private-dev-runtime.sh --env-file <runtime.env> --live` for the
   existing static/live isolation and resource snapshot checks. Independently inspect
   the UDP listener with `ss -4 -lnu` and `ss -6 -lnu`, Docker port mappings, and
   external IPv4/IPv6 reachability: no public syslog receiver or Loki/Alloy API.
   Leave the two existing Tailscale HTTPS routes, Funnel and router policy unchanged.

### Capacity, retention and failure evidence

Get the real Loki storage path without assuming Docker's data root:

```bash
logging_container=$(docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  --file deploy/private-dev/compose.yaml ps --quiet loki)
logging_storage=$(docker inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/loki"}}{{.Source}}{{end}}{{end}}' "$logging_container")
[[ -n "$logging_storage" ]] && sudo du -sh -- "$logging_storage"
df -h /
docker stats --no-stream
```

Inspect usage at idle, during normal browsing and an owner-triggered bounded sync,
and again after the retention period plus index/compactor deletion lag. Record in
#159 how usage changes, container CPU/memory/PIDs, source/send failures and rejected
lines/ingestion. Retention is asynchronous: confirm eventual physical deletion from
volume use and compactor behaviour, not just absence from queries (lookback hides
expired entries). Do not shorten production retention or inject artificial samples
into live Loki to manufacture evidence. No automatic capacity alert is added.

If either platform disk threshold is crossed, stop **only Alloy and Loki** before
further ingestion; do not stop product/database services or prune Docker globally.
Diagnose Loki/Alloy logs, host free space and the exact Loki volume. Reclaim unrelated
storage only through its owner's procedure. Resume with the reviewed configuration
only after restoring headroom; unexpectedly fast growth requires a retention/rate
review. A hard filesystem quota is not present in this initial configuration.

For persistence, note a recent query timestamp/body, recreate only Loki/Grafana with
`up --detach --no-deps --force-recreate loki grafana`, then search that same timestamp.
For independence, stop Alloy briefly while making a normal product read and checking
readiness, then start it again and verify new events arrive. Loss during the outage
is expected; restart does not replay missed Docker logs. Collector health alone does
not prove successful delivery. Docker's remote-driver errors can also prevent local
cache writes, so the cache is not an independent durable audit copy.

### Rollback and safe history recovery

For configuration rollback, restore the previously reviewed checkout/configuration
and deploy the same compatible application digest through the existing procedure to
restore its prior local logging driver. Stop Alloy/Loki and recreate Grafana from the
prior provisioning. Keep metrics and PostgreSQL running; no product schema change is
involved. Logging configuration changes apply on recreation, not restart.

If Loki history is corrupt or disposable history must be purged, first stop Alloy
and Loki, inspect the Loki container's mounts, and explicitly review the exact
`loki-data` volume target. Remove only the Loki container, then only that verified
volume; let `up --detach --no-deps loki alloy` recreate it. Never run private-dev
`down --volumes`, `docker volume prune`, delete a guessed host path or touch
PostgreSQL, Prometheus or Grafana volumes. Lost log history has no backup and cannot
be recovered; source logs survive only within their own remaining cache/container.
Record the action and the absence of an automatic recovery claim in #159.

## Owner-triggered deployment

The direct host command remains the proven deployment/recovery entry point.
The [owner-approved Actions promotion](#owner-approved-actions-promotion) invokes
it through restricted SSH after verifying CI evidence; neither path deploys on merge.

Choose a trusted `main` source revision and review its successful required checks,
image publication summary, scan/SBOM evidence and immutable digest. Both selected
values stay explicit in the command:

```bash
deploy/private-dev/bin/deploy-private-dev \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --image ghcr.io/rubhern/videogame-platform@sha256:<approved-64-hex-digest> \
  --source-revision <approved-full-40-character-main-sha> \
  --initiator rubhern
```

Under one non-blocking host lock the command:

1. validates that the target is `vgpdev`, the protected runtime configuration
   renders, smoke credentials exist, dependencies are healthy, and evidence storage
   is writable;
2. resolves the supplied revision's GHCR tag for verification only, requires its
   published digest to equal the supplied digest, pulls by digest, and verifies the
   image's source, revision and version OCI labels;
3. builds the digest-pinned, no-retry Playwright smoke runner before changing the
   database or application;
4. runs the selected image once as `videogame_app_migrator`, applies and validates
   only packaged production Flyway migrations, and stops immediately on failure;
5. force-recreates only the application service with the selected digest, waits for
   candidate health, and verifies the created container uses the inspected image;
6. runs the deployment smoke against that candidate and the private HTTPS boundary,
   then verifies structured correlation/trace evidence and collector receipt;
7. finalizes the JSON evidence record as `success`, or as `failure` with the phase
   that stopped. A failed outcome is never changed to success because another
   container or older version answers health checks.

The smoke checks management liveness/readiness; `/actuator/info` version and source
revision; the matching single frontend startup console message; the HTTP/JVM/JDBC metric catalogue; `GET /api/v1/releases` returning a
valid local page (including zero items) or the approved `CATALOGUE_NOT_READY`
response; the matching Spanish shell rendering in Chromium with IGDB hosts blocked;
real Keycloak authorization with an opaque `HttpOnly`, `Secure`, `SameSite=Lax` BFF
session, no browser-stored OAuth material, and CSRF-protected logout; W3C
trace/correlation propagation in structured logs; and trace receipt by the collector.
It deliberately does not rate a game, traverse every screen, or run provider
synchronization, and it is not product acceptance.

## Owner-approved Actions promotion

The original promotion path has successful real-host evidence on #160. The refined
single-dispatch flow still requires owner review, the environment Settings change below
and acceptance on its published main revision. New host changes retain their separate
owner approval; workflow dispatch authorizes only application promotion. The
[platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#artefact-and-delivery)
owns the application/runtime boundary and
[ADR-0023](../../docs/decisions/0023-automate-owner-approved-private-dev-application-promotion.md)
records the accepted trust decision. The workflow and scripts own job mechanics.

### Protected GitHub and tailnet setup

Use the public repository's eligible free GitHub-hosted runner/environment and
existing free personal tailnet; recheck eligibility before enabling. No paid fallback
or additional runtime service is authorized.

Configure the GitHub **dev** environment without required reviewers, wait timers or
custom deployment approval rules. **Run workflow is the sole human approval**.
Keep administrator bypass disabled and permit only the selected **main branch**
(no tags). The workflow checks this policy before the environment job and again
before private connectivity, refusing the old reviewer configuration rather than
waiting for another approval.

For an existing environment, the owner must open **Repository Settings → Environments
→ dev → Deployment protection rules**, remove **rubhern** from **Required reviewers**
and disable that rule, then save. Remove any wait timer or custom approval rule if
configured. In **Deployment branches and tags**, retain **Selected branches and tags**
with exactly the **main branch** and no tags; keep administrator bypass disabled.
Do not delete/recreate the environment or move its secrets. This is an explicit owner
Settings change; repository code does not change it. Verify the saved policy before
dispatching. Existing host/key/tailnet configuration remains required.

Keep all five credentials/connection values as environment secrets, never repository
secrets or workflow inputs:

| Environment secret | Owner-provided value |
|---|---|
| `PRIVATE_DEV_TS_CLIENT_ID` | Dedicated Tailscale OAuth client ID |
| `PRIVATE_DEV_TS_CLIENT_SECRET` | OAuth secret limited to writable auth keys and `tag:vgp-deploy` |
| `PRIVATE_DEV_SSH_HOST` | Exact private `vgpdev.*.ts.net` hostname |
| `PRIVATE_DEV_SSH_KEY` | Dedicated Ed25519 private key for the restricted account |
| `PRIVATE_DEV_SSH_KNOWN_HOSTS` | Exact hostname plus trusted host public key, verified through the existing owner connection/console |

Generate the deployment key on the owner's trusted workstation, store the private
key only in the protected environment, and install only its public key on the host.
Do not discover/trust a new host key during deployment. For rotation, install the
new public key through owner administration, update the environment secret, verify
the restricted path, then remove the old key. Revoke the OAuth client and SSH key to
disable automation; preserve the normal owner administration/recovery path.

The [Tailscale Action](https://github.com/tailscale/github-action) creates an
ephemeral tagged node and logs out in cleanup. Give that tag only TCP 22 to the
specific existing host. Audit all existing grants/ACLs: an earlier allow-all rule
would defeat a narrow added grant. It must reach neither another host nor product
HTTPS, Keycloak, Grafana or management services. Keep owner access and Serve routes
intact. Use ordinary OpenSSH, without enabling Tailscale SSH, Funnel, router
forwarding or a public listener. Validate positive TCP-22 and negative other-service
policy tests before adding the credential.

### Restricted host installation

Through the existing owner administration connection, install a **standalone,
root-owned clean Git checkout** of the reviewed main revision at
`/opt/videogame-platform`. Keep its Git metadata root-owned too, without group/world
write access; do not copy a worktree's `.git` pointer, build outputs or private files.
The runner never updates this checkout. Git and Python 3 are host prerequisites.

Create a dedicated `vgp-deploy` system account with `/bin/sh` only for sshd's
forced-command invocation, no password authentication, no Docker/runtime group
membership and no other sudo rights. Install its public key in root-owned
`/etc/ssh/authorized_keys/vgp-deploy` with mode 0644 (the key is public), under a
root-owned searchable directory, using the `restrict` authorized-key option.
The account must not be able to replace its authorized keys.

Add this reviewed sshd drop-in; run `sudo sshd -t`, keep a working owner session
and reload SSH only after the configuration validates:

```text
Match User vgp-deploy
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AuthorizedKeysFile /etc/ssh/authorized_keys/%u
    ForceCommand /usr/bin/sudo -n /opt/videogame-platform/deploy/private-dev/bin/promote-private-dev
    DisableForwarding yes
    PermitTTY no
    PermitUserRC no
Match all
```

Install a root-owned mode-0440 sudoers drop-in and validate it with `visudo -cf`
before enabling the key. The empty argument specification permits no arguments:

```text
Defaults:vgp-deploy env_keep += "SSH_ORIGINAL_COMMAND"
vgp-deploy ALL=(root) NOPASSWD: /opt/videogame-platform/deploy/private-dev/bin/promote-private-dev ""
```

The isolated Python forced command validates its strict request grammar, clears the
inherited environment and checks privileged code ownership before loading repository
code. It has fixed runtime/evidence paths and cannot accept a shell, file upload,
alternate checkout, environment file or Compose operation.

Preserve the same deployment lock for root automation and the owner operator.
Configure a reviewed tmpfiles entry and create it before first promotion:

```text
f /run/lock/videogame-platform-dev-deployment.lock 0660 root vgp-runtime - -
```

### Acknowledge the applied runtime contract

After reviewing/applying the actual runtime and dependency configuration using its
existing explicit procedures, acknowledge its clean installed Git contract:

```bash
sudo python3 /opt/videogame-platform/scripts/private_dev_promotion.py --installed-contract \
  | sudo tee /etc/videogame-platform/dev/deployment-contract.sha256 >/dev/null
sudo chown root:vgp-runtime /etc/videogame-platform/dev/deployment-contract.sha256
sudo chmod 0640 /etc/videogame-platform/dev/deployment-contract.sha256
```

This acknowledgement is an owner statement that the runtime was reviewed/applied;
writing the hash does not deploy a dependency. Stop rather than acknowledge a rollout
that has not happened. A tooling-only change may need just an approved checkout
update; review the diff to distinguish that from real service changes. Preserve
private runtime/secrets under `/etc`, evidence under `/var/lib`, and bind-mounted
runtime configuration at its reviewed location. File replacement/restart alone may
not update imported Keycloak state, database roles or Grafana's persisted credentials.

### Promote and verify

After the complete trusted-main build/security gates and publication succeed, open
**Actions → Promote application to private dev → Run workflow**, select **main** and
press **Run workflow**. There are no artifact/run input fields and no second approval.
The owner dispatch authorizes deploying the exact main SHA captured by GitHub for
that invocation. Checkout and artifact selection remain pinned to that SHA even if
main advances while the run is queued; dispatching before evidence is ready fails.

The workflow derives the unique main `push` Build and verify run, its current attempt
and the immutable OCI digest from its retained `application-image-publication-<SHA>`
record. It does not choose a latest image or fall back to a different SHA, older attempt
or older successful run. Absent, expired, mismatched or ambiguous evidence refuses
promotion; multiple build runs for that SHA are conservatively refused. It checks
quality/security gates, main ancestry and the deployment contract, revalidates the
same derived tuple before connectivity, and the host independently rechecks source
trust and digest/OCI binding. No build occurs during promotion.

Pending Actions runs are serialized but the queue is not FIFO; the existing host
lock also refuses concurrent manual deployment.

This refinement changes the installed deployment tooling contract: update the reviewed
root-owned host checkout and acknowledge it through the existing procedure before
the first refined promotion. Review the diff first; this does not authorize a runtime
dependency upgrade. Leaving the old contract installed deliberately refuses promotion.

The host rechecks public main CI/source evidence and binds the candidate's runtime
fingerprint to the installed and acknowledged contract. The required localizer must
already be running, healthy and match the resolved service configuration and local
image. A different contract or unrelated application Compose overlay stops before
migration/activation. The fixed `videogame-platform-dev` project has one base runtime,
including localization. An application still labelled with the retired localization
overlay can transition through normal promotion because the endpoint now lives in
the base definition; the required localizer is still checked and left intact.

Read the Actions summary and retained `private-dev-promotion-*` receipt. Original
deployment JSON and detailed log remain under the protected evidence root in a
per-invocation directory; use owner administration/sudo to inspect them. Failed SSH,
missing receipts or workflow timeout are failures, never proof that deployment stopped
or succeeded. Inspect the host before another promotion, and follow the existing
[recovery assessment](#rollback-versus-forward-fix); no automatic rollback is added.
GitHub public API rate limits, unavailable/expired CI artifacts or private-network
failure refuse promotion; there is no retry that converts uncertain deployment into
success.

Open the application, enable DevTools Console info messages, disable browser network
cache and reload. Confirm the
[frontend build identity](../../frontend/README.md#deployed-build-identity)
matches the expected version and SHA prefix. Check runtime container identities are
unchanged, including catalogue-localizer. Record real-host restriction, success/failure and recovery acceptance in
the current delivery issue (#257 for the localization lifecycle) as required by the
[runbook](../../docs/development/operations-runbook.md#deploying-an-immutable-digest).

## Backup, restore, rollback and host-loss recovery

These are the owner-triggered recovery controls. Repository validation
(`scripts/test-private-dev-backup-recovery.sh`) proves only the encryption,
integrity, retention and decision logic; every step that reads the live database or
a real host is environment evidence recorded in the runbook.

### State model

The irreplaceable durable state is the two PostgreSQL databases: `videogame_platform`
(application state and product-owned curation) and `videogame_keycloak` (identity
configuration and runtime accounts such as the deployment-smoke user). Both are
captured as logical `pg_dump` custom-format artifacts. Catalogue/provider data is
reconstructable by IGDB synchronization. Roles and passwords are **not** backed up:
`docker/postgres/init` bootstraps them from the protected secret files when a clean
data volume initializes, which is why a backup never stores a credential.

### One-time backup key setup

Generate the backup keypair on a trusted machine that is not the host, keep the
private key and passphrase offline, and import only the public key on `vgpdev`, so
host compromise cannot expose backup contents. `<recipient>` is the key's email or
fingerprint.

```bash
# On the offline owner machine (once): create the keypair and export the public key.
gpg --full-generate-key
gpg --armor --export <recipient> > vgp-backup-public.asc

# On vgpdev (once): import only the public key into a dedicated keyring.
install -d -m 0700 /etc/videogame-platform/dev/backup-gnupg
gpg --homedir /etc/videogame-platform/dev/backup-gnupg --import vgp-backup-public.asc
```

### Encrypted backup with integrity and retention

Runs on `vgpdev` while the dependency stack is healthy. It dumps both databases,
encrypts each to the public key, writes a manifest and `SHA256SUMS`, self-verifies,
and prunes older backups to the retention count. Copying the destination off the host
(for example over Tailscale with `rsync`) is the owner action that makes the backup
"outside the host".

```bash
deploy/private-dev/bin/backup-private-dev \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --gnupg-home /etc/videogame-platform/dev/backup-gnupg \
  --recipient <recipient> \
  --destination /mnt/vgp-backups \
  --initiator rubhern \
  --keep 7
```

Integrity and retention are re-checkable anywhere, without Docker or the private key:

```bash
deploy/private-dev/bin/verify-private-dev-backup --backup /mnt/vgp-backups/<backup-id>
deploy/private-dev/bin/verify-private-dev-backup --backups-root /mnt/vgp-backups --expect-at-least 7
```

### Isolated restore

Restores a verified backup into a **distinct** Compose project, never the live one.
It rebuilds a clean database volume (destroying only that isolated project's
volumes), bootstraps roles from the protected secret files, streams each decrypted
dump into `pg_restore`, and verifies the restored schema, catalogue tables and
Keycloak realm/account counts. It requires the private key in `--gnupg-home` and
explicit destructive confirmation.

The backup can predate account erasures, so pass a copy of the owner's deletion ledger
(see [the ledger copy on the host](#ledger-copy-for-a-restore)): the command then starts
only the restored Keycloak, which the rehearsal override publishes on loopback port 8190,
replays every erasure against it and records the receipt in the evidence. Without
`--deletion-ledger` it stops after the database checks; the application or a smoke must
not start in that project before a verified replay.

```bash
deploy/private-dev/bin/restore-private-dev \
  --backup /mnt/vgp-backups/<backup-id> \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --gnupg-home /etc/videogame-platform/dev/restore-gnupg \
  --isolated-project vgp-restore-rehearsal \
  --confirm-destroy-isolated-target \
  --deletion-ledger ~/vgp-ledger-replay/deletion-ledger.jsonl \
  --evidence-directory /var/lib/videogame-platform/dev/deployment-evidence
```

Tear the rehearsal down afterwards with
`docker compose ... --project-name vgp-restore-rehearsal down --volumes`.

#### Ledger copy for a restore

The ledger lives on the owner's workstation; a restore needs a current copy on the host
for the duration of the command only. From the workstation, after `personal-data-ledger
… status` has brought both owner copies up to date:

```bash
ssh videogame-platform-lenovo 'install -d -m 0700 ~/vgp-ledger-replay'
scp -p ~/vgp-admin/private-dev/deletion-ledger.jsonl videogame-platform-lenovo:vgp-ledger-replay/
```

The file must stay `0600` in the `0700` directory, or the restore refuses it. Remove the
directory once the restore has finished (`rm -r ~/vgp-ledger-replay`); the copy holds
only identifiers and times, but the owner's copies are the record.

### Rollback versus forward fix

Before recovering a bad deployment, decide whether an older image is still
schema-compatible. The assessment compares the applied Flyway version with the
candidate's packaged migration version and enforces that an applied migration is
never reverted.

```bash
deploy/private-dev/bin/assess-recovery-strategy \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --target-migration-version <candidate-image-flyway-version> \
  --target-image ghcr.io/rubhern/videogame-platform@sha256:<digest>
```

`ROLLBACK` redeploys the older digest with the normal deployment command (its
migration step finds nothing to apply). `FORWARD_FIX` means building and deploying a
corrected revision. `REFUSE` means the inputs are inconsistent; do nothing until they
are explained.

### Host-loss recovery

Recovery does not depend on the original hardware; any compatible Linux host is
sufficient:

1. Rebuild the host foundation and run the one-time preparation above.
2. Optionally validate the backup first with an isolated rehearsal as above.
3. Put a current [ledger copy](#ledger-copy-for-a-restore) on the host and restore into
   the live project. Host-loss recovery is the only mode that may use the live project
   name: it refuses a host that holds any container or volume of that project, removes
   nothing, and first writes `DELETION-REPLAY-PENDING` in the deployment evidence
   directory, which makes `deploy-private-dev` and every promotion refuse. It restores
   both databases, starts only Keycloak, replays every erasure and removes the block only
   after the replay is verified:

   ```bash
   sudo deploy/private-dev/bin/restore-private-dev --host-loss-recovery \
     --backup /mnt/vgp-backups/<backup-id> \
     --env-file /etc/videogame-platform/dev/runtime.env \
     --gnupg-home /etc/videogame-platform/dev/restore-gnupg \
     --deletion-ledger ~/vgp-ledger-replay/deletion-ledger.jsonl
   ```

   If the replay fails, deployments stay blocked: fix the cause (Keycloak health, the
   ledger copy) and repeat only the replay with
   `restore-private-dev --resume-deletion-replay --env-file … --deletion-ledger …`. If
   the command stops before the replay, during the restore itself, remove that project's
   containers and volumes deliberately and start step 3 again.
4. Deploy the last-good image digest with `deploy-private-dev` and confirm the smoke
   and readiness checks pass.
5. Record the recovery decision, backup id, image digest, migration version and
   outcomes from the generated evidence records.

## Catalogue localization helper

The base Compose includes the required acquisition container and the application's
internal translation endpoint under
[ADR-0024](../../docs/decisions/0024-maintain-catalogue-localization-in-the-private-dev-runtime.md).
The shared service definition in `tools/catalogue-localization/compose.yaml` owns
helper mechanics; the workstation overlay adds its optional profile and loopback
access. Private dev has no localization profile or overlay to reapply.

The helper uses the internal data network, no published port, a read-only model mount,
a non-root runtime and bounded resources. Preserve the model manifest/licences and
weights outside the checkout. `CATALOGUE_TRANSLATION_MODEL_DIR` in protected
`runtime.env` selects that immutable directory; never replace the protected file
with the example or erase its current application image/version entries.

### One-time migration to the standard runtime

Run only after owner review, publication/merge, current-main CI and separate approval
to operate on `vgpdev`. This procedure handles the owner-reported current state:
the model remains at
`/var/lib/videogame-platform/dev/catalogue-models/tcbig-initial`, already selected
in runtime.env, while the helper was temporarily removed during #160 preparation.
Do not reinstall, move, modify or delete those weights. The old application can keep
serving PostgreSQL content during this rollout.

1. Open an owner administration session. Replace the revision placeholder with the
   reviewed merged main SHA containing this change. Use the same host deployment
   lock through installation, targeted rollout and acknowledgement:

   ```bash
   sudo -i
   set -Eeuo pipefail
   umask 0022
   exec 9>/run/lock/videogame-platform-dev-deployment.lock
   flock --nonblock 9
   cd /opt/videogame-platform
   test -z "$(git status --porcelain --untracked-files=all)"
   approved_revision='REPLACE_WITH_REVIEWED_MERGED_MAIN_SHA'
   [[ "$approved_revision" =~ ^[0-9a-f]{40}$ ]]
   git fetch origin main
   git merge-base --is-ancestor "$approved_revision" origin/main
   git checkout --detach "$approved_revision"
   test -z "$(git status --porcelain --untracked-files=all)"
   ```

   Keep the installed checkout root-owned and unwritable by unprivileged users, as
   required by [restricted host installation](#restricted-host-installation).
   `umask 0022` keeps checked-out files world-readable: Grafana and Prometheus mount
   them directly and fail on `0640` files. Protected secret directories keep their own
   restrictive modes.
   A failed command means stop and investigate before acknowledging or dispatching.
   If unrelated runtime changes are included in the revision, review/apply their
   own rollout first; this procedure authorizes only localization.

2. Preserve the existing model selection and check its manifest/revision. If its
   selection is missing, use an owner editor to restore this one line in runtime.env
   while preserving all other entries:

   ```bash
   grep --fixed-strings --line-regexp --quiet \
     'CATALOGUE_TRANSLATION_MODEL_DIR=/var/lib/videogame-platform/dev/catalogue-models/tcbig-initial' \
     /etc/videogame-platform/dev/runtime.env
   test -s /var/lib/videogame-platform/dev/catalogue-models/tcbig-initial/manifest.json
   test -s /var/lib/videogame-platform/dev/catalogue-models/tcbig-initial/revision.txt
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env
   compose=(docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file /opt/videogame-platform/deploy/private-dev/compose.yaml --profile application)
   "${compose[@]}" ps --all --format json \
     >/var/lib/videogame-platform/dev/localizer-runtime-before.json
   ```

3. Build and recreate **only catalogue-localizer** from the new base definition:

   ```bash
   "${compose[@]}" build --pull catalogue-localizer
   "${compose[@]}" up --detach --no-deps --force-recreate \
     --wait --wait-timeout 180 catalogue-localizer
   localizer_id=$("${compose[@]}" ps --quiet catalogue-localizer)
   actual_hash=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$localizer_id")
   test "$("${compose[@]}" config --hash catalogue-localizer)" = "catalogue-localizer $actual_hash"
   "${compose[@]}" ps --all --format json \
     >/var/lib/videogame-platform/dev/localizer-runtime-after.json
   ```

   Compare the two inventories: application, PostgreSQL, Keycloak and every
   observability container must retain their IDs. The helper healthcheck loads and
   validates the mounted model/tokenizers; fix mount readability for UID 10001 or a
   failed manifest before proceeding. Do not run project-wide `up`, `down`,
   `--remove-orphans` or `--volumes` for this migration.

4. Only after the actual rollout passes, perform the protected contract
   [applied-runtime acknowledgement](#acknowledge-the-applied-runtime-contract)
   from this clean installed revision. Release the lock and leave the root session:

   ```bash
   flock --unlock 9
   exec 9>&-
   exit
   ```

   Dispatch **Promote application to private dev** on main through the
   [normal promotion procedure](#promote-and-verify). This first promotion gives the
   recreated application its base endpoint; it also replaces a retired overlay
   label if present. Confirm the endpoint from container configuration and that the
   helper's ID/image, model mount and health remain unchanged. Record evidence in #257.

If helper rollout fails, keep the existing application and all PostgreSQL data.
Do not acknowledge or dispatch. Diagnose/recreate only the helper with the reviewed
image/model. A checkout change alone cannot roll back a container; use the explicit
runtime rollback below. Keep old model directories and image identities for recovery.

### Subsequent runtime or model rollout

Normal backend/frontend promotion runs preflight, the one-shot migration, application
recreation and smoke; it leaves the localizer and other persistent services intact.
A stopped, missing or unhealthy localizer refuses promotion, and a differing service
hash or local image ID requires its explicit rollout. Use the same installed Compose
version for rollout and preflight; after a Compose upgrade, review/reapply the helper
if its resolved hash changes.

For a helper runtime/image/configuration change, review/install its matching checkout,
hold the same deployment lock, then use step 3's targeted build/recreation. For a model
change, install into a **new immutable directory** using the
[backend model procedure](../../backend/README.md#catalogue-translation-runtime-and-models),
change only its protected selection and recreate only the helper; no application
recreation is needed for its stable internal endpoint. For rollback, select the retained
previous model/image and apply its reviewed helper definition with the same targeted
command. Verify health/configuration/image and acknowledge the matching Git contract
only after the required rollout. Model bytes are immutable; never edit a mounted
directory in place. Successful PostgreSQL translations survive helper/model changes.

During diagnosis, stopping the helper leaves product reads and valid synchronization
available; it temporarily blocks new application deployment. Restore it with the
targeted command before resuming promotion. Once running, its restart policy recovers
it after a host reboot; an intentionally stopped container must first be started.
The [operations runbook](../../docs/development/operations-runbook.md#catalogue-localization)
owns retry/diagnosis and the pending real-host lifecycle acceptance.

## Documentation portal

**Prepared configuration; owner review, deployment approval and real-host acceptance
are still required.** This independent static service uses
[the portal workflow](../../documentation/README.md) and the separate
[Compose project](../../documentation/deploy/compose.private-dev.yaml). Application promotion, migration,
telemetry and identity procedures do not start or replace it.

### Endpoint and policy preflight

The executable route owner is [`documentation-serve`](../../documentation/deploy/documentation-serve):
tailnet HTTPS **9443** proxies only **127.0.0.1:4322**. Keep application **443**,
Keycloak **8443** and the separate Grafana route in #296 intact; **10000** is reserved
for that Grafana work pending its own operator review. A repository reservation is
not evidence that a live port is free. Before every first install/rebuild:

1. From an authorized owner device, read `tailscale serve status --json` and
   `tailscale funnel status` on `vgpdev`. Save a protected snapshot outside Git.
   Inspect all Serve/Services configuration, Docker mappings and `ss -4 -lntp` /
   `ss -6 -lntp`. Stop if 9443 or 4322 has another owner. Never use `serve reset`.
2. Review the **effective** tailnet ACL/grants in the Tailscale admin policy editor.
   Merge the [policy example](../../documentation/deploy/tailscale-policy.hujson.example), substituting
   the owner identity and host address only in the protected policy. Permit only
   the owner on host TCP 9443. Remove any wider overlapping allow; grants are
   additive, so the example alone cannot restrict an existing allow-all policy.
   Preserve the promoter's SSH-only restriction and all other approved access.
3. Run the policy editor's tests against the whole policy: owner accept, a real
   non-owner tailnet identity deny, and `tag:vgp-deploy` deny for 9443. Repeat
   for the host's Tailscale IPv6 address when used. Inspect group membership,
   wildcard/autogroup rules, tags, shared-node access and any separate grants that
   could authorize this destination. Keep the policy/test evidence protected.
   Do not apply ingress until those tests pass; the script's explicit review flag
   acknowledges this operator step and does not evaluate remote policy itself.
4. Keep Funnel, router forwarding/UPnP and public ingress disabled. Public IPv4 and
   every global IPv6 address require separate non-tailnet denied-access evidence.
   No extra login, OAuth client or authentication proxy is needed: this entire
   knowledge base is private to authorized owner devices.

### Build and install after approval

Build on the owner workstation/CI runner, not the 8 GB host. Use a clean reviewed
source revision after the owner has approved publication of that revision; a build
from uncommitted work is only local review evidence. The source label and saved
image ID bind the install to the reviewed content. No registry is required.

```bash
npm ci --prefix documentation --ignore-scripts
(cd documentation && npx playwright install --with-deps chromium && npm run validate)
bash documentation/scripts/test-container.sh
revision="$(git rev-parse HEAD)"
docker build --build-arg SOURCE_REVISION="$revision" \
  --tag "vgp-documentation:$revision" documentation
image_id="$(docker image inspect --format '{{.Id}}' "vgp-documentation:$revision")"
docker save --output documentation/.cache/documentation-image.tar "$image_id"
sha256sum documentation/.cache/documentation-image.tar
```

Transfer only the reviewed static image and portal deployment sources after approval.
These files are installed independently of the application's privileged checkout:

```bash
# Owner workstation, after review and deployment approval:
scp documentation/.cache/documentation-image.tar vgpdev:/var/tmp/vgp-documentation-image.tar
rsync --archive --relative documentation/deploy/ vgpdev:~/vgp-documentation/
```

On `vgpdev`, compare the printed tar checksum with the workstation checksum before
loading it. Verify the loaded image ID and revision label against the reviewed build:

```bash
cd ~/vgp-documentation
sha256sum /var/tmp/vgp-documentation-image.tar
docker load --input /var/tmp/vgp-documentation-image.tar
image_id="sha256:<verified-image-id>"
docker image inspect --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}' "$image_id"
```

Keep the previous image and protected `documentation.env` for rollback. Create
`/etc/videogame-platform/dev/documentation.env` with
`DOCUMENTATION_IMAGE=sha256:<verified-image-id>` using the host-foundation ownership
and protected-file procedure; the operator running Compose must be able to read it.
The image must already be loaded. The file contains an immutable identity, no
credentials. From `~/vgp-documentation` on the host:

```bash
docker compose --env-file /etc/videogame-platform/dev/documentation.env \
  --file documentation/deploy/compose.private-dev.yaml up --detach --no-deps --wait documentation
curl --fail http://127.0.0.1:4322/
documentation/deploy/documentation-serve apply --owner-policy-reviewed
documentation/deploy/documentation-serve check
```

The container is non-root, read-only and bounded, with no application/data/telemetry
networks or secrets. Compose owns resource/log limits. `restart: unless-stopped`
and Serve `--bg` persist across restart; Docker and `tailscaled` must remain enabled
under the host-foundation procedure. On rebuild, reload the reviewed image, recreate
only this project and reapply the route after the effective policy preflight.

### Host acceptance

From an authorized Tailscale owner device, open
`https://<vgpdev-tailnet-dns-name>:9443/` without an SSH tunnel. Verify trusted TLS,
landing/sidebar/breadcrumb navigation, a deep link with a heading fragment, Pagefind
search returning a result, both C4 views and the long synchronization/OIDC sequences
in light/dark themes at desktop and phone widths. Check the diagram full-size control,
scrolling, SVG and original-source links. Search in the deployed production build
must work with third-party requests blocked.

Confirm with Docker inspection and both `ss` address families that only host IPv4
loopback publishes 4322 and no global/LAN/IPv6 upstream is bound. From outside the
tailnet probe public IPv4 and every global IPv6 address on 4322 and 9443; also probe
the LAN address. Every request must fail. Verify a disallowed tailnet identity is
denied (or retain the official effective-policy denied-test evidence where that
identity cannot be exercised). Recheck Funnel and compare the saved Serve snapshot,
excluding only 9443. Recheck application browsing/login, Keycloak discovery, and any
installed Grafana route. After reboot, repeat HTTPS, search, links, bindings and policy
checks. Measure idle/representative RAM/CPU and host headroom; configured limits alone
do not establish capacity. Record acceptance in the issue/PR and the runbook's evidence
boundary; local browser/container checks cannot supply this evidence.

### Isolated rollback and removal

To revert content, restore the previous immutable `DOCUMENTATION_IMAGE` in the
protected file and run only the same documentation Compose `up` command. To remove:

```bash
documentation/deploy/documentation-serve remove
docker compose --env-file /etc/videogame-platform/dev/documentation.env \
  --file documentation/deploy/compose.private-dev.yaml down
```

Remove only the portal grant/test entries after removing the endpoint; retain other
policy rules and image evidence until rollback is no longer needed. Verify the
application, Keycloak and Grafana endpoints still work. The helper refuses unrelated
9443 configuration and checks that every other Serve route is preserved. Never reset
Serve, run application migrations, or bring down the application's Compose project
as part of portal removal.

### Abuse-control rehearsal (#274)

The [platform abuse boundary](../../docs/architecture/deployment/mvp-platform-and-delivery.md#abuse-and-overload-boundaries)
owns the admission design, edge hypotheses and residual email-quota risk. The
[security signal guide](../../docs/development/observability.md#identity-and-overload-security-signals)
owns counters and their interpretation. These changes are prepared for review;
no running private-dev service is changed by editing this checkout.

After a separately approved application/Keycloak rollout, check the selected runtime
settings and observe existing Grafana HTTP/Hikari signals. Native security counters
are available through the existing optional `compose.product-growth.yaml` overlay;
its product-success series keep their previous behaviour. The new security job must
be `up`; a missing counter before its first event is normal. Keep management and
metrics private. Do not send signup/reset bursts to the live realm: private-dev uses
Mailjet, so such a check can consume the real shared allowance and send real mail.

API admission is off by default. Once the reviewed application image contains this
increment, protected `runtime.env` controls `APPLICATION_API_ADMISSION_ENABLED` and
the two `APPLICATION_API_MAX_REQUESTS` / `APPLICATION_API_MAX_WRITES` values in
[`runtime.env.example`](runtime.env.example). Explicitly set the flag to `true` only
for an owner-approved controlled trial; 8/2 are experimental defaults, not production
capacity. Return it to `false` after the trial. Apply changes through the existing
approved recreation/promotion procedure with the same immutable image; editing the
file alone does not change a running container, and no recompilation is required.
Do not set zero to disable admission. Public calibration belongs to #275/#276.

Validate actual Compose interpolation without starting containers, reading protected
runtime files or rebuilding an image:

```bash
python3 scripts/test-api-admission-private-dev.py
```

This uses the real private-dev Compose with disposable placeholder inputs and checks
implicit defaults, explicit enable/overrides and explicit disable. Spring context/MVC
tests separately prove the rendered setting names bind to the feature at runtime.
The production-identity CI gate also runs this Compose check.

Run the focused admission tests locally:

```bash
./mvnw -pl backend test -Dtest=ApiAdmissionConfigurationTest,ApiAdmissionInterceptorTest,SessionSecurityIntegrationTest
```

Rehearse the identity policy on a Docker-capable workstation with
`bash scripts/validate-production-identity.sh`. It creates its own internal network,
synthetic accounts and Mailpit; it never uses the private-dev database or SMTP
credentials. It verifies temporary per-account lockout and recovery, response-shape
comparisons, native security counters and log minimization. Its registration risk
characterization explicitly retains the R17 disclosure and unverified compensatory controls; a green identity-policy gate
is not approval of public exposure. The bounded timing samples are loopback evidence,
not VPS latency or proof of timing indistinguishability. Do not repoint this test at
an existing realm. The Postman collections also assert the safe capacity Problem if
a separately controlled disposable workload returns it; ordinary success tests
continue to reject overload as a successful journey.

A native-distribution loopback run can characterize Keycloak behaviour when Docker
is unavailable, but a dev-file identity database cannot substitute for the disposable
PostgreSQL gate, packaged BFF/browser checks or the actual #275/#276 proxy/VPS proof.
Before public exposure, exercise rate rejection, spoofed forwarding headers,
shared-IP users, direct-port denial, bounded overload/recovery and SMTP quota/failure
handling at that real boundary. None is established by tailnet-only accessibility.
