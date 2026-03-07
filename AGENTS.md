# AGENTS.md

## Роль агента
Senior Java developer, который аккуратно следует стилю проекта — многомодульного Java-проекта со Spring Boot стартерами. Пользуешься не самыми простыми и популярными решениями, а исходишь из структуры проекта. Всегда проверяешь существующий код, чтобы писать в том же стиле.

## Описание пользователя
Java tech lead ближе к 40, опытный, не терпящий неточностей и задач сделанных абы как. Требует тестов и проверки гипотез — без них не принимает код. Любое важное изменение нужно согласовать. Нужно слушать пользователя и не угадывать, что сделать, а делать то, что говорит; если не согласен — можно спорить аргументированно.

## Правила для AI-агентов

### При создании новых сервисов и компонентов
1. **НЕ используй `@Service`, `@Component`, `@Repository`** для автоматического сканирования бинов
2. **Создавай бины явно** в конфигурационных классах через `@Bean` методы
3. **Конфигурационные классы** находятся в пакете `config` каждого модуля
4. **Пример структуры**:
   ```java
   // ❌ НЕПРАВИЛЬНО:
   @Service
   public class MyService { ... }
   
   // ✅ ПРАВИЛЬНО:
   public class MyService { ... }  // Без аннотаций
   
   @Configuration
   public class MyModuleConfig {
       @Bean
       @ConditionalOnMissingBean
       public MyService myService(...) {
           return new MyService(...);
       }
   }
   ```
5. **Исключения**: `@Repository` на интерфейсах JPA репозиториев допустимо (это интерфейсы, не классы)

### При создании новых модулей
1. **Создай pom.xml** с правильной структурой зависимостей (см. Code Style)
2. **Добавь модуль в parent pom.xml** в секцию `<modules>`
3. **Создай package structure**: `ru.girchev.aibot.<module-name>.<layer>`
4. **Если нужны Entity**: наследуй от `User` или `Message` из `aibot-common`
5. **Создай Flyway миграцию** в `aibot-app/src/main/resources/db/migration/`
6. **Создай конфигурационный класс** для всех бинов модуля (e.g., `MyModuleConfig`)

### При работе с Entity
1. **НЕ дублируй Entity** между модулями - используй наследование
2. **Базовые Entity** только в `aibot-common`
3. **Специфичные поля** в дочерних Entity (e.g., `telegram_id` в `TelegramUser`)
4. **Используй JPA Inheritance JOINED** для User
5. **Используй JPA Inheritance SINGLE_TABLE** для Message (все сообщения в одной таблице, специфичные данные в metadata JSONB)
6. **Discriminator** обязателен для полиморфных запросов

### При добавлении новых AI-провайдеров
1. **Создай новый модуль** `ai-<provider-name>` (e.g., `ai-anthropic`)
2. **Создай Service** с методом `generateResponse(String prompt, ...)`
3. **Создай Properties** для конфигурации (API key, URL)
4. **Добавь зависимость** в модули, которые будут использовать провайдера
5. **НЕ добавляй Entity** - провайдеры stateless

### При работе с БД
1. **Все миграции** в `aibot-app/src/main/resources/db/migration/`
2. **Формат**: `V<number>__<description>.sql` (e.g., `V1__Create_initial_tables.sql`)
3. **Индексы обязательны** для foreign keys и часто используемых полей
4. **Используй `IF NOT EXISTS`** для идемпотентности
5. **Timestamps**: `TIMESTAMP WITH TIME ZONE` (не `TIMESTAMP`)

### При добавлении метрик
1. **Используй `AiBotMeterRegistry`** из `aibot-common`
2. **Формат метрик**: `<module>.<action>.<metric>` (e.g., `rest.request.processing.time`)
3. **Типы метрик**: Counter, Timer, Gauge
4. **Добавь описание** в Grafana dashboard

### При работе с приоритизацией
1. **Используй `PriorityRequestExecutor`** для всех AI-запросов
2. **НЕ вызывай AI-сервисы напрямую** - только через executor
3. **Приоритеты**: ADMIN (10 потоков), VIP (5 потоков), REGULAR (1 поток)
4. **Whitelist** управляется через `WhitelistService`

