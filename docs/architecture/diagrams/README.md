# Architecture diagrams

This directory contains the editable architecture and technical diagrams for
VideoGame Platform.

The diagrams communicate approved decisions. They do not replace the Product Brief,
domain model, use cases, solution architecture, platform design, delivery lifecycle,
technology baseline, OpenAPI contract, or ADRs.

**Status:** Diagram baseline established for the approved MVP solution, private-dev
deployment target, walking-skeleton delivery gate, and the implemented issue #40
OIDC/BFF compatibility proof. Future views should be added only when they answer a
new architectural question or clarify an approved or implemented change.

The governing decision is
[ADR-0013: Use model-backed and purpose-specific architecture diagrams](../../decisions/0013-use-model-backed-and-purpose-specific-architecture-diagrams.md).

## 1. Tool strategy

Three tools are intentionally used because they solve different problems.

| Tool | Use it for | Source of truth |
|---|---|---|
| Structurizr DSL | C4 System Context, Container, Deployment, and future architecture views that benefit from one shared model | `structurizr/workspace.dsl` |
| diagrams.net | Highly visual communication views with deliberate layout, colours, annotations, and optional technology logos | The `.drawio` file for that visual view |
| Mermaid | Detailed context maps, dependency rules, sequences, and delivery flows that are not already owned by an approved Markdown document | Each standalone `.mmd` file |

Do not recreate every diagram in all three tools. Duplication is allowed only for a
temporary comparison or a clearly labelled derived communication view.

## 2. Authority and ownership

The authority order is:

1. Approved architecture documents, domain documents, contracts, and ADRs own the
   decisions.
2. Structurizr owns the canonical C4 architecture model and its C4 views.
3. An approved Markdown document owns any Mermaid diagram embedded in that document.
4. Standalone Mermaid files own only the additional communication views listed below.
5. diagrams.net owns only the deliberately polished visual communication view.
6. Files under `generated/` are derived exports and must never be edited directly.

A diagrams.net picture must not introduce a component, dependency, protocol, or
deployment decision that does not exist in an approved source or the Structurizr
model.

## 3. Directory layout

```text
docs/architecture/diagrams/
├── README.md
├── structurizr/
│   ├── workspace.dsl
│   ├── workspace.json              # Generated after manual layout; commit it when used
│   └── structurizr.properties
├── diagrams-net/
│   └── private-dev-deployment.drawio
├── mermaid/
│   ├── module-context-map.mmd
│   ├── hexagonal-dependency-rules.mmd
│   ├── oidc-bff-session-sequence.mmd
│   ├── session-csrf-logout-sequence.mmd
│   ├── authenticate-and-create-rating-sequence.mmd
│   ├── synchronize-bounded-catalogue-sequence.mmd
│   ├── catalogue-persistence-model.mmd
│   └── delivery-pipeline.mmd
├── generated/
│   ├── structurizr/
│   ├── diagrams-net/
│   └── mermaid/
└── scripts/
    └── render-mermaid.sh
```

## 4. Current diagram catalogue

### Structurizr

`structurizr/workspace.dsl` contains:

- C4 System Context.
- C4 Container.
- C4 Private Dev target deployment.

The C4 diagrams share one model. Change a person, system, container, relationship, or
deployment node once in the DSL rather than maintaining independent drawings.

Each C4 view answers a structural question:

| View | Question answered |
|---|---|
| System Context | Who uses VideoGame Platform, and which external systems does the product depend on? |
| Containers | Which runtime containers own the React UI, same-origin BFF/API, product data, and external identity relationships? |
| Private Dev Deployment | Where would the approved private target containers and infrastructure nodes run? This remains a target design, not issue #40 runtime evidence. |

C4 intentionally does not describe callback order, token visibility, session-cookie
contents, or CSRF processing. The implementation-backed sequence diagrams below own
those behavioural communication questions.

### diagrams.net

`diagrams-net/private-dev-deployment.drawio` is the polished target-deployment
communication view. It represents the approved design, not infrastructure that has
already been provisioned.

Use it for:

- a repository landing page;
- portfolio or presentation material;
- an architecture review where visual scanning matters;
- a version with colours, explanatory annotations, and technology logos.

It is a derived view. Structurizr and the approved architecture documents remain
authoritative.

### Mermaid

The canonical Mermaid files are:

