# Agent Loop Research: Mixed Tool Payload Recovery

## Context

In production logs we observed terminal events (`FINAL_ANSWER`) containing mixed content:

- user-facing prose
- raw tool payload markers (`http_get`, `<arg_key>`, `<arg_value>`, `</tool_call>`)

This caused Telegram FSM to route `RESPONSE_GENERATED -> ERROR` when no valid final text was extracted.

## Canonical Spring AI Behavior

For user-controlled tool execution (`internalToolExecutionEnabled=false`), Spring AI expects this loop:

1. Call model with tool definitions.
2. If `ChatResponse.hasToolCalls()` is true, execute via `ToolCallingManager.executeToolCalls(...)`.
3. Append tool result to conversation history.
4. Call model again until no tool calls remain.
5. Return final assistant text.

References:

- Spring AI tools docs:
  - https://github.com/spring-projects/spring-ai/blob/main/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools.adoc
- OpenAI assistant/tool call schema:
  - https://developers.openai.com/api-reference/chat/create

## Root Cause in OpenDaimon

`SpringAgentLoopActions.think()` made the branch decision only by `response.hasToolCalls()`.

- If provider returned tool intent as plain text (not structured `tool_calls`), branch fell through to "final answer".
- FSM then transitioned to `ANSWERING -> COMPLETED`, emitting terminal `FINAL_ANSWER`.
- Telegram safety guard detected raw payload markers and converted to `EMPTY_RESPONSE`.

## Implemented Design

### 1) ReAct recovery in `SpringAgentLoopActions`

- Detect mixed payload markers in assistant text.
- Recover a tool call from text:
  - tool name (`<tool_name>` or standalone tool line)
  - arguments (`<arg_key>/<arg_value>`, `key=value`, URL fallback)
- Build a synthetic `ChatResponse` containing `AssistantMessage.toolCalls`.
- Store synthetic response in context (`KEY_LAST_RESPONSE`) and continue through normal `executeTool()` path.

This keeps the loop agentic even when the provider emits textual tool directives.

### 2) Telegram terminal handling

- For mixed terminal payloads, extract user-visible text prefix before first tool marker.
- Send recovered prefix when non-empty.
- Keep `EMPTY_RESPONSE` only for pure payload (no user-visible text).

## Validation Strategy

Added tests cover:

- detection/recovery of mixed payload (`spring-ai`)
- continued loop with tool events and clean terminal answer (`spring-ai`)
- repeated mixed payload ending with `MAX_ITERATIONS` instead of `ERROR` (`spring-ai`)
- Telegram recovery of mixed terminal text and pure-payload fallback (`telegram`)

