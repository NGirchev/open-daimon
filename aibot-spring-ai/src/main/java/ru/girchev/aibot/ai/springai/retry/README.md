# OpenRouter: ретрай и ротация моделей

## Для AI / быстрый контекст (не пересказывать код заново)

**Цепочка вызовов (stream):**  
`MessageTelegramCommandHandler` → `SpringAIGateway.generateResponse()` → `SpringAIChatService.streamChat()` (аннотирован `@RotateOpenRouterModels(stream=true)`) → аспект `OpenRouterModelRotationAspect.rotateModels()` → `streamWithRetry()`.

**Кандидаты:** берутся в аспекте из `OpenRouterRotationRegistry.getCandidatesByCapabilities(command.modelCapabilities(), preferred)`. Реализация — `SpringAIModelRegistry`: модели из yml + free-модели из OpenRouter API. Список обрезается по `maxAttempts`. Если `getCandidatesByCapabilities` вернул пусто и есть `modelConfig` — подставляется один кандидат `[modelConfig]`.

**Почему ретрай может не произойти:**
- **Один кандидат:** у команды capabilities = `{AUTO}` (например `DefaultAICommandFactory` для ADMIN). В реестре только `openrouter/auto` имеет AUTO → один кандидат → при ошибке стрима `index + 1 >= candidates.size()`, ретрай не делается.
- Для REGULAR/VIP capabilities (CHAT, CHAT+TOOL_CALLING+WEB и т.д.) кандидатов обычно несколько (openrouter/auto, gemma3:1b, free-модели) — ретрай возможен.

**Где рождается ошибка пустого стрима:**  
`WebClientLogCustomizer` (фильтр WebClient) в `logAndBufferErrorsIfNeeded` оборачивает тело ответа (Flux&lt;DataBuffer&gt;) в `handle()`. При признаках «пустой стрим» (usage есть, finish_reason есть, nonEmptyContentChunks=0, диагноз «reasoning-only» или «стрим завершился по лимиту») вызывается `sink.error(new OpenRouterEmptyStreamException(diagnosis))`. Ошибка идёт по цепочке: DataBuffer → парсер SSE Spring AI → Flux&lt;ChatResponse&gt; → до аспекта.

**Обработка в аспекте (stream):**  
`streamWithRetry()` возвращает `SpringAIStreamResponse(Flux.defer(...).onErrorResume(...))`. При ошибке из inner flux срабатывает `onErrorResume`: если `isRetryable(error)` (в т.ч. OpenRouterEmptyStreamException в cause chain) и есть следующий кандидат — рекурсивно `streamWithRetry(..., index+1)`; иначе — `Flux.error(nextError)`.

**Логи ретрая (искать в логах):**
- Переключение: `OpenRouter stream retry: switching from model=... to next candidate model=...`
- Нет кандидатов для ретрая: `OpenRouter stream retry: no more candidates (current=..., totalCandidates=...), cannot retry`
- Нет кандидатов вообще: `OpenRouter stream retry: no candidates available`
- Один кандидат при старте: `OpenRouter model rotation: only 1 candidate(s), retry on stream error will not switch model`

**Ключевые классы:**  
`OpenRouterModelRotationAspect`, `OpenRouterRotationRegistry` / `SpringAIModelRegistry`, `WebClientLogCustomizer` (sink.error(OpenRouterEmptyStreamException)), `OpenRouterEmptyStreamException`, `SpringAIChatService.streamChat`, `DefaultAICommandFactory` (capabilities по приоритету пользователя).

---

## Обзор

Ретрай и ротация моделей OpenRouter реализованы через AOP-аспект `OpenRouterModelRotationAspect`, перехватывающий методы с аннотацией `@RotateOpenRouterModels`. Для stream-запросов ошибка может прийти из стрима (в т.ч. `OpenRouterEmptyStreamException` при пустом/reasoning-only ответе); аспект обрабатывает её в `onErrorResume` и при наличии кандидатов переключается на следующую модель.

## Компоненты

### Аннотация `@RotateOpenRouterModels`

- `stream` — `true` для стриминговых вызовов (`streamChat`), иначе синхронный `callChat`.
- Вешается на методы `SpringAIChatService`: `streamChat(...)`, `callChat(...)`.

### Аспект `OpenRouterModelRotationAspect`

