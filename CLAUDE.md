# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AI Bot Router - A multi-module Spring Boot 3.3.3 application (Java 21) that routes user interactions from multiple interfaces (Telegram, REST API, Web UI) to various AI service providers (DeepSeek, OpenRouter, Spring AI framework). The system supports conversation threading, automatic summarization for long conversations, streaming responses, and priority-based request execution.

## Essential Build & Test Commands

### Building
```bash
# Full build with tests
mvn clean install

# Build without tests (for Docker)
mvn clean package -DskipTests

# Build specific module
mvn clean install -pl aibot-common
```

### Running the Application
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

# Specific test class
mvn test -Dtest=MessageTelegramCommandHandlerIntegrationTest -pl aibot-app

# Specific test method
mvn test "-Dtest=TelegramUserRepositoryTest#whenSaveUser_thenUserIsSaved" -pl aibot-app

# Module-specific tests
mvn test -pl aibot-telegram
```

### Database Migrations
```bash
# Run Flyway migrations manually
mvn flyway:migrate -pl aibot-common

# Flyway info
mvn flyway:info -pl aibot-common
```

## Architecture Overview

### Module Structure
```
aibot-parent/
├── aibot-common/       # Core domain logic, entities, services, repositories
├── aibot-telegram/     # Telegram Bot interface implementation
├── aibot-rest/         # REST API + SSE streaming endpoints
├── aibot-ui/           # Web UI (Thymeleaf + static resources)
├── aibot-deepseek/     # DeepSeek AI gateway implementation
├── aibot-openrouter/   # OpenRouter AI gateway implementation
├── aibot-spring-ai/    # Spring AI framework gateway (Ollama, OpenAI)
└── aibot-app/          # Main application that aggregates all modules
```

**Dependency Rules:** Modules depend on `aibot-common` but NOT on each other. Only `aibot-app` depends on interface/gateway modules.

### Key Architectural Patterns

#### 1. Gateway Pattern (AI Provider Abstraction)
- **Interface:** `AIGateway` with `supports(AICommand)` and `generateResponse(AICommand)` methods
- **Registry:** `AIGatewayRegistry` dynamically discovers and selects appropriate gateway
- **Implementations:** `SpringAIGateway`, `DeepSeekGateway`, `OpenRouterGateway`

#### 2. Factory Pattern (Priority-based Command Creation)
- **Interface:** `AICommandFactory<A, C>` with `priority()`, `supports()`, and `createCommand()` methods
- **Registry:** `AICommandFactoryRegistry` selects factory by lowest priority value
- **Key Factories:**
  - `ConversationHistoryAICommandFactory` (priority: 0) - builds commands with conversation history when `threadKey` metadata exists
  - `DefaultAICommandFactory` (priority: LOWEST_PRECEDENCE) - fallback for simple commands

#### 3. Command Handler Pattern
- **Interface:** `ICommandHandler<T, C, R>` with `canHandle()`, `handle()`, and `priority()` methods
- **Registry:** `CommandHandlerRegistry` selects handler based on type compatibility and priority
- **Base Commands:** `ICommand<T>` → `IChatCommand` (adds text and streaming support)

#### 4. Priority-based Request Execution (Bulkhead)
- **Service:** `PriorityRequestExecutor` uses Resilience4j bulkhead per user priority level (VIP/REGULAR/BLOCKED)
- **Sync:** `CommandSyncService` manages per-user semaphores (VIP: 3, others: 2 concurrent requests)

### Conversation Flow

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

### Conversation Context Modes

The system supports TWO mutually exclusive modes controlled by `ai-bot.common.conversation-context.enabled`:

**Mode 1: Manual Context Building (`enabled: true`)**
- `ConversationHistoryAICommandFactory` builds context manually
- `ConversationContextBuilderService` loads messages from `aibot_message` table
- Full control over token budget, window size, and summary integration
- Use this mode for custom context management logic

**Mode 2: Spring AI ChatMemory (`enabled: false`)**
- Uses Spring AI's `MessageChatMemoryAdvisor` with custom `SummarizingChatMemory`
- Messages stored in `spring_ai_chat_memory` table
- Automatic conversation tracking by conversationId
- Use this mode for Spring AI framework integration

### Conversation Summarization

**Automatic Long Conversation Management:**
- **Service:** `SummarizationService` (async execution)
- **Trigger:** When `totalTokens >= maxContextTokens * summaryTriggerThreshold` (default: 70%)
- **Process:**
  1. Filter old messages (before threshold)
  2. Build summarization prompt with existing summary
  3. Call AI with low temperature (0.3) to generate JSON: `{summary, memory_bullets}`
  4. Update `ConversationThread` with new summary and memory bullets
  5. Track `messagesAtLastSummarization` to avoid re-summarizing same messages
- **Integration:** Next context building uses summary instead of old messages, keeping token count manageable

### Database Schema

**Entity Inheritance:**

**Users (JPA JOINED strategy):**
```
User (base table)
├── TelegramUser (telegramId)
└── RestUser (email)
```

**Messages (JPA SINGLE_TABLE strategy):**
```
AIBotMessage
- role: USER, ASSISTANT, SYSTEM
- content: TEXT
- thread: ConversationThread reference
- sequenceNumber: ordering within thread
- tokenCount: estimated tokens
- metadata: JSONB (interface-specific data)
- responseData: JSONB (AI provider response)
```

**Conversation Threading:**
```
ConversationThread
- totalMessages, totalTokens
- summary, memoryBullets (JSON array)
- messagesAtLastSummarization
- active status (24h timeout)
```

**Assistant Roles:**
```
AssistantRole
- versionHash: content hash for deduplication
- content: system prompt
- usageCount, lastUsedAt
- Only one active role per user
```

### Configuration Patterns

**Module Auto-Configuration:**
Each module provides `@AutoConfiguration` class with conditional bean registration:
- `CoreAutoConfig` (aibot-common) - core services, registries
- `TelegramAutoConfig` - enabled by `ai-bot.telegram.enabled=true`
- `RestAutoConfig` - enabled by `ai-bot.rest.enabled=true`
- `SpringAIAutoConfig` - enabled by `ai-bot.ai.spring-ai.enabled=true`

**Properties Hierarchy:**
```yaml
ai-bot:
  common:
    conversation-context:
      enabled: false  # Context mode selection
      max-context-tokens: 8000
      default-window-size: 20
      summary-trigger-threshold: 0.7
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

