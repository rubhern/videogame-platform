# Architecture diagrams

Diagrams visualize approved architecture; the owning documents, OpenAPI, Flyway SQL,
code/configuration, and ADRs remain authoritative ([ADR-0013](../../decisions/0013-use-model-backed-and-purpose-specific-architecture-diagrams.md)).
Each view answers one question; do not recreate a view in another tool or introduce
a decision only in a drawing.

| Source | Question answered | Owning source |
|---|---|---|
| `structurizr/workspace.dsl` | C4 System Context and Container views of the running system | Solution architecture |
| `mermaid/module-context-map.mmd` | How the business modules and adapters relate, and which contracts cross the boundary | Solution architecture, ADR-0018 |
| `mermaid/oidc-bff-session-sequence.mmd` | How a browser obtains an opaque BFF session without holding tokens | Identity code, configuration and tests |
| `mermaid/session-csrf-logout-sequence.mmd` | How a state change is protected by same-origin metadata and CSRF, using logout | OpenAPI, identity code and tests |
| `mermaid/rating-intent-authentication-sequence.mmd` | How an anonymous rating press becomes an authenticated rating command exactly once | Identity and frontend code, use cases `UC-004`/`UC-005` |
| `mermaid/synchronize-bounded-catalogue-sequence.mmd` | How scheduled/manual triggers share date-interval reconciliation, page by page and Game by Game | ADR-0017, use case `UC-009` |
| `mermaid/persistence-ownership.mmd` | Which module owns which tables and how they relate | Flyway SQL |
| `mermaid/delivery-pipeline.mmd` | How a merged change becomes a validated deployment or a recorded failure | Platform design, private-dev README |

Views describe implemented behaviour, including post-MVP catalogue acquisition
and localization. A view of approved-but-unbuilt behaviour must say so in its title.

## Edit and render

Edit the source, use approved terminology, avoid deferred infrastructure, then render
and inspect connectors, text, clipping, and contrast. Update the table above when
adding, renaming, or removing a view.

```bash
bash docs/architecture/diagrams/scripts/render-mermaid.sh
```

The script owns the pinned renderer and the ignored output path. To view the C4
model, mount `docs/architecture/diagrams/structurizr/` in the maintained Structurizr local editor; the DSL is canonical and `workspace.json` only retains manual layout.
Generated exports stay ignored unless a repository consumer requires them and drift
validation is added in the same change.

## Portal rendering

The [documentation portal](../../../documentation/README.md) renders every catalogue
source and inline Mermaid block into ignored light/dark SVGs at build time. It uses
the same pinned Mermaid CLI as the command above. The official digest-pinned
Structurizr C4-PlantUML exporter derives both C4 views directly from `workspace.dsl`,
with no second relationship model. A digest-pinned offline PlantUML renderer and
bundled C4 library produce the SVGs; intermediate definitions stay ignored. Existing automatic layout is authoritative for these
exports; `workspace.json` remains an editor layout artefact, never a replacement model.
The portal provides full-size scrolling, standalone SVGs and original sources.

Portal validation rejects missing catalogue entries, render errors, broken assets
and internal links. Browser checks cover both themes and phone/desktop layouts;
inspect the resulting diagrams for connectors, labels, multiline text, spacing,
clipping and contrast before accepting a visual change. Rendering success alone
is insufficient.
