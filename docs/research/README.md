# Research

Store evidence produced during product discovery here.

## Current artifacts

- [Synthetic interview preparation](phase-1-user-interviews.md): research rehearsal
  and validation plan; not user evidence.
- [Metacritic journey comparison](competitor-journey-comparison-metacritic.md):
  competitor evidence and opportunity hypotheses.
- [Prototype usability test guide](prototype-usability-test-guide.md): moderated
  Spanish participant script, observation template, and the four-of-five journey
  decision rule.
- [Accepted simulated session sheets](simulated-session-observation-sheets.md): five
  synthetic session records accepted for the private learning-project decision.
- [Accepted simulated round synthesis](simulated-round-synthesis.md): 4/5 unaided
  plus a focused simulated regression, with a final `PASS` decision.
- [Game-data-provider spike](game-data-providers-spike.md): documentary comparison
  and the conditional technical decision after the first authenticated IGDB PoC.
- [IGDB PoC control sample](igdb-poc-sample.csv): frozen 60-case sample used by
  the approved acceptance thresholds.
- [First authenticated IGDB PoC results](igdb-poc-results.md): reviewed
  `CONDITIONAL_PASS`, accepted limitations, and product decision.
- [IGDB PoC results template](igdb-poc-results-template.md): review structure
  for future authenticated evidence.
- [IGDB PoC tool](../../tools/igdb-poc/README.md): isolated Java CLI for
  authenticated capture and deterministic offline validation.

## Closed prototype evidence

The focused simulated regression is recorded in the synthesis and observation-sheet
appendix. It resolves the blocking and important prototype findings for the current
learning objective. Further prototype testing is optional unless the journey rules,
release mode, or evidence objective changes.

The owner accepts the simulated round as decision-grade evidence for this private
training product. Its synthetic provenance remains mandatory: it cannot support
claims about real-user behaviour, demand, retention, or product–market fit.

The private learning MVP may load attributed covers directly from the IGDB CDN under
[ADR-0001](../decisions/0001-reference-igdb-cover-images.md), without copying provider
image binaries. Public or commercial provider terms, copied-image or
application-storage rights, and redistribution remain deferred until the release mode
changes.

Do not commit personal data, credentials, provider secrets, or material that cannot be
stored in this repository.
