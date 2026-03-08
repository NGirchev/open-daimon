# AGENTS.md

## Agent role
Act as a senior Java developer who follows the project style consistently — a multi-module Java project with Spring Boot starters. Use solutions that fit the existing structure rather than the most obvious or popular ones. Always check existing code to match the same style.

## User profile
Java tech lead, experienced, intolerant of sloppy work. Requires tests and verification of hypotheses — code is not accepted without them. Significant changes must be agreed. Listen to the user and do what they ask; if you disagree, argue with reasoning.

## Rules for AI agents

### Language in code and documentation
- **Code, comments, javadoc, commit messages, and in-repo documentation** (AGENTS.md, READMEs in packages) must be written in **English**.
- User-facing strings (i18n in `.properties`, bot messages) may be in any language.
- Exception and log messages in code must be in English. See also [.cursor/rules/english-in-code.mdc](.cursor/rules/english-in-code.mdc).

### When creating new services and components
1. **Do NOT use `@Service`, `@Component`, `@Repository`** for automatic bean scanning
2. **Create beans explicitly** in configuration classes via `@Bean` methods
3. **Configuration classes** live in the `config` package of each module
4. **Example**:
   ```java
   // ❌ WRONG:
   @Service
   public class MyService { ... }
   
   // ✅ CORRECT:
   public class MyService { ... }  // No annotations
   
   @Configuration
   public class MyModuleConfig {
       @Bean
       @ConditionalOnMissingBean
       public MyService myService(...) {
           return new MyService(...);
       }
   }
   ```
5. **Exception:** `@Repository` on JPA repository interfaces is allowed (interfaces, not classes)

### When creating new modules
1. **Create pom.xml** with the correct dependency structure (see Code Style)
2. **Add the module** to parent pom.xml in the `<modules>` section
3. **Package structure:** `io.github.ngirchev.aibot.<module-name>.<layer>`
4. **If entities are needed:** extend `User` or `Message` from `aibot-common`
5. **Create a Flyway migration** in `aibot-app/src/main/resources/db/migration/`
6. **Create a configuration class** for all beans of the module (e.g. `MyModuleConfig`)

### When working with entities
1. **Do not duplicate entities** across modules — use inheritance
2. **Base entities** only in `aibot-common`
3. **Module-specific fields** in subclasses (e.g. `telegram_id` in `TelegramUser`)
4. **Use JPA Inheritance JOINED** for User
5. **Use JPA Inheritance SINGLE_TABLE** for Message (all messages in one table, specific data in metadata JSONB)
6. **Discriminator** is required for polymorphic queries

### When adding new AI providers
1. **Create a new module** `ai-<provider-name>` (e.g. `ai-anthropic`)
2. **Create a Service** with `generateResponse(String prompt, ...)`
3. **Create Properties** for configuration (API key, URL)
4. **Add the dependency** to modules that will use the provider
5. **Do not add entities** — providers are stateless

### When working with the database
1. **All migrations** in `aibot-app/src/main/resources/db/migration/`
2. **Naming:** `V<number>__<description>.sql` (e.g. `V1__Create_initial_tables.sql`)
3. **Indexes are required** for foreign keys and frequently queried fields
4. **Use `IF NOT EXISTS`** for idempotency
5. **Timestamps:** `TIMESTAMP WITH TIME ZONE` (not `TIMESTAMP`)

### When adding metrics
1. **Use `AiBotMeterRegistry`** from `aibot-common`
2. **Metric format:** `<module>.<action>.<metric>` (e.g. `rest.request.processing.time`)
3. **Types:** Counter, Timer, Gauge
4. **Add description** in the Grafana dashboard

### When working with prioritization
1. **Use `PriorityRequestExecutor`** for all AI requests
2. **Do not call AI services directly** — only via the executor
3. **Priorities:** ADMIN (10 threads), VIP (5 threads), REGULAR (1 thread)
4. **Whitelist** is managed via `WhitelistService`

