# Сравнение веток `fsm-4-3` (open-daimon-2) vs `fsm-4-2` (open-daimon) от Claude Code

> Дата: 2026-04-19. Обе ветки — параллельные реализации одной функциональной цели (ReAct-цикл агента со стримингом в Telegram и FSM для обработки сообщений), расходятся от `fsm-4`. Базовые коммиты: `1f17159` (fsm-4-3) и `c7d01aa` (fsm-4-2).

## TL;DR

| Область | `fsm-4-3` (open-daimon-2) | `fsm-4-2` (open-daimon) | Победитель |
|---|---|---|---|
| Стриминг-фильтр `<think>/<tool_call>` | Отдельный `StreamingAnswerFilter` с state-machine, обрабатывает split-tags | Пост-обработка + `recoverToolCallFromText` на полном тексте | **fsm-4-3** — чище, покрыт unit-тестами |
| Timeout в `blockLast` | ❌ отсутствует | ✅ `Duration.ofMinutes(10)` + fallback на `chatModel.call()` | **fsm-4-2** — надёжнее |
| Recovery чистого tool-call из смешанного ответа | `tryParseRawToolCall` (XML) | `recoverToolCallFromText` (XML + markers) | ≈ ничья |
| Восстановление истории из БД | ❌ нет | ✅ `restoreHistoryFromPrimaryStore` | **fsm-4-2** — полнее |
| Суммаризация памяти | Удалены `CompositeAgentMemory`/`SemanticAgentMemory`/`FactExtractor` | Новый `SummarizingChatMemory` (partial summarization) | **fsm-4-2** (по задаче) / **fsm-4-3** (по простоте) |
| Проверка живости URL | ❌ нет | ✅ `UrlLivenessCheckerImpl` + интеграция в `WebTools` | **fsm-4-2** |
| Модель рендера Telegram | Sealed `RenderedUpdate` + pure-функциональный renderer + FSM с rollback (`STATUS_ONLY ↔ TENTATIVE_ANSWER`) | Мутирующий контекст с `agentProgressPendingHtml` + rate-limited `flushPending*ToTelegram` | **fsm-4-3** по архитектуре, **fsm-4-2** по ratelimit-дисциплине |
| Ротация буфера Telegram | `TelegramBufferRotator` с приоритетом границ (параграф → точка → пробел) | Нет явной абстракции, handling внутри renderer | **fsm-4-3** |
| Покрытие стриминга тестами | 6 новых unit-тестов специально для стриминга + 1 IT | 3 новых теста на стриминг + 1 IT | **fsm-4-3** |
| `AgentPromptBuilder` / `SimpleChainExecutor` | ❌ нет | ✅ есть, выделены | **fsm-4-2** |

**Итог:** каждая ветка сильна в своей области. `fsm-4-3` делает чище и безопаснее именно **пайплайн стриминга** (event-driven, с явной state-machine рендера и фильтром в потоке). `fsm-4-2` шире по **функциям** (summarization, URL-liveness, history recovery, rate-limited batching). Проект-фаворит зависит от приоритета: хотите **качественную доставку UX** — берите `fsm-4-3`; хотите **больше фичей за счёт сложности** — `fsm-4-2`. Лучшее — смержить: `StreamingAnswerFilter` + `TelegramBufferRotator` + `RenderedUpdate` из `fsm-4-3` поверх `SummarizingChatMemory` + `UrlLivenessChecker` + `history-recovery` из `fsm-4-2`.

---

## 1. Архитектурные различия в ReAct-цикле (`SpringAgentLoopActions`)

### 1.1 Как фильтруется поток

**`fsm-4-3`** — `SpringAgentLoopActions.java:246-296`, метод `streamAndAggregate`:
- Новый выделенный класс `StreamingAnswerFilter` (147 строк, `opendaimon-spring-ai/.../agent/StreamingAnswerFilter.java`) — конечный автомат на трёх состояниях: `OUTSIDE`, `INSIDE_THINK`, `INSIDE_TOOL_CALL`.
- Буферизует хвост длиной до `MAX_TAG_LEN - 1`, чтобы корректно обработать теги, расщеплённые между чанками (`<th` + `ink>` → `<think>`).
- Вывод фильтра идёт напрямую в `ctx.emitEvent(AgentStreamEvent.partialAnswer(...))` — слой Telegram получает уже «чистый» поток без думания/XML.

**`fsm-4-2`** — `SpringAgentLoopActions.java:230-277`, метод `streamThinkResponse`:
- Фильтрация на фоне потока, напрямую в `doOnNext` с применением `resolveStreamDelta`.
- `ensureFinalAnswerTailStreamed` (строка 388) эмитит `FINAL_ANSWER_CHUNK` по мере накопления, отслеживая префикс в `KEY_STREAMED_VISIBLE_FINAL_ANSWER`.
- XML-шум удаляется **только на post-stream** через `sanitizeFinalAnswerText` + `recoverToolCallFromText` (строка 981).

**Вердикт.** `fsm-4-3` — чище: фильтрация происходит в чистой функциональной pipe-модели, XML никогда не попадает в consumer. `fsm-4-2` делает это «по месту» и полагается на то, что пост-обработка перехватит утечки — но между эмитом дельты и пост-обработкой клиент может увидеть `<think>...` в UI.

`★ Insight ─────────────────────────────────────`
- Разделение stateless-фильтра от stateful-action (как в fsm-4-3) — классический приём: «функциональное ядро / императивная оболочка». Фильтр тестируется изолированно (`StreamingAnswerFilterTest`, 152 строки), а ReAct-цикл использует его как блок.
- fsm-4-2 объединяет streaming + tool-call-recovery + delta-emission в одной 47-строчной `doOnNext`-лямбде — это сложнее читать и тестировать.
`─────────────────────────────────────────────────`

### 1.2 Обработка timeouts и fallback

**`fsm-4-3`** — `blockLast()` без аргументов (`SpringAgentLoopActions.java:280`). Если upstream (LLM-провайдер) никогда не эмитит `onComplete`, поток зависнет навсегда. Нет fallback на `chatModel.call()`.

**`fsm-4-2`** — `blockLast(Duration.ofMinutes(10))` + catch (`SpringAgentLoopActions.java:262-269`). При исключении проверяет, был ли хоть один chunk: если нет — делает fallback на синхронный `chatModel.call(prompt)` и логирует предупреждение.

**Вердикт.** `fsm-4-2` заметно надёжнее в проде.

### 1.3 Recovery tool-call из смешанного текста

Оба проекта борются с тем, что локальные модели (Ollama/Qwen) могут выдать `<tool_call>...</tool_call>` в виде обычного текста, а не structured calls.

- **`fsm-4-3`** — `tryParseRawToolCall` (строка 596): парсит `<name>…</name>` + пары `<arg_key>…</arg_key><arg_value>…</arg_value>`, строит JSON вручную. Fallback-выполнение напрямую через `ToolCallback.call()`.
- **`fsm-4-2`** — `recoverToolCallFromText` (строка 981): похожий парсер, плюс распознаёт несколько разновидностей маркеров + cleanup leading text.

