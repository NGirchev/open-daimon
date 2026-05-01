# Codex Workstation Setup

This guide documents the project-specific Codex, MCP, and Serena setup needed to work on OpenDaimon from a new machine.

Do not commit personal global files such as `~/.codex/config.toml`, `~/.codex/hooks.json`, or `~/.serena/serena_config.yml`. This repository keeps portable examples and project instructions instead.

## What Is Versioned

- `AGENTS.md` contains the project instructions for Codex and other coding agents.
- `.serena/project.yml` contains the shared Serena project configuration.
- `.serena/memories/` contains curated project memories that are safe to share.
- `docs/codex/mcp.example.json` is a portable MCP example for clients that support repo-local MCP config.
- `docs/codex/config.example.toml` is a template for the relevant `~/.codex/config.toml` sections.
- `docs/codex/serena.example.yml` is a template for the relevant `~/.serena/serena_config.yml` values.

The local `.mcp.json`, `.serena/project.local.yml`, `.serena/cache/`, and OS/editor artifacts stay ignored.

## Prerequisites

Install the normal project toolchain first:

- Java 21
- Maven wrapper support through `./mvnw`
- Docker, for integration tests and local runtime dependencies
- Node.js with `npx`, for Context7 and JetBrains MCP bridge
- `uvx`, for Serena
- `rg`, for fast repository search
- `ast-outline`, optional but recommended because `AGENTS.md` asks agents to use it for structural pre-reads

For JetBrains MCP, open the project in a JetBrains IDE with the MCP plugin enabled. The documented bridge expects the plugin SSE endpoint at `http://127.0.0.1:64342/sse`.

## Codex Global Config

Copy the relevant sections from `docs/codex/config.example.toml` into `~/.codex/config.toml`.

Replace:

- `<absolute-path-to-open-daimon>` with the real checkout path on that machine.

Keep API keys and personal preferences out of repository files. If a server requires authentication, configure it through the normal global Codex or provider-specific mechanism on that workstation.

The important MCP servers for this project are:

- `serena`, for project-aware symbol navigation and memories.
- `jetbrains`, for IDE-indexed Java navigation and inspections.
- `context7`, for current framework and library docs.
- `exa`, for web research when needed.

Restart Codex after editing `~/.codex/config.toml`. MCP discovery is session-scoped, so changed servers usually do not appear in an already-running session.

## Repo-Local MCP Option

If the Codex build or another MCP-aware client supports repo-local `.mcp.json`, create a local copy:

```bash
cp docs/codex/mcp.example.json .mcp.json
```

The committed example uses Serena `--project-from-cwd`, so it does not contain a machine-specific project path.

Keep `.mcp.json` ignored. It is allowed to diverge per machine when a local MCP endpoint, transport, or path differs.

## Serena Global Config

Copy the relevant values from `docs/codex/serena.example.yml` into `~/.serena/serena_config.yml`.

Recommended behavior for this project:

- Keep `project_serena_folder_location: "$projectDir/.serena"` so shared memories live in the checkout.
- Keep `web_dashboard: true` for diagnostics.
- Keep `web_dashboard_open_on_launch: false` so Serena does not open a browser tab on every startup.
- Use `language_backend: LSP` by default. Use JetBrains only when you intentionally want Serena to depend on the IDE backend.

After the first activation, Serena may update the global `projects` list with the machine's real checkout path.

## Hooks And Skills

Codex hooks and skills are workstation-level assets, not OpenDaimon runtime files.

The current local setup uses:

- `~/.codex/hooks.json` for hook orchestration.
- `~/.codex/skills/continuous-learning-v2` for session observations.
- Java/OpenDaimon skills such as `fix-java`, `open-daimon-spring-patterns`, `springboot-tdd`, and `springboot-verification`.

Do not vendor those directories into this repository. If another machine should behave the same way, install or sync the Codex skill stack into that machine's `~/.codex/skills`, then enable hooks in the global Codex config:

```toml
[features]
codex_hooks = true
memories = true
```

Hooks should remain optional for building and testing OpenDaimon. A fresh agent must still be able to work from `AGENTS.md`, the Maven project, and the MCP templates.

## Smoke Check

From the repository root, start a new Codex session and check:

```bash
codex mcp list
```

Expected MCP servers:

- `context7`
- `serena`
- `exa`
- `jetbrains`, when the JetBrains IDE plugin is running

Then ask Codex to verify that Serena is active for `open-daimon`. If Serena starts but the dashboard does not open automatically, that is expected with the recommended config.

For code verification after edits, keep using the repository rule:

```bash
./mvnw clean compile
```