### Security
1. **API keys** ONLY in environment variables
2. **Do not commit** `application.yml` with real keys
3. **Use `@PreAuthorize`** to protect REST endpoints (if you add Spring Security)
4. **Validate input** with Jakarta Validation (`@Valid`, `@NotNull`, etc.)

### Testing
1. **Unit tests** for services (Mockito)
2. **Integration tests** for repositories (Testcontainers)
3. **Coverage** at least 70% for critical business logic
4. **Do not mock entities** — use real objects
5. **Use `@DataJpaTest`** for repository tests

## Project Overview

**AI Bot Router** — a multi-module Java project for interacting with various AI services through different interfaces (Telegram, REST API, Web UI), with Spring AI integration (OpenRouter, Ollama) and support for custom context and templates.

### Architectural concept

The project uses a **modular architecture**: each module can be built independently for a given client. For example:
- Build only `aibot-telegram` without `aibot-rest`
- Use only `aibot-rest` without `aibot-telegram`, or `aibot-ui` without `aibot-telegram`
- Build only `aibot-spring-ai` without interface modules (wiring the required entry points)
- Include `aibot-gateway-mock` as a provider stub for test scenarios without external APIs
- Each module has its own entities and can run autonomously

### Technology stack

- **Java 21** (LTS)
- **Spring Boot 3.3.3** (Spring Framework 6.2.6)
- **Maven** (multi-module project)
- **PostgreSQL 17.0** with Flyway migrations
- **Resilience4j** for bulkhead pattern (request prioritization)
- **Caffeine** for caching
- **Micrometer + Prometheus + Grafana** for metrics
- **Elasticsearch + Kibana + Metricbeat** for logging
- **Testcontainers** for integration tests
- **Lombok** to reduce boilerplate

## Module structure

### 1. `aibot-common` (Core Module)
**Purpose:** Base module with shared business logic, models, and services.

**Key components:**
- `User` — base entity with JPA Inheritance (JOINED strategy)
- `Message` — entity for storing dialog messages (combines UserRequest and ServiceResponse)
- `CommandHandler<T, C, R>` — command handling interface (Command pattern)
- `PriorityRequestExecutor` — request prioritization (ADMIN/VIP/REGULAR)
- `BulkHeadAutoConfig`, `BulkHeadProperties` — thread pool configuration
- `AIBotMeterRegistry` — metrics for monitoring  
**Dependencies:** Spring Data JPA, PostgreSQL, Resilience4j, Caffeine, Micrometer

### 2. `aibot-telegram` (Telegram Interface Module)
**Purpose:** Module for Telegram Bot API.

**Key components:**
- `TelegramBot`
- Configuration: `TelegramAutoConfig`, `TelegramServiceConfig`, `TelegramProperties`
- Command handlers: `StartTelegramCommandHandler`, `MessageTelegramCommandHandler`, `RoleTelegramCommandHandler`, `NewThreadTelegramCommandHandler`, `HistoryTelegramCommandHandler`, `ThreadsTelegramCommandHandler`, `BugreportTelegramCommandHandler`, `LanguageTelegramCommandHandler`
- Services: `TelegramUserService`, `TelegramMessageService`, `TelegramUserSessionService`, `TelegramWhitelistService`, `TypingIndicatorService`
- Entities: `TelegramUser`, `TelegramUserSession`, `TelegramWhitelist`

**Dependencies:** `aibot-common`, Telegram Bots API (6.9.7.0)

**DB tables:** `telegram_user`, `telegram_user_session`, `telegram_whitelist`

### 3. `aibot-rest` (REST API Module)
**Purpose:** REST API module.

**Key components:**
- `RestUser extends User` — entity for REST users (field `email`)
- Controllers: `SessionController`
- Handlers: `RestChatMessageCommandHandler`, `RestChatStreamMessageCommandHandler`
- Configuration: `RestAutoConfig`, `RestFlywayConfig`, `RestJpaConfig`
- Services: `ChatService`, `RestUserService`, `RestMessageService`, `RestAuthorizationService`
- Exceptions: `RestExceptionHandler`, `UnauthorizedException`

**Dependencies:** `aibot-common`, Springdoc OpenAPI (Swagger)

