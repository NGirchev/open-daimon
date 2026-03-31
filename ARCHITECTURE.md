# Architecture

## Project Overview

**OpenDaimon** — a multi-module Java project for interacting with various AI services through different interfaces (Telegram, REST API, Web UI), with Spring AI integration (OpenRouter, Ollama) and support for custom context and templates.

### Architectural Concept

The project uses a **modular architecture**: each module can be built independently for a given client. For example:
- Build only `opendaimon-telegram` without `opendaimon-rest`
- Use only `opendaimon-rest` without `opendaimon-telegram`, or `opendaimon-ui` without `opendaimon-telegram`
- Build only `opendaimon-spring-ai` without interface modules (wiring the required entry points)
- Include `opendaimon-gateway-mock` as a provider stub for test scenarios without external APIs
- Each module has its own entities and can run autonomously

### Technology Stack

- **Java 21** (LTS)
- **Spring Boot 3.3.3** (Spring Framework 6.2.6)
- **Maven** (multi-module project)
- **PostgreSQL 17.0** with Flyway migrations
- **Resilience4j** for bulkhead pattern (request prioritization)
- **Caffeine** for caching
- **Micrometer + Prometheus + Grafana** for metrics
- **Elasticsearch + Kibana + Logstash** for logging
- **Testcontainers** for integration tests
- **Lombok** to reduce boilerplate

## Module Structure

### 1. `opendaimon-common` (Core Module)
**Purpose:** Base module with shared business logic, models, and services.

**Key components:**
- `User` — base entity with JPA Inheritance (JOINED strategy)
- `Message` — entity for storing dialog messages (combines UserRequest and ServiceResponse)
- `CommandHandler<T, C, R>` — command handling interface (Command pattern)
- `PriorityRequestExecutor` — request prioritization (ADMIN/VIP/REGULAR)
- `BulkHeadAutoConfig`, `BulkHeadProperties` — thread pool configuration
- `OpenDaimonMeterRegistry` — metrics for monitoring  
**Dependencies:** Spring Data JPA, PostgreSQL, Resilience4j, Caffeine, Micrometer

### 2. `opendaimon-telegram` (Telegram Interface Module)
**Purpose:** Module for Telegram Bot API.

**Key components:**
- `TelegramBot`
- Configuration: `TelegramAutoConfig`, `TelegramServiceConfig`, `TelegramProperties`
- Command handlers: `StartTelegramCommandHandler`, `MessageTelegramCommandHandler`, `RoleTelegramCommandHandler`, `NewThreadTelegramCommandHandler`, `HistoryTelegramCommandHandler`, `ThreadsTelegramCommandHandler`, `BugreportTelegramCommandHandler`, `LanguageTelegramCommandHandler`
- Services: `TelegramUserService`, `TelegramMessageService`, `TelegramUserSessionService`, `TelegramWhitelistService`, `TypingIndicatorService`
- Entities: `TelegramUser`, `TelegramUserSession`, `TelegramWhitelist`

**Dependencies:** `opendaimon-common`, Telegram Bots API (6.9.7.0)

**DB tables:** `telegram_user`, `telegram_user_session`, `telegram_whitelist`

### 3. `opendaimon-rest` (REST API Module)
**Purpose:** REST API module.

**Key components:**
- `RestUser extends User` — entity for REST users (field `email`)
- Controllers: `SessionController`
- Handlers: `RestChatMessageCommandHandler`, `RestChatStreamMessageCommandHandler`
- Configuration: `RestAutoConfig`, `RestFlywayConfig`, `RestJpaConfig`
- Services: `ChatService`, `RestUserService`, `RestMessageService`, `RestAuthorizationService`
- Exceptions: `RestExceptionHandler`, `UnauthorizedException`

**Dependencies:** `opendaimon-common`, Springdoc OpenAPI (Swagger)

**DB tables:** `rest_user`

### 4. `opendaimon-ui` (Web UI Module)
**Purpose:** Web interface for browser use.

