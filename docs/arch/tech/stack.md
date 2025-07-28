## 🛠️ Technology Stack – MVP (Local‑First Edition)

| Layer / Concern | Technology | Rationale |
|-----------------|------------|-----------|
| **Front‑end** | React 18 + Vite + TypeScript | MIT licences; instant HMR, modern bundling. |
| **Design system** | Tailwind CSS + Headless UI | Utility CSS + accessible headless components. |
| **Mobile / PWA** | Google Workbox | Apache‑2.0; generates service‑worker for offline catalogue. |
| **API Gateway / Ingress** | **Kong OSS** (IngressClass) | Unified gateway: OIDC, rate‑limit, gRPC, WebSocket. Edge CDN to be evaluated post‑MVP. |
| **Back‑end micro‑services** | Spring Boot 3 (Java 21) | Apache‑2.0; mature ecosystem, AOT native option. |
| **Sync API contracts** | OpenAPI 3.1 + springdoc | Generate docs + TS client; both Apache‑2.0. |
| **Async messaging / event log** ★ | **Apache Kafka + Strimzi** (K8s operator) | High‑throughput, free to self‑host; Strimzi automates rolling upgrades. |
| **Databases** | *Catalogue*: PostgreSQL 16<br>*Reviews*: MongoDB 6<br>*Ratings*: PostgreSQL table `user_ratings`<br>*Search*: OpenSearch 2<br>*Cache*: Redis 7 | OSS licences (Postgres LGPL, MongoDB SSPL, OpenSearch Apache‑2.0, Redis BSD). |
| **Identity & SSO** | Keycloak 23 (OIDC) | Apache‑2.0; social logins, RBAC, tokens. |
| **AuthZ policy** | Open Policy Agent (OPA) sidecar | Apache‑2.0; decoupled fine‑grained authZ. |
| **Container build** | Paketo Buildpacks | Apache‑2.0; reproducible CVE‑scanned images. |
| **Orchestrator** ★ | **MicroK8s** (single‑node Kubernetes on WSL 2) | Lightweight, upstream‑compatible; perfect for local training with zero cloud bill. |
| **Service Mesh** | Istio 1.22 | Apache‑2.0; mTLS, retries, canary traffic shifting. |
| **CI / CD** | GitHub Actions (CI) → **Argo CD** (GitOps) | Pull‑based delivery reconciles manifests inside MicroK8s. |
| **Observability** | OpenTelemetry → Prometheus / Grafana / Loki / Tempo | CNCF stack; all OSS. |
| **Security tooling** | Trivy • OWASP ZAP • Renovate | MIT / Apache‑2.0; shift‑left scans & dep updates. |
| **Static docs site** | MkDocs Material (GitHub Pages) | MIT licence; GitHub Pages free for public projects. |
| **Infrastructure tools install** | **Helm charts (manual)** — Terraform TBD | Quick bootstrap; Terraform will be revisited when infra grows beyond single node. |

