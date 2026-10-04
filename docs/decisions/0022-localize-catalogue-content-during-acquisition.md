# ADR-0022: Localize catalogue content during acquisition

- **Status:** Accepted architecture; implementation awaiting owner review and private-host acceptance
- **Date:** 2026-10-04
- **Owner:** Ruben Hernandez
- **Decision authority:** [#235](https://github.com/rubhern/videogame-platform/issues/235) and explicit implementation instruction
- **Extends:** [ADR-0017](0017-discover-catalogue-members-automatically-from-igdb.md)

## Context and decision

Gameómetro needs Spanish catalogue descriptions without character-priced SaaS or a
translation dependency in visitor requests. Genres and game modes use product-curated
labels associated with stable typed IGDB references. Unknown values and English IGDB
summaries use OPUS-MT through CTranslate2 on CPU. PostgreSQL stores source, provenance
and valid derived content separately. Curation always wins over provider wording.

A narrow Catalogue application port owns fixed English-to-Spanish translation.
A private Python helper keeps one model loaded; its adapter owns the HTTP protocol and
runtime revision. It is an optional acquisition component of the modular monolith,
with no database, discovery, generic language API, scheduler or product endpoint.
Local development extends the same helper definition through an optional Compose
overlay, with loopback access and explicit startup flags alongside observability;
the [local setup](../development/local-setup.md#local-catalogue-translations) owns those commands.
Domain/application never know Python, model formats or CTranslate2 types. The helper
has no published host port in private dev and is absent from product readiness.
Inference runs after an independently valid Game commit, outside PostgreSQL
transactions and revision locks. Failed enrichment cannot roll that Game back.
The localization publication rechecks source and ownership atomically before changing
serving state. Reads remain PostgreSQL-only.

A normalized source fingerprint includes the fixed source/target languages. Durable
PostgreSQL reservations serialize matching in-flight work and fence expired claims.
Completed translations are immutable and reused across Games and replays; target
links retain the last valid content after source changes or failure. An ambiguous
runtime timeout keeps its reservation until expiry. The operator backfill visits
bounded UUID keyset pages of taxonomy and summary targets; successful state is its
progress, and a small local checkpoint resumes batches. Failed batches retain their
cursor. Neither the catalogue nor its translations are copied into application memory.
Acquisition refreshes its existing synchronization heartbeat between bounded targets.
At 25,001 stored summaries, selecting IDs before translation joins and using the
eligible-source partial index visits 101 source rows and performs 101 link lookups,
instead of joining every summary before the page limit. The integration test records
`EXPLAIN (ANALYZE, BUFFERS)` evidence and checks this bound; no latency threshold is
used as a correctness assertion.

The migration is additive and performs no summary inference or summary-table rewrite.
Older application versions ignore derived tables/columns; stopping the helper and
rolling back the application preserves source and acquired Spanish content. Taxonomy
labels changed by curation remain Spanish after rollback. Storage grows with unique
normalized source revisions; a retention policy requires measured storage evidence.

## Model choice and implementation evidence

Choose [Helsinki-NLP OPUS-MT TC-big EN→ES](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-es)
(CC BY 4.0). The [standard model](https://huggingface.co/Helsinki-NLP/opus-mt-en-es)
(Apache 2.0) was the only alternative tested. Model authors are the Helsinki-NLP /
OPUS-MT team; conversion to CTranslate2 INT8 changes the supplied weights' format.
The immutable model archive/repository revisions, checksums, licences and runtime
versions are owned by `tools/catalogue-localization` manifests and installer.
The [CTranslate2 OPUS-MT guide](https://opennmt.net/CTranslate2/guides/opus_mt.html)
provides the supported conversion boundary.

Point-in-time evidence on 2026-10-04: six real IGDB summaries retrieved through the
approved API: Ace Combat 8, Elden Ring, Rushcremental, Young Suns, Disco Elysium and
The Witcher 3, 153–804 characters. Both models used CPU INT8, two intra-op threads,
beam 2, full sentence chunks up to 256 tokens, one warm-up and one bounded sample pass.
No content was truncated; the helper rejects an exhausted output bound. This is a
small implementation comparison, not an exhaustive benchmark or a quality score.

| Observation on the local WSL2 host | Standard | TC-big |
|---|---:|---:|
| Converted weights, vocabularies and tokenizers | 78.7 MiB | 234.0 MiB |
| Peak process RSS | 183.9 MiB | 354.0 MiB |
| Steady RSS after sample pass | 162.9 MiB | 350.2 MiB |
| Warm inference throughput | 5.82 summaries/s | 2.35 summaries/s |
| Sample wall / process CPU time | 1.03 / 2.02 s | 2.55 / 5.00 s |
| Per-summary measured latency | 0.10–0.27 s | 0.30–0.60 s |

TC-big gives more natural imperatives in Ace Combat 8, a clear Spanish expansion of
"life sim" in Young Suns, and readable genre/narrative prose in Disco Elysium and The
Witcher 3. Its larger footprint is modest for the approved 8 GB private host. Proper
names such as FromSoftware, Geralt and Revachol survive; neither model guarantees
canonical Spanish lore terminology, and TC-big's rendering of Tarnished and gender
agreement in Elden Ring need owner quality acceptance. No rewriting or glossary engine
is introduced.

At the measured 2.35 summaries/s, 100,000 similar unique summaries imply roughly
12 inference hours and one million roughly five days, before database, HTTP, long
content, shared-host contention and retry costs. Identical content and curated labels
consume no inference. These estimates are arithmetic extrapolations from a tiny local
sample, not private-host measurements or delivery promises. CPU utilization was about
196% during inference (two cores); background/idle resource behavior and coexistence
with the persistent application remain unmeasured on `vgpdev`.
The runtime-only container also started with the installer-produced TC-big manifest
and returned a valid native translation under the configured limits. One post-request
local observation was 536.5 MiB of cgroup memory (including file cache), with CPU back
at 0.01%; it is neither a sustained idle measurement nor a private-host peak estimate.

The helper image builds locally; CI owns both supported image architectures. The
[operations runbook](../development/operations-runbook.md#catalogue-localization)
owns pending host acceptance and reproducible measurement. Operational installation,
update and private topology belong to the linked canonical operator sources.

## Alternatives and revisit triggers

JNI/JNA adds a JVM/native integration without product benefit. A per-request process
reloads the model, penalizing large backfills. A generic translation service, broker,
paid API or another database adds unsupported operational boundaries. The selected
private helper retains the model with a small fixed acquisition protocol.

Revisit the model only if owner-reviewed quality or measured host constraints are
insufficient. Revisit batching/parallelism when measured broad-catalogue duration is
unacceptable; first tune bounded CPU inference and PostgreSQL access. Do not introduce
distributed infrastructure without a new approved need.
