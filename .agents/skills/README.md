# Repository skills

This directory contains project-specific and vendored instruction skills for Codex.
Repository authorities and `AGENTS.md` take precedence over generic external advice.

## Project-specific skills

| Name | Purpose |
|---|---|
| `product-brief-review` | Review the Product Brief and product-alignment evidence without inventing decisions. |
| `scalability-by-design` | Review APIs, queries, persistence and other data paths for bounded work, deterministic behavior and evidence-based scale. |
| `videogame-platform-backend-development` | Implement, refactor, debug, or review the backend according to the approved project architecture and contracts. |

## Vendored external skills

| Name | Upstream repository | Upstream path | Commit SHA | License | Incorporated | Modification status |
|---|---|---|---|---|---|---|
| `java-springboot` | `github/awesome-copilot` | `skills/java-springboot` | `318066d2213b510e89b500ed0d53506c54093ddc` | MIT; GitHub, Inc. | 2026-08-18 | Vendored without modification |
| `architecture-patterns` | `wshobson/agents` | `plugins/backend-development/skills/architecture-patterns` | `367cb6a4a182cf7e9b0a17c9429f7411ddd9cf35` | MIT; Copyright (c) 2024 Seth Hobson | 2026-08-18 | Vendored without modification |
| `tdd` | `mattpocock/skills` | `skills/engineering/tdd` | `9c9f36ccd3995266cd675468af71639c8dde1ec5` | MIT; Copyright (c) 2026 Matt Pocock | 2026-08-18 | Vendored without modification |

The corresponding upstream MIT notices are preserved under `licenses/`.

## Updating a vendored skill

1. Inspect the upstream skill tree and repository license at the candidate commit.
2. Record the exact commit SHA; never vendor from a floating branch without pinning
   the resolved commit.
3. Replace the complete skill directory with the upstream directory, including all
   referenced files, scripts, metadata, and assets.
4. Do not adapt upstream content silently. Put project-specific constraints in
   `AGENTS.md` or a project-specific skill.
5. Update this table and the preserved license notice when necessary.
6. Validate frontmatter, unique names, internal references, and repository docs.
7. Review the complete diff and confirm that the external advice remains subordinate
   to approved repository decisions.
