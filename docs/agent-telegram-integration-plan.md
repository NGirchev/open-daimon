# Agent Mode as Default Telegram Handler

## Goal

When `open-daimon.agent.enabled=true`, the regular `MessageTelegramCommandHandler` is replaced
by an agent-aware handler. Users send normal messages — the agent handles them with ReAct reasoning
and tool calls transparently.

No `/agent` command needed. No user-facing changes.

## Current State

```
MessageTelegramCommandHandler (FSM-driven)
  ├── resolveUser
  ├── validateInput
  ├── saveMessage (to DB)
  ├── prepareMetadata (thread, role, model preference)
  ├── createCommand (AIRequestPipeline → RAG → AICommand)
  ├── generateResponse (aiGateway.generateResponse)  ← THIS CHANGES
  ├── saveResponse (to DB, update RAG metadata)
  └── sendResponse (stream/non-stream + keyboard)

AgentTelegramCommandHandler (simple, no FSM)
  ├── validate /agent prefix
  ├── AgentCommandHandler.handle(AgentChatCommand)
  └── sendMessage (plain text, no keyboard, no DB save)
```

**Problem**: `AgentTelegramCommandHandler` skips message persistence, threading, streaming,
keyboard rendering, RAG metadata tracking, and error handling. It cannot replace the message handler.

## Target Architecture

```
MessageTelegramCommandHandler (FSM-driven, unchanged outer flow)
  ├── resolveUser
  ├── validateInput
  ├── saveMessage
  ├── prepareMetadata
  ├── createCommand
  ├── generateResponse ← DELEGATES BASED ON agent.enabled
  │     ├── [agent OFF]: aiGateway.generateResponse(aiCommand)
  │     └── [agent ON]:  agentExecutor.execute(agentRequest)
  │                        ├── builds AgentRequest from aiCommand context
  │                        ├── runs ReAct loop (think → tool → observe → ...)
  │                        └── returns AgentResult → mapped to AIResponse
  ├── saveResponse
  └── sendResponse (stream/non-stream + keyboard)
```

Single handler. Same DB persistence. Same streaming. Same keyboard.
Only the "generate response" step changes.

## Implementation Plan

### Phase 1: Agent-aware generateResponse action

**File**: `TelegramMessageHandlerActions.java` — `generateResponse()` method

**Change**: add agent branch inside `generateResponse`:

```java
@Override
public void generateResponse(MessageHandlerContext ctx) {
    if (agentExecutor != null && agentEnabled) {
        generateAgentResponse(ctx);
    } else {
        generateGatewayResponse(ctx);
    }
}
```

**generateAgentResponse(ctx)**:
1. Build `AgentRequest` from context:
   - `task` = user text (or augmented query if RAG processed documents)
   - `conversationId` = thread key
   - `metadata` = from ctx.getMetadata()
   - `maxIterations` = from AgentProperties
   - `enabledTools` = empty (all tools)
2. Call `agentExecutor.execute(request)` (synchronous first, streaming later)
3. Map `AgentResult.finalAnswer()` → set on context as response text
4. Map `AgentResult.terminalState()` → handle errors (FAILED → error type)

**generateGatewayResponse(ctx)** = current code, extracted as-is.

### Phase 2: Wire AgentExecutor into TelegramMessageHandlerActions

**File**: `TelegramCommandHandlerConfig.java`

**Change**: inject `ObjectProvider<AgentExecutor>` and `AgentProperties` into
`messageHandlerActions` bean. Use `ObjectProvider` — agent beans may not exist
when `agent.enabled=false`.

```java
@Bean
public TelegramMessageHandlerActions messageHandlerActions(
        ...,
        ObjectProvider<AgentExecutor> agentExecutorProvider,
        ObjectProvider<AgentProperties> agentPropertiesProvider) {
    return new TelegramMessageHandlerActions(
            ...,
            agentExecutorProvider.getIfAvailable(),
            agentPropertiesProvider.getIfAvailable());
}
```

**File**: `TelegramMessageHandlerActions.java` constructor

**Change**: add two optional fields:
```java
private final AgentExecutor agentExecutor;       // null when agent disabled
private final AgentProperties agentProperties;   // null when agent disabled
```

### Phase 3: Remove standalone AgentTelegramCommandHandler

When agent mode replaces the message handler, `/agent` command is redundant.

1. Delete `AgentTelegramCommandHandler.java`
2. Remove bean from `TelegramCommandHandlerConfig`
3. Remove `open-daimon.telegram.commands.agent-enabled` property
4. Remove `/agent` from `TelegramBotMenuService`

### Phase 4: Streaming support (optional, follow-up)

Current `generateResponse` supports streaming via `aiGateway`:
- `aiGateway.generateStreamResponse()` returns `AIStreamResponse`
- Handler sends paragraphs as they arrive

Agent streaming:
- `agentExecutor.executeStream(request)` returns `Flux<AgentStreamEvent>`
- Map stream events to chat response chunks
- Send thinking/tool indicators as typing status
- Stream final answer as paragraphs

This is a separate effort — synchronous agent execution is sufficient for Phase 1.

## Bean Condition Changes

| Bean | Current condition | New condition |
|------|------------------|---------------|
| `messageHandlerActions` | always (when telegram enabled) | always (inject agent optionally) |
| `messageTelegramCommandHandler` | always | always (handles both modes) |
| `agentTelegramCommandHandler` | `agent.enabled=true` | **REMOVED** |
| `AgentCommandHandler` | `agent.enabled=true` | `agent.enabled=true` (still needed for REST/UI) |

## Configuration

```yaml
open-daimon:
  agent:
    enabled: true          # enables agent beans + replaces telegram message handling
    max-iterations: 10
    tools:
      http-api:
        enabled: true
```

No new properties. `agent.enabled=true` is the only switch.

## Risks

1. **Response latency**: agent multi-step execution is slower than single gateway call.
   Mitigation: typing indicator runs throughout; streaming (Phase 4) shows progress.

2. **Token cost**: multiple LLM calls per message.
   Mitigation: `maxIterations` limits cost. AUTO strategy falls back to SIMPLE
   when no tools are available — effectively same as current behavior.

3. **RAG interaction**: agent receives augmented query from pipeline.
   If agent also calls tools that fetch data, context may be redundant.
   Mitigation: agent prompt should mention that document context is already provided.

4. **Error handling**: agent FAILED state must map to existing error types
   (`MessageHandlerErrorType`) for proper Telegram error messages.

## Testing

1. **Unit test**: mock `AgentExecutor`, verify `generateAgentResponse` builds correct
   `AgentRequest` and maps `AgentResult` to context.
2. **Fixture test**: new fixture that enables agent mode, sends a message with mock
   agent executor, verifies full flow (DB save, response, keyboard).
3. **Smoke test**: update `AgentAutoConfigSmokeIT` to verify agent beans inject into
   telegram handler actions.

## Estimated Scope

- Phase 1: ~100 lines changed in `TelegramMessageHandlerActions`
- Phase 2: ~15 lines in `TelegramCommandHandlerConfig`
- Phase 3: ~50 lines deleted
- Phase 4: separate PR

Total: small, focused change. Most complexity is in existing code that stays unchanged.
