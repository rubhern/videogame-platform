### Non-Functional (MVP-level)
* **Performance:** API P99 < 300 ms, homepage P95 FCP < 2 s on 3G.
* **Security:** OWASP Top-10 mitigations, HTTPS-only, CSP headers.
* **Compliance:** GDPR – deletion & export endpoints in backlog.
* **Availability:** 99.5 % monthly SLO for public endpoints.
* **Observability:** trace > 90 % sampled in staging, 10 % in prod; custom metrics for rating submissions & searches.


## 🔒📈 Non-Functional Requirements – Detailed Explanation

| NFR | What the target means in practice | Why it matters for a videogame catalogue | How we’ll measure & enforce it |
|-----|-----------------------------------|------------------------------------------|--------------------------------|
| **Performance**<br>• **API P99 < 300 ms**<br>• **Homepage FCP < 2 s on 3 G** | *API P99* ⇒ 99 % of backend requests (GET /search, POST /rating …) must finish < 300 ms **inside the pod**, CDN latency excluded.<br>*FCP (First Contentful Paint)* is a Core Web Vital; the first text/image on `/` must appear within 2 s on a 1.6 Mbps (Fast 3 G) simulation. | Discovery must feel instant. Slow pages drop engagement; every +100 ms can cut conversions. | • **k6**/Gatling load test in CI.<br>• Prometheus histogram `http_request_duration_seconds{quantile="0.99"}`.<br>• Lighthouse-CI budget fails if FCP > 2 s. |
| **Security**<br>• OWASP Top-10 mitigations<br>• HTTPS-only<br>• CSP headers | Protect against injection, XSS, broken auth, etc. All traffic via TLS 1.3 with HSTS. `Content-Security-Policy` blocks inline scripts & mixed content. | Ratings/reviews are user-generated; an XSS could deface pages or steal tokens. HTTPS secures logins on public Wi-Fi. | • Nightly **OWASP ZAP** scan; high-sev findings block release.<br>• ALB forces 443; port 80 → 301.<br>• Helmet middleware sets CSP, X-Frame-Options; Snyk/Dependabot keep deps patched. |
| **Compliance**<br>• GDPR Right-to-Be-Forgotten & export endpoints (backlog) | Data model & APIs must let us delete **all** personal data (ratings, reviews, tokens) or export it (JSON/CSV). Self-service UI can wait, but backend hooks must exist. | EU users may sign up day 1; retrofitting GDPR later is expensive and risky. | • Unit tests assert `DELETE /user/{id}` cascades.<br>• Tables/indices tag columns with `pii=true` for future automation. |
| **Availability**<br>• 99 .5 % SLO / month | ≤ 3 h 40 m total downtime per month, including planned maint in business hours. Multi-AZ but not multi-region. | MVP tolerates short night outages but must be “up most of the time” to collect feedback. | • Uptime ping → Grafana SLO dashboard.<br>• Post-mortem if error-budget burn > 50 %. |
| **Observability**<br>• ≥ 90 % traces sampled in staging, 10 % in prod<br>• Business metrics for ratings & searches | *Tracing*: every request in staging; 10 % in prod to manage cost.<br>*Metrics*: `ratings.count`, `reviews.count`, `search.latency`, `rating.avg`. | Debugging distributed flows + measuring feature uptake (e.g., Twitch promo spike) requires traces & dashboards. | • OpenTelemetry SDK → Jaeger (staging) / Tempo (prod).<br>• Loki logs correlated by `trace_id`.<br>• Prometheus alerts on metric anomalies. |

---

### Architectural implications

* **Performance** → CDN, Redis edge cache, Brotli, async DB writes.
* **Security** → OAuth/OIDC provider, mTLS in service mesh, Trivy scans in CI.
* **Availability** → stateless pods across 3 AZs, RDS Multi-AZ or DynamoDB global tables.
* **Observability** → OTel starter wired into every Spring Boot service from Sprint 0.
* **Compliance** → All tables include `user_id`; prefer soft-delete + TTL for cheap RTBF.

Embedding these NFRs from the start keeps the MVP small **and** production-ready, avoiding costly rewrites later.