**DB tables:** `rest_user`

### 4. `aibot-ui` (Web UI Module)
**Purpose:** Web interface for browser use.

**Key components:**
- `PageController`, `UIAuthController`
- `UIAutoConfig`, `UIProperties`
- Templates: `templates/login.html`, `templates/chat.html`
- Static: `static/css/chat.css`, `static/js/chat.js`

**Dependencies:** `aibot-rest`, Spring Boot Web, Thymeleaf

### 5. `aibot-spring-ai` (Spring AI Integration Module)
**Purpose:** Integration with LLM providers via Spring AI (OpenRouter, Ollama) and chat memory.

**Key components:**
- Configuration: `SpringAIAutoConfig`, `SpringAIProperties`, `SpringAIModelConfig`, `SpringAIFlywayConfig`
- Services: `SpringAIGateway`, `SpringAIChatService`, `SpringAIPromptFactory`
- OpenRouter (openrouter-auto-rotation in packages `openrouter`, `openrouter.metrics`): Properties, ApiClient, ModelEntry, ClientConfig, ModelStatsRecorder, StreamMetricsTracker, FreeModelResolver, ModelCapabilitiesMapper, FreeModelResolverScheduler; rotation: `OpenRouterModelRotationAspect`, `RotateOpenRouterModels`
- Chat memory: `SummarizingChatMemory`
- Web/logging: `RestClientLogCustomizer`, `WebClientLogCustomizer`
- Tools: `WebTools`

**Dependencies:** `aibot-common`, Spring AI, WebClient

### 6. `aibot-gateway-mock` (Gateway Mock Module)
**Purpose:** Stub for integration tests and scenarios without external API.

**Key components:**
- Provider response mocks
- DTOs for test scenarios

**Dependencies:** `aibot-common`

### 7. `aibot-app` (Application Module)
**Purpose:** Main application module that assembles all modules.

**Key components:**
- `Application` — main class with `@SpringBootApplication`
- Flyway migrations in `src/main/resources/db/migration/`
- `application.yml` — application configuration

**Dependencies:** `aibot-telegram`, `aibot-rest`, `aibot-ui`, `aibot-spring-ai`, `aibot-gateway-mock` (transitively pulls in all other modules)

## Database structure

### Inheritance hierarchy (JPA Inheritance)
```
user (base table, JOINED strategy)
├── telegram_user (telegram_id)
└── rest_user (email)

message (base table, SINGLE_TABLE strategy)
- Stores all messages (USER, ASSISTANT, SYSTEM)
- Telegram-specific data in metadata (session_id)
- REST-specific data in metadata (client_ip, user_agent, endpoint)
```

### Main tables
- `user` — base user table (discriminator: `user_type`)
- `telegram_user`, `rest_user` — tables for each user type
- `message` — all dialog messages (replaces user_request and service_response)
- `telegram_user_session` — Telegram user sessions
- `telegram_whitelist` — bot access whitelist
- `conversation_thread` — conversation threads for grouping messages

## Code style and conventions

### Dependency order in pom.xml
**IMPORTANT:** Follow this order in EVERY pom.xml (see comments in files):
1. Project-specific modules (groupId: `io.github.ngirchev`)
2. Spring dependencies (groupId: `org.springframework`)
3. Database dependencies (jdbc, jpa, postgres, h2)
4. Other utilities and libraries (logging, json, etc.)
5. Test-related dependencies (scope: `test`)

**All versions MUST be in `<properties>`!**

### Java code style
- **Java 21** with modern features
- **Lombok** to reduce boilerplate (`@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Slf4j`)
- **Functional patterns** where possible (Vavr is used)
- **Package structure:** `io.github.ngirchev.aibot.<module>.<layer>` (e.g. `io.github.ngirchev.aibot.telegram.service`)

### Entity guidelines
- Base entities in `aibot-common` (`User`, `Message`)
- Module-specific entities in modules (`TelegramUser`, `RestUser`)
- **JPA Inheritance JOINED** for User
- **JPA Inheritance SINGLE_TABLE** for Message (all messages in one table)
- `@PrePersist` and `@PreUpdate` for automatic timestamps
- Discriminator column: `user_type` (values: `TELEGRAM`, `REST`) for User
- Discriminator column: `message_type` for Message (default `MESSAGE`)