### Безопасность
1. **API keys** ТОЛЬКО в environment variables
2. **НЕ коммить** `application.yml` с реальными ключами
3. **Используй `@PreAuthorize`** для защиты REST endpoints (если добавишь Spring Security)
4. **Валидация входных данных** через Jakarta Validation (`@Valid`, `@NotNull`, etc.)

### Тестирование
1. **Unit tests** для сервисов (Mockito)
2. **Integration tests** для репозиториев (Testcontainers)
3. **Покрытие** минимум 70% для критичной бизнес-логики
4. **Не мокай Entity** - используй реальные объекты
5. **Используй `@DataJpaTest`** для тестов репозиториев

## Project Overview

**AI Bot Router** - многомодульный Java-проект для взаимодействия с различными AI-сервисами через разные интерфейсы (Telegram, REST API, Web UI) с интеграцией через Spring AI (OpenRouter, Ollama) и возможностью добавления собственного контекста и шаблонов.

### Архитектурная концепция

Проект построен по принципу **модульной архитектуры**, где каждый модуль может быть собран независимо под конкретного клиента. Например:
- Можно собрать только `aibot-telegram` без `aibot-rest`
- Можно подключить только `aibot-rest` без `aibot-telegram`, или `aibot-ui` без `aibot-telegram`
- Можно собрать только `aibot-spring-ai` без интерфейсных модулей (подключив нужные точки входа)
- Можно включать `aibot-gateway-mock` как провайдер-заглушку для тестовых сценариев без внешних API
- Каждый модуль имеет свои Entity и может работать автономно

### Технологический стек

- **Java 21** (LTS)
- **Spring Boot 3.3.3** (Spring Framework 6.2.6)
- **Maven** (multi-module project)
- **PostgreSQL 17.0** с Flyway миграциями
- **Resilience4j** для bulkhead pattern (приоритизация запросов)
- **Caffeine** для кэширования
- **Micrometer + Prometheus + Grafana** для метрик
- **Elasticsearch + Kibana + Metricbeat** для логирования
- **Testcontainers** для интеграционных тестов
- **Lombok** для уменьшения boilerplate кода

## Модульная структура

### 1. `aibot-common` (Core Module)
**Назначение**: Базовый модуль с общей бизнес-логикой, моделями и сервисами.

**Ключевые компоненты**:
- `User` - базовая Entity с JPA Inheritance (JOINED strategy)
- `Message` - Entity для хранения сообщений в диалоге (объединяет функциональность UserRequest и ServiceResponse)
- `CommandHandler<T, C, R>` - интерфейс для обработки команд (паттерн Command)
- `PriorityRequestExecutor` - сервис для приоритизации запросов (ADMIN/VIP/REGULAR)
- `BulkHeadAutoConfig`, `BulkHeadProperties` - конфигурация пулов потоков
- `AIBotMeterRegistry` - метрики для мониторинга
**Зависимости**: Spring Data JPA, PostgreSQL, Resilience4j, Caffeine, Micrometer

### 2. `aibot-telegram` (Telegram Interface Module)
**Назначение**: Модуль для работы с Telegram Bot API.

**Ключевые компоненты**:
- `TelegramBot`
- Конфигурация: `TelegramAutoConfig`, `TelegramServiceConfig`, `TelegramProperties`
- Command handlers: `StartTelegramCommandHandler`, `MessageTelegramCommandHandler`, `RoleTelegramCommandHandler`, `NewThreadTelegramCommandHandler`, `HistoryTelegramCommandHandler`, `ThreadsTelegramCommandHandler`, `BugreportTelegramCommandHandler`
- Сервисы: `TelegramUserService`, `TelegramMessageService`, `TelegramUserSessionService`, `TelegramWhitelistService`, `TypingIndicatorService`
- Entities: `TelegramUser`, `TelegramUserSession`, `TelegramWhitelist`

**Зависимости**: `aibot-common`, Telegram Bots API (6.9.7.0)

**Таблицы БД**: `telegram_user`, `telegram_user_session`, `telegram_whitelist`

### 3. `aibot-rest` (REST API Module)
**Назначение**: Модуль для предоставления REST API.