**IMPORTANT:** When creating `@ConfigurationProperties` classes:
- All values are mandatory (must be in `application.yml`)
- NO default values in code - only in configuration files
- Use `@Validated` with `@NotNull`, `@Min`, `@Max` annotations
- Use wrapper types (`Integer`, `Double`, `Boolean`) to support `@NotNull`

Example:
```java
@ConfigurationProperties(prefix = "ai-bot.context")
@Validated
@Getter
@Setter
public class ContextProperties {
    @NotNull(message = "maxContextTokens is required")
    @Min(value = 1000, message = "maxContextTokens must be >= 1000")
    private Integer maxContextTokens; // NO default value!
}
```

### Database Migrations

**Modular Flyway Strategy:**
- Each module has migration path: `src/main/resources/db/migration/<module>/`
- Paths: `core/`, `telegram/`, `rest/`, `springai/`
- Each module's `FlywayConfig` registers its locations
- Migrations run in order across all modules

**Adding New Migration:**
1. Create file in appropriate module path: `V<number>__Description.sql`
2. Follow naming: `V1__Create_base_tables.sql`, `V2__Add_user_fields.sql`
3. Run `mvn flyway:migrate -pl aibot-common` to apply

### Streaming Support

**SSE (Server-Sent Events) Streaming:**
- **Command Level:** `IChatCommand.stream()` boolean flag
- **Gateway Level:** `SpringAIGateway` returns `SpringAIStreamResponse` wrapping `Flux<ChatResponse>`
- **REST Handler:** `RestChatStreamMessageCommandHandler` converts Flux to SSE
- **Telegram Handler:** `MessageTelegramCommandHandler` uses `AIUtils.processStreamingResponse()` for chunk aggregation
- **Persistence:** Final aggregated response saved to DB after stream completes

### File Processing Architecture

The system supports two types of file attachments with different processing pipelines:

