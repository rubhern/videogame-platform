# AI assistance configuration

This document owns how AI coding agents are configured in this repository: which
instruction files each agent reads, where skills live, and the provenance, licensing,
and update policy for external skills.

It does not restate the rules those files contain. [`AGENTS.md`](../../AGENTS.md) owns
the instructions themselves and the instruction/skill precedence;
[`docs/README.md`](../README.md) owns documentation ownership; the
[delivery lifecycle](delivery-lifecycle.md) owns validation selection and the
Definition of Done.

## Instruction files

| File | Read by | Responsibility |
|---|---|---|
| [`AGENTS.md`](../../AGENTS.md) | Codex, and Claude Code through the import below | The complete, agent-independent instruction set |
| [`CLAUDE.md`](../../CLAUDE.md) | Claude Code | `@AGENTS.md` import plus the behaviour specific to Claude Code |
| [`.claude/rules/`](../../.claude/rules) | Claude Code | Path-scoped hard constraints loaded when a matching file is read |
| [`.claude/settings.json`](../../.claude/settings.json) | Claude Code | Versioned permissions |
| [`.codex/config.toml`](../../.codex/config.toml) | Codex | Sandbox and approval policy |
| [`.worktreeinclude`](../../.worktreeinclude) | Claude Code | Ignored files copied into a new worktree |

`AGENTS.md` is the single instruction source. Claude Code does not read it directly,
so `CLAUDE.md` imports it with `@AGENTS.md` and adds only what differs for that agent.
Never copy instructions between the two files.

A rule in `.claude/rules/` is a restriction plus a pointer to the document that owns
the reasoning. It never becomes a second explanation of an approved decision.

Both agents run with wide local autonomy. `.codex/config.toml` grants Codex full
access with no approval prompts. `.claude/settings.json` is versioned and shared, and
expresses the same intent in three lists:

- **allow**: the repository validation scripts, the Maven Wrapper, npm, and read-only
  `gh` commands run without a prompt.
- **ask**: `git commit`, `git push`, `git merge`, pull-request creation and merge, and
  artefact publication are permitted but prompt every time, so they happen only when
  the owner asks for them. `AGENTS.md` states the rule this enforces.
- **deny**: reads of `.env` files and credential stores. `.env.example` and
  `backend/.env.example` stay readable because they hold placeholders; a deny rule
  cannot carry an exception, so the patterns match the real files only.

`.claude/settings.local.json` and `.claude/worktrees/` are ignored machine-local
state.

## Skills

`.agents/skills/` holds the single canonical copy of every skill.
`.claude/skills/` contains symlinks into it, so both agents read identical text and no
skill is ever maintained twice. Add or edit a skill under `.agents/skills/` and link
it; never create a Claude-only or Codex-only copy.

External skills are advisory. They never authorize a platform, service, paid resource,
deployment target, or change to an approved architecture decision. `AGENTS.md` owns
the precedence and states which skill applies to which area.

### Project-specific skills

| Name | Purpose |
|---|---|
| `product-brief-review` | Review the Product Brief and product-alignment evidence without inventing decisions. |
| `scalability-by-design` | Review APIs, queries, persistence and other data paths for bounded work, deterministic behavior and evidence-based scale. |
| `videogame-platform-backend-development` | Implement, refactor, debug, or review the backend according to the approved project architecture and contracts. |
| `videogame-platform-frontend-development` | Implement, refactor, debug, test, or review the React/TypeScript frontend according to the approved SPA, BFF, OpenAPI, state, accessibility, and testing decisions. |
| `validate` | Select the smallest local validation set that can detect a regression in what changed. |
| `openapi-change` | Change a product-facing HTTP contract in the required contract-first order. |
| `issue-implement` | Take a GitHub issue from `Ready` to a reviewable working tree, including documentation and the Project board. |

### CLI-managed external skills

The generated [`skills-lock.json`](../../skills-lock.json) owns source, upstream path,
and content tracking for these skills.

| Name | Purpose | Use when |
|---|---|---|
| `terraform-style-guide` | HashiCorp Terraform organization, naming, formatting, security, and review guidance. | Writing or reviewing Terraform while preserving the approved OCI, zero-cost, state, secret, and provisioning gates. |
| `terraform-test` | Terraform test files, plan/apply modes, assertions, mocks, and CI test patterns. | Adding or troubleshooting `.tftest.hcl`; prefer plan or mock evidence unless an approved test explicitly requires resources. |
| `dockerfile-optimise` | Docker build caching, multi-stage images, minimal runtimes, and container hardening guidance. | Changing or reviewing a Dockerfile or image build; this skill is experimental upstream, and the repository's multi-architecture, non-root, immutable-image contract remains authoritative. |
| `github-actions-templates` | Generic GitHub Actions test, build, security, matrix, and deployment patterns. | Changing workflows after applying the repository's selective pull-request gates, complete trusted-`main` integration, least privilege, GHCR, and protected deployment rules. |
| `observability-monitoring` | Generic metrics, logs, traces, dashboards, alerts, and Prometheus/Grafana patterns. | Working on an evidenced observability need; bounded labels, safe telemetry, optional OpenTelemetry-compatible export, zero cost, and the constrained private environment override its production/distributed examples. |