**Ключевые компоненты**:
- `RestUser extends User` - Entity для REST-пользователей (поле `email`)
- Контроллеры: `SessionController`
- Handlers: `RestChatMessageCommandHandler`, `RestChatStreamMessageCommandHandler`
- Конфигурация: `RestAutoConfig`, `RestFlywayConfig`, `RestJpaConfig`
- Сервисы: `ChatService`, `RestUserService`, `RestMessageService`, `RestAuthorizationService`
- Исключения: `RestExceptionHandler`, `UnauthorizedException`

**Зависимости**: `aibot-common`, Springdoc OpenAPI (Swagger)

**Таблицы БД**: `rest_user`

### 4. `aibot-ui` (Web UI Module)
**Назначение**: Веб-интерфейс для работы через браузер.

**Ключевые компоненты**:
- `PageController`, `UIAuthController`
- `UIAutoConfig`, `UIProperties`
- Шаблоны: `templates/login.html`, `templates/chat.html`
- Статика: `static/css/chat.css`, `static/js/chat.js`

**Зависимости**: `aibot-rest`, Spring Boot Web, Thymeleaf

### 5. `aibot-spring-ai` (Spring AI Integration Module)
**Назначение**: Интеграция с LLM провайдерами через Spring AI (OpenAI/OpenRouter, Ollama) и чат-память.

**Ключевые компоненты**:
- Конфигурация: `SpringAIAutoConfig`, `SpringAIProperties`, `SpringAIModelConfig`, `SpringAIFlywayConfig`
- Сервисы: `SpringAIGateway`, `SpringAIChatService`, `SpringAIPromptFactory`
- OpenRouter (фича openrouter-auto-rotation целиком в пакетах `openrouter`, `openrouter.metrics`): Properties, ApiClient, ModelEntry, ClientConfig, ModelStatsRecorder, StreamMetricsTracker, FreeModelResolver, ModelCapabilitiesMapper, FreeModelResolverScheduler; ротация: `OpenRouterModelRotationAspect`, `RotateOpenRouterModels`
- Chat memory: `SummarizingChatMemory`
- Web/логирование: `RestClientLogCustomizer`, `WebClientLogCustomizer`
- Tools: `WebTools`

**Зависимости**: `aibot-common`, Spring AI, WebClient

### 6. `aibot-gateway-mock` (Gateway Mock Module)
**Назначение**: Заглушка для интеграционных тестов и сценариев без внешнего API.

**Ключевые компоненты**:
- Моки ответов провайдера
- DTO для тестовых сценариев

**Зависимости**: `aibot-common`

### 7. `aibot-app` (Application Module)
**Назначение**: Основной модуль для запуска приложения, объединяет все модули.

**Ключевые компоненты**:
- `Application` - главный класс с `@SpringBootApplication`
- Flyway миграции в `src/main/resources/db/migration/`
- `application.yml` - конфигурация приложения

**Зависимости**: `aibot-telegram`, `aibot-rest`, `aibot-ui`, `aibot-spring-ai`, `aibot-gateway-mock` (транзитивно подтягивает все остальные модули)

## Структура базы данных

### Иерархия наследования (JPA Inheritance)
```
user (базовая таблица, JOINED strategy)
├── telegram_user (telegram_id)
└── rest_user (email)

message (базовая таблица, SINGLE_TABLE strategy)
- Хранит все сообщения (USER, ASSISTANT, SYSTEM)
- Telegram-специфичные данные хранятся в metadata (session_id)
- REST-специфичные данные хранятся в metadata (client_ip, user_agent, endpoint)
```

### Основные таблицы
- `user` - базовая таблица пользователей (discriminator: `user_type`)
- `telegram_user`, `rest_user` - специфичные таблицы для разных типов пользователей
- `message` - все сообщения в диалогах (объединяет функциональность user_request и service_response)
- `telegram_user_session` - сессии Telegram-пользователей
- `telegram_whitelist` - whitelist для доступа к боту
- `conversation_thread` - потоки диалогов для группировки сообщений

## Code Style и конвенции

### Структура зависимостей в pom.xml
**ВАЖНО**: Следуй этому порядку в КАЖДОМ pom.xml (см. комментарии в файлах):
1. Project-specific modules (groupId: `ru.girchev`)
2. Spring dependencies (groupId: `org.springframework`)
3. Database dependencies (jdbc, jpa, postgres, h2)
4. Other utilities and libraries (logging, json, etc.)
5. Test-related dependencies (scope: `test`)