Оба подхода рабочие, но `fsm-4-2` покрывает больше вариантов синтаксиса (по тестам `SpringAgentLoopActionsMixedToolPayloadTest`, 376 строк).

### 1.4 Память и история

- **`fsm-4-3`** удаляет `CompositeAgentMemory`, `FactExtractor`, `SemanticAgentMemory` (экономия ~378 строк) — расчёт на то, что Spring AI `ChatMemory` достаточно. Упрощение.
- **`fsm-4-2`** вводит `SummarizingChatMemory` (330 строк) — когда count messages > limit, старая половина суммаризуется в `SystemMessage`, свежая половина остаётся как есть. Также добавляет `restoreHistoryFromPrimaryStore` (из `OpenDaimonMessageRepository`) — ChatMemory после рестарта восстанавливается из БД.

**Вердикт.** Для прода и долгих тредов подход `fsm-4-2` правильнее — без него при рестарте приложения вся память теряется. Но реализация `SummarizingChatMemory` имеет **критический баг синхронизации** (см. п. 4.2).

### 1.5 Разделение промптов

- **`fsm-4-3`** — промпт собирается inline в методах SpringAgentLoopActions.
- **`fsm-4-2`** — выделен `AgentPromptBuilder` (152 строки) с статическими методами `buildSystemPrompt`, `buildUserMessage`, `buildMaxIterationsSynthesisSystemPrompt`. Явные правила для модели («NEVER fabricate URLs… copy byte-for-byte from tool result»).

**Вердикт.** `fsm-4-2` лучше — тестируемость и отдельный контроль промптов.

---

## 2. Архитектурные различия в Telegram-слое

### 2.1 Контекст FSM

**`fsm-4-3`** (`MessageHandlerContext.java`):
```
statusMessageId            // 💭 Thinking bubble
tentativeAnswerMessageId   // ℹ️ Answering bubble (отдельное сообщение)
statusBuffer, tentativeAnswerBuffer
currentIteration, toolCallSeenThisIteration
AgentRenderMode { STATUS_ONLY, TENTATIVE_ANSWER }
```
Две отдельные "пузырьковые" цели + явный режим. Поддерживает **rollback**: если после старта TENTATIVE_ANSWER пришёл tool_call — bubble удаляется, текст сворачивается обратно в status.

**`fsm-4-2`** (`MessageHandlerContext.java`):
```
agentProgressMessageId, agentProgressPendingHtml
agentFinalAnswerMessageId, agentFinalAnswerText
agentProgressChunks : List<AgentProgressChunk>   // для rate-limiting
agentFinalAnswerDeliveredLength                  // tracking для resume
```
Два «канала» (progress и final answer) + явный rate-limiting через очередь чанков. Ротация/flushing реализованы в actions через `flushPendingProgressToTelegram(force)`.

**Вердикт.** fsm-4-3 проще и компактнее, fsm-4-2 — точнее по rate limits Telegram (30/сек). Для высоконагруженных ботов fsm-4-2 безопаснее от 429. Но у fsm-4-2 `agentProgressChunks` списка без синхронизации (см. баг в п. 4.5).

### 2.2 Рендерер стрима

**`fsm-4-3`** (`TelegramAgentStreamRenderer.java`):
- Возвращает `RenderedUpdate` (sealed interface) — **чистая функция, без побочных эффектов**.
- Варианты: `ReplaceTrailingThinkingLine`, `AppendFreshThinking`, `AppendToolCall`, `RollbackAndAppendToolCall`, `NoOp`.
- `TelegramMessageHandlerActions` (императивная оболочка) принимает `RenderedUpdate` и применяет.

**`fsm-4-2`** (`TelegramAgentStreamRenderer.java`):
- Мутирует `MessageHandlerContext` напрямую и сам форматирует HTML.
- `renderWebSearchToolCall`, `isUrlTool`, friendly error constants — расширенная логика для форматирования инструментов.
- Принимает `UrlLivenessChecker` для sanitization финального ответа.

**Вердикт.** fsm-4-3 архитектурно чище (разделение presentation/side-effects). fsm-4-2 богаче по UX-логике (friendly-сообщения, liveness-проверка).

### 2.3 Ротация буфера

- **`fsm-4-3`** — `TelegramBufferRotator` (86 строк). Приоритет разрыва: `\n\n` → `. ` → `! ` → `? ` → whitespace → hard cut. Покрыто `TelegramBufferRotatorTest` (98 строк).
- **`fsm-4-2`** — нет выделенного класса, разрывы обрабатываются внутри flush-методов.

**Вердикт.** `fsm-4-3` — отдельная тестируемая абстракция. Плюс.

### 2.4 Вспомогательные классы

`fsm-4-3` выносит в отдельные файлы:
- `TelegramHtmlEscaper` (29 строк)
- `ToolLabels` (42 строки)
- `RenderedUpdate` (56 строк)

`fsm-4-2` держит эквивалент inline внутри renderer/actions.

**Вердикт.** fsm-4-3 лучше по cohesion/SRP.

---

## 3. Тестовое покрытие

### fsm-4-3 (open-daimon-2)
Новые unit-тесты — 12 файлов:
- `SpringAgentLoopActionsMaxIterationsTest` (146) — summary LLM + fallback
- `SpringAgentLoopActionsObserveTest` (214)
- `SpringAgentLoopActionsRawToolCallTest` (265) — парсинг XML tool calls
- `SpringAgentLoopActionsStreamingTest` (132)
- `SpringAgentLoopActionsStripTagsTest` (81)
- `SpringAgentLoopActionsToolCallTagsTest` (219)
- `StreamingAnswerFilterTest` (152) — state-machine фильтра
- `TelegramBufferRotatorTest` (98)
- `TelegramMessageHandlerActionsStreamingTest` (420)
- `HttpApiToolTest` (123)
- `SpringAIAgentOllamaStreamIT` (217) — integration
- `TelegramReActStreamingOllamaManualIT` (526) — e2e manual

### fsm-4-2 (open-daimon)
Новые unit-тесты — 9 файлов:
- `SpringAgentLoopActionsHistoryRecoveryTest` (224) — восстановление из БД
- `SpringAgentLoopActionsMixedToolPayloadTest` (376) — recovery из text
- `SpringAgentLoopActionsThinkTagsTest` (87)
- `SummarizingChatMemoryTest` (49) — **слишком маленький** для 330 строк логики
- `UrlLivenessCheckerImplTest` (110)
- `AgentPromptBuilderTest` (20) — минимальный
- `SimpleChainExecutorTest` (71)
- `MessageHandlerContextAgentProgressTest` (97)
- `TelegramBotTest` (35) + `TelegramReActStreamingOllamaManualIT` (542)

**Вердикт.** `fsm-4-3` — явно более глубокое покрытие streaming/buffer/rendering. `fsm-4-2` покрыл новые фичи (memory, liveness, history recovery), но тесты на `SummarizingChatMemory` и `AgentPromptBuilder` очевидно неполны относительно сложности логики.

