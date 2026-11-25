# ADR-0053 — Publish revocable player profiles by alias

- **Status:** Proposed for technical review and production acceptance
- **Date:** 2026-11-24
- **Owner:** Ruben Hernandez
- **Issue:** [#593](https://github.com/rubhern/videogame-platform/issues/593)

## Context

The [Product Brief](../product/product-brief.md#opt-in-public-player-profiles)
approves a shareable profile whose audience and fields the player controls. The
same alias can identify the author of a future review. The sign-in identity is private.

## Decision

Identity owns the alias and the sharing settings. PostgreSQL stores the canonical
lowercase alias with a primary key and one current assignment per account. An
account-scoped transaction lock and the submitted revision serialize changes,
including concurrent first claims. A conflicting claim rolls back any attempted
retirement of the previous alias. A retired public alias retains its spelling,
with its owner, timestamps, revision and visibility removed.

API delivery combines the Identity policy and bounded Ratings and Catalogue
reads. Each collection uses a user-scoped count and one page in PostgreSQL. Only
public catalogue fields and the shared rating's single score are selected. The
section's visibility is checked both before and after its page is prepared.
A database constraint requires every public profile to have a shared section
and every private profile to have both switches off.

Serve the shell for `/u/{alias}` using
[ADR-0049](0049-insert-link-preview-metadata-into-the-served-entry-point.md).
An indexed lookup supplies the canonical alias. The HTML carries `noindex` and
stays outside the sitemap. Profile HTML and JSON, including misses, use
`Cache-Control: no-store`. A missing, retired or private alias returns 404.
Third parties can retain previously shared previews or copies.

The account-deletion admission fence hides the profile from public reads. The
protected erasure and replay procedure retires the alias and verifies that no
assignment remains before completion. Moderation uses the same persistence
operation from the protected production host. See the
[data-protection record](../development/data-protection.md#record-of-processing-activities)
and [operator guide](../../deploy/public-production/README.md#moderate-a-public-player-profile).

## Alternatives and consequences

Using sign-in usernames would couple authentication to public handles. Copying
ratings and library rows into Identity would add synchronization and withdrawal
lag. Shared caches with a freshness lifetime could outlive withdrawal. Indexed
local access gives a constant number of data calls and memory proportional to
page size. Exact counts and deep offsets retain the cost of the current paging
contract. Ratings' existing listing projection carries the first known release
date and Catalogue's current Visits signal. The existing keyset rebuild fills
ordered fields for previously rated games. A bounded Catalogue synchronization
event refreshes the Visits field from the accepted signal within the same transaction.
Statistics read fixed-size aggregates from Ratings and Catalogue for the sections
that are still public at delivery.

An archived database can only reserve spellings it contains. Recovery replays the
independent erasure ledger before reopening. A restore that discards unbacked
commits can lose later alias claims or withdrawals. Keep that candidate fenced
during the profile-specific owner review in the operator guide.