**Все версии ДОЛЖНЫ быть вынесены в `<properties>`!**

### Java Code Style
- **Java 21** с использованием современных фич
- **Lombok** для уменьшения boilerplate (`@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Slf4j`)
- **Functional patterns** где возможно (используется библиотека Vavr)
- **Package structure**: `ru.girchev.aibot.<module>.<layer>` (e.g., `ru.girchev.aibot.telegram.service`)

### Entity Guidelines
- Базовые Entity в `aibot-common` (`User`, `Message`)
- Специфичные Entity в модулях (`TelegramUser`, `RestUser`)
- Используется **JPA Inheritance JOINED** для User
- Используется **JPA Inheritance SINGLE_TABLE** для Message (все сообщения в одной таблице)
- `@PrePersist` и `@PreUpdate` для автоматического заполнения timestamps
- Discriminator column: `user_type` (values: `TELEGRAM`, `REST`) для User
- Discriminator column: `message_type` для Message (по умолчанию `MESSAGE`)

### Service Layer
- Интерфейсы для сервисов (e.g., `UserService`, `UserPriorityService`)
- Реализации с суффиксом `Impl` (e.g., `UserPriorityServiceImpl`)
- `@RequiredArgsConstructor` для dependency injection
- `@Slf4j` для логирования

### Spring Bean Configuration
**ВАЖНО**: В этом проекте НЕ используются аннотации `@Service`, `@Component`, `@Repository` для автоматического сканирования бинов!
- **Все бины создаются явно** в конфигурационных классах через `@Bean` методы
- **Конфигурационные классы** находятся в пакете `config` каждого модуля (e.g., `TelegramServiceConfig`, `CoreAutoConfig`)
- **Преимущества**: явный контроль над созданием бинов, условная конфигурация через `@ConditionalOnProperty`, лучшая тестируемость
- **Пример**: вместо `@Service` на классе создай `@Bean` метод в соответствующем `*Config` классе

#### Пример использования ObjectProvider:

```java
// ✅ ПРАВИЛЬНО: использование ObjectProvider для опциональных/ленивых бинов
@Bean
@ConditionalOnMissingBean
public MessageTelegramCommandHandler messageTelegramCommandHandler(
        ObjectProvider<TelegramBot> telegramBotProvider,  // Опциональный бин
        PriorityRequestExecutor priorityRequestExecutor,
        // ... другие зависимости
) {
    return new MessageTelegramCommandHandler(telegramBotProvider, priorityRequestExecutor, ...);
}

// В классе handler:
public class MessageTelegramCommandHandler {
    private final ObjectProvider<TelegramBot> telegramBotProvider;
    
    public void sendMessage(Long chatId, String text) {
        // Бин получается только при необходимости
        telegramBotProvider.getObject().sendMessage(chatId, text);
    }
}
```

**Когда использовать ObjectProvider:**
- Когда бин может отсутствовать (опциональный)
- Когда нужна ленивая загрузка (получение бина только при использовании)
- Когда нужно избежать циклических зависимостей
- Когда бин создается условно (через `@ConditionalOnProperty`)

**Когда использовать @Lazy:**
- Когда бин всегда должен существовать, но нужна ленивая инициализация
- Когда нужно разорвать циклическую зависимость на уровне создания бина

### Command Pattern
- Интерфейс `CommandHandler<T extends CommandType, C extends Command<T>, R>`
- Каждый модуль имеет свою реализацию (e.g., `TelegramCommandHandler`)
- Registry для регистрации обработчиков (`AiBotCommandHandlerRegistry`)

### Метрики и мониторинг
- Используй `AiBotMeterRegistry` для регистрации метрик
- Метрики должны быть в формате: `<module>.<action>.<metric>` (e.g., `telegram.message.processing.time`)
- Все метрики экспортируются в Prometheus

## Конфигурация

- Структура конфигурации соответствует `ai-bot.*` (модули `telegram`, `rest`, `ui`, `ai.spring-ai`), все feature toggles работают через `*.enabled`.
- Конфиги и комментарии по ключам хранятся в `aibot-app/src/main/resources/application.yml`.

