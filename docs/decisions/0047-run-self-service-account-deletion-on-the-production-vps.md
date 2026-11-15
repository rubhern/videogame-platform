# ADR-0047: Run self-service account deletion on the production VPS

- **Status:** Proposed
- **Date:** 2026-11-15
- **Owner:** Ruben Hernandez
- **Scope:** Public production runtime
- **Issue:** [#543](https://github.com/rubhern/videogame-platform/issues/543)
- **Related:** [ADR-0043](0043-reduce-planned-downtime-with-online-captures-and-an-application-handover.md),
  [ADR-0044](0044-run-the-unverified-account-cleanup-unattended-on-the-production-vps.md)

## Context

The application already implements self-service deletion. A signed-in user's request is committed
in PostgreSQL. An executor then claims it through the private `accountdeletionexecutor` management
operation, and the existing ledger erases the account with independent evidence. Production keeps
the executor off, so «Mi perfil → Eliminar mi cuenta» reports that deletion is unavailable, and a
user must write to the contact address.

ADR-0044 placed the unattended cleanup and its custody on the VPS: a signing key, an archive key
pair, two cloud ledger copies, two cloud archive copies and the identity transport and fence. It
listed a long-running self-service runner on the same custody as a reason to revisit. Two facts
shape the runner:

- **Management is loopback-only.** The management port answers only on the application
  container's loopback, and its other operations carry no authentication of their own.
- **Releases replace the application.** Upgrades and ADR-0043 handovers restart it.

## Decision

A supervised systemd runner on the VPS processes self-service requests with the ADR-0044 custody.

- **Activation:**
  - One owner input, `PRODUCTION_ACCOUNT_DELETION_ENABLED`, turns the executor on. Its capability
    lives in the protected secrets directory, owned by the application UID with mode `0600`.
  - The upgrader adds the settings and the empty capability off. It turns them on only forward,
    after checking the capability, and refuses a release that would silently disable them.
- **Custody:** the runner reads the cleanup's protected configuration, so both erasures use one
  ledger, one archive, the same keys, the same identity transport and the same deny/drain fence.
  Each erasure enters the fence for its own steps, including when it resumes after an
  interruption.
- **Transport:** each exchange runs `docker exec` into the one running application container and
  reaches the loopback management port with the image's `nc`. The capability travels on stdin,
  never in a command line. Nothing is published.
- **Coordination:**
  - While an upgrade or handover journal is active, the runner neither sends a heartbeat nor
    claims. Admission therefore closes within 60 seconds, and no revocation or erasure races the
    release.
  - A backup fence stops the application, so exchanges fail and the runner retries.
  - The ledger lock serializes the runner and the daily cleanup.
- **Supervision:** every deployment holds the unit to its reviewed command line, repoints it and
  restarts it onto the new release without waiting. Stopping the unit is the reviewed way to refuse
  new requests.

## Alternatives considered

- **Publish the management port to host loopback:** the port must then listen on every container
  interface, which exposes the unauthenticated operations to the edge and the data networks.
- **A runner off the VPS:** it needs a second always-on host or a tunnel into the management port,
  a paid or new service that the bounded budget excludes.
- **Keep deletion by email:** no new process, but users depend on the owner's manual erasure.

## Consequences

- **Residual risk:** ADR-0044's residual risk now also covers self-service erasures. A VPS
  compromise can read the archived emails of deleted accounts.
- **Identity outage:** each self-service erasure closes the identity host behind its maintenance
  response for its steps, as a cleanup round does.
- **Contention:** a self-service erasure and the daily cleanup can collide on the ledger lock. The
  loser retries: five minutes for the runner, the next day for the cleanup, with its alert.
- **Releases:** an upgrade pauses admission for its duration, and a request already admitted waits
  until the release ends.

## Revisit when

- Deletion volume makes the per-erasure identity fence noticeable.
- The archive must become unreadable from the VPS (ADR-0044).
- The management port gains authentication for every operation, which would allow a direct
  private listener.
