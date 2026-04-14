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
- [ ] Add Memory RAG (search over old dialogs via OpenSearch/Elasticsearch/https://qdrant.tech/)
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
- [ ] Ability to read telegram chat history if needed
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
- [ ] FSM pipeline resilience
  - Make `extractText` and `runVisionOcr` idempotent (check VectorStore for existing chunks before writing)
  - Persist FSM intermediate states to DB for crash recovery and retry
  - Eliminate response loss window between AI call completion and DB save

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

- [x] **Telegram Integration** — agent mode via application property
  - `TelegramMessageHandlerActions` delegates to `AgentExecutor` when `open-daimon.agent.enabled=true`
  - Agent mode is transparent — no `/agent` command, all messages go through agent pipeline

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

- [ ] Bug 2026-04-11 10:56:21.190 [opendaimon_bot Telegram Connection] ERROR o.t.t.u.DefaultBotSession - api.telegram.org
  2026-04-11T10:56:21.190938830Z java.net.UnknownHostException: api.telegram.org
  2026-04-11T10:56:21.190941994Z 	at java.base/java.net.InetAddress$CachedLookup.get(Unknown Source)...
- [ ] Bug for summarizing in group chat 2026-04-11 07:20:05.388 [boundedElastic-20] ERROR i.g.n.o.a.s.s.SpringAIChatService - Spring AI stream error. model=openrouter/auto, body={reasoning={max_tokens=1500}}
  2026-04-11T07:20:05.389665794Z io.github.ngirchev.opendaimon.common.exception.SummarizationFailedException: Conversation summarization failed. Please start a new session (/newthread).
  2026-04-11T07:20:05.389668410Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.performSummarizationAndUpdateChatMemory(SummarizingChatMemory.java:189)
  2026-04-11T07:20:05.389670903Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.get(SummarizingChatMemory.java:93)
  2026-04-11T07:20:05.389673235Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.before(MessageChatMemoryAdvisor.java:83)
  2026-04-11T07:20:05.389675561Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.lambda$adviseStream$1(MessageChatMemoryAdvisor.java:125)
  2026-04-11T07:20:05.389677906Z 	at reactor.core.publisher.FluxMap$MapSubscriber.onNext(FluxMap.java:106)
  2026-04-11T07:20:05.389680160Z 	at reactor.core.publisher.FluxSubscribeOnValue$ScheduledScalar.run(FluxSubscribeOnValue.java:181)
  2026-04-11T07:20:05.389682487Z 	at reactor.core.scheduler.SchedulerTask.call(SchedulerTask.java:68)
  2026-04-11T07:20:05.389684744Z 	at reactor.core.scheduler.SchedulerTask.call(SchedulerTask.java:28)
  2026-04-11T07:20:05.389686976Z 	at java.base/java.util.concurrent.FutureTask.run(Unknown Source)
  2026-04-11T07:20:05.389689272Z 	at java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(Unknown Source)
  2026-04-11T07:20:05.389691598Z 	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(Unknown Source)
  2026-04-11T07:20:05.389693884Z 	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(Unknown Source)
  2026-04-11T07:20:05.389696169Z 	at java.base/java.lang.Thread.run(Unknown Source)
  2026-04-11T07:20:05.389698518Z Caused by: java.lang.RuntimeException: Summarization failed
  2026-04-11T07:20:05.389700852Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.summarizeThread(SummarizationService.java:77)
  2026-04-11T07:20:05.389703190Z 	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)
  2026-04-11T07:20:05.389705472Z 	at java.base/java.lang.reflect.Method.invoke(Unknown Source)
  2026-04-11T07:20:05.389707744Z 	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:359)
  2026-04-11T07:20:05.389710074Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:196)
  2026-04-11T07:20:05.389718882Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:163)
  2026-04-11T07:20:05.389721711Z 	at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:380)
  2026-04-11T07:20:05.389726935Z 	at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119)
  2026-04-11T07:20:05.389729358Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184)
  2026-04-11T07:20:05.389731669Z 	at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:728)
  2026-04-11T07:20:05.389734051Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService$$SpringCGLIB$$0.summarizeThread(<generated>)
  2026-04-11T07:20:05.389736559Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.performSummarizationAndUpdateChatMemory(SummarizingChatMemory.java:164)
  2026-04-11T07:20:05.389738956Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.get(SummarizingChatMemory.java:93)
  2026-04-11T07:20:05.389741259Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.before(MessageChatMemoryAdvisor.java:83)
  2026-04-11T07:20:05.389743612Z 	at org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.lambda$adviseStream$1(MessageChatMemoryAdvisor.java:125)
  2026-04-11T07:20:05.389745977Z Caused by: java.lang.RuntimeException: Failed to generate response from Spring AI
  2026-04-11T07:20:05.389748461Z 	at io.github.ngirchev.opendaimon.ai.springai.service.SpringAIGateway.generateResponse(SpringAIGateway.java:126)
  2026-04-11T07:20:05.389750852Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.callAiAndParseSummaryResult(SummarizationService.java:138)
  2026-04-11T07:20:05.389753191Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.performSummarization(SummarizationService.java:95)
  2026-04-11T07:20:05.389755683Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService.summarizeThread(SummarizationService.java:72)
  2026-04-11T07:20:05.389758031Z 	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)
  2026-04-11T07:20:05.389760394Z 	at java.base/java.lang.reflect.Method.invoke(Unknown Source)
  2026-04-11T07:20:05.389762655Z 	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:359)
  2026-04-11T07:20:05.389765704Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:196)
  2026-04-11T07:20:05.389768047Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:163)
  2026-04-11T07:20:05.389770400Z 	at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:380)
  2026-04-11T07:20:05.389772893Z 	at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119)
  2026-04-11T07:20:05.389778481Z 	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184)
  2026-04-11T07:20:05.389780953Z 	at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:728)
  2026-04-11T07:20:05.389783274Z 	at io.github.ngirchev.opendaimon.common.service.SummarizationService$$SpringCGLIB$$0.summarizeThread(<generated>)
  2026-04-11T07:20:05.389785837Z 	at io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory.performSummarizationAndUpdateChatMemory(SummarizingChatMemory.java:164)
  2026-04-11T07:20:05.389788452Z Caused by: org.springframework.ai.retry.NonTransientAiException: HTTP 400 - {"error":"model is required"}
  2026-04-11T07:20:05.389791003Z 	at org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration$2.handleError(SpringAiRetryAutoConfiguration.java:126)
  2026-04-11T07:20:05.389793487Z 	at org.springframework.web.client.ResponseErrorHandler.handleError(ResponseErrorHandler.java:58)
  2026-04-11T07:20:05.389795774Z 	at org.springframework.web.client.StatusHandler.lambda$fromErrorHandler$1(StatusHandler.java:71)
  2026-04-11T07:20:05.389798093Z 	at org.springframework.web.client.StatusHandler.handle(StatusHandler.java:146)
  2026-04-11T07:20:05.389800339Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.applyStatusHandlers(DefaultRestClient.java:831)
  2026-04-11T07:20:05.389802653Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.lambda$readBody$4(DefaultRestClient.java:820)
  2026-04-11T07:20:05.389804962Z 	at org.springframework.web.client.DefaultRestClient.readWithMessageConverters(DefaultRestClient.java:216)
  2026-04-11T07:20:05.389807267Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.readBody(DefaultRestClient.java:819)
  2026-04-11T07:20:05.389822105Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.lambda$body$0(DefaultRestClient.java:750)
  2026-04-11T07:20:05.389824588Z 	at org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec.exchangeInternal(DefaultRestClient.java:579)
  2026-04-11T07:20:05.389826868Z 	at org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec.exchange(DefaultRestClient.java:533)
  2026-04-11T07:20:05.389829162Z 	at org.springframework.web.client.RestClient$RequestHeadersSpec.exchange(RestClient.java:680)
  2026-04-11T07:20:05.389844438Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.executeAndExtract(DefaultRestClient.java:814)
  2026-04-11T07:20:05.389846893Z 	at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.body(DefaultRestClient.java:750)
  2026-04-11T07:20:05.389849206Z 	at org.springframework.ai.ollama.api.OllamaApi.chat(OllamaApi.java:115) - Also message was sent to personal chat instead of group
- [ ] Bug: WebTools.fetchUrl DataBufferLimitException → model responds in English (2026-04-11)
  - `WebClient` default buffer limit is 256KB (262144 bytes); large pages (e.g. GitHub issues) exceed it
  - `fetchUrl` catches the exception and returns empty string `""`
  - Model receives empty tool result, generates a fallback response ignoring the language instruction
  - Root cause: `SpringAIAutoConfig.webClient()` creates WebClient via `builder.build()` without `maxInMemorySize`
  - Observed: `google/gemini-2.5-flash-lite` via `openrouter/auto` responded in English despite `languageCode=ru`
  - Fix 1: Set `maxInMemorySize` in `SpringAIAutoConfig.webClient()` (e.g. 2MB)
  - Fix 2: Investigate why language instruction (`"Prefer responding in Russian"`) is lost after tool call failure — check if system message is preserved in the retry/fallback path
  - Log: `WebTools.fetchUrl failed for url=[https://github.com/anthropics/claude-code/issues/42796]: DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144`
- [ ] Cancel button for model selection + grouping
- [ ] Bug - custom role for group chat is not working
- [ ] Show thinking + smooth text display in telegram
- [ ] Show thinking in web