**Module Auto-Configuration:**
Каждый модуль предоставляет класс `@AutoConfiguration` с условной регистрацией бинов:
- `CoreAutoConfig` (aibot-common) - основные сервисы, реестры
- `TelegramAutoConfig` - включается через `ai-bot.telegram.enabled=true`
- `RestAutoConfig` - включается через `ai-bot.rest.enabled=true`
- `SpringAIAutoConfig` - включается через `ai-bot.ai.spring-ai.enabled=true`

**Properties Hierarchy:**
```yaml
ai-bot:
  common:
    summarization:
      max-context-tokens: 8000
      summary-trigger-threshold: 0.7
      keep-recent-messages: 20
    manual-conversation-history:  # История, управляемая common (ручной контекст)
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

**ВАЖНО:** При создании классов `@ConfigurationProperties`:
- Все значения обязательны (должны быть указаны в `application.yml`)
- НЕ задавай дефолтные значения в коде - только в конфигурации
- Используй валидацию: `@Validated` с `@NotNull`, `@Min`, `@Max`
- Используй типы-обертки (`Integer`, `Double`, `Boolean`) для поддержки `@NotNull`

Пример:
```java
@ConfigurationProperties(prefix = "ai-bot.context")
@Validated
@Getter
@Setter
public class ContextProperties {
    @NotNull(message = "maxContextTokens обязателен")
    @Min(value = 1000, message = "maxContextTokens должен быть >= 1000")
    private Integer maxContextTokens; // БЕЗ дефолтного значения!
}
```

## Команды сборки и тестирования

### Сборка
```bash
# Полная сборка с тестами
mvn clean install

# Сборка без тестов (для Docker)
mvn clean package -DskipTests

# Сборка конкретного модуля
mvn clean install -pl aibot-common
```

### Запуск приложения
```bash
# Локальная разработка (требует инфраструктуру)
docker-compose up -d postgres prometheus grafana
mvn spring-boot:run -pl aibot-app

# Полный Docker deployment
mvn clean package -DskipTests
docker-compose up -d
```

### Тестирование
```bash
# Все тесты
mvn test

# Конкретный тест-класс
mvn test -Dtest=MessageTelegramCommandHandlerIntegrationTest -pl aibot-app

# Конкретный тест-метод
mvn test "-Dtest=TelegramUserRepositoryTest#whenSaveUser_thenUserIsSaved" -pl aibot-app

# Тесты конкретного модуля
mvn test -pl aibot-telegram

# SpringAIGatewayIT (стриминг, без Ollama)
mvn test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
```

**Запуск тестов на Windows:**
- Для `mvnw.cmd` нужна переменная **JAVA_HOME** (путь к JDK 21). Если не задана глобально: JDK часто лежит в `C:\Users\<user>\.jdks\corretto-21.0.10` (IDEA) или в File → Project Structure → SDKs скопировать путь.
- В **PowerShell** из корня проекта (одной строкой):
  ```powershell
  $env:JAVA_HOME = "C:\Users\ngirc\.jdks\corretto-21.0.10"; cd c:\Work\IdeaProjects\ai-bot; .\mvnw.cmd test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
  ```
  (подставьте свой путь к JDK вместо `corretto-21.0.10`).
- Если запускаете только тесты модуля, сначала соберите зависимости: `.\mvnw.cmd install -DskipTests`, затем команду `test` выше.
- Из **IntelliJ IDEA**: правый клик по классу `SpringAIGatewayIT` → Run 'SpringAIGatewayIT' (JAVA_HOME не нужен, IDEA подставляет свой JDK).

### Миграции БД
```bash
# Запуск Flyway миграций вручную
mvn flyway:migrate -pl aibot-common

