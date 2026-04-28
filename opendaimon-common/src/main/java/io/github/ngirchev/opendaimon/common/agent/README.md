# Agent Framework Architecture

Production-ready AI agent framework for Spring Boot. Agents autonomously solve tasks
by reasoning (thinking), acting (tool calls), and observing (tool results) in a loop
driven by a Finite State Machine.

## Module Layout

```
opendaimon-common/agent/          <-- Interfaces, FSM factory, domain objects
opendaimon-spring-ai/agent/       <-- Spring AI implementations (ChatModel, tools, memory)
opendaimon-telegram/handler/impl/ <-- Telegram channel adapter (FSM-based agent invocation)
```

## Execution Strategies

| Strategy | When | How |
|----------|------|-----|
| **REACT** | Tools available | Think-Act-Observe loop via FSM |
| **SIMPLE** | No tools | Single LLM call, immediate answer |
| **PLAN_AND_EXECUTE** | Complex multi-step | LLM generates plan, ReAct executes each step |
| **AUTO** (default) | Always | Tools present -> REACT, otherwise SIMPLE |

## ReAct Loop FSM

```
INITIALIZED ──[START]──> THINKING (action: think)

THINKING ──[auto]──┬─ [hasError]            ──> FAILED
                   ├─ [isMaxIterationsReached] ──> MAX_ITERATIONS
                   ├─ [hasToolCall]          ──> TOOL_EXECUTING (action: executeTool)
                   ├─ [hasFinalAnswer]       ──> ANSWERING (action: answer)
                   └─ [else]                 ──> FAILED (empty LLM output)

TOOL_EXECUTING ──[auto]──┬─ [hasError] ──> FAILED
                         └─ [else]     ──> OBSERVING (action: observe)

OBSERVING ──[auto]──> THINKING (action: think, loop back)

ANSWERING ──[auto]──> COMPLETED (terminal)
```

- **Single external event**: `START`. All subsequent transitions are auto-transitions.
- **FSM is a stateless singleton** — each execution creates a fresh `AgentContext`.
- Guard predicates evaluate on `AgentContext` fields.

## Sequence: Telegram Message → Agent Execution

Agent mode has dual semantics controlled by `open-daimon.agent.enabled`:

1. **Module gate** — when `false`, no `AgentExecutor` bean is created and the entire agent module
   is inactive. All requests go through `AIGateway`. The `/mode` Telegram command is not registered.
2. **Default for new users** — when `true`, new `TelegramUser` records are created with
   `agentModeEnabled=true`. Existing users with `agentModeEnabled=null` also resolve to `true`.
   Individual users can override this default via the `/mode` Telegram command.

When `open-daimon.agent.enabled=true`, `TelegramMessageHandlerActions.generateResponse()` delegates
to `AgentExecutor` only when the per-user flag resolves to `true`
(`user.agentModeEnabled != null ? user.agentModeEnabled : defaultAgentModeEnabled`).

```
User                 TelegramBot     MessageHandler(FSM)    TelegramMessageHandlerActions    StrategyDelegating    ReActExecutor      FSM        SpringAgentLoopActions    LLM       ToolCallingManager
 │                       │                │                         │                              │                    │            │                │                    │              │
 │ <message>             │                │                         │                              │                    │            │                │                    │              │
 │──────────────────────>│                │                         │                              │                    │            │                │                    │              │
 │                       │ TelegramCommand│                         │                              │                    │            │                │                    │              │
 │                       │───────────────>│                         │                              │                    │            │                │                    │              │
 │                       │                │  generateResponse(ctx)  │                              │                    │            │                │                    │              │
 │                       │                │───────────────────────> │                              │                    │            │                │                    │              │
 │                       │                │                         │ AgentRequest                  │                    │            │                │                    │              │
 │                       │                │                         │────────────────────────────> │                    │            │                │                    │              │
 │                       │                │                         │                              │  execute(request)   │            │                │                    │              │
 │                       │                │                         │                              │───────────────────> │            │                │                    │              │
 │                       │                │                         │                              │                    │ handle(ctx) │                │                    │              │
 │                       │                │                         │                              │                    │──────────> │                │                    │              │
 │                       │                │                         │                              │                    │            │  think(ctx)    │                    │              │
 │                       │                │                         │                              │                    │            │───────────────>│                    │              │
 │                       │                │                         │                              │                    │            │                │  chatModel.call()  │              │
 │                       │                │                         │                              │                    │            │                │───────────────────>│              │
 │                       │                │                         │                              │                    │            │                │   tool call / text │              │
 │                       │                │                         │                              │                    │            │                │<───────────────────│              │
 │                       │                │                         │                              │                    │            │  [hasToolCall] │                    │              │
 │                       │                │                         │                              │                    │            │  executeTool() │                    │              │
 │                       │                │                         │                              │                    │            │───────────────>│                    │              │
 │                       │                │                         │                              │                    │            │                │  executeToolCalls()│              │
 │                       │                │                         │                              │                    │            │                │─────────────────────────────────>│
 │                       │                │                         │                              │                    │            │                │   observation      │              │
 │                       │                │                         │                              │                    │            │                │<─────────────────────────────────│
 │                       │                │                         │                              │                    │            │  observe()     │                    │              │
 │                       │                │                         │                              │                    │            │  loop → think  │                    │              │
 │                       │                │                         │                              │                    │            │  answer()      │                    │              │
 │                       │                │                         │                              │                    │ AgentResult │                │                    │              │
 │                       │                │                         │                              │  AgentResult        │<───────────│                │                    │              │
 │                       │                │                         │                              │<───────────────────│            │                │                    │              │
 │                       │                │                         │ responseText                  │                    │            │                │                    │              │
 │                       │                │                         │<────────────────────────────│                    │            │                │                    │              │
 │                       │                │  ctx.setResponseText()  │                              │                    │            │                │                    │              │
 │                       │                │<───────────────────────│                              │                    │            │                │                    │              │
 │                       │  sendMessage   │                         │                              │                    │            │                │                    │              │
 │                       │<───────────────│                         │                              │                    │            │                │                    │              │
 │  Agent response       │                │                         │                              │                    │            │                │                    │              │
 │<──────────────────────│                │                         │                              │                    │            │                │                    │              │
```