| File | Question answered | Decision or evidence owner |
|---|---|---|
| `module-context-map.mmd` | Which strategic DDD boundaries exist, and which relationships are allowed? | `../mvp-solution-architecture.md` |
| `hexagonal-dependency-rules.mmd` | Which dependency directions are allowed inside Catalogue and Ratings? | `../mvp-solution-architecture.md` |
| `oidc-bff-session-sequence.mmd` | How does the implemented browser → Spring Security BFF → Keycloak 26.7 flow establish an opaque application session, and where do codes, cookies, and tokens travel? | ADR-0003, ADR-0007, `../api/openapi.yaml`, `IdentitySecurityConfiguration`, the `oidc` profile, realm import, and issue #40 tests |
| `session-csrf-logout-sequence.mmd` | How does the implemented session read return CSRF material, and how does `POST /api/v1/session` validate browser metadata, cookie-bound session and explicit CSRF proof before logout? | `../api/openapi.yaml`, `SessionController`, `SameOriginStateChangeFilter`, Spring Security configuration, and issue #40 tests |
| `authenticate-and-create-rating-sequence.mmd` | How should authentication at future rating confirmation and replay-safe rating creation behave? | `../application/mvp-use-cases.md`; approved design, not implemented by issue #40 |
| `synchronize-bounded-catalogue-sequence.mmd` | How should bounded IGDB synchronization, staging, validation, review, and last-valid-state preservation behave? | `../application/mvp-use-cases.md` and `../deployment/mvp-platform-and-delivery.md` |
| `catalogue-persistence-model.mmd` | Which PostgreSQL catalogue tables, columns, keys, and cardinalities are implemented? | `../../development/database-migrations.md`; versioned SQL is the executable authority |
| `delivery-pipeline.mmd` | How does source become an image and then a manually approved private-dev deployment? | `../deployment/mvp-platform-and-delivery.md` and `../../development/delivery-lifecycle.md` |

For the two issue #40 sequence diagrams, approved intent remains owned by
[ADR-0003](../../decisions/0003-use-a-same-origin-bff-and-http-json-api.md),
[ADR-0007](../../decisions/0007-use-keycloak-as-the-initial-identity-provider.md),
the API conventions, and OpenAPI. Exact implemented behaviour is evidenced by the
Spring Security Java configuration, application YAML, reviewed Keycloak realm, and
automated backend/browser tests. The diagrams are communication views and must be
updated with those sources; they do not become a separate protocol specification.

The exact issue #40 implementation evidence is:

- [`IdentitySecurityConfiguration`](../../../backend/src/main/java/com/videogameplatform/identity/configuration/IdentitySecurityConfiguration.java),
  including `/auth/login`, PKCE, OIDC validation, server-side authorized-client
  storage, and `POST /api/v1/session` logout;
- [`SameOriginStateChangeFilter`](../../../backend/src/main/java/com/videogameplatform/identity/configuration/SameOriginStateChangeFilter.java)
  and the configured Spring Security CSRF filter chain;
- [`SessionController`](../../../backend/src/main/java/com/videogameplatform/api/delivery/SessionController.java)
  for the minimal no-store session representation;
- [`application-oidc.yaml`](../../../backend/src/main/resources/application-oidc.yaml)
  and [`application.yaml`](../../../backend/src/main/resources/application.yaml) for
  client, callback, session timeout, and cookie settings;
- the reviewed [Keycloak realm import](../../../docker/keycloak/import/videogame-platform-realm.json);
- [`SessionSecurityIntegrationTest`](../../../backend/src/test/java/com/videogameplatform/api/delivery/SessionSecurityIntegrationTest.java),
  [`OidcIdTokenValidationTest`](../../../backend/src/test/java/com/videogameplatform/identity/configuration/OidcIdTokenValidationTest.java),
  and the real [Keycloak browser proof](../../../frontend/tests/oidc-session.spec.ts).

The conceptual domain model remains embedded in `../domain/mvp-domain-model.md`, and
the change lifecycle remains embedded in `../../development/delivery-lifecycle.md`.
They are not duplicated here. The catalogue persistence model is a distinct physical
view and must not be interpreted as a replacement for that conceptual domain model.
Its versioned Flyway migration owns executable schema detail; update the communication
view in the same change whenever a forward migration changes the represented shape.

## 5. View Structurizr diagrams

From WSL:

