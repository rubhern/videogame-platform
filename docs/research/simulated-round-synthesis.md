# Round synthesis — accepted simulated usability round

- **Status:** Accepted as decision-grade internal evidence for the learning project
- **Prototype:** [VideoGame Platform — Prototipo móvil del recorrido MVP](https://www.figma.com/design/DlnALCtbf4zYjcJDF2ixnK/VideoGame-Platform-%E2%80%94-Prototipo-m%C3%B3vil-del-recorrido-MVP?node-id=0-1)
- **Sessions:** Five simulated participant sessions
- **Simulation date:** 2026-07-28
- **Focused regression date:** 2026-07-28
- **Research guide:** `prototype-usability-test-guide.md`
- **Decision use:** Product Brief journey gate for the private learning project, by
  explicit owner decision on 2026-07-28

> These observations remain synthetic and must never be presented as behaviour from
> real participants, market validation, or product–market-fit evidence. Ruben
> Hernandez explicitly accepts them as sufficient internal evidence to exercise the
> product-decision workflow in this private learning project.

## 1. Simulated round result

| Participant | Core journey unaided | Blocking issue | Aggregate vs personal understood | Unreleased rule understood |
|---|---|---|---|---|
| P-01 | yes | no | yes | yes |
| P-02 | yes | no | yes | yes |
| P-03 | no | yes — contradictory personal-rating state | yes, after clarification | yes |
| P-04 | yes | no | yes | yes |
| P-05 | yes | no | yes | yes |

- **Journey result used by the learning project:** 4 / 5 unaided
- **Decision:** **PASS**
- **Reason:** The accepted round reached the four-of-five threshold. The focused
  simulated regression then verified that F-01 is resolved and that no blocking
  issue remains in the corrected rating path.

This pass closes the prototype journey gate for the private learning project. It
does not change the original participant outcomes and does not claim a second
five-participant round.

## 2. What appears to work

### Aggregate and personal rating separation

All five simulated participants distinguish `NOTA MEDIA` from `TU NOTA`. The two-column layout, explicit labels and separate colours work consistently on both the game page and `Mis puntuaciones`.

**Related research question:** RQ-3

**Decision confidence:** Sufficient to retain the pattern for this learning project.

### Return to personal ratings

The post-save call to action and bottom navigation make `Mis puntuaciones` easy to find. Four participants use the direct call to action; the fifth also recognises the persistent navigation.

**Related research questions:** RQ-4, RQ-5

**Decision confidence:** Medium-high for this learning project.

### Delete protection and empty state

All five understand the confirmation step, the consequence of deletion and the resulting empty state. No participant interprets deletion as affecting the aggregate rating.

**Related research question:** RQ-5

**Decision confidence:** High for this learning project.

### Unreleased-game explanation

All five understand that the product does not invent a date and does not accept a rating before release. Two request a wishlist-like action, but that is a product-scope request rather than evidence that the rule is unclear.

**Related research question:** RQ-6

**Decision confidence:** High for comprehension; no evidence for desirability.

### Bounded catalogue framing

The `MUESTRA CURADA · 8` label is noticed early by four participants, and all five understand the catalogue boundary when they encounter the explanation. This reduces the risk that zero results are interpreted as a broken global search.

**Related research questions:** RQ-1, RQ-7

**Decision confidence:** Medium-high for this learning project.

## 3. Findings and resolution

| ID | Finding | Original simulated evidence | Severity | Implemented resolution | Regression status |
|---|---|---:|---|---|---|
| F-01 | Initial card says `TU NOTA 9` before the rating is created | 3/5 notice; 1/5 cannot continue unaided | **Blocking** | All pre-rating entry points now show `TU NOTA · SIN PUNTUAR`; numeric personal ratings appear only in saved/edit states | Resolved |
| F-02 | Zero-result filter action appears disabled or non-actionable | 2/5 hesitate; 1/5 needs level-2 help | Important | The active action is labelled `Ver resultados`; the zero count remains secondary context | Resolved |
| F-03 | `Frescura`, `procedencia` and `precisión` are not plain-language concepts | 3/5 cannot explain at least one field immediately | Important | Labels now use `Última revisión`, `Fuente` and `Detalle de fecha` | Resolved |
| F-04 | Rating edit appears to auto-save without sufficient confirmation | 3/5 wait for a save control or ask whether it changed | Important | Selecting 8 opens an explicit success state; `Hecho` returns to a list showing 8 and a recent-update message | Resolved |
| F-05 | Ambiguous-game platform copy is internally inconsistent | 2/5 notice `PC · CONSOLAS` versus `Plataforma · Por confirmar` | Important | The Witcher IV now states `PC confirmado · consolas pendientes` consistently | Resolved |
| F-06 | Text-only game cards slow recognition and make the prototype feel unfinished | 4/5 mention imagery; 2/5 scan more slowly | Important, not gate-blocking | The eight cards use distinct product-owned colour coding and short editorial identifiers; no third-party covers were added | Resolved for this fidelity |
| F-07 | Catalogue-boundary link is easy to ignore after recovery | 2/5 do not open it | Minor | The zero-result copy now says directly that the curated sample contains eight games | Resolved |
| F-08 | Traceability information feels duplicated | 1/5 explicitly flags it; 2/5 skim it | Minor | Source, date detail, and last review are consolidated into one compact block | Resolved |

## 4. Contradictory evidence to retain

- The bold editorial style helps hierarchy for P-01 and P-04, while P-05 considers the lack of covers unfinished. This does **not** yet justify replacing the visual direction.
- The detailed provenance fields increase trust for P-04 but feel technical to P-02 and P-03. The likely response is progressive disclosure or clearer wording, not removal.
- Two participants want ratings or interest signals before release, while all five understand the current disabled rule. This is a product-policy preference, not a usability failure.
- The Fable hero creates a strong visual starting point, but one participant opens it despite the PS5 task. Its prominence may support discovery while competing with task-oriented filtering.

## 5. Initial expected task performance

This table preserves the risks inferred from the original five simulated sessions.
The focused regression result is recorded in section 8.

| Task | Expected unaided completion | Main risk |
|---|---:|---|
| Discover and filter | 4–5 / 5 | Hero prominence and compact results lacking region |
| Understand game page | 4 / 5 | Technical traceability terminology |
| Rate in context | 4 / 5 | Contradictory pre-existing `TU NOTA 9` state |
| Retrieve, edit and delete | 4–5 / 5 | Edit auto-save feedback |
| Unreleased/ambiguous game | 5 / 5 | Platform wording inconsistency |
| Zero results and boundary | 4 / 5 | Action looks disabled; catalogue link is secondary |

## 6. Scope requests — do not automatically add

The simulated participants request:

- wishlists or `Me interesa`;
- prices and store links;
- trailers, screenshots and real covers;
- direct links to official data sources;
- genres and additional game information.

These requests are plausible, but the simulation provides no evidence of frequency or value. Keep them in a separate opportunity list and do not expand the approved MVP before real observations support the change.

## 7. Implemented prototype changes

The 2026-07-28 revision implements F-01 through F-08. It retains the approved scope,
the eight-game sample, the inline 1–10 selector, the rating prohibition before
release, and the product-owned visual treatment.

## 8. Learning-project readiness decision

**Current readiness:** Proceed to minimum implementation contracts.

### Focused simulated regression

The regression reused the accepted P-03 scenario and inspected every changed
critical path. It was a focused simulation, not a new participant session.

| Check | Expected result | Result |
|---|---|---|
| Pre-rating identity | Aggregate `8,7`; personal state `Sin puntuar` | Pass |
| Rating creation | Inline selector → simulated access → saved personal `9` | Pass |
| Rating edit | Select 8 → visible confirmation → list shows personal `8` | Pass |
| Rating deletion | Confirmation shows 8 → empty personal list | Pass |
| Zero-result recovery | Active `Ver resultados`; eight-game boundary visible | Pass |
| Ambiguous release | PC confirmed, consoles pending, no rating allowed | Pass |
| Rating format | Decimal-comma aggregate; no score uses `/10` | Pass |
| Catalogue navigation | Eight catalogue rows open their game pages | Pass |

The Figma audit found 23 intended states, no missing fonts, no legacy
`Procedencia`/`Frescura`/`Precisión de fecha` labels, and no score formatted with a
denominator. Reviewed mobile screenshots showed no material overflow in the changed
states.

## 9. Gate statement

```text
Learning-project journey gate: PASS
Accepted simulated round: 4/5 unaided
Focused simulated regression: blocking issue resolved
External user validation: NOT CLAIMED
```
