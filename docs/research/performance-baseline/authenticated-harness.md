# Authenticated and mixed functional harness

## Scope and decision

This implemented slice of [#85](https://github.com/rubhern/videogame-platform/issues/85)
validates the existing authenticated MVP flows locally under WSL. It extends the
[k6 harness](../../../tools/performance/baseline.js), not the product, and does not
establish capacity, an SLO, stress/recovery or soak evidence. Clock backsteps are
retained independently of functional checks. No optimization, exporter, production
instrumentation, CI gate or releasable-artefact version increment is introduced.

The [OpenAPI contract](../../architecture/api/openapi.yaml) owns session, personal
rating search/sort and conditional-command semantics. The
[solution architecture](../../architecture/mvp-solution-architecture.md) owns the
BFF and identity boundary. The executable owners for this experiment are
[authenticated.py](../../../tools/performance/authenticated.py),
[authenticated.js](../../../tools/performance/authenticated.js),
[mixed.js](../../../tools/performance/mixed.js) and the existing
[dataset generator](../../../tools/performance/dataset.sh).

## Method and isolation

The existing application, Keycloak and PostgreSQL topology is reused. The
`authenticated` profile supplies a small synthetic catalogue with no initial ratings;
this is a functional fixture, not a growth forecast. The application connects only
to the verified disposable performance database. The existing Keycloak service
connects to its disposable identity database in that same PostgreSQL container.
Persistent catalogue and identity databases remain separate and untouched.

Three deterministic synthetic usernames and disjoint sets of three eligible Games
are assigned to three scenarios, each with exactly one VU. Assignment uses a fixed
scenario environment slot, independent of k6's global VU allocation. Passwords are
random per run and supplied through a temporary mode-0700 directory/mode-0600 file;
passwords, session cookies, CSRF material and authorization codes are absent from
retained evidence. Subject IDs are created by real Keycloak; reproducibility concerns
the recipe and isolated histories, not reuse of an old principal.

Provisioning reuses the management-only `KeycloakAdmin` seam already exercised by
[scripts/test-private-dev-oidc-provisioning-keycloak.py](../../../scripts/test-private-dev-oidc-provisioning-keycloak.py).
Its admin token remains in the controller and never enters the product workload.
The VUs visit `/auth/login/keycloak`, submit the hosted Keycloak HTML form, follow
the authorization-code callback through the BFF and retain only opaque cookies.
They never call an OAuth token endpoint or send a product bearer token. Session
rotation and HttpOnly/SameSite=Lax are checked. A second iteration reuses the cookie
jar with `noCookiesReset`, obtains current CSRF material from `/session`, and checks
that the session response contains only the product's minimal fields.

Local k6 DNS/port mappings preserve the product's real localhost Host headers,
redirect URI, issuer and cookie domains while reaching the existing containers.
This uses [k6's documented hosts option](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#hosts).
It changes network routing only. Remote TLS origins and the approved secure cookie
name can be supplied in the same workload configuration without those local mappings.
No session or authentication protection is disabled. Runtime log output is suppressed
so even a failed HTTP callback cannot persist its authorization-code URL; failures
remain visible through status/error metrics, checks and request-budget thresholds.

Each user prepares two personal ratings through conditional API creation. Each of
two measured journeys reads all three supported sorts, searches canonical titles
and approved aliases, checks another user's seeded rating is absent in its own scope,
and creates, reads, updates, reads and deletes its own third rating. Reads verify
values and returned validators; mutation results verify coherent available aggregate
context. A missing-CSRF creation attempt must fail with the existing stable error.
There is no personal platform/region/genre filter in the current contract; none is
invented. All list responses remain one bounded page of at most three expected items.

Mixed composition adds a separate anonymous VU cycling over the existing five public
cases. The original public executor remains unchanged; authenticated-only execution
has its own module/options. Each VU pauses one second after requests. This controller
has a fixed small budget, no increasing-load or sustained-load CLI switches.

## Preparation, recovery and reproduction

Start the supported local application from current main using the
[local setup procedure](../../development/local-setup.md). Disable scheduled
synchronization and stop the optional localizer. The controller refuses remote Docker
engines, an existing performance database and an unexpected persistent application
boundary. The existing disposable-container/image/anonymous-volume and SQL schema
checksum controls remain mandatory.

```bash
python3 tools/performance/authenticated.py run \
  --runtime-checkout /path/to/local/checkout-with-existing-env-files --workload mixed
```

`--workload authenticated` selects the same authenticated scenarios without public
traffic. `--workload mixed` is the default and was functionally executed here.

Raw evidence goes to ignored `tools/performance/results/authenticated-*`: manifest,
workload, inspected k6 options, timestamped metrics, native summary, case/scenario
results, paired wall/monotonic samples, periodic existing Actuator/Docker/cgroup/SQL
observations and restoration receipts. Login redirects and raw headers/bodies are
never exported. Functional failures fail the run; clock-invalid runs retain
`capacityInterpretationAllowed: false` without failing otherwise correct flows.

Normal completion deletes every synthetic rating through the API and terminates
BFF sessions with CSRF. The controller verifies zero remaining ratings and deletes
its newly created accounts with an ID/email ownership check. Finally it restores
application and Keycloak connections to their original persistent databases, checks
readiness, compares catalogue counts and the aggregate fingerprint of persistent
rating rows, then removes only the verified disposable database and anonymous volume.
The temporary credential directory is removed even after failure. If interruption
prevents normal completion, disposal of the isolated identity/application databases
removes partial state. Recovery verifies the recorded disposable container ID before mutation, so an old
receipt cannot remove a newer experiment. It can be resumed idempotently:

```bash
python3 tools/performance/authenticated.py restore \
  tools/performance/results/authenticated-EXACT-RUN
```


## Evidence and conclusion

The [final report](remote-harness.md) owns capacity conclusions.
The [authenticated-evidence.json](authenticated-evidence.json) retains this method’s historical results.
Clock-invalid local runs remain functional/query diagnostics only; they are not capacity evidence.
Raw artifacts remain ignored under `tools/performance/results/`.