---

## 4. Баги и риски

### 4.1 ❗ fsm-4-3 — `blockLast()` без timeout

**Файл:** `opendaimon-spring-ai/.../agent/SpringAgentLoopActions.java:280`.

```java
.doOnNext(text -> ctx.emitEvent(AgentStreamEvent.partialAnswer(text, iteration)))
.blockLast();
```

Серьёзность: **HIGH**. Нет timeout, нет fallback. Если `chatModel.stream(prompt)` не эмитит `onComplete` (разрыв соединения, зависший провайдер), поток Reactor блокируется бесконечно. Поток-потребитель FSM ждёт тоже.

**Фикс:** `.blockLast(Duration.ofMinutes(10))` + try/catch с fallback на `chatModel.call(prompt)`, как в fsm-4-2.

### 4.2 ❗ fsm-4-2 — `SummarizingChatMemory` race condition

**Файл:** `opendaimon-spring-ai/.../memory/SummarizingChatMemory.java:96-129, 157-223`.

Серьёзность: **HIGH**. В классе нет ни одной синхронизирующей конструкции (`synchronized`, `Lock`, `Atomic*`). При concurrent-вызовах `get(conversationId)` для одного `conversationId` два потока одновременно могут:
1. Оба увидеть `messageLimitReached=true`.
2. Оба вызвать `performSummarizationAndUpdateChatMemory`.
3. Оба выполнить `delegate.clear(conversationId)` в строке 202 — состояние перезапишется дважды, часть сообщений может быть потеряна между `clear()` и `add()` другого потока.

Кроме того, `summarizationService.summarizeThread` (LLM call) в `get()` — тяжёлый side-effect в методе, который контрактно выглядит как «просто прочитать». Это архитектурно спорно.

**Фикс:** либо `synchronized (conversationId.intern())`, либо переместить summarization в отдельный background job, запускаемый event-ом `SummarizationStartedEvent` (который уже публикуется).

### 4.3 fsm-4-2 — race между `lastResponse` и `fullText`

**Файл:** `SpringAgentLoopActions.java:230-276`.

Серьёзность: **MEDIUM**. `doOnNext` выполняется reactive-scheduler-ом, а `blockLast` завершает подписку. `lastResponse.set(chunk)` и `fullText.append(delta)` не атомарны между собой. Если `blockLast` выйдет по timeout, `lastResponse` и `fullText` могут быть на разных chunk'ах.

**Фикс:** композитный `AtomicReference<StreamState>` (record с `lastResponse` + `fullText.toString()` + toolCalls), обновляется атомарно в `compareAndSet`.

### 4.4 fsm-4-2 — `terminalChunk.getResult()` без null-guard

**Файл:** `SpringAgentLoopActions.java:~354` (`mergeStreamingText`). `getResult()` и `.getOutput()` — оба могут быть null (Spring AI не даёт гарантий при partial streams). Серьёзность: **MEDIUM**. NPE → необработанная ошибка в loop'е.

### 4.5 ❗ fsm-4-2 — `agentProgressChunks` без синхронизации

**Файл:** `MessageHandlerContext.java`, поле `List<AgentProgressChunk> agentProgressChunks`.

Serving thread добавляет чанки из Reactor-пайплайна, а scheduler-thread читает их в `flushPendingProgressToTelegram`. `ArrayList` без external sync → `ConcurrentModificationException` или потеря чанков. Серьёзность: **HIGH** в условиях стриминга.

**Фикс:** `CopyOnWriteArrayList` или `ConcurrentLinkedQueue`.

### 4.6 fsm-4-2 — `UrlLivenessCheckerImpl` без кеша

**Файл:** `UrlLivenessCheckerImpl.java:66-78`. Каждый вызов `stripDeadLinks` делает N HEAD-запросов без кеша. Ответ с 20 URL × 500ms → 10 сек задержки перед выдачей финального ответа пользователю. Серьёзность: **MEDIUM**. UX-регресс на длинных ответах.

**Фикс:** `Caffeine`-кеш с TTL 5-10 минут по URL.

### 4.7 fsm-4-2 — возможный leak DataBuffer

**Файл:** `WebTools.java:~190`. `DataBufferUtils.read()` без явного `.releaseMemory()` на cancel. Серьёзность: **MEDIUM**. Если WebClient-подписчик отменяется, буферы могут оставаться в пуле.

**Фикс:** `.doFinally(sig -> DataBufferUtils.release(buf))` или использовать готовый `BodyExtractors.toDataBuffers()`.

### 4.8 fsm-4-3 — возможные дубликаты в `collectedToolCalls`

**Файл:** `SpringAgentLoopActions.java:265-267`.
```java
if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
    collectedToolCalls.addAll(output.getToolCalls());
}
```
Если Spring AI отправит один и тот же tool call в нескольких chunk'ах (редко, но возможно при некоторых backend'ах), в список попадут дубликаты. Серьёзность: **LOW**. Дедупликация по `id` была бы страховкой.

### 4.9 fsm-4-3 — `containsToolMarker` сканирует буфер на каждый chunk

**Файл:** `TelegramMessageHandlerActions.java:468`. Сканирование O(n·m), где n = длина буфера, m ≈ 8 маркеров. На буферах в несколько тысяч символов и частых chunk'ах — заметный CPU-overhead. Серьёзность: **LOW-MEDIUM**. Рекомендуется `Aho-Corasick` или хотя бы ленивое вычисление (не скан каждый chunk, а только после `\n\n`).

### 4.10 fsm-4-3 — потеря `foldedProse` при двойном fail

**Файл:** `TelegramMessageHandlerActions.java:~501` — если `deleteMessage` не удалось и `editHtml` тоже упал, `foldedProse` теряется и пользователь видит неполный status. Серьёзность: **LOW**. Нужен retry или запасная стратегия (например, новый bubble).

### 4.11 Обе ветки — отсутствует cancellation семантика

Ни `fsm-4-3`, ни `fsm-4-2` не обрабатывают отмену пользователем (пользователь ушёл или сессию закрыл) в середине стрима. `blockLast` продолжит сжигать токены. Серьёзность: **MEDIUM**. Нужна реактивная отмена через `Disposable`.

### 4.12 Обе ветки — ошибки в `chatModel.stream` глотаются в `onErrorResume`

Если upstream упал после нескольких chunk'ов, в fsm-4-3 — `Flux.empty()` замалчивает причину; в fsm-4-2 — в catch либо re-throw (если были chunk'и), либо fallback на `call()`. fsm-4-2 корректнее логирует, но в обоих случаях не передаёт детали ошибки в event stream к UI. Серьёзность: **LOW-MEDIUM**.

---

## 5. Где сделано лучше и почему

