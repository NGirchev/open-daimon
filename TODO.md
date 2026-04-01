- [ ] add web authentication module
- [x] add web application module
- [ ] mobile app from web version
- [ ] example wizard with FSM
- [x] model selection via buttons
- [x] role selection via buttons
- [x] conversation history
- [ ] alerting
- [x] fix logs
- [x] add pulse for conversation
- [ ] Add RAG (search over old dialogs via OpenSearch/Elasticsearch/https://qdrant.tech/)
- [ ] Add automatic topic completion detection (semantic similarity)
- [ ] Integrate tiktoken for accurate token counting
- [x] MCP internet
- [x] Put more than 3 messages in history
- [x] Add icons to buttons
- [ ] Add ability to specify custom API token
- [x] Improve message grouping
- [x] Improve format conversion for Spring AI to pass temperature
- [ ] Add request pricing and budget calculation
- [ ] Rest whitelist
- [ ] Reply options as buttons
- [ ] Telegram RAG Module
- [ ] OpenCode Module (Claude)
- [ ] MCP Module
- [ ] Voice recognition
- [ ] Agent publication news module
- [ ] UI Dashboard with full administration functionality
- [ ] Image generation
- [ ] Encoding DB
- [ ] Asynchronous processing for telegram
- [ ] Show simple description for the models
- [ ] Clearing RAG + File
- [ ] Hide Buttons for models if needed

## Agent Framework Pivot

- [x] **Agent Loop** — ReAct cycle with FSM-based state management
  - `AgentExecutor` interface with `ReActAgentExecutor` (FSM: THINKING → TOOL_EXECUTING → OBSERVING → loop)
  - `SpringAgentLoopActions` using `ChatModel` with `internalToolExecutionEnabled=false`
  - Max iterations guard, error handling, streaming via `Flux<AgentStreamEvent>`
  - `AgentAutoConfig` with conditional beans (`open-daimon.agent.enabled`)

- [x] **Tool Use** — delegated to Spring AI (no custom ToolRegistry)
  - Spring AI `@Tool` + `ToolCallingManager` + `SpringBeanToolCallbackResolver` handles discovery/invocation
  - Built-in tools: `WebTools` (web_search, fetch_url), `HttpApiTool` (http_get, http_post)

- [x] **Orchestration Layer** — multi-step task execution with DAG
  - `DefaultAgentOrchestrator` with topological sort (Kahn's algorithm) and cycle detection
  - `PersistingAgentOrchestrator` decorator saves execution to DB
  - Error recovery: failed step skips dependents, independent steps continue
  - DB tables: `agent_execution`, `agent_execution_step` (Flyway V10)

- [x] **Pluggable Memory** — semantic long-term memory
  - `AgentMemory` SPI: `store(fact)`, `recall(query, topK)`, `forget(factId)`
  - `SemanticAgentMemory` — VectorStore-backed similarity search
  - `CompositeAgentMemory` — combines multiple memory sources
  - Memory integrated into `think()` — recalls relevant facts before each LLM call

- [x] **Telegram Integration** — `/agent` command
  - `AgentTelegramCommandHandler` intercepts `/agent <task>`, delegates to `AgentExecutor`
  - Registered via `TelegramCommandHandlerConfig` with `@ConditionalOnProperty`

- [ ] **opendaimon-spring-boot-starter** — auto-configuration starter for easy integration
  - New module `opendaimon-spring-boot-starter` with `AutoConfiguration.imports`
  - Minimal dependency: `opendaimon-common` + `opendaimon-spring-ai`

- [x] **FactExtractionMemory** — LLM auto-extracts key facts after agent conversations
  - `FactExtractor` calls LLM to extract facts, stores via `AgentMemory.store()`
  - Integrated into `SpringAgentLoopActions.answer()` (only for non-trivial conversations with tool use)
  - Best-effort — failures don't affect agent response

- [x] **AgentStrategy SPI** — configurable execution strategies
  - `AgentStrategy` enum: AUTO, REACT, SIMPLE, PLAN_AND_EXECUTE
  - `StrategyDelegatingAgentExecutor` — primary executor, selects strategy based on request
  - `SimpleChainExecutor` — single LLM call without tools (fast path)
  - `PlanAndExecuteAgentExecutor` — LLM generates plan, then executes each step with ReAct
  - AUTO: selects REACT if tools available, SIMPLE otherwise

- [ ] **REST Integration** — agent endpoint for REST/UI