## Memory Architecture

Long-term agent memory is provided by Spring AI's `ChatMemory` bean — specifically
the project's `SummarizingChatMemory` wrapper (wired by `SpringAIAutoConfig`). No
separate agent-level fact-extraction layer exists: a single LLM summarization pass
(triggered by the regular chat flow) already produces a rolling JSON summary and
`memory_bullets` that are persisted on `ConversationThread` and replayed on the
next turn.

```
          ┌──────────────────────────────────────┐
          │    SummarizingChatMemory (Bean)      │
          │  add(conversationId, messages)       │
          │  get(conversationId) → List<Message> │
          └──────────────────┬───────────────────┘
                             │ on recall
                             ▼
              SystemMessage  = "Conversation summary: …
                                 Key facts:
                                 - <bullet 1>
                                 - <bullet 2>"
              + prior user / assistant turns
```

**Recall**: on the first iteration `SpringAgentLoopActions.think()` calls
`chatMemory.get(conversationId)`, merges any `SystemMessage` from memory into the
active system prompt, and appends the remaining turns.

**Store**: after the final answer, `SpringAgentLoopActions.answer()` calls
`chatMemory.add(conversationId, [UserMessage, AssistantMessage])`. The summarization
pass runs as part of the normal chat pipeline — not as an extra agent step — so the
final edit of the user-visible message is not blocked by extra LLM calls.

## Orchestration (Multi-Agent Plans)

```
OrchestrationPlan
  ├── Step A: "Research topic"     (no deps)
  ├── Step B: "Analyze competitors" (no deps)
  └── Step C: "Write report"       (depends on A, B)

DefaultAgentOrchestrator
  1. Topological sort (Kahn's algorithm)
  2. Execute A → execute B → enrich C with A+B outputs → execute C
  3. If A fails → C is skipped (dependency failed)

PersistingAgentOrchestrator (decorator)
  - Saves AgentExecutionEntity + steps to DB before/after
```

## Bean Wiring (AgentAutoConfig)

Activated by `open-daimon.agent.enabled=true`.

```
ChatModel (OpenAI or Ollama)
  └──> SpringAgentLoopActions
         ├──> ToolCallingManager (Spring AI auto-discovered)
         ├──> List<ToolCallback> (auto-discovered @Tool beans)
         └──> ChatMemory (SummarizingChatMemory from SpringAIAutoConfig)

AgentLoopFsmFactory.create(actions)
  └──> ExDomainFsm<AgentContext, AgentState, AgentEvent> (singleton)
         └──> ReActAgentExecutor

SimpleChainExecutor (ChatModel)
PlanAndExecuteAgentExecutor (ChatModel, ReActAgentExecutor)

StrategyDelegatingAgentExecutor (@Primary AgentExecutor)
  ├──> ReActAgentExecutor
  ├──> SimpleChainExecutor
  └──> PlanAndExecuteAgentExecutor

DefaultAgentOrchestrator (StrategyDelegatingAgentExecutor)
  └──> PersistingAgentOrchestrator (if AgentExecutionRepository available)

HttpApiTool (opt-in: agent.tools.http-api.enabled=true)
```

## Configuration

```yaml
open-daimon:
  agent:
    enabled: true                  # feature flag
    max-iterations: 10             # safety limit per execution
    tools:
      http-api:
        enabled: false             # opt-in (SSRF protection)
```

## Key Design Decisions

1. **FSM over imperative loop** — declarative state transitions, testable guards,
   visible state machine graph. Single `START` event triggers the entire chain.

2. **Stateless FSM singleton** — thread-safe sharing. All mutable state lives on
   `AgentContext` (created per execution).

3. **Strategy pattern** — `StrategyDelegatingAgentExecutor` selects executor at runtime.
   `AUTO` mode chooses based on available tools.

4. **SPI interfaces in common module** — `AgentExecutor`, `AgentOrchestrator` have
   zero Spring AI dependency. Long-term memory is delegated to Spring AI's
   `ChatMemory` (shared with the chat flow), so no separate agent-memory SPI is
   required.

5. **Application-level activation** — agent mode is toggled via `open-daimon.agent.enabled=true`.
   `TelegramMessageHandlerActions` checks for `AgentExecutor` presence and delegates directly.

6. **Opt-in tools** — `HttpApiTool` requires explicit `enabled: true` to prevent SSRF.
   Tool filtering by `enabledTools` per request.

7. **Decorator persistence** — `PersistingAgentOrchestrator` wraps core orchestrator.
   Persistence is optional, doesn't pollute core logic.