### В `fsm-4-3` лучше:
1. **Архитектура потокового фильтра.** `StreamingAnswerFilter` — изолированный, тестируемый автомат. В `fsm-4-2` эквивалентная логика размазана по inline-коду. Принцип: «stateless core, stateful shell» соблюдён.
2. **Рендерер как sealed-иерархия `RenderedUpdate`.** Чистые трансформации event → update, без побочных эффектов. Actions — императивная оболочка. Тесты renderer'а проще.
3. **Покрытие стриминга тестами.** В 3 раза больше assertions на streaming-сценарии (split-tags, rotation, strip-tags, streaming aggregation).
4. **Отдельные классы `TelegramBufferRotator`, `TelegramHtmlEscaper`, `ToolLabels`.** SRP соблюдён, тесты изолированы.
5. **FSM rollback-семантика `STATUS_ONLY ↔ TENTATIVE_ANSWER`.** Если модель стартовала ответ, а потом решила вызвать tool — bubble удаляется и prose складывается обратно в status. В fsm-4-2 такой явной семантики нет.

### В `fsm-4-2` лучше:
1. **Устойчивость к зависаниям:** `blockLast(Duration.ofMinutes(10))` + fallback на `chatModel.call()`.
2. **Summarization памяти:** `SummarizingChatMemory` решает проблему длинных тредов — fsm-4-3 её просто не решает. (Несмотря на баг с concurrency, решение — верное направление.)
3. **History recovery из БД:** `restoreHistoryFromPrimaryStore` — после рестарта агент не теряет контекст.
4. **URL liveness validation:** `UrlLivenessCheckerImpl` + интеграция в `WebTools` уменьшают галлюцинации URL.
5. **`AgentPromptBuilder` + `SimpleChainExecutor`:** выделение промптинга и chain-execution в отдельные компоненты — тестируемо и переиспользуемо.
6. **Rate-limited batching в Telegram:** `flushPendingProgressToTelegram(force)` + очередь чанков — корректнее соблюдает лимиты Telegram API.
7. **Расширенный `WebTools`:** error-reason constants (`REASON_TOO_LARGE`, `REASON_UNREADABLE_2XX`), structured errors, bounded reads — более product-ready.

---

## 6. Рекомендации по слиянию

Если идеал — взять лучшее из обоих:

**Из `fsm-4-3` перенести в `fsm-4-2`:**
- `StreamingAnswerFilter` + тесты — заменить ручную `sanitizeFinalAnswerText` / `stripToolCallTags` смесь.
- `RenderedUpdate` sealed-иерархия — переписать `TelegramAgentStreamRenderer` как pure-функцию.
- `TelegramBufferRotator` — заменить inline-логику во flush-методах.
- Тесты streaming/observe/raw-tool-call/strip-tags.
- FSM rollback-логика `STATUS_ONLY ↔ TENTATIVE_ANSWER`.

**Из `fsm-4-2` перенести в `fsm-4-3`:**
- `blockLast(Duration.ofMinutes(10))` + fallback на `call()`.
- `SummarizingChatMemory` — **после** исправления бага синхронизации (п. 4.2).
- `restoreHistoryFromPrimaryStore` — восстановление истории из БД.
- `UrlLivenessCheckerImpl` — **после** добавления кеша (п. 4.6).
- `AgentPromptBuilder` — выделить промпты.
- `WebTools` с расширенной обработкой ошибок + bounded reads.
- `rate-limited batching` для прогресс-сообщений Telegram.

**Исправить в любом случае:**
- Cancellation-семантика (п. 4.11).
- Propagation ошибок stream → UI (п. 4.12).

---

## 7. Сводная таблица багов

| # | Ветка | Файл | Проблема | Severity |
|---|---|---|---|---|
| 4.1 | fsm-4-3 | `SpringAgentLoopActions.java:280` | `blockLast()` без timeout/fallback | **HIGH** |
| 4.2 | fsm-4-2 | `SummarizingChatMemory.java:96-223` | Нет синхронизации, race в `get` + summarize | **HIGH** |
| 4.3 | fsm-4-2 | `SpringAgentLoopActions.java:230-276` | Race `lastResponse` vs `fullText` в async потоке | MEDIUM |
| 4.4 | fsm-4-2 | `SpringAgentLoopActions.java:~354` | `terminalChunk.getResult()` без null-guard | MEDIUM |
| 4.5 | fsm-4-2 | `MessageHandlerContext.java` | `agentProgressChunks` не синхронизирован | **HIGH** |
| 4.6 | fsm-4-2 | `UrlLivenessCheckerImpl.java:66-78` | Нет кеша, N HEAD-запросов на ответ | MEDIUM |
| 4.7 | fsm-4-2 | `WebTools.java:~190` | Возможный leak DataBuffer при cancel | MEDIUM |
| 4.8 | fsm-4-3 | `SpringAgentLoopActions.java:265-267` | Возможны дубли в `collectedToolCalls` | LOW |
| 4.9 | fsm-4-3 | `TelegramMessageHandlerActions.java:468` | `containsToolMarker` O(n·m) на chunk | LOW-MEDIUM |
| 4.10 | fsm-4-3 | `TelegramMessageHandlerActions.java:~501` | Потеря `foldedProse` при двойном fail | LOW |
| 4.11 | обе | streaming-путь | Нет cancellation-семантики при уходе пользователя | MEDIUM |
| 4.12 | обе | streaming-путь | Ошибки stream не доходят до UI как event | LOW-MEDIUM |

---

## 8. Финальная оценка

| Критерий | fsm-4-3 | fsm-4-2 |
|---|:---:|:---:|
| Чистота архитектуры | 9/10 | 6/10 |
| Функциональная полнота | 6/10 | 9/10 |
| Надёжность (timeouts, fallback) | 5/10 | 7/10 |
| Тестовое покрытие | 8/10 | 6/10 |
| Concurrency корректность | 7/10 | 4/10 |
| UX в Telegram | 8/10 | 7/10 |
| Product-readiness (URL, история, summaries) | 4/10 | 8/10 |
| **Итого (ср.)** | **6.7** | **6.7** |

Счёт формально равный, но по профилю: `fsm-4-3` — «инженерно-чистое» решение с высокой стоимостью дорабатывания фичей; `fsm-4-2` — «product-first» с долгом по concurrency и чистоте. Для merge в master предпочтительнее взять `fsm-4-3` как основу **архитектуры**, а из `fsm-4-2` портировать конкретные фичи (`SummarizingChatMemory` после фикса, `UrlLivenessChecker`, history recovery, `AgentPromptBuilder`).















# Сравнительное ревью текущих веток `open-daimon` и `open-daimon-2`

Дата ревью: 2026-04-19  
Рабочая директория основного проекта: `/path/to/open-daimon`

## 1. Область ревью

Сравнивались текущие `HEAD` двух локальных репозиториев:

| Репозиторий | Ветка | HEAD | Состояние |
|---|---:|---|---|
| `/path/to/open-daimon` | `fsm-4-2` | `c55b2a149a4b2b567cda8f290e62394a085a132c` | clean |
| `/path/to/open-daimon-2` | `fsm-4-3` | `459ae2fce1385bfa5fa8588a8e199471ef2b12a3` | clean |

