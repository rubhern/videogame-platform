# Repository skills

This directory contains project-specific and vendored instruction skills for Codex.
Repository authorities and `AGENTS.md` take precedence over generic external advice.

## Project-specific skills

| Name | Purpose |
|---|---|
| `product-brief-review` | Review the Product Brief and product-alignment evidence without inventing decisions. |
| `scalability-by-design` | Review APIs, queries, persistence and other data paths for bounded work, deterministic behavior and evidence-based scale. |
| `videogame-platform-backend-development` | Implement, refactor, debug, or review the backend according to the approved project architecture and contracts. |
| `videogame-platform-frontend-development` | Implement, refactor, debug, test, or review the React/TypeScript frontend according to the approved SPA, BFF, OpenAPI, state, accessibility, and testing decisions. |

## Vendored external skills

| Name | Purpose | Upstream repository | Upstream path | Commit SHA | License | Incorporated | Modification status |
|---|---|---|---|---|---|---|---|
| `java-springboot` | General Spring Boot development guidance. | `github/awesome-copilot` | `skills/java-springboot` | `318066d2213b510e89b500ed0d53506c54093ddc` | MIT; GitHub, Inc. | 2026-08-18 | `vendored-unmodified` |
| `architecture-patterns` | General Clean, Hexagonal, and DDD architecture patterns. | `wshobson/agents` | `plugins/backend-development/skills/architecture-patterns` | `367cb6a4a182cf7e9b0a17c9429f7411ddd9cf35` | MIT; Copyright (c) 2024 Seth Hobson | 2026-08-18 | `vendored-unmodified` |
| `tdd` | Behavior-focused red/green test-driven development. | `mattpocock/skills` | `skills/engineering/tdd` | `9c9f36ccd3995266cd675468af71639c8dde1ec5` | MIT; Copyright (c) 2026 Matt Pocock | 2026-08-18 | `vendored-unmodified` |
| `vercel-react-best-practices` | React and Next.js performance review guidance; framework-specific advice remains subordinate to the project SPA baseline. | `vercel-labs/agent-skills` | `skills/react-best-practices` | `dd089a8c752c966dee8bf0f27cb625ba193ffd9e` | MIT declared in skill frontmatter; no separate upstream license notice at this commit | 2026-08-22 | `vendored-unmodified` |
| `vercel-composition-patterns` | React 19 composition and component-API guidance. | `vercel-labs/agent-skills` | `skills/composition-patterns` | `dd089a8c752c966dee8bf0f27cb625ba193ffd9e` | MIT declared in skill frontmatter; no separate upstream license notice at this commit | 2026-08-22 | `vendored-unmodified` |
| `react-testing` | Behavior-focused React, Vitest/RTL, accessibility, and E2E test-boundary guidance. | `affaan-m/ecc` | `skills/react-testing` | `d8409a4b0813771235555e32e3d8046a73988bfa` | MIT; Copyright (c) 2026 Affaan Mustafa | 2026-08-22 | `vendored-unmodified` |
| `frontend-accessibility-best-practices` | React accessibility guidance for semantics, keyboard, focus, announcements, motion, and touch. | `sergiodxa/agent-skills` | `skills/frontend-accessibility-best-practices` | `40e21b46189d5c7de6610b68a25280af863f8775` | MIT; Copyright (c) 2026 Sergio Xalambrí | 2026-08-22 | `vendored-unmodified` |

Available upstream MIT notices are preserved under `licenses/`. The Vercel skills
carry their MIT declaration in their unmodified `SKILL.md` frontmatter; the pinned
upstream commit does not contain a separate license notice to copy.

The unmodified `react-testing` entrypoint contains optional cross-links to other ECC
skills and repository-level rules outside its upstream skill directory. Those
packages are not runtime resources of `react-testing` and were not implicitly
vendored; every resource internal to the requested upstream directory is present.

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
8. Start a new Codex session when the active session's discovery catalogue needs to
   refresh newly added repository skills.
