# Mobile-first clickable prototype

- **Status:** Validated for the private learning-project journey gate
- **Fidelity:** Medium
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-28
- **Figma file:** [Open the clickable prototype](https://www.figma.com/design/DlnALCtbf4zYjcJDF2ixnK)
- **Start frame:** `01 · Lanzamientos` (`4:346`)
- **Catalogue access:** use `Abrir los 8 →` from the start frame
- **Test guide:** [Prototype usability test guide](../research/prototype-usability-test-guide.md)
- **Round result:** [Accepted simulated synthesis](../research/simulated-round-synthesis.md)

This prototype represents the complete approved learning-MVP journey before
implementation. It is an interaction and comprehension artefact, not production
software, provider evidence, or proof of user demand.

## Purpose

The prototype is designed to test whether a release-aware player can:

1. discover a relevant game in a deliberately bounded catalogue;
2. understand its release context, provenance, freshness, and date precision;
3. distinguish the aggregate rating from their personal rating;
4. create a rating through an inline selector and a simulated authentication
   boundary;
5. retrieve, edit, and delete the rating from `Mis puntuaciones`;
6. understand zero results, ambiguous dates, an empty personal list, and why an
   unreleased game cannot be rated.

## Guided path

The main clickable path starts in `01 · Lanzamientos` and uses
*Death Stranding 2: On the Beach*:

1. open the game page;
2. open the inline 1–10 rating selector;
3. select `9`;
4. continue through the simulated authentication overlay;
5. return to the same game page with the personal rating saved;
6. open `Mis puntuaciones`;
7. edit or delete the rating;
8. reach the empty personal-list state.

Filters, zero results, the bounded-catalogue explanation, the eight game pages, and
the ambiguous-date example are available as secondary paths.

## Curated sample

The catalogue contains eight recognisable games. All eight open a game page from
`14 · Catálogo navegable · 8 juegos`. Coverage is intentionally small and is
declared in the interface rather than presented as an exhaustive database.

| Game | Prototype release state | Demonstrated rating state |
|---|---|---|
| Death Stranding 2: On the Beach | Released | Aggregate `8,7`; personal rating can become `9` |
| Donkey Kong Bananza | Released | Aggregate `8,3`; not personally rated |
| Ghost of Yōtei | Released | Aggregate `8,6`; not personally rated |
| Hollow Knight: Silksong | Released | Aggregate `9,1`; not personally rated |
| Resident Evil Requiem | Released | Aggregate `8,4`; not personally rated |
| Pragmata | Released | Aggregate `7,8`; not personally rated |
| Fable | Unreleased | Aggregate unavailable; personal rating disabled |
| The Witcher IV | Ambiguous future date | Aggregate unavailable; personal rating disabled |

All aggregate values, counts, and personal ratings in the prototype are internal
demonstration data. They are not copied from IGDB, professional reviews, or another
rating product.

## Represented states

| Frame | Purpose |
|---|---|
| `01 · Lanzamientos` | Visual mobile-first landing and release discovery |
| `02 · Filtros` | Platform, region, date-precision, and search controls |
| `03 · Cero resultados` | Recovery from an unsupported search/filter combination |
| `04 · Ficha del juego` | Released game with platform, region, provenance, freshness, and exact date |
| `05 · Fecha ambigua` | Unreleased game with no invented release date |
| `06 · Acceso · Overlay` | Simulated authentication at the rating boundary |
| `07 · Selector de nota · Overlay` | Inline 1–10 rating selection |
| `08 · Ficha · Nota guardada` | Saved personal rating and updated aggregate context |
| `09 · Mis puntuaciones` | Personal rating retrieval and management |
| `10 · Editar nota · Overlay` | Direct rating edit |
| `11 · Eliminar nota · Overlay` | Explicit destructive-action confirmation |
| `12 · Lista vacía` | Empty personal list after deletion |
| `13 · Catálogo acotado` | Transparent sample scope and data disclaimer |
| `14 · Catálogo navegable · 8 juegos` | Complete entry point to all eight curated game pages |
| `15 · Ficha · Donkey Kong Bananza` | Released-game detail with aggregate and unrated personal state |
| `16 · Ficha · Ghost of Yōtei` | Released-game detail with aggregate and unrated personal state |
| `17 · Ficha · Hollow Knight Silksong` | Released-game detail with aggregate and unrated personal state |
| `18 · Ficha · Resident Evil Requiem` | Released-game detail with aggregate and unrated personal state |
| `19 · Ficha · Pragmata` | Released-game detail with aggregate and unrated personal state |
| `20 · Ficha · Fable · Próximo lanzamiento` | Confirmed future release with aggregate unavailable and rating disabled |
| `21 · Mis puntuaciones · Nota actualizada` | Personal list with the edited score and explicit saved feedback |
| `22 · Nota actualizada · Overlay` | Confirmation after changing the personal score |
| `23 · Eliminar nota 8 · Overlay` | Delete confirmation consistent with the edited score |

## Approved rating interaction rules

- The aggregate rating is always visible wherever rating context is presented.
- A numeric aggregate is formatted to one decimal. Spanish product copy uses a
  decimal comma, for example `8,7`.
- Scores never display a denominator such as `/10`.
- Aggregate and personal ratings are labelled and displayed separately.
- The personal rating is one integer from 1 to 10.
- Tapping the personal-rating control opens a compact 1–10 selector in context; no
  separate rating page is required.
- Authentication occurs only when an unauthenticated visitor confirms a rating, and
  successful authentication returns them to the same game context.
- An unreleased game cannot be rated. Its personal selector is disabled and its
  aggregate is shown as `No disponible`; the product must not invent a numeric mean.

## Validation status

The Figma artefact has been checked for:

- all 23 intended states;
- a working route from the complete catalogue to each of the eight game pages and
  back;
- the complete guided path and critical branches;
- visible separation of aggregate and personal ratings;
- no score formatted with `/10`;
- no visual overflow in the reviewed frames;
- an explicit bounded-catalogue and demonstration-data statement.

The owner accepts the five-session simulation for the private learning-project
decision. It reached 4/5 unaided. The 2026-07-28 focused simulated regression
verified that F-01 through F-08 are resolved and that no blocking issue remains, so
the journey gate is `PASS`. This is not real-participant or demand evidence.

## Known limitations

- Authentication, persistence, aggregate recalculation, authorization, loading, and
  backend failures are simulated.
- All eight games have an accessible detail page. Only *Death Stranding 2: On the
  Beach* has the complete authentication, rating creation, retrieval, edit, and
  deletion path wired; the other released-game pages show static aggregate and
  personal-rating states.
- The wired happy path asks the participant to choose `9`; every number is shown,
  but the static prototype does not persist every possible selection.
- Release dates, rating values, and counts are demonstration content and must not be
  treated as current provider data.
- The prototype validates mobile interaction first. Responsive desktop behaviour,
  keyboard details, and production accessibility require later implementation
  checks.
- The artefact cannot demonstrate repeat use, real account-creation abandonment,
  catalogue synchronization, or actual value retention.

## Change control

Figma is the source of truth for the prototype's visual and interaction state.
Repository Markdown remains the source of truth for product rules, assumptions,
decisions, test evidence, and implementation scope. A material prototype change must
update this document, the story map, and any affected product decision.