Общая база для обеих веток относительно `origin/master`:

```text
d52845a76fd039996b8f89600e22cd25dd6cc30a
```

Важно: локальные remote-ссылки на соседние feature-ветки оказались неактуальными, поэтому основное сравнение делалось по двум рабочим деревьям и их текущим `HEAD`, а не по `origin/fsm-4-*`.

## 2. Методика проверки

1. Проверил состояние git и текущие ветки в обоих репозиториях.
2. Сравнил изменения каждой ветки относительно общей базы `origin/master`.
3. Сравнил рабочие деревья `open-daimon` и `open-daimon-2` напрямую.
4. Собрал оба проекта из чистых временных снапшотов `HEAD`, чтобы исключить влияние локальных `target/` и IDE-файлов.
5. Запустил тесты в обоих снапшотах.
6. Отдельно просмотрел ключевые классы агентного streaming flow, Telegram FSM, REST security config и contract tests.

## 3. Результаты сборки и тестов

| Проверка | `open-daimon` / `fsm-4-2` | `open-daimon-2` / `fsm-4-3` |
|---|---|---|
| `./mvnw -DskipTests compile` | ✅ BUILD SUCCESS | ✅ BUILD SUCCESS |
| `./mvnw test` | ❌ BUILD FAILURE | ✅ BUILD SUCCESS |
| Основная причина | 14 падений в `opendaimon-rest` / `SessionControllerContractTest` из-за Spring Security test slice | Все модули прошли |

Деталь по `open-daimon`:

- `opendaimon-common`: 261 tests, 0 failures, 2 skipped.
- `opendaimon-spring-ai`: 322 tests, 0 failures, 1 skipped.
- `opendaimon-rest`: 14 failures в `SessionControllerContractTest`.
- `opendaimon-telegram` не дошёл до запуска из-за падения предыдущего модуля.

Типичные симптомы REST-падений:

- ожидался `200/204/401`, фактически приходит `403` или `401`;
- SSE tests не стартуют async, потому что request блокируется фильтрами до controller;
- exception handler tests получают пустой content type, потому что запрос не доходит до handler.

Деталь по `open-daimon-2`:

- `opendaimon-common`: 261 tests, 0 failures, 2 skipped.
- `opendaimon-spring-ai`: 382 tests, 0 failures, 1 skipped.
- `opendaimon-rest`: 110 tests, 0 failures.
- `opendaimon-telegram`: 327 tests, 0 failures.

## 4. Краткий вердикт

Ни одну из двух веток я бы не принимал «как есть» без правок:

- `open-daimon` (`fsm-4-2`) сейчас **не merge-ready**, потому что `./mvnw test` падает. При этом в этой ветке лучше соблюдён проектный контракт Telegram agent streaming: есть отдельный `FINAL_ANSWER_CHUNK` flow, ReAct streaming отделён от progress, non-stream execution сохраняет `chatModel.call()`, есть fallback с `stream -> call`, более сильная работа с conversation history/agent memory и URL liveness.
- `open-daimon-2` (`fsm-4-3`) сейчас **лучше как инженерная база для дальнейшего доведения**, потому что тесты зелёные и Telegram rendering разложен на более чистые компоненты (`RenderedUpdate`, `TelegramBufferRotator`, `TelegramHtmlEscaper`, `ToolLabels`). Но в ней есть существенные семантические регрессии: замена `FINAL_ANSWER_CHUNK` на `PARTIAL_ANSWER` нарушает инвариант проекта, non-stream ReAct path насильно идёт через `chatModel.stream()`, max-iterations summary не сохраняется в history, а Telegram flow содержит blocking `Thread.sleep`.

Оптимальное решение — брать за основу более чистую декомпозицию `open-daimon-2`, но переносить/восстанавливать из `open-daimon` следующие вещи:

1. контракт `FINAL_ANSWER_CHUNK` для финального ответа;
2. разделение streaming и non-stream LLM вызовов;
3. fallback `chatModel.stream() -> chatModel.call()`;
4. реальное streaming-поведение `SimpleChainExecutor.executeStream()`;
5. сохранение max-iterations final answer в `ChatMemory`;
6. историю/agent memory, если это входит в продуктовый scope;
7. URL liveness, но с принудительным edit уже отправленного Telegram final message после terminal sanitization.

## 5. Ключевые отличия решений

### 5.1. Контракт streaming events

#### `open-daimon`

`AgentStreamEvent.EventType` содержит dedicated event:

```text
FINAL_ANSWER_CHUNK
```

Это соответствует проектному инварианту из `AGENTS.md`:

- `THINKING` / progress status и final answer должны быть разными Telegram messages;
- progress message нельзя перезаписывать финальным ответом;
- final answer streaming должен идти через dedicated final-answer message / `FINAL_ANSWER_CHUNK` flow.

В `open-daimon` ReAct и SimpleChain executors эмитят `FINAL_ANSWER_CHUNK`, а Telegram FSM обрабатывает эти chunks отдельно от progress status.

#### `open-daimon-2`

`FINAL_ANSWER_CHUNK` заменён на:

```text
PARTIAL_ANSWER
```

и весь Telegram flow адаптирован под tentative-answer bubble. Это удобно как обобщённая модель «текстовых дельт», но противоречит текущему project contract. Название `PARTIAL_ANSWER` размывает различие между progress/reasoning и именно final-answer stream. Внутри текущей ветки это согласовано тестами, но для проекта как целого это регрессия контракта.

#### Где лучше

Лучше сделано в `open-daimon` по correctness и совместимости с проектными инвариантами. В `open-daimon-2` лучше сделана локальная чистота Telegram renderer, но event contract выбран хуже.

### 5.2. ReAct LLM вызовы: stream vs call

#### `open-daimon`

В `SpringAgentLoopActions` есть явное разделение:

- non-stream execution использует `chatModel.call(prompt)`;
- stream execution использует `chatModel.stream(prompt)`;
- если stream недоступен или вернул пустой stream, есть fallback на `call()`.

Это правильно для провайдеров, у которых streaming может быть отключён, не реализован или вести себя иначе, чем обычный `call`.

#### `open-daimon-2`

`think()` всегда вызывает `streamAndAggregate(ctx, prompt)`, а тот всегда вызывает `chatModel.stream(prompt)`. Это касается даже обычного `AgentExecutor.execute(...)`, где caller не просил streaming.

#### Где лучше

Лучше сделано в `open-daimon`: non-stream path должен оставаться non-stream path. `open-daimon-2` рискует сломать провайдеры/моки/режимы, где `call()` работает, а `stream()` нет.

### 5.3. SimpleChain streaming

#### `open-daimon`

`SimpleChainExecutor.executeStream()` реально использует streaming:

- вызывает stream wrapper;
- батчит ответ по параграфам;
- эмитит `FINAL_ANSWER_CHUNK`;
- досылает tail, если часть финального ответа не была доставлена chunks;
- проверяет mixed tool payload в simple-chain ответе.

