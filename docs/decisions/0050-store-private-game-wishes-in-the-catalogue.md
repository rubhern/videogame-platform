# ADR-0050 — Store private game wishes in the catalogue

- **Status:** Accepted
- **Date:** 2026-11-20
- **Owner:** Ruben Hernandez
- **Issue:** [#590](https://github.com/rubhern/videogame-platform/issues/590)

## Context

Players asked for somewhere to keep the games they are waiting for. The accepted
learning MVP left personal lists outside its cut. The first list needs to admit
every locally published game and to join release discovery efficiently. [#591](https://github.com/rubhern/videogame-platform/issues/591)
may extend the same game record with playing states.

## Decision

The owner approved one private library entry per authenticated product user and
game, with **Deseado** as the first state. A wish targets the game as a whole.
Platform targeting and public visibility are outside this slice. Catalogue owns
the relation because it owns the game identity and publishes the releases used by
both the list and its discovery filters. The relation carries the time the wish
was added. Removing the wish removes the initial entry.

Private, principal-scoped reads share the release queries without changing the
public representations or cache keys. The account lifecycle handles the relation
under the existing export and verified erasure procedures. A wish is independent
of a rating, playing status, editorial attention and score.

## Alternatives and consequences

A separate wishlist model would require moving the same identity and meaning if
playing states were added later. Storing per-platform wishes now would introduce
multiple meanings of a wish and change the uniqueness rule. The approved
game-level relation can be extended if the owner approves evidence for either
capability. PostgreSQL can scope joins and pages by user without another service
or a cross-module persistence read.
