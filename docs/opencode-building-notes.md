# OpenCode Agent-Building Notes

These notes were checked against the official OpenCode documentation on 2026-10-04.

## Verified project structure

OpenCode supports a project-level `opencode.json` or `opencode.jsonc` file. It also discovers custom project agents from `.opencode/agents/`. The Markdown filename becomes the agent name, so `firstelement-security.md` becomes `firstelement-security`.

Each agent Markdown file may use YAML frontmatter including:

- `description`: what the agent does;
- `mode`: `primary`, `subagent`, or `all`;
- `model`: optional `provider/model-id` override;
- `temperature`: optional generation setting;
- `hidden`: hide a subagent from autocomplete while allowing programmatic invocation;
- `permission`: per-agent permissions.

This kit omits model overrides so it uses the user’s configured model/provider rather than embedding a provider assumption.

## Verified permissions

Permission values are `allow`, `ask`, or `deny`. The relevant keys are `read`, `edit`, `bash`, `task`, `external_directory`, `webfetch`, `websearch`, `skill`, `lsp`, and other tool names. `permission` replaces the older boolean `tools` configuration for new setups.

Rules can be granular. The kit allows repository editing and shell execution for the requested full-access mode, while denying `.env` reads, external-directory access, pushes, hard resets, and selected destructive database/system commands. These explicit denies are safety boundaries, not a substitute for reviewing OpenCode’s resolved config.

`task` is restricted so `firstelement-orchestrator` can invoke only `firstelement-*` agents. Specialized subagents have `task: deny` and therefore cannot recursively fan out.

## Verified automatic approval

OpenCode supports `--auto` and `opencode run --auto`. Auto mode approves permission requests that are not explicitly denied. It does not override explicit `deny` rules.

Recommended commands:

```bash
opencode --auto
opencode run --auto "Use firstelement-orchestrator and start Phase 0."
```

Do not use an undocumented “dangerously skip permissions” flag. Use the installed version’s documented `--auto` behavior and record the version and invocation in the repository’s migration run log.

## Verified instructions behavior

OpenCode loads project `AGENTS.md` rules and supports an `instructions` array in `opencode.jsonc` for additional files. This kit explicitly includes the product requirements, PR brief, plan, and orchestrator brief in that array so every agent sees the same execution contract.

`AGENTS.md` takes precedence over the repository’s Claude-compatible rules when both are present in the same category. The kit keeps the legacy `AGENT.md` and `CLAUDE.md` under `docs/` for reference and explains how the target migration rules supersede framework-specific limitations.

## Sources

- https://opencode.ai/docs/agents/
- https://opencode.ai/docs/config/
- https://opencode.ai/docs/permissions/
- https://opencode.ai/docs/rules/