### Service layer
- Interfaces for services (e.g. `UserService`, `UserPriorityService`)
- Implementations with `Impl` suffix (e.g. `UserPriorityServiceImpl`)
- `@RequiredArgsConstructor` for dependency injection
- `@Slf4j` for logging

### Spring bean configuration
**IMPORTANT:** This project does NOT use `@Service`, `@Component`, `@Repository` for automatic bean scanning!
- **All beans are created explicitly** in configuration classes via `@Bean` methods
- **Configuration classes** live in the `config` package of each module (e.g. `TelegramServiceConfig`, `CoreAutoConfig`)
- **Benefits:** explicit control over bean creation, conditional config via `@ConditionalOnProperty`, better testability
- **Example:** instead of `@Service` on a class, add a `@Bean` method in the corresponding `*Config` class

#### ObjectProvider example:

```java
// ✅ CORRECT: ObjectProvider for optional/lazy beans
@Bean
@ConditionalOnMissingBean
public MessageTelegramCommandHandler messageTelegramCommandHandler(
        ObjectProvider<TelegramBot> telegramBotProvider,  // Optional bean
        PriorityRequestExecutor priorityRequestExecutor,
        // ... other dependencies
) {
    return new MessageTelegramCommandHandler(telegramBotProvider, priorityRequestExecutor, ...);
}

// In handler class:
public class MessageTelegramCommandHandler {
    private final ObjectProvider<TelegramBot> telegramBotProvider;
    
    public void sendMessage(Long chatId, String text) {
        // Bean is obtained only when needed
        telegramBotProvider.getObject().sendMessage(chatId, text);
    }
}
```

**When to use ObjectProvider:**
- When the bean may be absent (optional)
- When lazy loading is needed (obtain bean only on use)
- To avoid circular dependencies
- When the bean is created conditionally (`@ConditionalOnProperty`)

**When to use @Lazy:**
- When the bean must always exist but initialization should be lazy
- To break a circular dependency at bean creation time

### Command pattern
- Interface `CommandHandler<T extends CommandType, C extends Command<T>, R>`
- Each module has its own implementation (e.g. `TelegramCommandHandler`)
- Registry for handlers (`AiBotCommandHandlerRegistry`)

### Metrics and monitoring
- Use `AiBotMeterRegistry` to register metrics
- Metric format: `<module>.<action>.<metric>` (e.g. `telegram.message.processing.time`)
- All metrics are exported to Prometheus

## Configuration

- Configuration namespace is `ai-bot.*` (modules `telegram`, `rest`, `ui`, `ai.spring-ai`); feature toggles use `*.enabled`.
- Config keys and comments live in `aibot-app/src/main/resources/application.yml`.

**Module auto-configuration:**  
Each module provides an `@AutoConfiguration` class with conditional bean registration:
- `CoreAutoConfig` (aibot-common) — core services, registries
- `TelegramAutoConfig` — enabled via `ai-bot.telegram.enabled=true`
- `RestAutoConfig` — enabled via `ai-bot.rest.enabled=true`
- `SpringAIAutoConfig` — enabled via `ai-bot.ai.spring-ai.enabled=true`

**Properties hierarchy:**
```yaml
ai-bot:
  common:
    summarization:
      max-context-tokens: 8000
      summary-trigger-threshold: 0.7
      keep-recent-messages: 20
    manual-conversation-history:  # Common-managed history (manual context)
      enabled: false  # false = Spring AI ChatMemory
      max-response-tokens: 4000
      default-window-size: 20
      include-system-prompt: true
      token-estimation-chars-per-token: 4
    bulkhead:
      enabled: true
  telegram:
    enabled: true
  rest:
    enabled: true
  ai:
    spring-ai:
      enabled: true
```