```bash
cd docs/architecture/diagrams/structurizr

docker pull structurizr/structurizr

docker run \
  --rm \
  -it \
  -p 8080:8080 \
  --user "$(id -u):$(id -g)" \
  -v "$PWD:/usr/local/structurizr" \
  structurizr/structurizr local
```

Open:

```text
http://localhost:8080
```

The explicit `--user` avoids common WSL bind-mount write-permission failures.

Structurizr reads `workspace.dsl`. When layout is edited through the browser, it may
write `workspace.json`. Commit both files when the JSON is needed to preserve manual
layout.

Do not run Structurizr against the repository root. Mount only the
`structurizr/` directory as its writable data directory.

## 6. View diagrams.net diagrams

Open the online diagrams.net editor or draw.io Desktop, then:

1. Select **File → Open From → Device**.
2. Choose `diagrams-net/private-dev-deployment.drawio`.
3. Edit the `.drawio` source.
4. Export SVG or PNG into `generated/diagrams-net/`.

Dragging the `.drawio` file onto a blank diagrams.net canvas also opens it.

## 7. View Mermaid diagrams

### In an IDE

Use an IDE or editor extension that previews Mermaid `.mmd` files. Keep the raw
`.mmd` source in Git.

### In GitHub Markdown

GitHub renders fenced Mermaid blocks inside Markdown. For durable repository pages,
prefer linking a generated SVG or copying a small diagram into a nearby Markdown
document only when avoiding duplication is still practical.

### From the command line

Render all standalone Mermaid sources with the version pinned by the script:

```bash
bash docs/architecture/diagrams/scripts/render-mermaid.sh
```

The script prefers the official pinned Mermaid CLI container when Docker is running
and otherwise uses `npx`. This avoids depending on an unverified local Puppeteer
browser while retaining a non-Docker path. Both paths use Mermaid CLI `11.16.0`.

The script uses the official Mermaid CLI package and writes SVG files to:

```text
docs/architecture/diagrams/generated/mermaid/
```

Render one file manually:

```bash
npx --yes -p @mermaid-js/mermaid-cli@11.16.0 mmdc \
  -i docs/architecture/diagrams/mermaid/module-context-map.mmd \
  -o docs/architecture/diagrams/generated/mermaid/module-context-map.svg \
  -b transparent
```

The script, container fallback, and manual command pin Mermaid CLI to `11.16.0`; they
never resolve an unspecified latest version.

## 8. Editing rules

Before adding or changing a diagram:

1. Identify the architectural or product source that owns the decision.
2. Make each diagram answer one primary question.
3. Choose Structurizr for model-backed C4 views.
4. Choose Mermaid for behaviour, domain, dependency, state, and delivery diagrams.
5. Choose diagrams.net only when visual presentation provides real value.
6. Update the source file, not a generated SVG or PNG.
7. Avoid speculative infrastructure and deferred technologies.
8. Keep names aligned with the approved ubiquitous language.
9. Record durable decisions in the relevant document or ADR, not only in a diagram.
10. Regenerate exports and review the visual diff before merge.
11. For implementation-backed sequences, cite the exact code, configuration,
    contract, and executable test evidence in this catalogue.

## 9. Naming rules

Use lower-case kebab-case names that describe the question or view:

```text
authenticate-and-create-rating-sequence.mmd
private-dev-deployment.drawio
```

Use the suffix only when it adds meaning:

- `-context-map`
- `-sequence`
- `-state`
- `-domain-model`
- `-deployment`
- `-pipeline`

Avoid generic names such as `architecture-final-v3-new.drawio`.

## 10. Generated files

Generated files are useful for Markdown, reviews, and publishing, but they are
disposable.

Recommended formats:

- SVG for repository documentation and scalable review.
- PNG only when a consumer cannot display SVG.
- PDF only for a fixed presentation or external review package.

Never edit a generated export. Change its source and regenerate it. Generated exports
are ignored by default and remain local until a repository document needs to reference
one. If an export becomes a committed documentation dependency, remove the relevant
ignore rule and add generated-output drift validation in the same change.

## 11. Diagram validation and future CI

The current local syntax and render validation is:

```bash
bash docs/architecture/diagrams/scripts/render-mermaid.sh
```

A future dedicated CI diagram gate may:

- validate the Structurizr workspace;
- render all Mermaid sources;
- fail on invalid diagram syntax;
- optionally export Structurizr views;
- detect generated-output drift if generated files become committed dependencies.

This automation should be added after the local commands are stable and repeatable.
