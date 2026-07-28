# Prototype usability test guide

- **Status:** Ready to run
- **Research type:** Moderated task-based usability test
- **Owner and facilitator:** Ruben Hernandez
- **Last updated:** 2026-07-27
- **Prototype:** [Mobile-first clickable prototype](../product/clickable-prototype.md)
- **Target sample:** Five representative users
- **Expected session length:** 25–30 minutes

This guide tests the usability and comprehension of the approved journey. It does
not test market demand, product–market fit, provider accuracy, or production
performance. Record only real participant observations; do not use synthetic
responses as results.

## 1. Decision to support

The journey gate passes when at least four of five representative users complete:

> release discovery → game page → rating → `Mis puntuaciones`

without assistance or a blocking usability problem.

If the gate fails, revise only the problems supported by observed evidence and run a
focused follow-up round before defining implementation contracts.

## 2. Research questions

1. Can participants understand the bounded-catalogue release view and its filters?
2. Can they interpret platform, region, provenance, freshness, and date precision?
3. Do they distinguish the aggregate rating from their own rating?
4. Can they rate a released game through the inline selector and simulated
   authentication transition?
5. Can they retrieve, edit, and delete the rating from `Mis puntuaciones`?
6. Do they understand why an unreleased game has no numeric aggregate and cannot be
   rated?
7. Can they recover from zero results and understand the catalogue boundary?

## 3. Participant profile

Recruit five Spanish-speaking people who:

- play on at least two platforms or regularly compare platform releases;
- research several recent or upcoming games per month;
- already use a wishlist, backlog, ratings, notes, or another personal record;
- normally begin this kind of journey on a phone.

Avoid recruiting only close collaborators or people who already know the design.
Record relevant behavioural differences, but do not collect unnecessary personal
data.

## 4. Preparation

Before each session:

