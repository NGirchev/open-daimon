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

## Agent Framework Pivot

- [ ] **Agent Loop** — plan → act → observe → reflect cycle with configurable strategies
  - Create `AgentExecutor` interface with default `ReActAgentExecutor` implementation
  - Each iteration: LLM decides next action (tool call or final answer), executes it, feeds observation back
  - Max iterations guard to prevent infinite loops
  - Configurable strategies via `AgentStrategy` SPI: ReAct, Plan-and-Execute, simple chain
  - Leverage existing `AIGateway` + `AIRequestPipeline` as the LLM backbone

- [ ] **Orchestration Layer** — multi-step task execution, tool chaining, error recovery
  - Build on top of Agent Loop: `AgentOrchestrator` manages a DAG of steps
  - Each step = agent call or tool call with input/output mapping
  - Error recovery: retry with exponential backoff (reuse existing Resilience4j), fallback to alternative tool/model
  - State machine per execution: PENDING → RUNNING → WAITING_TOOL → COMPLETED / FAILED
  - Persist execution state in DB (new `agent_execution` table) for long-running tasks

- [ ] **Pluggable Memory** — semantic long-term memory, fact extraction, beyond chat history
  - Define `AgentMemory` SPI with methods: `store(fact)`, `recall(query, topK)`, `forget(factId)`
  - `ConversationMemory` — adapter over existing `SummarizingChatMemory` (already implemented)
  - `SemanticMemory` — VectorStore-backed (reuse existing Spring AI VectorStore + embedding infrastructure)
  - `FactExtractionMemory` — after each conversation, LLM extracts key facts → stores as embeddings
  - Memory is injected into Agent Loop as context before each LLM call

- [ ] **opendaimon-spring-boot-starter** — auto-configuration starter for easy integration
  - New module `opendaimon-spring-boot-starter` with `spring.factories` / `AutoConfiguration.imports`
  - Auto-configures: AgentExecutor, ToolRegistry, AgentMemory, AIGateway chain
  - Properties namespace: `open-daimon.agent.*` (strategy, max-iterations, memory-type)
  - Conditional beans: `@ConditionalOnProperty`, `@ConditionalOnClass` for optional modules
  - Minimal dependency: just `opendaimon-common` + `opendaimon-spring-ai`, Telegram/REST/UI stay optional

- [ ] **Tool Use Framework** — declarative tool definitions on top of Spring AI Function Calling
  - `@AgentTool(name, description)` annotation on Spring beans — auto-registered in `ToolRegistry`
  - `ToolRegistry` collects all tools, provides schema for LLM function calling prompt
  - `ToolExecutor` handles invocation, input validation (JSON Schema), output serialization
  - Built-in tools: `WebSearchTool`, `DatabaseQueryTool`, `HttpApiTool`, `CodeExecutionTool`
  - Tool results feed back into Agent Loop as observations