### Manually vendored external skills

| Name | Purpose | Upstream repository | Upstream path | Commit SHA | License | Incorporated | Modification status |
|---|---|---|---|---|---|---|---|
| `java-springboot` | General Spring Boot development guidance. | `github/awesome-copilot` | `skills/java-springboot` | `318066d2213b510e89b500ed0d53506c54093ddc` | MIT; GitHub, Inc. | 2026-08-18 | `vendored-unmodified` |
| `architecture-patterns` | General Clean, Hexagonal, and DDD architecture patterns. | `wshobson/agents` | `plugins/backend-development/skills/architecture-patterns` | `367cb6a4a182cf7e9b0a17c9429f7411ddd9cf35` | MIT; Copyright (c) 2024 Seth Hobson | 2026-08-18 | `vendored-unmodified` |
| `tdd` | Behavior-focused red/green test-driven development. | `mattpocock/skills` | `skills/engineering/tdd` | `9c9f36ccd3995266cd675468af71639c8dde1ec5` | MIT; Copyright (c) 2026 Matt Pocock | 2026-08-18 | `vendored-unmodified` |
| `vercel-react-best-practices` | React and Next.js performance review guidance; framework-specific advice remains subordinate to the project SPA baseline. | `vercel-labs/agent-skills` | `skills/react-best-practices` | `dd089a8c752c966dee8bf0f27cb625ba193ffd9e` | MIT declared in skill frontmatter; no separate upstream license notice at this commit | 2026-08-22 | `vendored-unmodified` |
| `vercel-composition-patterns` | React 19 composition and component-API guidance. | `vercel-labs/agent-skills` | `skills/composition-patterns` | `dd089a8c752c966dee8bf0f27cb625ba193ffd9e` | MIT declared in skill frontmatter; no separate upstream license notice at this commit | 2026-08-22 | `vendored-unmodified` |
| `react-testing` | Behavior-focused React, Vitest/RTL, accessibility, and E2E test-boundary guidance. | `affaan-m/ecc` | `skills/react-testing` | `d8409a4b0813771235555e32e3d8046a73988bfa` | MIT; Copyright (c) 2026 Affaan Mustafa | 2026-08-22 | `vendored-unmodified` |
| `frontend-accessibility-best-practices` | React accessibility guidance for semantics, keyboard, focus, announcements, motion, and touch. | `sergiodxa/agent-skills` | `skills/frontend-accessibility-best-practices` | `40e21b46189d5c7de6610b68a25280af863f8775` | MIT; Copyright (c) 2026 Sergio Xalambrí | 2026-08-22 | `vendored-unmodified` |

Available upstream MIT notices are preserved under
[`.agents/skills/licenses/`](../../.agents/skills/licenses). The Vercel skills carry
their MIT declaration in their unmodified `SKILL.md` frontmatter; the pinned upstream
commit does not contain a separate license notice to copy.

The unmodified `react-testing` entrypoint contains optional cross-links to other ECC
skills and repository-level rules outside its upstream skill directory. Those packages
are not runtime resources of `react-testing` and were not implicitly vendored; every
resource internal to the requested upstream directory is present.

The pinned Vercel trees contain upstream Markdown trailing whitespace. The reviewed
change whitespace gate excludes only those two byte-identical vendored paths; all
repository-owned files remain subject to `git diff --check`.

`scripts/validate-docs.sh` reads the `vendored-unmodified` rows of the table above to
decide which skill trees are exempt from repository link rules. Keep the table shape
when editing it.

## Add a project-specific skill

1. Create `.agents/skills/<name>/SKILL.md` with `name` and `description` frontmatter,
   where `name` matches the directory.
2. Link it: `ln -s ../../.agents/skills/<name> .claude/skills/<name>`.
3. Add the skill to the project-specific table above and to the required-file list in
   `scripts/validate-docs.sh`.
4. Run `bash scripts/validate-docs.sh`.
5. Start a new agent session; a skill added mid-session is not discovered.

## Update a vendored skill

1. Inspect the upstream skill tree and repository license at the candidate commit.
2. Record the exact commit SHA; never vendor from a floating branch without pinning
   the resolved commit.
3. Replace the complete skill directory with the upstream directory, including all
   referenced files, scripts, metadata, and assets.
4. Do not adapt upstream content silently. Put project-specific constraints in
   `AGENTS.md` or a project-specific skill.
5. Update the table above and the preserved license notice when necessary.
6. Validate frontmatter, unique names, internal references, and repository docs.
7. Review the complete diff and confirm that the external advice remains subordinate
   to approved repository decisions.
8. Start a new agent session when the discovery catalogue needs to refresh.
