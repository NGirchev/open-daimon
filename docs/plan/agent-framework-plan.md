# Agent Framework Pivot — Implementation Plan

> **Architecture documentation**: see [`opendaimon-common/.../agent/README.md`](../../opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/agent/README.md)
> for the full architecture guide with diagrams, sequence flows, and bean wiring.
>
> **Sequence diagram**: see [`docs/agent-sequence.puml`](../agent-sequence.puml)

## Overview

Pivot OpenDaimon from "AI chat wrapper" to "production-ready AI agent framework for Spring Boot".
The existing infrastructure (AIGateway, AIRequestPipeline, Resilience4j, VectorStore, SummarizingChatMemory)
serves as the foundation — we build the agent layer on top, not from scratch.

## Implementation Order

> Order matters. Each layer depends on the previous one.

### Phase 1: Tool Use Framework

**Why first:** Without tools, an agent has nothing to "act" on — it's just a chat loop.

**What to build:**
- `@AgentTool(name, description)` annotation on Spring beans — auto-registered in `ToolRegistry`
- `ToolRegistry` collects all tools, provides JSON Schema for LLM function calling prompt
- `ToolExecutor` handles invocation, input validation, output serialization
- Built-in tools: `WebSearchTool`, `HttpApiTool` (more added later)
- Tool results feed back into Agent Loop as observations

**Reuses:** Spring AI Function Calling, existing `AIGateway`

**Done when:** A tool can be declared with `@AgentTool`, auto-discovered, and called by the LLM via function calling.

---

### Phase 2: Agent Loop

**Why second:** ReAct loop needs ToolRegistry to have something to "act" on.

**What to build:**
- `AgentExecutor` interface with default `ReActAgentExecutor` implementation
- Each iteration: LLM decides next action (tool call or final answer), executes it, feeds observation back
- Max iterations guard to prevent infinite loops
- Configurable strategies via `AgentStrategy` SPI: ReAct, Plan-and-Execute, simple chain
- FSM-based state management (use `io.github.ngirchev:fsm` library for the agent loop state machine)

**Reuses:** `AIGateway`, `AIRequestPipeline`, `ToolRegistry` (Phase 1)

**Done when:** An agent can receive a task, autonomously call tools in a loop, and return a final answer.

---

### Phase 3: Pluggable Memory — SUPERSEDED

> **Status**: superseded. The earlier plan introduced a parallel `AgentMemory` /
> `SemanticAgentMemory` / `CompositeAgentMemory` / `FactExtractor` stack that ran
> an extra synchronous LLM call (plus per-fact embeddings) on every `answer()` —
> this added ~30 s to the final Telegram edit without providing new signal.
>
> Long-term memory is now delivered by the existing `SummarizingChatMemory`
> (wired in `SpringAIAutoConfig`). It performs a single rolling-summary LLM
> call from the chat pipeline, persists `{summary, memory_bullets}` on
> `ConversationThread`, and replays them as a `SystemMessage` on the next
> `ChatMemory.get(conversationId)`. The agent loop simply consumes this
> `ChatMemory` via `SpringAgentLoopActions` — no dedicated agent-memory SPI.

**Reuses:** `SummarizingChatMemory`, `SummarizationService`, `ConversationThread`.

**Done when:** agent recalls relevant facts from past conversations via the
shared `ChatMemory` bean, with no extra LLM call on the critical finalization
path.

---

### Phase 4: Orchestration Layer

**Why fourth:** Most complex. Needed when the agent works and multi-step workflows are required.

**What to build:**
- `AgentOrchestrator` manages a DAG of steps on top of Agent Loop
- Each step = agent call or tool call with input/output mapping
- Error recovery: retry with exponential backoff (reuse Resilience4j), fallback to alternative tool/model
- State machine per execution: PENDING -> RUNNING -> WAITING_TOOL -> COMPLETED / FAILED
- Persist execution state in DB (new `agent_execution` table) for long-running tasks

**Reuses:** `AgentExecutor` (Phase 2), `Resilience4j` bulkhead/retry, `ToolRegistry` (Phase 1)

**Done when:** A multi-step workflow (e.g., "research topic, summarize findings, draft email") executes autonomously with recovery on failure.

---

### Phase 5: Spring Boot Starter

**Why last:** Building a starter over an unstable API is wasted effort. API must stabilize first.

**What to build:**
- New module `opendaimon-spring-boot-starter` with `AutoConfiguration.imports`
- Auto-configures: `AgentExecutor`, `ToolRegistry`, `ChatMemory`, `AIGateway` chain
- Properties namespace: `open-daimon.agent.*` (strategy, max-iterations)
- Conditional beans: `@ConditionalOnProperty`, `@ConditionalOnClass` for optional modules
- Minimal dependency: `opendaimon-common` + `opendaimon-spring-ai`; Telegram/REST/UI stay optional

**Reuses:** All previous phases

**Done when:** A Java developer adds one Maven dependency + 3 properties and gets a working agent.

---

## FSM Integration (Parallel Track)

Refactor existing flows to use `io.github.ngirchev:fsm:1.0.2` library.
Can proceed in parallel with agent framework phases.

**Priority order:**
1. Document Preprocessing Pipeline (`SpringDocumentPreprocessor`) — complex branching, retry, fallback
2. AI Request Pipeline (`AIRequestPipeline`) — conditional stages, extensibility
3. Message Command Handler (`MessageTelegramCommandHandler`) — 6 exception paths, retry logic
4. Telegram Message Coalescing (`TelegramBot`) — already uses sealed-class state pattern
5. Model Selection (`DefaultAICommandFactory`) — low priority, simple enough without FSM

**Existing PlantUML diagrams:** `docs/fsm-*.puml` — use as reference for state definitions.

---

## Competitive Positioning

| Niche | Python | Java/Spring |
|-------|--------|-------------|
| LLM abstraction | LangChain | Spring AI |
| Agent framework | LangGraph, CrewAI, AutoGen | **Nothing → OpenDaimon** |
| Chat wrapper | Open WebUI, LibreChat | (not our fight) |

Target: Java/Spring Boot developers who need production-ready AI agents without pulling Python into their stack.
