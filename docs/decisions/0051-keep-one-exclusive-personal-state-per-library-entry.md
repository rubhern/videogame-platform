# ADR-0051 — Keep one exclusive personal state per library entry

- **Status:** Proposed
- **Date:** 2026-11-22
- **Owner:** Ruben Hernandez
- **Issue:** [#591](https://github.com/rubhern/videogame-platform/issues/591)

## Context

[ADR-0050](0050-store-private-game-wishes-in-the-catalogue.md) stores one private
library entry per authenticated product user and game, with **Deseado** as its first
state, and anticipated that playing states could extend the same entry. Players also
want to record where each game stands for them: Pendiente, Jugando, Completado or
Abandonado.

## Decision

The owner approved on 2026-11-22 that the library entry carries exactly one state from
**Deseado**, **Pendiente**, **Jugando**, **Completado** and **Abandonado**. The state
is game-level: the platform played stays with the per-platform rating
([ADR-0031](0031-count-one-community-vote-per-rating-across-platforms.md)). The entry
records only when it last entered its current state.

The wishlist is the set of entries in the Deseado state. Leaving Deseado drops the
game's wishlist position and save time; entering it again joins the end of the list.
Removing a wish removes only a Deseado entry, while removing a game from the library
removes the entry whatever its state. Saving a wish converts an entry in another state.

Until a game has a release that has occurred, only Deseado can be chosen for it
(owner decision, 2026-11-22). "Occurred" is the catalogue's effective release status
`released`, evaluated on the trusted Europe/Madrid date. Entering any other state checks
this under the owner's lock, in the same transaction as the write. A state an entry
already holds is kept if the release is later delayed or withdrawn.

A database constraint keeps one entry per user and game, and another keeps the
wishlist fields present exactly while the state is Deseado. Every change for one user
serializes on the same transaction-scoped lock the wishlist order already uses.

States are private. They never change ratings, community scores, rankings or attention,
and a rating never changes a state. The interface may offer rating a game after it
becomes Completado or Abandonado, but never rates it. Public exposure belongs to
[#593](https://github.com/rubhern/videogame-platform/issues/593).

## Alternatives and consequences

An independent wish flag beside the playing state would let a game be wished and
Pendiente at once. It would also need a second set of rules, double the selector
states and make the wishlist ambiguous. Per-platform states would change the uniqueness
rule and duplicate what ratings already record. Start and finish dates were deferred:
the first version keeps only the last change time, and adding dates later needs only
new columns.

A client from the previous release that saves a wish during an upgrade still works,
because the new columns have defaults. Such a client briefly lists entries in other
states as wishes until the upgrade completes.