**Key components:**
- `PageController`, `UIAuthController`
- `UIAutoConfig`, `UIProperties`
- Templates: `templates/login.html`, `templates/chat.html`
- Static: `static/css/chat.css`, `static/js/chat.js`

**Dependencies:** `opendaimon-rest`, Spring Boot Web, Thymeleaf

### 5. `opendaimon-spring-ai` (Spring AI Integration Module)
**Purpose:** Integration with LLM providers via Spring AI (OpenRouter, Ollama) and chat memory.

**Key components:**
- Configuration: `SpringAIAutoConfig`, `SpringAIProperties`, `SpringAIModelConfig`, `SpringAIFlywayConfig`
- Services: `SpringAIGateway`, `SpringAIChatService`, `SpringAIPromptFactory`
- OpenRouter (openrouter-auto-rotation in packages `openrouter`, `openrouter.metrics`): Properties, ApiClient, ModelEntry, ClientConfig, ModelStatsRecorder, StreamMetricsTracker, FreeModelResolver, ModelCapabilitiesMapper, FreeModelResolverScheduler; rotation: `OpenRouterModelRotationAspect`, `RotateOpenRouterModels`
- Chat memory: `SummarizingChatMemory`
- Web/logging: `RestClientLogCustomizer`, `WebClientLogCustomizer`
- Tools: `WebTools`

**Dependencies:** `opendaimon-common`, Spring AI, WebClient

### 6. `opendaimon-gateway-mock` (Gateway Mock Module)
**Purpose:** Stub for integration tests and scenarios without external API.

**Key components:**
- Provider response mocks
- DTOs for test scenarios

**Dependencies:** `opendaimon-common`

### 7. `opendaimon-app` (Application Module)
**Purpose:** Main application module that assembles all modules.

**Key components:**
- `Application` — main class with `@SpringBootApplication`
- Flyway migrations in `src/main/resources/db/migration/`
- `application.yml` — application configuration

**Dependencies:** `opendaimon-telegram`, `opendaimon-rest`, `opendaimon-ui`, `opendaimon-spring-ai`, `opendaimon-gateway-mock` (transitively pulls in all other modules)

## Database Structure

### Inheritance Hierarchy (JPA Inheritance)
```
user (base table, JOINED strategy)
├── telegram_user (telegram_id)
└── rest_user (email)

message (base table, SINGLE_TABLE strategy)
- Stores all messages (USER, ASSISTANT, SYSTEM)
- Telegram-specific data in metadata (session_id)
- REST-specific data in metadata (client_ip, user_agent, endpoint)
```

### Main Tables
- `user` — base user table (discriminator: `user_type`)
- `telegram_user`, `rest_user` — tables for each user type
- `message` — all dialog messages (replaces user_request and service_response)
- `telegram_user_session` — Telegram user sessions
- `telegram_whitelist` — bot access whitelist
- `conversation_thread` — conversation threads for grouping messages

## Architectural Patterns

### 1. Gateway Pattern (AI Provider Abstraction)
- **Interface:** `AIGateway` with `supports(AICommand)` and `generateResponse(AICommand)`
- **Registry:** `AIGatewayRegistry` discovers and selects a compatible gateway
- **Implementations:** `SpringAIGateway`, `DeepSeekGateway`, `OpenRouterGateway`

### 2. Factory Pattern (Command Creation with Priorities)
- **Interface:** `AICommandFactory<A, C>` with `priority()`, `supports()`, and `createCommand()`
- **Registry:** `AICommandFactoryRegistry` picks the factory by lowest priority value
- **Key factories:**
  - `DefaultAICommandFactory` (priority: LOWEST_PRECEDENCE) — fallback for simple commands

### 3. Command Handler Pattern
- **Interface:** `ICommandHandler<T, C, R>` with `canHandle()`, `handle()`, and `priority()`
- **Registry:** `CommandHandlerRegistry` selects handler by type compatibility and priority
- **Base commands:** `ICommand<T>` → `IChatCommand` (adds text and streaming support)

