# IGDB authenticated PoC results

- **Status:** Reusable template
- **Decision owner:** Ruben Hernandez
- **Control sample:** [`igdb-poc-sample.csv`](igdb-poc-sample.csv)
- **Acceptance criteria:** Section 10.4 of
  [`game-data-providers-spike.md`](game-data-providers-spike.md)
- **Execution tool:** [`../../tools/igdb-poc/README.md`](../../tools/igdb-poc/README.md)

This is a reusable review template for future authenticated runs. It contains no
provider results and must not be presented as evidence. The reviewed first
execution is documented in [`igdb-poc-results.md`](igdb-poc-results.md).

## 1. Executive decision

**Decision:** Pending (`PASS`, `CONDITIONAL PASS`, or `FAIL`)

Summarize whether every blocking gate passed, which limitations remain, and
whether IGDB should proceed to the contractual gate.

## 2. Execution context

| Item | Value |
|---|---|
| Execution date | Pending |
| Git commit | Pending |
| Sample version/hash | Pending |
| Cases executed | Pending |
| Tool version/commit | Pending |
| Executor | Ruben Hernandez |

## 3. Authentication and security

Record OAuth success, credential handling, secret-redaction checks, and confirm
that no browser-to-IGDB calls or committed raw responses were introduced.

## 4. Data-quality metrics

Copy the reviewed aggregate metrics from the generated report.

| Metric | Threshold | Actual | Outcome |
|---|---:|---:|---|
| Exact-title search | 95% | Pending | Pending |
| Alternative/localized title | 80% | Pending | Pending |
| Provider ID, provenance, and synchronization date | 100% | Pending | Pending |
| Platform identification | 95% | Pending | Pending |
| Release date or precision | 90% | Pending | Pending |
| Region represented or explicitly unknown | 85% | Pending | Pending |
| Usable cover | 90% | Pending | Pending |
| Genre | 90% | Pending | Pending |
| Developer or publisher | 85% | Pending | Pending |
| Unexpected duplicates | At most 5% | Pending | Pending |

## 5. Release-data results

Summarize platform-region-date tuple accuracy, date precision, and release
status behavior. Do not validate these fields independently.

## 6. Edge cases

Document DLC, expansions, parent relationships, editions, ports, remasters,
delays, cancellations, and imprecise dates.

## 7. Operational results

| Item | Value |
|---|---:|
| Requests | Pending |
| Duration | Pending |
| p95 latency | Pending |
| Retries | Pending |
| HTTP 429 responses | Pending |
| Other errors | Pending |

Latency is observational in this PoC and is not a blocking SLA.

## 8. Offline validation

Record whether the captured canonical data was validated successfully without
IGDB availability.

## 9. Reviews and observed limitations

List every `REVIEW` case separately, especially changed future dates. Record
missing localized names, covers, companies, genres, regions, or unknown
mappings without hiding them in aggregate percentages.

## 10. Contractual and publication status

Record separately the evidence for attribution, data retention, image use,
public availability, future monetization, and any partnership requirement.
A technical pass does not close this section.

## 11. Recommendation and follow-up

State one recommendation:

- approve IGDB for the contractual gate;
- approve with explicit product limitations;
- reject IGDB and evaluate RAWG.

List any changes required in the Product Brief, assumptions, open questions,
and a future ADR.
