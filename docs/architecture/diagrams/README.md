# Architecture diagrams

Diagrams communicate approved architecture; owning documents, OpenAPI, Flyway SQL,
code/configuration, and ADRs remain authoritative.

| Tool/source | Ownership |
|---|---|
| `structurizr/workspace.dsl` | Canonical shared C4 System Context, Container, and private-`dev` target model |
| `mermaid/*.mmd` | Focused context/dependency/sequence/persistence/delivery views listed below |
| `diagrams-net/*.drawio` | Polished derived communication view only |
| `generated/` | Disposable exports; never edit directly |

Do not recreate every view in every tool or introduce a decision only in a drawing.

## Catalogue

| View | Question / owner |
|---|---|
| `module-context-map.mmd` | Business-module relationships; solution architecture |
| `hexagonal-dependency-rules.mmd` | Allowed dependency direction; solution architecture |
| `oidc-bff-session-sequence.mmd` | Implemented browser/BFF/Keycloak login/session flow; identity code/config/tests |
| `session-csrf-logout-sequence.mmd` | Implemented session/CSRF/logout flow; OpenAPI and identity code/tests |
| `authenticate-and-create-rating-sequence.mmd` | Approved future rating-return behaviour; application use cases |
| `synchronize-bounded-catalogue-sequence.mmd` | Approved future synchronization behaviour; use cases/platform |
| `catalogue-persistence-model.mmd` | Implemented physical catalogue schema; Flyway SQL |
| `delivery-pipeline.mmd` | Source-to-image/private-`dev` target flow; platform/delivery lifecycle |
| `private-dev-deployment.drawio` | Polished derived target deployment; Structurizr/platform |

Implementation-backed diagrams must follow the referenced code/config/tests and must
not claim future rating/synchronization behaviour as implemented evidence.

## Edit and validate

1. Identify the owning source and the single question the diagram answers.
2. Use Structurizr for shared C4, Mermaid for focused code-based views, and
   diagrams.net only for deliberate visual layout.
3. Edit source, use approved terminology, avoid deferred/speculative infrastructure,
   render, and inspect connectors/text/clipping/contrast.
4. Update this catalogue when adding, renaming, or removing a view.

Render Mermaid sources with:

```bash
bash docs/architecture/diagrams/scripts/render-mermaid.sh
```

The script owns the pinned renderer and output path. To view Structurizr, mount only
`docs/architecture/diagrams/structurizr/` in the local Structurizr container; its DSL
is canonical and `workspace.json` may retain manual layout. Generated exports remain
ignored unless a real repository consumer requires them and drift validation is
added in the same change.
