# Grafana guide

A plain-language tour of what production Grafana shows and how to read it: the alerts,
the dashboards, the logs and the traces. Read this first. [Observability](observability.md)
owns the technical contracts and the privacy rules, the
[operations runbook](operations-runbook.md#production-monitoring) owns what to do during an
incident, and the provisioning files linked below own every exact number. If this page and a
provisioning file disagree, the file is right; fix this page.

## The big picture

```text
 application ──(metrics, logs, traces)──► Collector ──┬─► Prometheus  "Platform metrics"
 Caddy, Keycloak, blackbox probes ──────────────────► │   (numbers over time, 7 days)
 host sampler (every minute) ──► node-exporter ─────► │
                                                       ├─► Loki        "Platform logs"
                                                       │   (text events, 6 days)
                                                       └─► Tempo       "Platform traces"
                                                           (1 request in 10, 72 hours)
 PostgreSQL read-only views ───────────────────────────►  "Platform read models"
                                                           (current state, no history)
```

Grafana stores none of this itself. It asks four **data sources** and draws the answers:

| Data source (name in Grafana) | What it holds | How far back |
|---|---|---|
| **Platform metrics** (Prometheus) | Numbers sampled every 30 seconds: requests, errors, latency, memory, CPU, disk, backups, certificates | 7 days (or 512 MiB, whichever comes first) |
| **Platform read models** (PostgreSQL) | Current database facts through read-only views: ratings, catalogue quality, synchronization run reports | Only *now*, except run reports, which keep their own retained history |
| **Platform logs** (Loki) | The application's log lines | 6 days |
| **Platform traces** (Tempo) | Step-by-step timing of one request in ten | 72 hours |

Where the numbers come from:

- **The application** (Spring Boot) reports its own metrics once a minute: HTTP requests,
  errors, latency, JVM memory, database connection pool, synchronization runs.
- **Caddy**, the public entrance, counts every request that reaches the site.
- **Keycloak**, the login service, counts logins, registrations, errors and emails.
- **The host sampler** is a small script on the VPS that runs every minute. It reads each
  container's memory, CPU, restarts and limits, the edge refusals, and the state of
  backups and account clean-up, then hands them to node-exporter. Every metric whose name
  starts with `production_` comes from it.
- **blackbox** is a *prober*. It does not serve anything to users: Prometheus asks it to
  visit `gameometro.com`, `auth.gameometro.com` and the Keycloak health page like an
  outside visitor would, and it reports whether that worked and when the TLS certificate
  expires. Its probes appear in the *Scrape and probe availability* and *TLS certificate
  lifetime* panels, and its own memory and CPU appear like every other container in the
  container panels, with `service="blackbox"`.
- **node-exporter** reports the VPS itself (CPU, memory, disk, I/O pressure) and passes
  on the host sampler's file.

## Alerts

### How an alert works

Every rule is a question Grafana asks every minute. Each rule here is written so the
answer is `1` when something is wrong and empty when all is well, so in the rule's graph
a line at **1** means "this is happening right now".

An alert moves through these states:

| State | Meaning |
|---|---|
| **Normal** | The condition is false. |
| **Pending** | The condition is true, but not yet for long enough. The *Pending period* (for example 2m) filters out short spikes. |
| **Firing** | The condition stayed true for the whole pending period. A notification goes out. |
| **Recovering** | The condition is false again, but Grafana waits for *Keep firing for* before closing. Memory pressure and CPU throttling wait 10 minutes, so a brief dip does not send a *Recovered* followed by a new *Problem*; every other rule uses 0s and closes on the next evaluation. |
| **No data** | The query returned nothing. Most rules treat that as Normal; the telemetry rules treat it as a problem, because silence there means you are blind. History shows it as *Normal (NoData)*. |
| **Normal (MissingSeries)** | This one instance disappeared from the answer while others may remain. Because these rules only return a line while the condition is true, this is the usual way an instance recovers: its value left the alert's band. |
| **Error** | The query itself failed. Most rules keep their last state. |

One rule can produce several **instances**, one per thing it watches. The memory rule has
one instance per container, so `blackbox` and `node-exporter` can fire while `postgres`
stays Normal. The rule page's **Instances** tab lists them; the **History** tab lists
every change of state, which is the fastest way to see whether an alert flaps. History
draws **one box per instance**, headed by its labels; scroll that header sideways to the
`service=` label to see which container the box belongs to. Read each box from the bottom
(oldest) up.

### How notifications reach Telegram

Delivery is on only in production, behind one owner switch. The
[notification template](../../deploy/private-dev/grafana/notifications.json.template)
owns the exact routing:

- Alerts are grouped by **environment, service, category and severity**. One message can
  carry several alerts of the same group.
- Grafana waits **1 minute** before the first message of a group, to collect related alerts.
- While a group keeps changing, it sends at most one update every **5 minutes**.
- A problem that stays unchanged is repeated every **4 hours**.
- Every firing produces a *Problem* message and every closing produces a *Recovered*
  message. An alert that crosses its threshold back and forth therefore sends a pair of
  messages each time.
- The registration notice uses its own route, repeats at most once a day and sends no
  "recovered" message.

### What each alert means

Rules come from three files: the [production rules](../../deploy/public-production/observability/grafana/alerts.json),
the [rules shared with private-dev](../../deploy/private-dev/grafana/provisioning/alerting/private-dev.json)
and the [registration notice](../../deploy/private-dev/grafana/provisioning/alerting/product-activity.json).
Each line gives the Grafana title, the rule identifier, what it means in plain words, when
it fires, and how long the condition must hold first. Severity *critical* means act soon;
*warning* means look when you can.

**Traffic and capacity**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Traffic has grown with pressure (`prod-traffic-trend`) | warning | Traffic jumped *and* the server is busy | Requests in the last 5 minutes are more than double the hourly average, at least 100 requests, and host CPU above 65 % | 10 min |
| Measured request capacity warning (`prod-capacity-warning`) | warning | Traffic approaching the measured capacity | 70–90 % of the calibrated requests per second | 5 min |
| Measured request capacity critical (`prod-capacity-critical`) | critical | Traffic at the measured capacity | 90 % or more | 5 min |
| Production capacity is uncalibrated (`prod-capacity-unknown`) | warning | No measured capacity exists | **Paused**: never fires | — |

**Entrance (Caddy) and application**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Edge 429 responses (`prod-edge-429`) | warning | The rate limiter is turning visitors away | 10 or more 429 answers in 5 minutes | 5 min |
| Edge 503 responses (`prod-edge-503`) | warning | The entrance answered "unavailable" on purpose (maintenance or overload) | 10 or more such 503 answers in 5 minutes | 5 min |
| Upstream HTTP or transport failures (`prod-upstream-503`) | critical | The application behind the entrance is failing or unreachable | 5 or more 500/502/503/504 answers from the application in 5 minutes | 2 min |
| High application error share (`prod-http-critical`) | critical | Many requests end in server errors | More than 10 % of requests are 5xx, with at least 100 requests in 5 minutes | 2 min |
| Unexpected server errors (`vgp-http-failures`) | warning | The application reports server errors it did not expect | 5 or more in 5 minutes (the "catalogue not ready" state does not count) | 2 min |
| Sustained product-route latency (`vgp-http-latency`) | warning | A main page of the product is slow for many people | The 95th-percentile time of one route is above 1 second, with at least 100 requests on it in 5 minutes | 5 min |

**Containers** (one instance per container; the label `service` names it)

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Container CPU saturation warning (`prod-cpu-warning`) | warning | A container uses most of the CPU it is allowed | 70–90 % of its `cpus` limit | 5 min |
| Container CPU saturation critical (`prod-cpu-critical`) | critical | A container uses all the CPU it is allowed | 90 % or more | 5 min |
| Container memory pressure warning (`prod-memory-warning`) | warning | A container is close to its memory limit | 85 % or more of its `mem_limit`, counting only memory the kernel cannot free (anonymous plus shared, such as PostgreSQL's buffers). File cache does not count. It stays firing above 95 % too, so crossing into critical adds a message instead of swapping two | 2 min |
| Container memory pressure critical (`prod-memory-critical`) | critical | A container is at its memory limit and risks being killed | 95 % or more, same reading | 1 min |
| Sustained container CPU throttling (`prod-cpu-throttle`) | warning | A container keeps being slowed down by its CPU limit while doing real work | Throttled in more than 20 % of its CPU periods **and** using more than half its `cpus` limit. A nearly idle exporter that bursts past its tiny quota at each scrape is throttled often but loses nothing, so it stays quiet | 10 min |
| Container OOM or restart detected (`prod-oom-restarts`) | critical | A container was killed for lack of memory, or restarted | Any OOM kill, restart or new start in the last 10 minutes | immediately |
| Container stopped or resource observations missing (`prod-container-missing`) | critical | A container is down, or the sampler cannot read it | Running = 0, or no resource reading | 3 min |

**Host and disk**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Host memory or I/O pressure (`prod-host-pressure`) | warning | The VPS as a whole is struggling | Any of: less than 20 % memory available, processes waiting for memory or disk, swapping, CPU stolen by the provider above 10 %, or less than 20 % CPU idle | 5 min |
| Low disk space warning (`prod-disk-warning`) | warning | The disk is filling | 15–25 % free | 5 min |
| Low disk space critical (`prod-disk-critical`) | critical | The disk is almost full | Less than 15 % free | 5 min |
| Disk projected to cross reserve within one day (`prod-disk-growth`) | warning | At the current pace, the disk will drop below 25 % free within 24 hours | The 6-hour trend predicts it | 30 min |

**Login and identity (Keycloak)**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Identity failure or mail-abuse indicators (`prod-identity-failures`) | warning | Many failed logins, or someone triggering many emails | 20 or more login errors in 5 minutes, or 30 or more verification/reset emails in 15 minutes | 5 min |
| Identity SMTP failure or lockout (`prod-identity-mail-failed`) | critical | Keycloak could not send an email, or accounts are being locked | Any email send failure, or 5 or more temporary lockouts in 5 minutes | 1 min |
| Keycloak readiness probe failed (`prod-identity-health`) | critical | Keycloak says it is not ready | The blackbox health probe fails | 2 min |

**Telemetry: is monitoring itself working?**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Telemetry target missing or failing (`prod-telemetry-missing`) | critical | Prometheus cannot read one of its sources, so some panels and alerts are blind | Any source (application, Collector, Caddy, host, Keycloak, probes, Prometheus) is down or absent | 3 min |
| Host or backup sampler missing/stale (`prod-sample-stale`) | critical | The host sampler stopped writing, so container, backup and edge figures are stale | No sample for more than 3 minutes, or the edge observation is incomplete | 2 min |
| Application telemetry unavailable (`vgp-telemetry-unavailable`) | warning | The application stopped sending metrics | A source is down, or the application's uptime stopped advancing | 5 min |
| Telemetry cardinality approaching budget (`prod-series-budget`) | warning | Prometheus is tracking more series than planned, usually a new label with too many values | More than 4000 active series | 15 min |

**Backups and account clean-up**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Backup attempt missing or failed (`prod-backup-unverified`) | critical | The last backup did not happen or failed | No attempt recorded, or the last one failed | 5 min |
| Off-host upload stale warning (`prod-backup-stale-warning`) | warning | The last copy outside the VPS is getting old | 14–24 hours old | 5 min |
| Off-host upload stale critical (`prod-backup-stale-critical`) | critical | No copy outside the VPS for a day | More than 24 hours old | 5 min |
| Backup cycle needs the owner (`prod-backup-cycle-attention`) | critical | The backup cycle stopped and asks for you | Its record says attention is needed | 1 min |
| Unverified-account cleanup needs the owner (`prod-unverified-cleanup-attention`) | warning | The daily removal of never-verified accounts failed | It asks for attention, or has not succeeded for 36 hours | 1 min |

**Certificates**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| TLS certificate expires soon warning (`prod-tls-warning`) | warning | A certificate renews soon and has not yet | 7–21 days left | 5 min |
| TLS certificate expires soon critical (`prod-tls-critical`) | critical | A certificate is about to expire | Less than 7 days left | 5 min |
| TLS probe failed (`prod-tls-failed`) | critical | `gameometro.com` or `auth.gameometro.com` cannot be reached over HTTPS from inside | The blackbox probe fails | 3 min |

**Database and catalogue**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Application database connection pressure (`prod-postgres-pressure`) | warning | PostgreSQL is using most of its allowed connections | More than 70 % of the server's connection limit (asked to PostgreSQL directly) | 5 min |
| Persistent database pool saturation (`prod-pool-critical`) | critical | Requests cannot get a database connection | 5 or more pool timeouts in 5 minutes, or requests waiting while the pool is 90 % busy | 2 min |
| Hikari connection acquisition pressure (`vgp-pool-pressure`) | warning | Requests had to wait for a database connection | Anyone waiting, or 2 or more timeouts in 5 minutes | 2 min |
| Catalogue synchronization needs attention (`vgp-sync-failures`) | warning | A catalogue synchronization run ended in a way that needs a person | A run finished with assessment `attention` in the last 15 minutes. Clean, data-quality-only, stopped and skipped runs stay quiet. When it closes, the 15-minute window expired; it does **not** mean a later run succeeded | 2 min |

**Product notice**

| Alert | Severity | Means | Fires when | Holds for |
|---|---|---|---|---|
| Registration activity observed (`vgp-registration-window`) | info | Someone registered. Good news, not a problem | At least one successful registration in 5 minutes | immediately |

### Reading an alert in Grafana

1. **Alerting → Alert rules**, open the rule.
2. **Query and conditions** shows the question and a graph: a line at 1 is an instance
   in trouble; the legend names it.
3. **Instances** lists who is firing right now.
4. **History** shows every change of state. Many short Firing → Normal cycles mean the
   threshold is flapping, not that something keeps breaking.
5. **View panel** opens the dashboard panel that shows the real values behind the 1.

## Dashboards

Open **Dashboards → VideoGame Platform**. The JSON files under
[`deploy/private-dev/grafana/dashboards/`](../../deploy/private-dev/grafana/dashboards/)
and the [production capacity dashboard](../../deploy/public-production/observability/grafana/capacity.json)
own every panel and query; production reuses the same dashboards as private-dev.

| Dashboard | Answers | Main data source |
|---|---|---|
| **Production / Capacity, security and recovery** | Is the server healthy and safe? Traffic against capacity, refused requests, p95 latency, CPU and memory per container, throttling, host memory and disk, database connections, login events, probe availability, restarts, sampler age, backups, certificates, series count | Platform metrics |
| **Application / HTTP** | Is the site answering well? Request volume, success share, 4xx/5xx, latency p50/p95/p99, slowest routes, error codes | Platform metrics |
| **Runtime / Resources** | Is the Java process healthy? Uptime, heap, garbage collection, threads, database pool, and whether telemetry is arriving | Platform metrics, plus PostgreSQL for database connections |
| **Catalogue Synchronization** | What did the IGDB imports do? Run history and outcomes, Games processed, catalogue quality, provider calls and retries, player scores, translation coverage and the translation helper's resources | Mostly Platform read models, provider panels from Platform metrics |
| **Product / Business** | How is the product used? Active ratings, rating distribution, most rated games, searches, page reads | Mixed |
| **Gameómetro · Growth** | Registrations and rating activity per day and per week | Mostly Platform metrics |

### How to read a panel

- **The time picker matters, but not everywhere.** Panel titles say which kind they are:
  - *range*: counts within the selected time range.
  - *current*: today's database state; ignores the time picker.
  - *observed process*: totals since the application last started; a restart resets them.
- **`rate` and `increase` are estimates.** A panel showing 2.7 errors is not wrong;
  Prometheus extrapolates between samples. Read the order of magnitude.
- **No data is not zero.** An empty panel can mean nothing happened, the data expired,
  or the source stopped reporting. Check *Scrape and probe availability* (capacity
  dashboard) or *Telemetry scrape availability* (runtime dashboard) before concluding
  anything.
- **Percentiles need traffic.** A p95 over a handful of requests mostly reflects one slow
  request. The latency alert asks for at least 100 requests for that reason.
- **Ratios against a limit** (CPU, memory, pool) read as a fraction: 0.85 means 85 % of
  the limit. A small container with a small limit reaches high ratios with very little use.
- **Inspect the query.** Every panel's menu has *Explore* and *Inspect → Query*; that
  shows exactly what it asks.

## Logs

The application's log lines go to **Platform logs**. Keycloak, PostgreSQL, Caddy and the
host keep their logs in Docker on the VPS; they are not here.

To search:

1. **Explore**, choose **Platform logs**.
2. Set a **short time range** first (15 minutes to 1 hour) around what you are looking at.
3. Switch the editor to **Code** and paste one of these:

```logql
{service_name="videogame-platform-backend"}
{service_name="videogame-platform-backend"} | detected_level=~"error|warn"
{service_name="videogame-platform-backend"} | correlationId="<X-Correlation-ID value>"
{service_name="videogame-platform-backend"} |= "http.request.completed"
{service_name="videogame-platform-backend"} | scope_name=~"com.videogameplatform.catalogue.*"
```

What the pieces mean:

- `{service_name="..."}` chooses the stream; this one is the application.
- `| detected_level=~"error|warn"` keeps only warnings and errors.
- `| correlationId="..."` finds every line of one request. An error response shown to a
  visitor carries this identifier in its `X-Correlation-ID` header.
- `|= "text"` keeps lines that contain that text.
- `scope_name` is the Java logger, roughly the part of the application that wrote the line.

Reading a line: click it to expand its fields. Useful ones are `http.route` (which page or
API), `http.status_code`, `duration_ms`, `error.code` and, for synchronization, the stage
and reason of a failed Game. A line written during a traced request also has a
`trace_id` and an **Open trace** link.

Logs never contain passwords, tokens, email addresses or raw provider data, by design.
An empty result can mean no event, the wrong time range, data older than 6 days, or a
line lost while Loki was down.

## Traces

A trace is the timeline of **one request**: how long the whole request took and how long
each step inside it took (security checks, each SQL query). Only **1 request in 10** is
kept, for 72 hours.

Three ways to find one:

1. **From a log line**: expand a line in Platform logs and click **Open trace**. If the
   link does not open a trace, that request was one of the nine in ten not kept.
2. **Search for slow requests**: Explore → **Platform traces** → **TraceQL**, and paste:

   ```traceql
   { resource.service.name = "videogame-platform-backend" && span:kind = server && duration > 500ms }
   ```

   Change `500ms` to the threshold you care about. Add `&& span.http.route = "/api/v1/..."`
   to narrow it to one route.
3. **From a trace back to logs**: on a span, click **Logs for this span**.

Reading a trace:

- Each bar is a **span**, one step. The top bar is the whole request; the bars under it
  are the steps it called, drawn to scale.
- The **longest bar under the top one** is where the time went. Many short SQL bars in a
  row suggest too many queries; one long SQL bar suggests one slow query.
- A SQL span shows its query with `?` in place of values. Values are never stored.
- A span in red ended in an error.

A slow route may need several attempts before one of its requests is sampled.

## Where to start

| Question | Start at |
|---|---|
| I got a Telegram message | The alert rule, then its **History** and **View panel** |
| Is the site OK right now? | *Production / Capacity, security and recovery*, then *Application / HTTP* |
| Why is this page slow? | *Application / HTTP → Slowest routes*, then a TraceQL search on that route |
| A visitor saw an error | Platform logs with the `correlationId` from the error |
| Did last night's import work? | *Catalogue Synchronization → Latest recorded run* |
| Is monitoring itself blind? | *Scrape and probe availability*, and the telemetry alerts |
