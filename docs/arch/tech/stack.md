## 🛠️ Technology Stack – MVP (All-Free Edition)

| Layer / Concern | Technology | Rationale |
|-----------------|------------|-----------|
| **Front-end** | React 18 + Vite + TypeScript | MIT licences; instant HMR, modern bundling. |
| **Design system** | Tailwind CSS + Headless UI | Utility CSS + accessible headless components. |
| **Mobile / PWA** | Google Workbox | Apache-2.0; generates service-worker for offline catalogue. |
| **API Gateway / Ingress** | **Kong OSS** (IngressClass) | Free/Apache-2.0; OIDC, rate-limit, gRPC, WebSocket. |
| **Back-end micro-services** | Spring Boot 3 (Java 21) | Apache-2.0; mature ecosystem, AOT native option. |
| **Sync API contracts** | OpenAPI 3.1 + springdoc | Generate docs + TS client; both Apache-2.0. |
| **Async messaging / event log** ★ | **Apache Kafka + Strimzi** (K8s operator) | Completely free to self-host; Strimzi automates rolling upgrades. |
| **Databases** | *Catalogue*: PostgreSQL 16<br>*Reviews*: MongoDB 6<br>*Ratings*: **PostgreSQL table “user_ratings”***<br>*Search*: OpenSearch 2<br>*Cache*: Redis 7 | All OSS licences (Postgres LGPL, MongoDB SSPL, OpenSearch Apache-2.0, Redis BSD). Using Postgres for ratings avoids pay-per-request DynamoDB. |
| **Identity & SSO** | Keycloak 23 (OIDC) | Apache-2.0; social logins, RBAC, tokens. |
| **AuthZ policy** | Open Policy Agent (OPA) sidecar | Apache-2.0; decoupled fine-grained authZ. |
| **Container build** | Paketo Buildpacks | Apache-2.0; reproducible CVE-scanned images. |
| **Orchestrator** ★ | **k3s** (lightweight CNCF Kubernetes dist.) | Fully OSS, single-binary; runs on any VM or bare-metal. |
| **Service Mesh** | Istio 1.22 | Apache-2.0; mTLS, retries, canary traffic shifting. |
| **CI / CD** | GitHub Actions – free minutes for public repos (CI) → Argo CD (GitOps) | Public repos have unlimited free minutes; Argo CD is Apache-2.0. |
| **Observability** | OpenTelemetry → Prometheus / Grafana / Loki / Tempo | CNCF stack; all OSS. |
| **Security tooling** | Trivy • OWASP ZAP • Renovate | All MIT / Apache-2.0; shift-left scans & dep updates. |
| **Static docs site** | MkDocs Material (GitHub Pages) | MIT licence; GitHub Pages free for public projects. |
| **Infrastructure-as-Code** | Terraform 1.8 + Helmfile | MPL-2.0 / Apache-2.0; declarative infra & charts. |