### 4. Priority-Based Request Execution (Bulkhead)
- **Service:** `PriorityRequestExecutor` uses Resilience4j bulkhead per user priority (VIP/REGULAR/BLOCKED)
- **Sync:** `CommandSyncService` manages per-user semaphores (VIP: 3, others: 2 concurrent requests)

## Dialog Processing Flow

```
User Input → CommandHandler → AICommandFactoryRegistry
    ↓
Factory Selection:
└─ DefaultAICommandFactory (simple command)
    ↓
AIGatewayRegistry selects compatible gateway
    ↓
Gateway executes request (streaming or non-streaming)
    ↓
Save USER message → Process response → Save ASSISTANT message
    ↓
Return response to user
```

## Dialog Context (Spring AI ChatMemory)

- Uses Spring AI `MessageChatMemoryAdvisor` with custom `SummarizingChatMemory`
- Messages stored in table `spring_ai_chat_memory`
- Automatic dialog tracking by conversationId

## Automatic Dialog Summarization

**Long dialog handling:**
- **Service:** `SummarizationService` (synchronously in Spring AI path)
- **Trigger:** `SummarizingChatMemory.get()` — when `spring_ai_chat_memory` message count reaches `history-window-size`
- **Process:**
  1. Filter old messages (up to threshold)
  2. Build summarization prompt with existing summary
  3. Call AI with low temperature (0.3) to produce JSON: `{summary, memory_bullets}`
  4. Update `ConversationThread` with new summary and memory bullets
  5. Track `messagesAtLastSummarization` to avoid re-summarizing the same messages
- **Integration:** On next context build, summary is injected as `SystemMessage`; `spring_ai_chat_memory` is cleared

**Error handling — intentional design:**
- If summarization AI call fails, `SummarizingChatMemory` throws `RuntimeException("Conversation summarization failed. Please start a new session (/newthread).")` — this is INTENTIONAL.
- The error surfaces to the user as a prompt to start a new session (`/newthread`).
- Do NOT silently swallow summarization failures (no `return false` fallback). The chat state after a failed summarization is inconsistent — history is not cleared, summary not written — continuing would give the model a corrupted context window.

**User notification before summarization:**
- `SummarizationStartedEvent` is published before summarization begins.
- `TelegramSummarizationListener` sends a notification message to the user so they know summarization is in progress (not a hang).

## Streaming Support

**SSE (Server-Sent Events) streaming:**
- **Command level:** `IChatCommand.stream()` boolean flag
- **Gateway level:** `SpringAIGateway` returns `SpringAIStreamResponse` wrapping `Flux<ChatResponse>`
- **REST handler:** `RestChatStreamMessageCommandHandler` converts Flux to SSE
- **Telegram handler:** `MessageTelegramCommandHandler` uses `AIUtils.processStreamingResponse()` to aggregate chunks
- **Persistence:** Final aggregated response is saved to the DB after the stream ends

## File Handling Architecture

The system supports two kinds of file attachments with different pipelines:

### Images (Multimodal API Flow)

```
User sends image via Telegram
    ↓
TelegramBot.mapToTelegramPhotoCommand()
    ↓
TelegramFileService.processPhoto()
    ├─ Download from Telegram API
    ├─ Save to MinIO (FileStorageService)
    └─ Create Attachment(type=IMAGE, data=bytes)
    ↓
TelegramCommand.attachments = [Attachment]
    ↓
AICommandFactoryRegistry.createCommand()
    ↓
DefaultAICommandFactory
    └─ Create ChatAICommand with attachments
    ↓
SpringAIGateway.generateResponse()
    └─ createUserMessage() with Media
        └─ UserMessage.builder().text().media(List<Media>).build()
    ↓
Vision-capable model (e.g., GPT-4o, Claude 3)
```

**Feature Flags:**
- `open-daimon.common.storage.enabled=true` - MinIO storage
- `open-daimon.telegram.file-upload.enabled=true` - Telegram file processing

