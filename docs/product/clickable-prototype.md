# Clickable prototype record

- **Status:** Historical validated interaction artefact
- **Figma:** [Open prototype](https://www.figma.com/design/DlnALCtbf4zYjcJDF2ixnK)
- **Start frame:** `01 · Lanzamientos` (`4:346`)
- **Owner:** Ruben Hernandez

The mobile-first medium-fidelity prototype represented the approved journey before
implementation. It contains eight transparently curated games and 23 states. All
games have a navigable page; *Death Stranding 2: On the Beach* has the complete
release → game → inline rating → simulated authentication → `Mis puntuaciones` →
edit/delete path.

The prototype established these interaction decisions:

- aggregate and personal ratings stay visible, separately labelled, and have no
  `/10` denominator;
- personal rating uses an in-context integer 1–10 selector;
- authentication occurs on confirmation and returns to the game context;
- unreleased games cannot be rated and show no invented aggregate/date;
- zero results explain the bounded catalogue;
- edit and delete provide explicit feedback/confirmation.

Its values, dates, counts, authentication, persistence, and recalculation are
demonstration data/behaviour. It is not provider evidence, production specification,
accessibility proof, or demand evidence. The accepted synthetic result and resolved
findings are retained in the [round synthesis](../research/simulated-round-synthesis.md).

Figma owns only the historical visual/interaction artefact. The Product Brief, story
map, domain/application contracts, OpenAPI, and implementation own current product
and technical behaviour.