**IMPORTANT:** For `@ConfigurationProperties` classes:
- All values are required (must be set in `application.yml`)
- Do NOT set default values in code — only in configuration
- Use validation: `@Validated` with `@NotNull`, `@Min`, `@Max`
- Use wrapper types (`Integer`, `Double`, `Boolean`) for `@NotNull`

Example:
```java
@ConfigurationProperties(prefix = "ai-bot.context")
@Validated
@Getter
@Setter
public class ContextProperties {
    @NotNull(message = "maxContextTokens is required")
    @Min(value = 1000, message = "maxContextTokens must be >= 1000")
    private Integer maxContextTokens; // No default in code!
}
```

## Build and test commands

### Build
```bash
# Full build with tests
mvn clean install

# Build without tests (e.g. for Docker)
mvn clean package -DskipTests

# Build a single module
mvn clean install -pl aibot-common
```

### Run application
```bash
# Local development (requires infrastructure)
docker-compose up -d postgres prometheus grafana
mvn spring-boot:run -pl aibot-app

# Full Docker deployment
mvn clean package -DskipTests
docker-compose up -d
```

### Testing
```bash
# All tests
mvn test

# Single test class
mvn test -Dtest=MessageTelegramCommandHandlerIntegrationTest -pl aibot-app

# Single test method
mvn test "-Dtest=TelegramUserRepositoryTest#whenSaveUser_thenUserIsSaved" -pl aibot-app

# Tests for one module
mvn test -pl aibot-telegram

# SpringAIGatewayIT (streaming, no Ollama)
mvn test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
```

**Running tests on Windows:**
- For `mvnw.cmd`, **JAVA_HOME** must point to JDK 21. If not set globally, JDK is often at `C:\Users\<user>\.jdks\corretto-21.0.10` (IDEA) or copy path from File → Project Structure → SDKs.
- In **PowerShell** from project root (single line):
  ```powershell
  $env:JAVA_HOME = "C:\Users\<user>\.jdks\corretto-21.0.10"; cd c:\path\to\ai-bot; .\mvnw.cmd test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
  ```
  (replace `<user>` and path with your JDK and project location).
- If running only module tests, build dependencies first: `.\mvnw.cmd install -DskipTests`, then the `test` command above.
- From **IntelliJ IDEA:** right-click class `SpringAIGatewayIT` → Run 'SpringAIGatewayIT' (JAVA_HOME not needed; IDEA uses its own JDK).

### DB migrations
```bash
# Run Flyway migrations manually
mvn flyway:migrate -pl aibot-common

# Flyway migration info
mvn flyway:info -pl aibot-common
```

**Modular Flyway strategy:**
- Each module has migration path: `src/main/resources/db/migration/<module>/`
- Paths: `core/`, `telegram/`, `rest/`, `springai/`
- Each module's `FlywayConfig` registers its locations
- Migrations run in order across all modules

**Adding a new migration:**
1. Create file in the module path: `V<number>__Description.sql`
2. Use naming like `V1__Create_base_tables.sql`, `V2__Add_user_fields.sql`
3. Run `mvn flyway:migrate -pl aibot-common` to apply

### Line endings (Linux, Mac, Windows)

The repo uses **LF only** for text files (`.gitattributes`). To avoid spurious "modified" files when switching between machines:

- **Linux / Mac:** use default Git behaviour (`core.autocrlf` unset or `false`). No extra config needed.
- **Windows:** set `git config core.autocrlf input` so Git converts CRLF→LF on commit and does not touch files on checkout; then working tree stays LF and matches the repo.
- **One-time renormalization** (if many files show as changed only by line endings): run from repo root:
  ```bash
  git add --renormalize .
  git status   # review, then commit
  git commit -m "Normalize line endings to LF"
  ```
  After that, all tracked files are stored with LF and `git status` stays clean across Linux/Mac/Windows.

## Architectural patterns

### 1. Gateway pattern (AI provider abstraction)
- **Interface:** `AIGateway` with `supports(AICommand)` and `generateResponse(AICommand)`
- **Registry:** `AIGatewayRegistry` discovers and selects a compatible gateway
- **Implementations:** `SpringAIGateway`, `DeepSeekGateway`, `OpenRouterGateway`

