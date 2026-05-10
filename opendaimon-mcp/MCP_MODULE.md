# OpenDaimon MCP Module

`opendaimon-mcp` adds Spring AI MCP client runtime support to OpenDaimon.

## Purpose

The module is intentionally delivery-channel agnostic. It does not add Telegram,
REST, or UI commands. Its job is to bring Spring AI MCP client autoconfiguration
and transports onto the application classpath so external MCP server tools can be
exposed as Spring AI `ToolCallbackProvider` beans.

OpenDaimon consumes those callbacks in `opendaimon-spring-ai`:

- Agent mode merges MCP callbacks into `agentToolCallbacks`.
- The normal Spring AI prompt flow adds MCP callbacks to prompts only when the
  command requests `TOOL_CALLING` in required or optional capabilities.
- External MCP tools are exposed according to `open-daimon.mcp.tool-access` rules.
  The rules use command `userPriority` metadata; missing or unknown priority
  denies external MCP tools.
  Filesystem MCP tools are ADMIN-only by default; other MCP tools can be exposed
  to VIP/REGULAR users when the rules allow it.
- Built-in tools (`web_search`, `fetch_url`, `http_get`, `http_post`) remain
  available independently of MCP.

Telegram exposes `/mcp` to show the external MCP tools available to the current
user. The command uses the same `open-daimon.mcp.tool-access` mapping as runtime
tool execution, so ADMIN-only tools are not displayed to VIP or REGULAR users.

## Configuration

OpenDaimon-level consumption of external MCP tools is controlled by:

```yaml
open-daimon:
  mcp:
    enabled: true
    tool-access:
      default-roles: [ADMIN, VIP, REGULAR]
      rules:
        - name-pattern: "^(read_file|read_text_file|read_media_file|read_multiple_files|write_file|edit_file|create_directory|list_directory|list_directory_with_sizes|directory_tree|move_file|search_files|get_file_info|list_allowed_directories)$"
          roles: [ADMIN]
```

Rules are evaluated in order by Java regular expression against
`ToolDefinition.name()`. The first matching rule wins. Tools without a matching
rule use `default-roles`. Built-in OpenDaimon tools are not governed by these MCP
rules.

Spring AI MCP client creation is controlled by Spring AI properties. OpenDaimon
defaults keep client creation enabled; applications can disable it with
`MCP_CLIENT_ENABLED=false` or `spring.ai.mcp.client.enabled=false`:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        type: SYNC
        request-timeout: 30s
```

Example connection shapes:

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            filesystem:
              command: npx
              args:
                - -y
                - "@modelcontextprotocol/server-filesystem"
                - /tmp
        sse:
          connections:
            remote:
              url: http://localhost:9000
        streamable-http:
          connections:
            remote-http:
              url: http://localhost:9001
              endpoint: /mcp
```

The bundled `opendaimon-app` setup includes the default filesystem MCP stdio
connection. The published starter defaults enable MCP client support but do not
define a concrete filesystem stdio connection for downstream applications:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        stdio:
          connections:
            filesystem:
              command: sh
              args:
                - -c
                - exec ${MCP_FILESYSTEM_COMMAND:npx -y @modelcontextprotocol/server-filesystem} ${MCP_FILESYSTEM_ROOT:/app/mcp-filesystem}
```

Disable it in Docker with `MCP_CLIENT_ENABLED=false`. The runtime image includes
Node.js/npm and preinstalls `@modelcontextprotocol/server-filesystem`; Docker
sets `MCP_FILESYSTEM_COMMAND=mcp-server-filesystem` so startup does not depend on
runtime `npx` package resolution. Without that environment variable, the bundled
configuration falls back to `npx -y @modelcontextprotocol/server-filesystem` for
local development. The server runs inside the OpenDaimon container and sees only
the container filesystem plus mounted volumes. The compose file mounts
`./mcp-filesystem` to `/app/mcp-filesystem`; keep that root narrow and do not
point it at `/`, `/app/config`, or directories containing secrets.

Even when configured, filesystem MCP tools are made available to ADMIN users by
the default `tool-access` rule. This is enforced in both agent and normal Spring
AI prompt flows.

The smoke test `FilesystemMcpSmokeTest` starts `@modelcontextprotocol/server-filesystem`
with `npx`, creates a temporary sandbox containing `alpha.txt` and `nested/`,
then calls the MCP `list_directory` tool. A successful run prints output like:

```text
Filesystem MCP tools: [read_file, read_text_file, read_media_file, read_multiple_files, write_file, edit_file, create_directory, list_directory, list_directory_with_sizes, directory_tree, move_file, search_files, get_file_info, list_allowed_directories]
Filesystem MCP list_directory result: [{"text":"[FILE] alpha.txt\n[DIR] nested"}]
```

## Tool Name Collisions

OpenDaimon deduplicates tool callbacks by `ToolDefinition.name()` while preserving
registration order. Built-in tools are registered first, so an external MCP tool
with the same name is ignored. External callbacks with reserved built-in names are
also ignored when the corresponding built-in tool is not currently enabled.