- **Вход:** из аргументов метода достаёт `SpringAIModelConfig` и `AICommand`.
- **Кандидаты:** `OpenRouterRotationRegistry.getCandidatesByCapabilities(command.modelCapabilities(), modelConfig.getName())`. Реализация — `SpringAIModelRegistry` (модели из yml + free из OpenRouter API). Список обрезается по `maxAttempts`. Если реестр вернул пусто — подставляется один кандидат `[modelConfig]`.
- **Синхронный путь:** `callWithRetry` — цикл по кандидатам, `pjp.proceed(replaceModelConfig(args, candidate))`, при retryable-ошибке переход к следующему.
- **Стрим:** `streamWithRetry(pjp, baseArgs, candidates, 0)` возвращает `SpringAIStreamResponse(Flux.defer(...).onErrorResume(...))`. При ошибке: если retryable и есть следующий кандидат — рекурсия `streamWithRetry(..., index+1)`; иначе — проброс ошибки.

### Откуда берётся ошибка «пустой стрим»

- **Класс:** `OpenRouterEmptyStreamException` (пакет `retry`).
- **Место:** `WebClientLogCustomizer.logAndBufferErrorsIfNeeded` — для OpenRouter SSE оборачивает тело ответа (`Flux<DataBuffer>`) в `handle()`. В конце обработки буфера проверяется: есть usage и finish_reason, но `chunksWithNonEmptyContent == 0`. Если диагноз «reasoning-only» или «стрим завершился по лимиту генерации» — вызывается `sink.error(new OpenRouterEmptyStreamException(diagnosis))`.
- Ошибка всплывает по цепочке до `Flux<ChatResponse>`, который обёрнут аспектом в `onErrorResume`, поэтому ретрай возможен только если аспект реально получил несколько кандидатов.

## Retryable-ошибки

- **OpenRouterEmptyStreamException** (в любой причине в цепочке cause) — да, ретрай.
- **WebClientResponseException:** 429, 402, 5xx — да; 404 — да (в т.ч. data policy; тело не проверяется); 400 с «Conversation roles must alternate» — да; остальные 4xx — нет.
- Ошибки без WebClientResponseException (таймауты, сеть и т.д.) — считаются retryable.

## Кандидаты и почему ретрай может не сработать

- Кандидаты определяются по `command.modelCapabilities()` из фабрики команд. В проекте используется **DefaultAICommandFactory** (не ConversationHistoryAICommandFactory).
- **ADMIN:** capabilities = `{AUTO}`. В реестре только `openrouter/auto` имеет AUTO → один кандидат → при ошибке стрима ретрай невозможен (нет «следующей» модели).
- **REGULAR:** `{CHAT}`. Подходят openrouter/auto, gemma3:1b, free-модели с CHAT → несколько кандидатов, ретрай возможен.
- **VIP:** `{CHAT, MODERATION, TOOL_CALLING, WEB}` — несколько моделей могут подходить, ретрай возможен.

Если нужен ретрай при AUTO, в аспекте можно добавить fallback: при единственном кандидате с AUTO дополнительно запрашивать кандидатов по `ModelCapabilities.CHAT` и объединять списки (см. план в .cursor/plans при необходимости).

## Логирование ретрая

- **Список кандидатов при старте:** `OpenRouter model rotation candidates (maxAttempts={}): [список имён]`
- **Один кандидат:** `OpenRouter model rotation: only N candidate(s), retry on stream error will not switch model`
- **Ошибка при попытке:** `OpenRouter stream retry: error caught. model=..., retryable=..., attempt=X of Y, reason=...`
- **Переключение модели при ретрае:** `OpenRouter stream retry: switching from model=... to next candidate model=... (next attempt X of Y). reason=...`
- **Нет следующих кандидатов:** `OpenRouter stream retry: no more candidates after attempt X of Y (current=...), cannot retry. reason=...`
- **Нет кандидатов вообще (крайний случай):** `OpenRouter stream retry: no candidates available (index=..., totalCandidates=...)`
- **Ошибка не retryable (stream):** `OpenRouter stream error not retryable. model=..., reason=...` (DEBUG)

## Конфигурация

```yaml
ai-bot:
  ai:
    spring-ai:
      openrouter-auto-rotation:
        max-attempts: 3   # макс. число кандидатов (попыток); по умолчанию в коде 2
        models:
          enabled: true
          # ...
```

## Связанные классы

- `SpringAIModelRegistry` — реализация `OpenRouterRotationRegistry`, хранит модели (yml + free из API), метод `getCandidatesByCapabilities`.
- `WebClientLogCustomizer` — эмитит `OpenRouterEmptyStreamException` в теле SSE при пустом/reasoning-only стриме.
- `OpenRouterStreamMetricsTracker` — метрики по стриму (doOnComplete/doOnError), не меняет распространение ошибки.
- `SpringAIGateway` — вызывает `chatService.streamChat()` / `callChat()`, оттуда аспект.
- `DefaultAICommandFactory` — задаёт `modelCapabilities()` в зависимости от приоритета пользователя (ADMIN/VIP/REGULAR).
