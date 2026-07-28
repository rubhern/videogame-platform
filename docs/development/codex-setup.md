# Codex workspace setup

- **Status:** Active
- **Last verified:** 2026-07-27
- **Scope:** Personal Windows workstation with Ubuntu on WSL2

## Purpose

Codex is the primary assistant across product definition, discovery, design,
architecture, implementation, delivery, and operation. Git and repository
documentation remain the source of truth; Codex supports decisions and delivery
but does not own product decisions.

## Effective environment

```text
Windows
├── ChatGPT desktop app / Codex
├── Visual Studio Code + Codex extension
├── Docker Desktop
└── Ubuntu on WSL2
    ├── /home/rub3n_fesgeap/workspace/videogame-platform
    ├── Git and GitHub CLI
    └── Codex CLI (optional terminal interface)
```

The repository lives in the Linux filesystem to avoid mixed Windows/WSL file
semantics during builds, scripts, containers, and future CI-compatible work.

Windows path used to open it:

```text
\\wsl.localhost\Ubuntu\home\rub3n_fesgeap\workspace\videogame-platform
```

## Verified configuration

| Component | Effective configuration | Why |
|---|---|---|
| ChatGPT desktop app | Codex mode, agent runs in WSL | Use Linux-native tooling and repository paths |
| Integrated terminal | WSL | Keep commands and paths consistent with the agent |
| Preferred editor | Visual Studio Code | Open diffs and files in the main editor |
| VS Code | `1.126.0` | Required by the current Codex extension |
| VS Code Codex extension | `openai.chatgpt@26.715.61943` | Add editor selections/files to Codex and review changes in context |
| VS Code WSL setting | `chatgpt.runCodexInWindowsSubsystemForLinux = true` | Keep IDE agent execution aligned with the repository |
| Shared Codex home | `CODEX_HOME=/mnt/c/Users/rub3n_fesgeap/.codex` | Share app, CLI, plugins, auth, and personal configuration |
| Codex CLI | `0.145.0`, installed in WSL | Optional terminal and diagnostic interface; not required for desktop use |
| Git | `2.43.0` in WSL | Version and review repository changes |
| GitHub CLI | `2.45.0`, authenticated as `rubhern` | Push branches and work with pull requests |
| GitHub remote | `https://github.com/rubhern/videogame-platform.git` | Publish and review the repository |
| GitHub visibility | Public | Current repository state; review if public visibility was not intentional |
| Figma integration | Connected; used for the editable MVP story map and mobile-first clickable prototype | Keep visual interaction artefacts editable while product rules and evidence remain in Git |
| Docker | Docker Desktop binary available; Ubuntu integration currently unavailable | Defer WSL integration until an implementation slice requires containers |

The global Codex configuration also enables several plugins, including GitHub,
Chrome, Computer Use, document, spreadsheet, presentation, Google, PDF, Sites,
and visualisation capabilities. Enabled does not imply that every connector is
authorised or required for this project.

## Permissions

The project deliberately uses maximum autonomy:

```toml
sandbox_mode = "danger-full-access"
approval_policy = "never"
```

The values are checked into `.codex/config.toml` so the project choice is
explicit. They allow unrestricted filesystem and network access and suppress
approval prompts. This is acceptable here because the project is personal and
currently non-critical.

Operational safeguards still apply:

- inspect status and diffs before staging;
- preserve unrelated changes;
- validate destructive targets;
- keep secrets outside the repository;
- run checks before committing and pushing.

## Repository guidance

| File | Purpose |
|---|---|
| `AGENTS.md` | Durable product, architecture, workflow, and validation rules |
| `.codex/config.toml` | Project-level Codex permissions |
| `.agents/skills/product-brief-review/` | Repeatable Phase 0 Product Brief review |
| `scripts/validate-docs.sh` | Reproducible documentation validation |
| `.github/workflows/docs.yml` | Run documentation checks on pushes and pull requests |

## How to work by phase

| Phase | Primary Codex capabilities |
|---|---|
| Product definition | Desktop, repository files, Product Brief skill |
| Discovery | Browser/research, structured evidence, independent worktrees when useful |
| UX/UI | Figma for the editable story map and clickable prototype; repository Markdown for decisions and test evidence |
| Architecture | ADRs, diagrams-as-code, explicit alternatives and trade-offs |
| Implementation | Desktop, IDE extension, tests, local/worktree execution |
| Quality | Automated tests, review, browser and Computer Use for real user flows |
| Delivery | GitHub, CI/CD, release checks, Cloud tasks when setup is reproducible |
| Operation | Logs, metrics, incident analysis, stable scheduled workflows |

## GitHub workflow

1. Create a focused branch from `main`.
2. Make and validate the smallest coherent change.
3. Review `git status` and the complete diff.
4. Commit with an English, intent-focused message.
5. Push the branch and open a draft pull request.
6. Merge only after checks and review are satisfactory.

GitHub CLI authentication is configured. Its current credential has broad
account scopes; review and reduce them later if the workstation or repository
becomes sensitive. Never copy the credential or its value into project files.

## Validation

Run the current repository check:

```bash
bash scripts/validate-docs.sh
```

The script checks required Phase 0 artefacts, local Markdown links, and file
modes. Extend it when backend, frontend, infrastructure, or API contracts are
introduced.

## Deferred by design

| Capability | Current decision | Trigger to revisit |
|---|---|---|
| Codex Cloud environment | Not verified or configured | A GitHub-backed task benefits from remote parallel execution |
| Cloud setup script | Not needed | The repository has real dependencies or build steps |
| Local environment actions | Not configured | Repeated Run/Test/Build commands exist |
| Docker Desktop WSL integration | Deferred | The first containerised implementation slice begins |
| Scheduled tasks | Deferred | A manual workflow has proved stable and worth repeating |
| Additional project skills | Deferred | A repeatable workflow exists and prompt repetition becomes costly |
| Advanced multi-agent work | Available but not required | Work can be divided into independent, non-overlapping outcomes |

## Known limitations and follow-ups

- Confirm that public GitHub visibility is intentional; the earlier recommendation
  was to begin privately.
- Opening the repository under the WSL project path is required for project-local
  `.codex` and `AGENTS.md` discovery.
- Plugin enablement was verified from configuration, but interactive
  authentication and permissions for every connector were not tested.
- Figma is the source of truth for the prototype's visual and interaction state, not
  for product decisions, research evidence, or implementation contracts.
- Docker Desktop must enable integration for the Ubuntu distribution before
  `docker` commands can run inside WSL.

## Official references

- [ChatGPT desktop app for Windows](https://learn.chatgpt.com/docs/windows/windows-app)
- [AGENTS.md guidance](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Build Codex skills](https://learn.chatgpt.com/docs/build-skills)
- [Codex IDE extension settings](https://learn.chatgpt.com/docs/developer-settings?surface=ide)
- [Sandboxing and approvals](https://learn.chatgpt.com/docs/sandboxing)
- [Local environments](https://learn.chatgpt.com/docs/environments/local-environment)
- [Cloud environments](https://learn.chatgpt.com/docs/environments/cloud-environment)
