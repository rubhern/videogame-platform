# ADR-0035: Separate private-production administration ingress

- **Status:** Proposed
- **Date:** 2026-11-07
- **Owner:** Ruben Hernandez
- **Related:** [#464](https://github.com/rubhern/videogame-platform/issues/464), [ADR-0025](0025-host-public-production-on-a-deferred-single-vps.md)

## Context

The installed private production runtime has no published Docker ports. Product HTTPS
already crosses a host loopback bridge to Caddy's approved fixed address. A temporary
Grafana bridge proves feasibility but follows a dynamic upstream address and a terminal.
Keycloak's public identity allowlist deliberately denies master and administration.

`hostname-admin` separates the console and REST origin, but the console's authentication
URLs still use the frontend URL. Keeping master's frontend on the public identity origin
would send its login into the public deny rule. See the
[Keycloak hostname guidance](https://www.keycloak.org/server/hostname) and
[the deployed version's console implementation](https://github.com/keycloak/keycloak/blob/26.7.5/services/src/main/java/org/keycloak/services/resources/admin/AdminConsole.java).
The owner explicitly authorized changing only `master.attributes.frontendUrl` for this
implementation; that authorization does not permit a live rollout.

## Decision

Prepare two dedicated owner-only Tailscale Serve HTTPS ports backed by hardened systemd
loopback bridges to two additional HTTP listeners on Caddy's existing fixed Docker edge
address. Caddy resolves Grafana and Keycloak by Docker service name. No additional proxy,
Keycloak instance, Docker publication or product-route change is needed. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#private-production-administration)
owns the topology; the [operator guide](../../deploy/public-production/README.md#private-production-administration)
owns rollout, ACL verification, acceptance and withdrawal.

Keep Keycloak's canonical hostname and the product realm unchanged. Set the separate
admin hostname and align only master's frontend URL under the existing upgrade lease,
backup fence and identity fence. Snapshot its previous attribute before applying and
refuse an unexpected pre-existing URL. Compare the master realm representation after
alignment; all other effective fields must remain unchanged. Keycloak may materialize
known typed defaults as identical string attributes; only exact equivalent aliases are
normalized for comparison. Reauthenticate the operator after the master issuer changes;
existing master tokens need a new login, while product tokens and sessions are unaffected. Public master and administration
routes remain denied independently of hostname and forwarded headers.

Grafana retains native authentication, its volume and provisioning. Set its external
HTTPS root and Secure cookies through the private adapter. The upgrade admits only
these exact setting changes, preserves all other protected settings and named volumes,
and binds its review hash to the administration migration. No runtime input is edited.

Install Serve and its bridges only after that upgrade completes and after an owner
review of the effective tailnet policy. Policy hashes are acknowledgements, not evidence
that another additive ACL/grant cannot authorize a non-owner. Keep actual positive and
negative device tests as acceptance requirements. Refuse conflicting routes and Funnel;
never reset Serve. Private listeners also honor recovery and identity-maintenance gates.

## Alternatives and consequences

- Publishing application ports on host loopback would simplify the bridge, but changes
  the approved zero-publication runtime and its upgrade invariants.
- Resolving upstream container addresses on the host would add discovery and restart
  synchronization. The already fixed Caddy peer is simpler and keeps upstream discovery
  inside Docker.
- Changing master's frontend URL is a narrow durable identity change. It avoids response
  rewriting, a custom hostname provider, public master routes and a second identity
  server. Master's URLs and issuer become private; the product realm's issuer stays exact.
- Withdrawal disables only the two Serve routes and bridge units. Runtime settings and
  master's private URL remain dormant, so withdrawal never downgrades an image or changes
  database state. Restoring the old attribute requires a separately reviewed forward
  change under the upgrade recovery procedure; the pre-change snapshot is retained.
- Host-to-edge reachability, real tailnet authorization, browser operation, independent
  public IPv4/IPv6 refusal and reboot persistence require authorized VPS evidence. Local
  tests cannot establish them. The previous manual Grafana test is not final acceptance.

Revisit if the host edge address/topology changes, additional administrators are approved,
Keycloak offers a native separate admin authentication origin, or measured contention
justifies a different ingress design. No paid service or public exposure is authorized.
