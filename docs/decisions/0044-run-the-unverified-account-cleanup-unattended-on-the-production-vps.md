# ADR-0044: Run the unverified-account cleanup unattended on the production VPS

- **Status:** Proposed
- **Date:** 2026-11-14
- **Owner:** Ruben Hernandez
- **Scope:** Where the 30-day unverified-account cleanup runs, which keys and storage identities it
  holds, and how it is scheduled, supervised and kept current across releases
- **Issue:** [#539](https://github.com/rubhern/videogame-platform/issues/539); self-service deletion
  follows in [#543](https://github.com/rubhern/videogame-platform/issues/543)
- **Related:** [ADR-0033](0033-select-lite-3-and-immutable-offhost-storage.md),
  [ADR-0036](0036-use-protected-actions-for-production-recovery.md),
  [ADR-0043](0043-reduce-planned-downtime-with-online-captures-and-an-application-handover.md)

## Context

The public privacy text promises that an account whose email is still unverified 30 days after its
creation is deleted by the next periodic cleanup. The cleanup exists
(`personal-data-ledger cleanup-unverified --execute`), but it only runs when the owner starts it, so
nothing keeps that promise. Public registration opened on 2026-11-08, so the first accounts reach
30 days on 2026-12-08.

The [data-protection record](../development/data-protection.md) assumed an owner-attended run from an
off-VPS context:
- archives encrypted to the owner's public key, with no decryption key on the VPS;
- manifests signed with an owner key held off the VPS;
- evidence that survives the loss of the web host.

An unattended run cannot wait for an owner unlock, and an owner machine is not always on. Two facts
change the balance:

- **Durability no longer depends on where the run happens.** Production erasure requires
  `--cloud-copies`. Every ledger append and archive write returns only after two OVHcloud copies, in
  separate projects and regions, acknowledge an exact read-back version, and the ledger copies carry
  a Compliance lock (#276, L8).
- **The archive code decrypts during sealing.** It decrypts to verify every sealed source, and its
  key preflight is an encrypt and decrypt round trip. An unattended run needs a decryption key
  wherever it runs, unless the evidence protocol changes.

The owner chose the VPS and a dedicated archive key pair on 2026-11-14.

## Options considered

| Option | Why chosen or not |
|---|---|
| Owner machine with owner-unlocked keys | Rejected: no run while the machine is off or locked, so the 30-day promise depends on attendance |
| VPS, encrypt-only, decryption checked monthly off the VPS | Rejected for now: changes the evidence protocol and its tests, and proves readability up to a month late |
| VPS with a dedicated archive key pair and a dedicated signing key | Chosen: no protocol change, runs every day, and the keys never sign or decrypt anything else |
| A separate host or managed scheduler | Rejected: new cost and topology without a measured need |

## Decision

- **The cleanup runs on the production VPS from a systemd timer, once a day at 06:15 UTC.** That
  time is clear of the 02:30 and 14:30 UTC captures and the catalogue synchronizations.
  `bin/unverified-cleanup run` first completes any incomplete erasure with `retry`, then runs
  `cleanup-unverified --execute` until nothing eligible remains, for at most five bounded rounds. It
  goes through the existing transport, deny/drain fence, legal archive, suppression proof and
  two-copy cloud ledger. It adds no deletion logic and never admits a new cleanup over a pending one.
- **Custody is dedicated and narrow.**
  - A passphrase-less **signing key** signs only deletion evidence.
  - A passphrase-less **archive key pair** encrypts and decrypts only the legal archive.
  - Neither key is the owner's personal key, and neither is used for backups.
  - **Four S3 identities** (two ledger, two archive) can write, read back and verify, and cannot
    delete. They are distinct from the backup identities.
  - The **Keycloak administrative credential** the transport already uses, read from an owner-only
    file and passed only on standard input. A narrower cleanup-only role is a later improvement,
    not a precondition.
  - An exported copy of both secret keys is kept in the owner's separately protected
    reconstruction material. Without it, losing the VPS would make the cleanup's legal archives
    unreadable. Restore replay, archive review and purge then use those keys and
    fingerprints off the VPS.
  - The keyring, ledger copies, archive roots, proof store and holds live under the protected
    administration directory, owner-only and outside Git. The archive purge, which deletes, remains
    an owner-run operation with its own credentials.
- **Releases keep the unit current.** Once installed, the cleanup service is held to the same
  reviewed `ExecStart` pattern as the backup services and repointed by every deployment. The timer
  is never changed by a deployment.
- **A failure is visible.** The run writes a bounded status beside the backup status, and host
  telemetry raises *Unverified-account cleanup needs the owner* when a run fails or no run has
  succeeded for 36 hours. A run that is killed can leave its identity fence closed; that is an
  owner incident with the existing release procedure, never a file deleted by hand.

## Consequences

- The 30-day promise is kept without owner attendance, as long as the VPS runs.
- **Accepted residual risk.** Whoever controls the VPS can:
  - read the archived email and creation time of deleted accounts for their three-year retention;
  - append forged ledger entries, archives or proofs.

  They cannot remove or alter existing ledger versions, which the Compliance lock protects. Such an
  attacker already holds the live database, the identity data and the Keycloak administrator
  credential.
- The rule that no decryption key is on the VPS, and the off-VPS signing key, no longer hold for
  this path. The data-protection record states the exception. Backups keep their public-only keyring
  on the VPS.
- Each daily run that finds candidates closes the identity host for at most two minutes per round
  behind a maintenance response. Product reads and existing sessions continue.
- Restore replay is unchanged: the ledger is fetched from its cloud copies and every recorded erasure
  is replayed before reopening.

## Revisit when

- Self-service deletion (#543) needs a long-running runner on the same custody.
- The archive should become unreadable from the VPS: adopt the encrypt-only protocol.
- A VPS compromise, or a change in retention, makes the residual risk unacceptable.