**Key components:**
- `TelegramFileService` (opendaimon-telegram) — downloads files from Telegram API
- `MinioFileStorageService` (opendaimon-common) — stores files in MinIO
- `Attachment` record (opendaimon-common) — file metadata + byte data
- `SpringAIGateway.createUserMessage()` — converts Attachment to Spring AI Media

### PDF Documents (RAG Pipeline Flow)

```
User sends PDF via Telegram
    ↓
TelegramBot.mapToTelegramDocumentCommand()
    ↓
TelegramFileService.processDocument()
    ├─ Download from Telegram API
    ├─ Save to MinIO
    └─ Create Attachment(type=PDF, data=bytes)
    ↓
(Future integration point)
DocumentProcessingService.processPdf()
    ├─ Extract: PagePdfDocumentReader reads PDF pages
    ├─ Transform: TokenTextSplitter splits into chunks
    └─ Load: VectorStore.add() generates embeddings
    ↓
RAGService.findRelevantContext(query, documentId)
    ├─ similaritySearch with filter by documentId
    └─ Return top-K relevant chunks
    ↓
RAGService.createAugmentedPrompt()
    └─ Combine context + user query
```

**Feature Flag:**
- `open-daimon.ai.spring-ai.rag.enabled=true` — RAG pipeline (uses SimpleVectorStore in-memory)

**Key components:**
- `DocumentProcessingService` (opendaimon-spring-ai) — ETL pipeline for PDF
- `RAGService` (opendaimon-spring-ai) — similarity search and prompt augmentation
- `SimpleVectorStore` — in-memory vector store (data is lost on restart)
- `RAGProperties` — config: chunkSize, chunkOverlap, topK, similarityThreshold

**Example RAG configuration:**
```yaml
open-daimon:
  ai:
    spring-ai:
      rag:
        enabled: true
        chunk-size: 500
        chunk-overlap: 100
        top-k: 5
        similarity-threshold: 0.7
```

**Note:** SimpleVectorStore is in-memory only. For production consider PGVector or Elasticsearch.

## Monitoring

**Metrics:**
- Custom `OpenDaimonMeterRegistry` wraps Micrometer
- Prometheus endpoint: `http://localhost:8080/actuator/prometheus`
- Grafana dashboards: `http://localhost:3000` (admin/admin123456)

**Health checks:**
- `http://localhost:8080/actuator/health`

**Logs:**
- Elasticsearch + Kibana: `http://localhost:5601`

## Useful Links (After Starting the Application)

- **Swagger UI:** http://localhost:8080/swagger-ui/index.html
- **Actuator Health:** http://localhost:8080/actuator/health
- **Prometheus Metrics:** http://localhost:8080/actuator/prometheus
- **Prometheus UI:** http://localhost:9090
- **Grafana:** http://localhost:3000 (admin/admin123456)
- **Kibana:** http://localhost:5601

## Environment Variables

Required for local development (create a `.env` file or export). Docker Compose reads `.env`; for Maven (e.g. `flyway:migrate`) export these or source `.env` before running:
```bash
TELEGRAM_USERNAME=your_bot_username
TELEGRAM_TOKEN=your_telegram_bot_token
OPENROUTER_KEY=your_openrouter_api_key
DEEPSEEK_KEY=your_deepseek_api_key
# Database (same as docker-compose postgres service; required for flyway:migrate)
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=opendaimon
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_secure_password
```

## Adding a New AI Provider

1. Create a new module `opendaimon-<provider>`
2. Add dependency on `opendaimon-common`
3. Implement `AIGateway`
4. Create `@AutoConfiguration` with `@ConditionalOnProperty`
5. Add a Properties class for API keys/endpoints
6. Register in `opendaimon-app/pom.xml`
7. Update `application.yml` with configuration

## Adding a New Interface

1. Create a new module `opendaimon-<interface>`
2. Implement command handlers extending `ICommandHandler`
3. Create command types implementing `ICommand` or `IChatCommand`
4. Create `@AutoConfiguration`
5. Add properties for interface-specific configuration
6. Register in `opendaimon-app/pom.xml`