# Информация о миграциях Flyway
mvn flyway:info -pl aibot-common
```

**Modular Flyway Strategy:**
- Каждый модуль имеет путь миграций: `src/main/resources/db/migration/<module>/`
- Пути: `core/`, `telegram/`, `rest/`, `springai/`
- Каждый `FlywayConfig` модуля регистрирует свои locations
- Миграции выполняются по порядку во всех модулях

**Добавление новой миграции:**
1. Создай файл в соответствующем пути модуля: `V<number>__Description.sql`
2. Следуй именованию: `V1__Create_base_tables.sql`, `V2__Add_user_fields.sql`
3. Запусти `mvn flyway:migrate -pl aibot-common` для применения

## Архитектурные паттерны

### 1. Gateway Pattern (Абстракция AI-провайдеров)
- **Интерфейс:** `AIGateway` с методами `supports(AICommand)` и `generateResponse(AICommand)`
- **Реестр:** `AIGatewayRegistry` динамически обнаруживает и выбирает подходящий gateway
- **Реализации:** `SpringAIGateway`, `DeepSeekGateway`, `OpenRouterGateway`

### 2. Factory Pattern (Создание команд с приоритетами)
- **Интерфейс:** `AICommandFactory<A, C>` с методами `priority()`, `supports()`, и `createCommand()`
- **Реестр:** `AICommandFactoryRegistry` выбирает фабрику по наименьшему значению приоритета
- **Ключевые фабрики:**
  - `ConversationHistoryAICommandFactory` (priority: 0) - создает команды с историей диалога, когда в metadata есть `threadKey`
  - `DefaultAICommandFactory` (priority: LOWEST_PRECEDENCE) - fallback для простых команд

### 3. Command Handler Pattern
- **Интерфейс:** `ICommandHandler<T, C, R>` с методами `canHandle()`, `handle()`, и `priority()`
- **Реестр:** `CommandHandlerRegistry` выбирает обработчик на основе совместимости типов и приоритета
- **Базовые команды:** `ICommand<T>` → `IChatCommand` (добавляет поддержку текста и стриминга)

### 4. Priority-based Request Execution (Bulkhead)
- **Сервис:** `PriorityRequestExecutor` использует Resilience4j bulkhead для каждого уровня приоритета пользователя (VIP/REGULAR/BLOCKED)
- **Синхронизация:** `CommandSyncService` управляет семафорами на пользователя (VIP: 3, остальные: 2 параллельных запроса)

## Поток обработки диалога

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

## Режимы контекста диалога

Система поддерживает ДВА взаимоисключающих режима, управляемых через `ai-bot.common.manual-conversation-history.enabled`:

**Режим 1: Ручное построение контекста (`enabled: true`)**
- `ConversationHistoryAICommandFactory` строит контекст вручную
- `ConversationContextBuilderService` загружает сообщения из таблицы `aibot_message`
- Полный контроль над бюджетом токенов, размером окна и интеграцией summary
- Используй этот режим для кастомной логики управления контекстом

**Режим 2: Spring AI ChatMemory (`enabled: false`)**
- Использует `MessageChatMemoryAdvisor` из Spring AI с кастомным `SummarizingChatMemory`
- Сообщения хранятся в таблице `spring_ai_chat_memory`
- Автоматическое отслеживание диалога по conversationId
- Используй этот режим для интеграции с фреймворком Spring AI

**Основное правило:** базовый/дефолтный режим для приложения — **Spring AI ChatMemory** (`ai-bot.common.manual-conversation-history.enabled=false`). Ручной режим (`enabled: true`) рассматривай как специальный режим для экспериментов и кастомных сценариев, а не как основную конфигурацию.

## Автоматическая суммаризация диалогов

**Управление длинными диалогами:**
- **Сервис:** `SummarizationService` (асинхронное выполнение)
- **Триггер:** Когда `totalTokens >= summarization.maxContextTokens * summarization.summaryTriggerThreshold` (например, 70%)
- **Процесс:**
  1. Фильтрация старых сообщений (до порога)
  2. Построение промпта для суммаризации с существующим summary
  3. Вызов AI с низкой температурой (0.3) для генерации JSON: `{summary, memory_bullets}`
  4. Обновление `ConversationThread` новым summary и memory bullets
  5. Отслеживание `messagesAtLastSummarization` для избежания повторной суммаризации тех же сообщений
- **Интеграция:** При следующем построении контекста используется summary вместо старых сообщений, сохраняя количество токенов управляемым

## Поддержка стриминга

**SSE (Server-Sent Events) Streaming:**
- **Уровень команды:** `IChatCommand.stream()` boolean флаг
- **Уровень gateway:** `SpringAIGateway` возвращает `SpringAIStreamResponse`, оборачивающий `Flux<ChatResponse>`
- **REST Handler:** `RestChatStreamMessageCommandHandler` конвертирует Flux в SSE
- **Telegram Handler:** `MessageTelegramCommandHandler` использует `AIUtils.processStreamingResponse()` для агрегации чанков
- **Персистентность:** Финальный агрегированный ответ сохраняется в БД после завершения стрима

## Архитектура обработки файлов

Система поддерживает два типа файловых вложений с разными пайплайнами обработки:

### Изображения (Multimodal API Flow)

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

**Ключевые компоненты:**
- `TelegramFileService` (aibot-telegram) - Загружает файлы из Telegram API
- `MinioFileStorageService` (aibot-common) - Хранит файлы в MinIO
- `Attachment` record (aibot-common) - Метаданные файла + байты данных
- `SpringAIGateway.createUserMessage()` - Конвертирует Attachment в Spring AI Media

**Хранение ссылок на вложения в истории (режим manual-conversation-history):**
- В таблице `message` колонка `attachments` (JSONB): массив объектов `{ storageKey, expiresAt, mimeType, filename }`. Ссылка и время истечения (TTL из `ai-bot.common.storage.minio.ttl-hours`) сохраняются при сохранении USER-сообщения (`TelegramMessageService.saveUserMessage` с вложениями).
- При построении контекста (`ConversationContextBuilderService.buildContext`) для USER-сообщений с непустым `attachments` и не истёкшим `expiresAt` изображения (по `mimeType` image/*) подгружаются из MinIO по `storageKey` и добавляются в контекст как content parts (text + image_url с data:base64). Если файл истёк или storage отключён — в контекст попадает только текст. Вложения доступны в рамках окна до суммаризации и до истечения TTL.

### PDF документы (RAG Pipeline Flow)

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
- `ai-bot.ai.spring-ai.rag.enabled=true` - RAG pipeline (использует SimpleVectorStore in-memory)

**Ключевые компоненты:**
- `DocumentProcessingService` (aibot-spring-ai) - ETL пайплайн для PDF
- `RAGService` (aibot-spring-ai) - Поиск по схожести и расширение промпта
- `SimpleVectorStore` - In-memory векторное хранилище (данные теряются при перезапуске)
- `RAGProperties` - Конфигурация: chunkSize, chunkOverlap, topK, similarityThreshold

**Пример конфигурации RAG:**
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

**Примечание:** SimpleVectorStore только in-memory. Для продакшена рассмотри PGVector или Elasticsearch.

## Мониторинг

**Метрики:**
- Кастомный `AIBotMeterRegistry` оборачивает Micrometer
- Prometheus endpoint: `http://localhost:8080/actuator/prometheus`
- Grafana dashboards: `http://localhost:3000` (admin/admin123456)