#### Images (Multimodal API Flow)

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

**Key Components:**
- `TelegramFileService` (aibot-telegram) - Downloads files from Telegram API
- `MinioFileStorageService` (aibot-common) - Stores files in MinIO
- `Attachment` record (aibot-common) - File metadata + data bytes
- `SpringAIGateway.createUserMessage()` - Converts Attachment to Spring AI Media

#### PDF Documents (RAG Pipeline Flow)

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
- `ai-bot.ai.spring-ai.rag.enabled=true` - RAG pipeline (uses SimpleVectorStore in-memory)

**Key Components:**
- `DocumentProcessingService` (aibot-spring-ai) - ETL pipeline for PDF
- `RAGService` (aibot-spring-ai) - Similarity search and prompt augmentation
- `SimpleVectorStore` - In-memory vector store (data lost on restart)
- `RAGProperties` - Configuration: chunkSize, chunkOverlap, topK, similarityThreshold

**RAG Configuration Example:**
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

**Note:** SimpleVectorStore is in-memory only. For production, consider PGVector or Elasticsearch.

### Monitoring

**Metrics:**
- Custom `AIBotMeterRegistry` wraps Micrometer
- Prometheus endpoint: `http://localhost:8080/actuator/prometheus`
- Grafana dashboards: `http://localhost:3000` (admin/admin123456)

**Health Checks:**
- `http://localhost:8080/actuator/health`

**Logs:**
- Elasticsearch + Kibana: `http://localhost:5601`

## Development Guidelines

### Code Style Rules

**From .cursor/rules/add-only-asked.mdc:**
- This is a Telegram bot assistant powered by AI
- Do NOT add extra functionality (menus, additional options) without explicit request
- Always ask permission before making changes beyond the direct request
- Implement only explicitly requested functionality
- If a class is not found, search for it thoroughly before creating a new one

**From .cursor/rules/configuration-properties.mdc:**
- See "Configuration Patterns" section above for `@ConfigurationProperties` rules

### POM Dependency Order

**From root pom.xml comment:**
When adding dependencies, follow this structure:
1. Project-specific modules (groupId: `ru.girchev`)
2. Spring dependencies (groupId: `org.springframework`)
3. Database dependencies (JDBC, JPA, PostgreSQL, H2)
4. Other utilities and libraries (logging, JSON, etc.)
5. Test-related dependencies (with `<scope>test</scope>`)

All versions must be extracted to `<properties>` section.

### Adding New AI Provider

1. Create new module `aibot-<provider>`
2. Add dependency on `aibot-common`
3. Implement `AIGateway` interface
4. Create `@AutoConfiguration` class with `@ConditionalOnProperty`
5. Add properties class for API keys/endpoints
6. Register in `aibot-app/pom.xml`
7. Update `application.yml` with configuration

### Adding New Interface

1. Create new module `aibot-<interface>`
2. Implement command handlers extending `ICommandHandler`
3. Create command types implementing `ICommand` or `IChatCommand`
4. Create `@AutoConfiguration` class
5. Add properties for interface-specific config
6. Register in `aibot-app/pom.xml`

## Useful URLs (After Starting Application)

- **Swagger UI:** http://localhost:8080/swagger-ui/index.html
- **Actuator Health:** http://localhost:8080/actuator/health
- **Prometheus Metrics:** http://localhost:8080/actuator/prometheus
- **Prometheus UI:** http://localhost:9090
- **Grafana:** http://localhost:3000 (admin/admin123456)
- **Kibana:** http://localhost:5601

## Environment Variables

Required for local development (create `.env` file or export):
```bash
TELEGRAM_USERNAME=your_bot_username
TELEGRAM_TOKEN=your_telegram_bot_token
OPENROUTER_KEY=your_openrouter_api_key
DEEPSEEK_KEY=your_deepseek_api_key
POSTGRES_PASSWORD=your_secure_password
```

## Related Documentation

- **AGENTS.md** - Detailed documentation for AI agents (architecture, modules, code style)
- **DEPLOYMENT.md** - Production deployment instructions
- **MODULAR_MIGRATIONS.md** - Flyway modular migration documentation
- **README.md** - Quick start guide and technology stack
