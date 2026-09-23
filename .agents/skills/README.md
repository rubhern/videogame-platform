# Repository skills

This directory holds the single canonical copy of every skill in this repository.
[`.claude/skills/`](../../.claude/skills) contains symlinks into it, so Codex and
Claude Code read identical text. Never maintain a second copy of a skill.

[`docs/development/ai-assistance.md`](../../docs/development/ai-assistance.md) is the
canonical source for the skill catalogue, the provenance, licensing, and update
policy of external skills, and the rest of the agent configuration.
[`AGENTS.md`](../../AGENTS.md) owns instruction and skill precedence: repository
sources and approved decisions always outrank generic skill advice.

`licenses/` preserves the available upstream license notices for the vendored skills.