1. Open the [Figma file](https://www.figma.com/design/DlnALCtbf4zYjcJDF2ixnK).
2. Start presentation mode from `01 · Lanzamientos`.
3. Use a mobile-sized viewport and reset the prototype before the participant starts.
4. Open `Abrir los 8 →` and verify that every catalogue row opens the correct game
   page and can return to the eight-game list.
5. Prepare the observation sheet in section 11.
6. If recording, obtain explicit consent and verify that audio/screen capture works.
7. Ask the participant not to enter a real password or sensitive information. The
   prototype uses a simulated example email.
8. Pilot the full script once with someone who will not be counted in the five-user
   result.

## 5. Facilitation rules

- Ask the participant to think aloud.
- Read each task without describing where to click.
- Allow silence and exploration before intervening.
- Do not praise, correct, or confirm a choice while a task is in progress.
- Ask neutral questions such as `¿Qué esperabas que ocurriera?`
- Separate a prototype defect from participant misunderstanding.
- Record exact behaviour and short verbatim phrases; avoid interpreting during the
  session.

### Assistance ladder

Use the smallest intervention necessary and record its level:

| Level | Intervention | Counts as unaided? |
|---|---|---|
| 0 | No help | Yes |
| 1 | Neutral prompt: `¿Qué intentarías ahora?` | Yes, if no direction is given |
| 2 | Restate the goal without naming a control | No |
| 3 | Point to an area or control | No |
| 4 | Explain or perform the action | No |

## 6. Opening script in Spanish

Read this text naturally:

> Gracias por ayudarme. Hoy vamos a probar un prototipo móvil de una web sobre
> lanzamientos y puntuaciones de videojuegos. Estoy probando el diseño, no tus
> habilidades: si algo resulta confuso, el problema es del prototipo.
>
> Algunas pantallas y datos son simulados. La muestra solo contiene ocho juegos y no
> pretende ser un catálogo completo. Te pediré que pienses en voz alta y me cuentes
> qué esperas antes de pulsar. Yo intentaré no ayudarte mientras haces las tareas.
>
> La sesión durará unos 25 minutos. No introduzcas contraseñas ni datos sensibles; el
> acceso es una simulación. ¿Tienes alguna pregunta antes de empezar?

If recording:

> Me gustaría grabar la pantalla y el audio únicamente para revisar esta prueba.
> Guardaré las notas sin datos personales innecesarios. ¿Me das permiso para grabar?

Record consent as `yes` or `no`. Continue without recording if consent is not given.

## 7. Warm-up

Ask for recent behaviour, not hypothetical preference:

1. `Cuéntame la última vez que buscaste información sobre un juego que acababa de salir o iba a salir.`
2. `¿Qué dispositivo y fuentes utilizaste?`
3. `¿Guardas listas, estados o puntuaciones de los juegos? ¿Dónde?`

Limit this section to three or four minutes.

## 8. Tasks and prompts

### Task 1 — Discover and filter

Give the participant the start screen and say:

> Quieres encontrar un lanzamiento que puedas jugar en PlayStation 5 desde España.
> Busca una opción relevante y abre su ficha.

Observe:

- whether the participant recognises the catalogue as curated;
- whether the eight-game catalogue and its game-page links are discoverable;
- whether search and filters are discoverable;
- whether active filters and result count are understood;
- whether they can reach *Death Stranding 2: On the Beach*.

After completion, ask:

- `¿Qué creías que incluía este catálogo?`
- `¿Qué información te ayudó a elegir el juego?`

### Task 2 — Understand the game page

Say:

> Antes de puntuarlo, explícame qué sabes sobre su lanzamiento y qué confianza te da
> la fecha que aparece.

Do not name the fields first. Observe whether the participant can explain:

- platform and region;
- exact date and date precision;
- provenance;
- freshness;
- manual verification state.

Then ask:

- `¿Qué significa para ti 8,7?`
- `¿Qué diferencia hay entre “Nota media” y “Tu nota”?`

### Task 3 — Rate in context

Say:

> Decide que tu puntuación para este juego es un 9 y guárdala.

Observe:

- whether the personal-rating control looks interactive;
- whether the participant opens the inline 1–10 selector;
- whether the absence of `/10` causes any ambiguity;
- whether the simulated authentication boundary is expected;
- whether they return to the same game and recognise the saved `9`;
- whether aggregate `8,7` and personal `9` remain clearly distinct.

Do not provide real credentials. If asked, say:

> Es una simulación; puedes continuar con el correo de ejemplo que ya aparece.

### Task 4 — Retrieve and maintain

Say:

> Más tarde quieres revisar esa puntuación. Encuéntrala en tu zona personal,
> cámbiala y después elimínala.

Observe:

- whether `Mis puntuaciones` is discoverable;
- whether the participant can identify aggregate and personal ratings in the list;
- whether edit and delete controls are distinct;
- whether deletion requires a deliberate confirmation;
- whether the empty-list state explains what happened and offers a useful next step.

### Task 5 — Unreleased and ambiguous game

Return to releases and say:

> Ahora abre un juego que todavía no tenga una fecha exacta. Cuéntame qué puedes hacer
> con su puntuación y por qué.

Observe whether the participant understands:

- the difference between an unknown date and a missing interface value;
- why the product does not invent a precise date;
- why the aggregate is `No disponible`;
- why the personal rating is disabled until release.

### Task 6 — Zero results and catalogue boundary

Say:

> Busca `Animal Well 2` manteniendo filtros exigentes. Cuando no aparezca ningún
> resultado, averigua qué ha pasado y vuelve a una lista útil.

Observe:

- whether the participant understands zero results;
- whether suggested recovery actions are useful;
- whether the catalogue-boundary explanation is found and trusted;
- whether they can clear the search or filters without help.

## 9. Debrief script

Ask:

1. `¿Qué parte te resultó más clara?`
2. `¿Dónde dudaste más o esperabas otra cosa?`
3. `¿En algún momento confundiste la nota media con tu nota?`
4. `¿Te quedó claro por qué no se puede puntuar un juego que aún no ha salido?`
5. `¿Qué información de la ficha te sobró y cuál echaste en falta para esta tarea?`
6. `Si volvieras dentro de una semana, ¿para qué abrirías primero esta web?`
7. `¿Hay algo más que no te haya preguntado y te parezca importante?`

Do not ask whether the participant “likes” the product as the primary outcome.
Preference can be noted, but observed task behaviour drives the journey decision.

## 10. Classification

Classify each observed issue after the session:

| Severity | Definition | Response |
|---|---|---|
| Blocking | Prevents the core journey unaided or causes a materially wrong understanding | Fix before contracts; retest |
| Important | Creates repeated hesitation, loss of confidence, or avoidable assistance | Fix if observed across participants or clearly affects the gate |
| Minor | Local friction that does not threaten task completion or comprehension | Prioritise after the gate |
| Suggestion | Preference or idea without observed task impact | Keep separate; do not expand scope automatically |

Also classify the cause:

- navigation or discoverability;
- terminology or comprehension;
- visual hierarchy;
- prototype mechanics;
- bounded-content limitation;
- out-of-scope need.

## 11. Session observation sheet

Copy this section once per participant. Use an anonymous ID such as `P-01`.

```markdown
## Participant P-__

- Date:
- Device/context:
- Profile fit:
- Recording consent: yes / no

| Task | Completed | Assistance level | Material observation | Severity |
|---|---|---:|---|---|
| Discover and filter | yes / no | 0–4 | | |
| Understand game page | yes / no | 0–4 | | |
| Rate in context | yes / no | 0–4 | | |
| Retrieve, edit, delete | yes / no | 0–4 | | |
| Unreleased/ambiguous game | yes / no | 0–4 | | |
| Zero results/boundary | yes / no | 0–4 | | |

- Distinguished aggregate and personal rating: yes / no
- Understood rating disabled before release: yes / no
- Core journey completed unaided: yes / no
- Exact participant phrases:
- Prototype defects:
- Out-of-scope requests:
```

## 12. Round synthesis

After all five sessions, create a separate results document containing only observed
evidence. Use this summary:

```markdown
| Participant | Core journey unaided | Blocking issue | Aggregate vs personal understood | Unreleased rule understood |
|---|---|---|---|---|
| P-01 | | | | |
| P-02 | | | | |
| P-03 | | | | |
| P-04 | | | | |
| P-05 | | | | |

Journey result: __ / 5 unaided
Gate: PASS / ITERATE
```

Group repeated observations, retain contradictory evidence, and link findings to the
relevant assumptions. Do not turn one participant preference into a product decision.

## 13. Decision rules after the round

- **Pass:** at least four of five participants complete the core journey unaided and
  no unresolved blocking issue remains.
- **Iterate:** fewer than four complete it unaided, or any blocking misunderstanding
  affects rating identity, authentication return, unreleased-game eligibility, date
  precision, or deletion.
- **Do not expand scope:** requests for wishlists, written reviews, prices, social
  features, or broad catalogue coverage remain observations unless repeated evidence
  changes the approved problem.
- **Proceed after a pass:** update the evidence register, then define only the
  minimum provider-independent contracts required for one vertical slice.

## 14. Data handling

- Use participant IDs rather than names in repository files.
- Do not commit recordings, email addresses, credentials, or raw personal data.
- Store consent and recordings outside Git with an explicit retention decision.
- Include short anonymised quotations only when they materially support a finding.
- Record failures and contradictory evidence; do not optimise the report for a pass.
