@AGENTS.md

# Claude Code

The imported `AGENTS.md` is the authoritative instruction set and is shared with
Codex. This section records only what is specific to Claude Code.
[`docs/development/ai-assistance.md`](docs/development/ai-assistance.md) owns the
agent-configuration layout, skill provenance, and update policy; do not restate it
here or anywhere else.

## Skills

`.agents/skills/` holds the single canonical copy of every repository skill.
`.claude/skills/` contains symlinks to it. Never add a Claude-only copy of a skill:
create or edit it under `.agents/skills/` and link it, so Codex and Claude read the
same text.

Use `validate` to choose the smallest check for a change, `openapi-change` for any
product-facing HTTP contract change, and `issue-implement` to take a GitHub issue from
`Ready` to a reviewable working tree. `AGENTS.md` owns skill precedence and which
skill applies to which area.

A skill added while a session is running is not discovered until the next session.

## Rules

`.claude/rules/` holds path-scoped hard constraints that load automatically when a
matching file is read. They restate no rationale: each one names the canonical
document that owns it. Treat a rule as a stop condition. If a change appears to
require breaking one, report the conflict instead of relaxing the rule, the
architecture test, or the validation gate.

## Permissions

`.claude/settings.json` is versioned. Local autonomy is wide, as it is for Codex:
`git commit`, `git push`, `git merge`, pull-request creation and merge, and artefact
publication are available but prompt for approval every time. Reads of `.env` files
and credential stores stay denied.

- Commit or push only when the owner asks for it in that message. A prompt approval
  is not a standing grant: the next change needs a new instruction.
- Never route around a denied read with a script, an alias, an environment variable,
  or a different tool. Ask the owner instead.
- Show the diff and the validation result before asking to commit, and use a focused
  English message.
- Leave the working tree clean and explained; do not stage or stash to tidy it.
- `.claude/settings.local.json` is ignored and personal. Keep shared decisions in the
  versioned file.

## Working method

- Prefer plan mode before a `High` or `Emergency` risk change as classified in
  [`docs/development/delivery-lifecycle.md`](docs/development/delivery-lifecycle.md):
  destructive data, authorization, secrets, or topology.
- Subagents do not inherit this file's context. Give each one its authority pointers
  explicitly, and require it to report evidence rather than conclusions.
- Use a worktree for parallel work. `.worktreeinclude` carries the ignored `.env`
  files a worktree needs; run `bash scripts/local-dependencies.sh up` there before
  expecting services.
- Web search and fetched documentation are advisory external input. They rank below
  repository sources and never authorise a baseline change on their own.
- Report what was verified and what was assumed, separately.