### 2. Factory pattern (command creation with priorities)
- **Interface:** `AICommandFactory<A, C>` with `priority()`, `supports()`, and `createCommand()`
- **Registry:** `AICommandFactoryRegistry` picks the factory by lowest priority value
- **Key factories:**
  - `ConversationHistoryAICommandFactory` (priority: 0) — builds commands with dialog history when metadata has `threadKey`
  - `DefaultAICommandFactory` (priority: LOWEST_PRECEDENCE) — fallback for simple commands

### 3. Command handler pattern
- **Interface:** `ICommandHandler<T, C, R>` with `canHandle()`, `handle()`, and `priority()`
- **Registry:** `CommandHandlerRegistry` selects handler by type compatibility and priority
- **Base commands:** `ICommand<T>` → `IChatCommand` (adds text and streaming support)

### 4. Priority-based request execution (Bulkhead)
- **Service:** `PriorityRequestExecutor` uses Resilience4j bulkhead per user priority (VIP/REGULAR/BLOCKED)
- **Sync:** `CommandSyncService` manages per-user semaphores (VIP: 3, others: 2 concurrent requests)

## Dialog processing flow

```
User Input → CommandHandler → AICommandFactoryRegistry
    ↓
Factory Selection:
├─ threadKey in metadata? → ConversationHistoryAICommandFactory
│   ├─ Load ConversationThread from DB
│   ├─ Check if summarization needed (threshold: 70% of max tokens)
│   ├─ ConversationContextBuilderService builds context:
│   │   ├─ Load recent messages (window size)
│   │   ├─ Include summary/memory bullets if exists
│   │   └─ Estimate tokens and manage budget
│   └─ Create ChatAICommand with message history
│
└─ No threadKey? → DefaultAICommandFactory (simple command)
    ↓
AIGatewayRegistry selects compatible gateway
    ↓
Gateway executes request (streaming or non-streaming)
    ↓
Save USER message → Process response → Save ASSISTANT message
    ↓
Return response to user
```

## Dialog context modes

The system has two mutually exclusive modes controlled by `ai-bot.common.manual-conversation-history.enabled`:

**Mode 1: Manual context building (`enabled: true`)**
- `ConversationHistoryAICommandFactory` builds context manually
- `ConversationContextBuilderService` loads messages from table `aibot_message`
- Full control over token budget, window size, and summary integration
- Use for custom context management logic

**Mode 2: Spring AI ChatMemory (`enabled: false`)**
- Uses Spring AI `MessageChatMemoryAdvisor` with custom `SummarizingChatMemory`
- Messages stored in table `spring_ai_chat_memory`
- Automatic dialog tracking by conversationId
- Use for framework integration with Spring AI

**Default:** The default for the application is **Spring AI ChatMemory** (`ai-bot.common.manual-conversation-history.enabled=false`). Manual mode (`enabled: true`) is for experiments and custom scenarios, not the primary configuration.

## Automatic dialog summarization

**Long dialog handling:**
- **Service:** `SummarizationService` (runs asynchronously)
- **Trigger:** When `totalTokens >= summarization.maxContextTokens * summarization.summaryTriggerThreshold` (e.g. 70%)
- **Process:**
  1. Filter old messages (up to threshold)
  2. Build summarization prompt with existing summary
  3. Call AI with low temperature (0.3) to produce JSON: `{summary, memory_bullets}`
  4. Update `ConversationThread` with new summary and memory bullets
  5. Track `messagesAtLastSummarization` to avoid re-summarizing the same messages
- **Integration:** On next context build, summary is used instead of old messages, keeping token count under control

## Streaming support

**SSE (Server-Sent Events) streaming:**
- **Command level:** `IChatCommand.stream()` boolean flag
- **Gateway level:** `SpringAIGateway` returns `SpringAIStreamResponse` wrapping `Flux<ChatResponse>`
- **REST handler:** `RestChatStreamMessageCommandHandler` converts Flux to SSE
- **Telegram handler:** `MessageTelegramCommandHandler` uses `AIUtils.processStreamingResponse()` to aggregate chunks
- **Persistence:** Final aggregated response is saved to the DB after the stream ends