#### `open-daimon-2`

`SimpleChainExecutor.executeStream()` по факту вызывает `chatModel.call(...)` и затем одним terminal event отдаёт `FINAL_ANSWER`. Streaming API есть, но для simple chain оно не streaming.

#### Где лучше

Лучше сделано в `open-daimon`: метод с названием `executeStream()` действительно stream-ит пользовательский final answer. В `open-daimon-2` это UX-регрессия и потенциально нарушение ожиданий REST/Telegram streaming endpoints.

### 5.4. Telegram renderer и FSM orchestration

#### `open-daimon`

Плюсы:

- progress и final answer разведены;
- dedicated final-answer message поддерживается через `FINAL_ANSWER_CHUNK`;
- есть защита от tool payload после начатого final stream;
- есть URL sanitization финального ответа.

Минусы:

- `TelegramMessageHandlerActions` большой и сложный;
- state transitions, rendering, throttling, URL sanitization и final-answer delivery сильно переплетены;
- сложнее локально тестировать отдельные решения.

#### `open-daimon-2`

Плюсы:

- появились более мелкие компоненты:
    - `RenderedUpdate`;
    - `TelegramBufferRotator`;
    - `TelegramHtmlEscaper`;
    - `ToolLabels`;
- rendering стал более декларативным;
- есть отдельные unit tests для buffer rotation и streaming behavior;
- error observations лучше отображаются пользователю.

Минусы:

- весь flow завязан на `PARTIAL_ANSWER`, а не на проектный `FINAL_ANSWER_CHUNK`;
- в pacing есть blocking `Thread.sleep` прямо внутри обработки agent events;
- tentative-answer bubble жизненный цикл сложнее связать с жёстким инвариантом «финальный ответ отдельным сообщением».

#### Где лучше

По maintainability лучше `open-daimon-2`: компоненты меньше и их проще тестировать. По соблюдению Telegram agent streaming invariants лучше `open-daimon`.

### 5.5. Tool-call parsing и mixed output

#### `open-daimon`

Сильнее fallback для смешанного ответа модели:

- умеет вынимать user-visible text до tool payload;
- пытается восстановить tool call из raw XML/text payload;
- поддерживает несколько форм аргументов (`arg_key`, `arg_value`, `url`, `query` и т.д.);
- не даёт tool payload утечь как финальный ответ.

Риск: восстановленный tool call может получить имя `unknown_tool`; дальше он попадает в structured tool execution path, где это закончится ошибкой tool manager. Это лучше, чем показать payload пользователю, но хуже, чем явно классифицировать malformed tool call.

#### `open-daimon-2`

Fallback более строгий:

- raw tool call исполняется только если удалось найти зарегистрированное имя tool;
- args parser завязан на `<arg_key>/<arg_value>`;
- callback вызывается напрямую, без ToolCallingManager.

Это безопаснее в части unknown tool, но менее гибко: raw payload без `arg_key/arg_value`, но с понятным URL/query, может быть проигнорирован.

#### Где лучше

Для production safety чуть лучше `open-daimon-2`, потому что unknown tool не отправляется в manager как будто это валидный structured call. Для real-world model messiness лучше `open-daimon`, потому что parser терпимее к форматам.

### 5.6. Max iterations

#### `open-daimon`

При достижении лимита итераций:

- формируется понятный notice о лимите;
- делается synthesis answer;
- final answer сохраняется в conversation history;
- состояние чистится после сохранения.

#### `open-daimon-2`

При достижении лимита:

- вызывается summary model without tools;
- fallback digest есть;
- final answer выставляется в `ctx`;
- но conversation history не сохраняется.

#### Где лучше

Лучше `open-daimon`: terminal answer должен попадать в history так же, как обычный финальный ответ, иначе следующий turn теряет важный контекст.

### 5.7. Conversation history и agent memory

#### `open-daimon`

Добавлены:

- `AgentMemory` / `AgentFact`;
- `SemanticAgentMemory`;
- `CompositeAgentMemory`;
- recall memory context в system prompt;
- восстановление conversation history из DB, если `ChatMemory` пустой.

Это лучше для long-running conversations и для Telegram chats, где in-memory history может потеряться после restart.

#### `open-daimon-2`

Решение проще: ставка на `ChatMemory` без отдельного semantic memory / DB recovery layer.

#### Где лучше

По функциональности и устойчивости к restart лучше `open-daimon`. По простоте и меньшему blast radius лучше `open-daimon-2`. Если цель веток — полноценный агентный UX, я бы переносил memory/history recovery из `open-daimon`, но только после стабилизации streaming contract.

### 5.8. URL liveness

#### `open-daimon`

Есть `UrlLivenessChecker` и реализация на WebClient:

- HEAD request;
- fallback на ranged GET при `405`;
- лимит количества URL;
- stripping dead markdown/bare links.

Это полезная продуктовая функция, особенно для LLM hallucinated citations.

#### `open-daimon-2`

Такой функциональности нет.

#### Где лучше

Идея лучше в `open-daimon`, но текущая интеграция с уже streamed Telegram final answer имеет баг: если sanitization укорачивает текст, уже отправленное сообщение может не быть отредактировано. Подробнее в разделе багов.

### 5.9. REST admin security

Обе ветки добавляют Spring Security и `AdminSecurityConfig` для `/api/v1/admin/**` / `/admin`.

Ключевое отличие — тесты:

- `open-daimon` добавил `spring-boot-starter-security`, но `SessionControllerContractTest` остался без импорта `AdminSecurityConfig` и без mock `RestUserRepository`. Поэтому `@WebMvcTest` поднимает default security behavior, и обычные session endpoints в тестах блокируются 401/403 до controller.
- `open-daimon-2` исправил test slice: импортирует `AdminSecurityConfig` и мокает `RestUserRepository`, поэтому REST tests зелёные.

#### Где лучше

Лучше `open-daimon-2`, потому что security change доведён до тестовой конфигурации.

## 6. Файлы, уникальные для каждой ветки

### Есть только в `open-daimon` / `fsm-4-2`

Ключевые проектные файлы:

- `opendaimon-app/src/it/java/io/github/ngirchev/opendaimon/app/AgentStreamingTelegramProgressIT.java`
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/agent/memory/AgentFact.java`
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/agent/memory/AgentMemory.java`
- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/service/UrlLivenessChecker.java`
- `opendaimon-spring-ai/AGENT_LOOP_RESEARCH.md`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/memory/CompositeAgentMemory.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/memory/SemanticAgentMemory.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/tool/UrlLivenessCheckerImpl.java`
- дополнительные tests для prompt builder, simple-chain streaming, history recovery, mixed tool payload и URL liveness.

### Есть только в `open-daimon-2` / `fsm-4-3`

Ключевые проектные файлы:

- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/service/ParagraphBatcher.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/StreamingAnswerFilter.java`
- `opendaimon-spring-ai/src/test/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAIAgentOllamaStreamIT.java`
- `opendaimon-spring-ai/src/test/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActionsMaxIterationsTest.java`
- `opendaimon-spring-ai/src/test/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActionsObserveTest.java`
- `opendaimon-spring-ai/src/test/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActionsRawToolCallTest.java`
- `opendaimon-spring-ai/src/test/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActionsStreamingTest.java`
- `opendaimon-spring-ai/src/test/java/io/github/ngirchev/opendaimon/ai/springai/agent/StreamingAnswerFilterTest.java`
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/RenderedUpdate.java`
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramBufferRotator.java`
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramHtmlEscaper.java`
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/ToolLabels.java`
- дополнительные tests для Telegram partial-answer streaming и buffer rotation.

## 7. Найденные баги в `open-daimon` / `fsm-4-2`

### OD-1. `./mvnw test` падает в REST module после добавления Spring Security

**Severity:** blocker для merge.  
**Файлы:**

- `opendaimon-rest/pom.xml`
- `opendaimon-rest/src/test/java/io/github/ngirchev/opendaimon/rest/controller/SessionControllerContractTest.java`
- для сравнения: соответствующий тест в `open-daimon-2` уже исправлен.

**Сценарий:** запуск `./mvnw test` на чистом `HEAD` `open-daimon/fsm-4-2`.

**Факт:** `opendaimon-rest` получает 14 failures в `SessionControllerContractTest`. После добавления `spring-boot-starter-security` тестовый slice `@WebMvcTest(SessionController.class)` больше не проходит через ожидаемую controller/exception-handler логику: запросы режутся security filter chain до controller.

**Почему это баг:** ветка не проходит обязательную verification loop. REST contract tests проверяют публичные session endpoints, но после security change тестовый контекст не импортирует `AdminSecurityConfig` и не мокает `RestUserRepository`, поэтому тестируется default security behavior, а не intended application behavior.

**Как исправлять:** как минимум перенести fix из `open-daimon-2`: импортировать `AdminSecurityConfig` в `SessionControllerContractTest` и добавить `@MockitoBean RestUserRepository`. После этого снова прогнать `./mvnw test`.

### OD-2. Terminal URL sanitization может не обновить уже отправленный Telegram final answer

**Severity:** normal.  
**Файлы:**

- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/command/handler/impl/fsm/TelegramMessageHandlerActions.java`
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/command/handler/impl/fsm/MessageHandlerContext.java`

**Сценарий:** final answer уже частично или полностью отправлен в dedicated Telegram final-answer message через `FINAL_ANSWER_CHUNK`. На terminal update включается URL preview / URL sanitization, и `UrlLivenessChecker` удаляет dead markdown link или заменяет bare URL, из-за чего sanitized text становится короче или равен уже доставленной длине.

**Факт:** `replaceAgentFinalAnswerText(...)` заменяет accumulated text и clamp-ит `agentFinalAnswerDeliveredLength` до новой длины. Затем `flushPendingFinalAnswerToTelegram(...)` публикует Telegram edit только если `getAgentFinalAnswerPendingChars() > 0`. Если sanitized text стал короче, pending chars будет `0`, и edit не отправится. Пользователь останется видеть старый уже отправленный текст с dead link.

**Почему это баг:** terminal sanitization обещает убрать нерабочие ссылки, но в самом важном сценарии — когда ответ уже был streamed — sanitized content может остаться только во внутреннем state, не попав в Telegram.

**Как исправлять:** после terminal sanitization помечать final-answer message как dirty и принудительно делать edit даже при `pendingChars == 0`, если sanitized text отличается от уже опубликованного текста.

### OD-3. Восстановленный `unknown_tool` уходит в structured tool path

**Severity:** low/normal, зависит от частоты malformed tool output.  
**Файл:** `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActions.java`

**Сценарий:** модель возвращает raw tool payload, в котором есть признаки tool call, но имя tool не удалось извлечь.

**Факт:** tolerant recovery может создать tool call с именем `unknown_tool`, после чего такой tool call попадает в обычный structured execution path через `ToolCallingManager`. Это завершится ошибкой tool execution, хотя причина на самом деле — malformed model output.

**Почему это баг:** ошибка становится менее диагностируемой и может выглядеть как проблема tool infrastructure, а не как некорректный формат ответа модели.

**Как исправлять:** не создавать structured `AssistantMessage.ToolCall` с synthetic `unknown_tool`; вместо этого выставлять explicit recoverable error / observation о malformed tool payload или просить модель повторить tool call в правильном формате.

## 8. Найденные баги в `open-daimon-2` / `fsm-4-3`

### OD2-1. `PARTIAL_ANSWER` заменяет обязательный `FINAL_ANSWER_CHUNK` contract

**Severity:** high для совместимости с проектными инвариантами.  
**Файлы:**

- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/agent/AgentStreamEvent.java`
- `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActions.java`
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/command/handler/impl/fsm/TelegramMessageHandlerActions.java`

**Сценарий:** downstream consumer или модуль ожидает project-level event `FINAL_ANSWER_CHUNK`, чтобы вести dedicated final-answer Telegram message и не смешивать progress с answer.

**Факт:** в `open-daimon-2` event type называется `PARTIAL_ANSWER`, и Spring AI/Telegram code полностью перешёл на него. Это согласовано внутри ветки, но не соответствует `AGENTS.md`, где прямо зафиксировано: final answer streaming must go through `FINAL_ANSWER_CHUNK` flow.

**Почему это баг:** это не просто rename. Контракт final-answer chunks используется как граница между progress/thinking и финальным ответом. `PARTIAL_ANSWER` делает контракт менее явным и ломает совместимость с code/tests/docs, которые ожидают `FINAL_ANSWER_CHUNK`.

**Как исправлять:** вернуть `FINAL_ANSWER_CHUNK` как canonical event для финального ответа. Если нужен общий термин, можно добавить compatibility alias, но Telegram final-answer flow должен быть привязан к dedicated final-answer chunk event.

### OD2-2. Non-stream ReAct execution всегда использует `chatModel.stream()`

**Severity:** high для провайдеров или тестов без streaming support.  
**Файл:** `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActions.java`

**Сценарий:** вызывается обычный `AgentExecutor.execute(...)`, а не `executeStream(...)`; используемый `ChatModel` поддерживает `call()`, но не поддерживает `stream()` или streaming отключён для выбранного провайдера/модели.

**Факт:** `think()` вызывает `streamAndAggregate(ctx, prompt)`, а `streamAndAggregate` безусловно делает `chatModel.stream(prompt)`. Если stream пустой/сломанный, non-stream execution вернёт ошибку вроде `LLM returned an empty stream`, хотя обычный `call()` мог бы успешно ответить.

**Почему это баг:** API contract `execute(...)` не должен требовать streaming capability. Это регрессия относительно `open-daimon`, где non-stream path использует `chatModel.call(prompt)`, а streaming path имеет fallback на `call()`.

**Как исправлять:** хранить флаг streaming execution в `AgentContext` или в executor path и использовать `chatModel.call(prompt)` для non-stream. Для stream path добавить fallback `stream -> call`, если stream не дал ни одного chunk.

### OD2-3. Max-iterations final answer не сохраняется в conversation history

**Severity:** normal.  
**Файл:** `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActions.java`

**Сценарий:** ReAct loop достигает `maxIterations`, генерирует summary/fallback answer и возвращает его пользователю. Следующий user turn идёт в той же conversation.

**Факт:** `handleMaxIterations(...)` выставляет `ctx.setFinalAnswer(summary)` и вызывает `cleanup(ctx)`, но не вызывает `saveConversationHistory(ctx)`. Обычный final answer сохраняется, а max-iterations answer — нет.

**Почему это баг:** пользователь получил terminal answer, но следующий turn не увидит этот ответ в `ChatMemory`; conversation continuity ломается именно в сложном сценарии, где контекст особенно важен.

**Как исправлять:** перед `cleanup(ctx)` сохранять user+assistant exchange так же, как в обычном `answer()` path.

### OD2-4. `Thread.sleep` блокирует обработчик Telegram stream events

**Severity:** normal; становится выше при concurrent Telegram chats и tool-heavy flows.  
**Файл:** `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/command/handler/impl/fsm/TelegramMessageHandlerActions.java`

**Сценарий:** несколько Telegram пользователей одновременно запускают agent flows с tool calls/observations; `open-daimon.telegram.agent-stream-edit-min-interval-ms` больше нуля.

**Факт:** `pacedForceFlushStatus(...)` делает `Thread.sleep(throttleMs - sinceLast)` прямо в потоке обработки agent event. Это задерживает не только edit status, но и продолжение обработки stream pipeline на этом worker thread.

**Почему это баг:** throttling Telegram API не должен блокировать worker thread. При нагрузке это снижает throughput и может задерживать другие chats, особенно если executor использует ограниченный пул.

**Как исправлять:** заменить sleep на неблокирующий debounce/scheduler или на stateful throttling, где event processing продолжается, а edit планируется отдельно.

### OD2-5. `SimpleChainExecutor.executeStream()` не stream-ит ответ

**Severity:** normal для streaming UX.  
**Файл:** `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SimpleChainExecutor.java`

**Сценарий:** simple-chain executor выбран для простого вопроса, а caller использует streaming API (`executeStream`) — например Telegram или REST SSE.

**Факт:** `executeStream()` вызывает `chatModel.call(...)`, ждёт полный ответ, а потом эмитит только terminal `FINAL_ANSWER`. Пользователь не получает incremental answer chunks.

**Почему это баг:** метод называется `executeStream()`, но для одного из основных executor paths не даёт streaming behavior. Это ухудшает UX и делает поведение simple-chain inconsistent с ReAct streaming.

**Как исправлять:** перенести подход из `open-daimon`: использовать `chatModel.stream(...)`, фильтровать think/tool payload, батчить chunks и эмитить dedicated final-answer chunk events до terminal final answer.

### OD2-6. Raw tool-call fallback слишком узко парсит аргументы

**Severity:** low/normal.  
**Файл:** `opendaimon-spring-ai/src/main/java/io/github/ngirchev/opendaimon/ai/springai/agent/SpringAgentLoopActions.java`

**Сценарий:** модель возвращает raw tool call с корректным именем tool, но аргументы не в форме `<arg_key>...</arg_key><arg_value>...</arg_value>` — например JSON body, URL-only payload или `query: ...`.

**Факт:** raw parser в `tryParseRawToolCall(...)` возвращает `Optional.empty()`, если не нашёл `arg_key/arg_value`. При этом `StreamingAnswerFilter` уже умеет скрывать `<tool_call>` блоки из visible stream, поэтому пользователь может получить пустой/обрезанный ответ вместо выполнения tool или понятной ошибки формата.

**Почему это баг:** LLM output formats на практике нестабильны. Если код уже содержит fallback для raw tool calls, он должен либо поддерживать распространённые формы аргументов, либо явно возвращать malformed-tool-call error, а не молча игнорировать payload.

**Как исправлять:** добавить поддержку JSON/url/query форматов или explicit error event для malformed raw tool call.

## 9. Что сделано лучше и что брать дальше

### Брать из `open-daimon`

1. `FINAL_ANSWER_CHUNK` как canonical event contract.
2. Явное разделение `execute` и `executeStream` в ReAct path.
3. Fallback `chatModel.stream() -> chatModel.call()`.
4. Реальный SimpleChain streaming.
5. Сохранение max-iterations answer в history.
6. Agent memory / DB history recovery, если это запланированная продуктовая часть.
7. URL liveness checker, но с исправлением terminal Telegram edit.

### Брать из `open-daimon-2`

1. Разделение Telegram rendering на маленькие классы.
2. `TelegramBufferRotator` и отдельные tests для разбиения длинных сообщений.
3. `TelegramHtmlEscaper` как единая точка экранирования.
4. `RenderedUpdate` как чистый результат renderer-а.
5. Error flag для observations и более честное отображение tool failures.
6. Исправление REST security test slice.
7. Дополнительные tests для raw tool calls, observe errors, max iterations и streaming filter.

## 10. Рекомендуемый план объединения

1. Сначала привести `open-daimon-2` к проектному streaming contract:
    - вернуть `FINAL_ANSWER_CHUNK`;
    - заменить `PARTIAL_ANSWER` или сделать compatibility bridge;
    - обновить tests на canonical event.
2. Исправить ReAct non-stream path:
    - `execute()` -> `chatModel.call()`;
    - `executeStream()` -> `chatModel.stream()` с fallback.
3. Перенести SimpleChain streaming из `open-daimon`.
4. Убрать blocking `Thread.sleep` из Telegram event path.
5. Добавить сохранение max-iterations answer в history.
6. Перенести REST test fix обратно в `open-daimon`, если ветка `fsm-4-2` продолжит жить.
7. После стабилизации core streaming перенести agent memory/history recovery и URL liveness из `open-daimon`.
8. Исправить terminal URL sanitization так, чтобы Telegram final message редактировался даже если sanitized text короче уже доставленного.
9. Прогнать минимум:

```bash
./mvnw clean compile
./mvnw test
```

10. Для критичного Telegram streaming желательно дополнительно прогнать/сохранить integration сценарий:

```bash
./mvnw -pl opendaimon-app -DskipITs=false verify
```

если в окружении доступны нужные контейнеры/профили.

## 11. Итог

- По **готовности CI** сейчас выигрывает `open-daimon-2`.
- По **соответствию project-specific streaming invariants** выигрывает `open-daimon`.
- По **maintainability Telegram layer** выигрывает `open-daimon-2`.
- По **истории/памяти агента** выигрывает `open-daimon`.
- По **REST security tests** выигрывает `open-daimon-2`.

Моя рекомендация: не выбирать одну ветку целиком. Лучший результат — гибрид: `open-daimon-2` как более чистая структурная основа, но с обязательным возвратом streaming semantics и history behavior из `open-daimon`.