**Health Checks:**
- `http://localhost:8080/actuator/health`

**Логи:**
- Elasticsearch + Kibana: `http://localhost:5601`

## Полезные ссылки (после запуска приложения)

- **Swagger UI:** http://localhost:8080/swagger-ui/index.html
- **Actuator Health:** http://localhost:8080/actuator/health
- **Prometheus Metrics:** http://localhost:8080/actuator/prometheus
- **Prometheus UI:** http://localhost:9090
- **Grafana:** http://localhost:3000 (admin/admin123456)
- **Kibana:** http://localhost:5601

## Переменные окружения

Требуются для локальной разработки (создай `.env` файл или экспортируй):
```bash
TELEGRAM_USERNAME=your_bot_username
TELEGRAM_TOKEN=your_telegram_bot_token
OPENROUTER_KEY=your_openrouter_api_key
DEEPSEEK_KEY=your_deepseek_api_key
POSTGRES_PASSWORD=your_secure_password
```

## Добавление нового AI-провайдера

1. Создай новый модуль `aibot-<provider>`
2. Добавь зависимость на `aibot-common`
3. Реализуй интерфейс `AIGateway`
4. Создай класс `@AutoConfiguration` с `@ConditionalOnProperty`
5. Добавь класс Properties для API keys/endpoints
6. Зарегистрируй в `aibot-app/pom.xml`
7. Обнови `application.yml` с конфигурацией

## Добавление нового интерфейса

1. Создай новый модуль `aibot-<interface>`
2. Реализуй command handlers, расширяющие `ICommandHandler`
3. Создай типы команд, реализующие `ICommand` или `IChatCommand`
4. Создай класс `@AutoConfiguration`
5. Добавь properties для интерфейс-специфичной конфигурации
6. Зарегистрируй в `aibot-app/pom.xml`