## File handling architecture

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
ConversationHistoryAICommandFactory / DefaultAICommandFactory
    └─ Create ChatAICommand with attachments
    ↓
SpringAIGateway.generateResponse()
    └─ createUserMessage() with Media
        └─ UserMessage.builder().text().media(List<Media>).build()
    ↓
Vision-capable model (e.g., GPT-4o, Claude 3)
```

**Feature Flags:**
- `ai-bot.common.storage.enabled=true` - MinIO storage
- `ai-bot.telegram.file-upload.enabled=true` - Telegram file processing

**Key components:**
- `TelegramFileService` (aibot-telegram) — downloads files from Telegram API
- `MinioFileStorageService` (aibot-common) — stores files in MinIO
- `Attachment` record (aibot-common) — file metadata + byte data
- `SpringAIGateway.createUserMessage()` — converts Attachment to Spring AI Media

**Storing attachment references in history (manual-conversation-history mode):**
- Table `message`, column `attachments` (JSONB): array of `{ storageKey, expiresAt, mimeType, filename }`. Reference and expiry (TTL from `ai-bot.common.storage.minio.ttl-hours`) are saved when saving the USER message (`TelegramMessageService.saveUserMessage` with attachments).
- When building context (`ConversationContextBuilderService.buildContext`), for USER messages with non-empty `attachments` and non-expired `expiresAt`, images (mimeType image/*) are loaded from MinIO by `storageKey` and added as content parts (text + image_url with data:base64). If the file is expired or storage is disabled, only text is added. Attachments are available within the window until summarization and until TTL expires.

### PDF documents (RAG Pipeline Flow)

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
- `ai-bot.ai.spring-ai.rag.enabled=true` — RAG pipeline (uses SimpleVectorStore in-memory)

**Key components:**
- `DocumentProcessingService` (aibot-spring-ai) — ETL pipeline for PDF
- `RAGService` (aibot-spring-ai) — similarity search and prompt augmentation
- `SimpleVectorStore` — in-memory vector store (data is lost on restart)
- `RAGProperties` — config: chunkSize, chunkOverlap, topK, similarityThreshold

**Example RAG configuration:**
```yaml
ai-bot:
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
- Custom `AIBotMeterRegistry` wraps Micrometer
- Prometheus endpoint: `http://localhost:8080/actuator/prometheus`
- Grafana dashboards: `http://localhost:3000` (admin/admin123456)

**Health checks:**
- `http://localhost:8080/actuator/health`

**Logs:**
- Elasticsearch + Kibana: `http://localhost:5601`

## Useful links (after starting the application)

- **Swagger UI:** http://localhost:8080/swagger-ui/index.html
- **Actuator Health:** http://localhost:8080/actuator/health
- **Prometheus Metrics:** http://localhost:8080/actuator/prometheus
- **Prometheus UI:** http://localhost:9090
- **Grafana:** http://localhost:3000 (admin/admin123456)
- **Kibana:** http://localhost:5601

## Environment variables

Required for local development (create a `.env` file or export):
```bash
TELEGRAM_USERNAME=your_bot_username
TELEGRAM_TOKEN=your_telegram_bot_token
OPENROUTER_KEY=your_openrouter_api_key
DEEPSEEK_KEY=your_deepseek_api_key
POSTGRES_PASSWORD=your_secure_password
```

## Adding a new AI provider

1. Create a new module `aibot-<provider>`
2. Add dependency on `aibot-common`
3. Implement `AIGateway`
4. Create `@AutoConfiguration` with `@ConditionalOnProperty`
5. Add a Properties class for API keys/endpoints
6. Register in `aibot-app/pom.xml`
7. Update `application.yml` with configuration

## Adding a new interface

1. Create a new module `aibot-<interface>`
2. Implement command handlers extending `ICommandHandler`
3. Create command types implementing `ICommand` or `IChatCommand`
4. Create `@AutoConfiguration`
5. Add properties for interface-specific configuration
6. Register in `aibot-app/pom.xml`
