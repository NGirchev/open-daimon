# Experiment 2 — Сравнение моделей: 5-3 vs 5-5

Сравнение двух архитектурных подходов к решению одной задачи: устранения ошибок
HTTP 429 (Too Many Requests) при отправке сообщений в Telegram во время агентского
streaming-цикла, плюс корректное отображение статуса/частичного ответа/финального
ответа в чате.

Базовая ветка обеих моделей — `fsm-5` (`b0dc300`, fsm-5-2-attachment-fix #26).
Изначальный фикс в `fsm-5` оперирует только локальным дебаунсом edit-вызовов
(`TelegramProgressBatcher`) и graceful-cut-ротацией длинного буфера
(`TelegramBufferRotator`). Глобальной квоты Telegram и retry-after-логики в нём нет.

## Хронология (25 апреля 2026)

```
17:30  fsm-5 (b0dc300)                          base
17:57  fsm-5-3-telegram-outbound-queue          checkout от fsm-5    [5-3 v1]
18:44  d073eff "Rate Limiter Codex"
       TelegramOutboundDispatcher + Impl + sender + tests
18:55  fsm-5-4-stream-aggregation               checkout от 5-3-v1
20:29  stash @fsm-5-4: "outbound queue + tests — saved before fresh start from fsm-5"
21:46  fsm-5-5-assistant-turn-view (fd271bc) "Rate Limiter Codex"   [5-5]
       AssistantTurn + TelegramAssistantTurnView + TelegramRateLimitedBot
22:08  fsm-5-3-telegram-stream-view             checkout от fsm-5    [5-3 v2]
23:02  6cf4af5 "Stream By Codex"
       TelegramAgentStreamModel + View + ChatPacer + reliable MessageSender
23:40  fsm-5-3-stream-view: review              Claude нашёл CRITICAL race
23:54  fsm-5-5: experiment2_claude.md           Codex нашёл P1 + 2× P2
```

5-3 и 5-5 написаны в один день в течение ~6 часов. 5-3 имеет две итерации
(outbound-queue → stream-view), которые здесь рассматриваются как одна школа
мысли. 5-5 — одна попытка, написанная между этими двумя итерациями 5-3.

## Школа мысли 5-3: разделение по слоям

Идея: «отделить транспорт (как именно отправить в Telegram, соблюдая лимиты) от
логики (что именно отправить)». Реализуется через несколько одноответственных
классов поверх `TelegramBot`.

### Версия 1 — `fsm-5-3-telegram-outbound-queue`

| Компонент | Роль |
|---|---|
| `TelegramOutboundDispatcher.submit(Operation) -> CompletableFuture` | async очередь отправки, per-chat |
| `Operation` с `coalescingKey`, `deadlineMs`, `retryOnRateLimit` | замена непрожитых edit'ов, deadline'ы, авто-retry |
| `TelegramOutboundDispatcherImpl` (323 строки) | per-chat queue + sliding-window глобальной квоты + drain через `ScheduledExecutorService` |
| `TelegramMessageSender` | пользовательский API, прячущий dispatcher |
| `TelegramDeliveryFailedException` | сигнализация наверх о фейле доставки |

Защита 429: реактивная — dispatcher держит окно, при 429 ретраится по retry_after,
deadline защищает от вечного ожидания, coalescing склеивает накопившиеся edit'ы.

После работы автор зафиксировал stash «saved before fresh start from fsm-5» и
переключился на параллельный эксперимент 5-5.

### Версия 2 — `fsm-5-3-telegram-stream-view`

| Компонент | Роль |
|---|---|
| `TelegramAgentStreamModel` (292 стр.) | provider-neutral state: status / candidate answer / confirmed answer |
| `TelegramAgentStreamView` (185 стр.) | рендер снапшотов модели в Telegram-сообщения |
| `TelegramChatPacer` (`tryReserve` / `reserve(timeoutMs)`) | per-chat pacing gate, 1с private / 3с group |
| `TelegramMessageSender.sendHtmlReliable... / editHtmlReliable` | парсинг retry_after из 429, до 2 попыток |

Защита 429: пассивная per-chat — pacer не пускает чаще `intervalMs(chatId)`;
reliable-методы парсят retry_after и повторяют. Глобальной квоты нет.

### Сильные стороны школы 5-3

- Чёткое разделение по слоям (Model / View / Pacer / Sender). Можно подменять каждый.
- `TelegramAgentStreamModel` (v2) провайдер-нейтральная — теоретически можно рендерить в Discord/Slack.
- Stateless утилиты (`TelegramProgressBatcher`, `TelegramBufferRotator`) хорошо изолированы.
- Coalescing edit'ов в v1 содержит правильную идею: если edit ещё в очереди, его можно заменить свежим снапшотом без двух round-trip'ов.

### Слабые стороны школы 5-3

- v1 переусложнена (futures, executor, coalescing keys) для случая, где достаточно sync-фасада.
- v2 оставляет около 600 строк мёртвого кода в `TelegramMessageHandlerActions` (старое дерево `handleAgentStreamEvent`, `handlePartialAnswer`, `promoteTentativeAnswer`, `editTentativeAnswer`, `rollbackAndAppendToolCall`, `forceFinalAnswerEdit` и связанные). Тесты `TelegramMessageHandlerActionsTentativeEditTest` дёргают это через reflection и зеленеют, создавая ложную уверенность в покрытии.
- v2 содержит CRITICAL race: `TelegramAgentStreamView.statusRenderedOffset` — обычное `int` поле без `volatile` / `synchronized` в singleton-bean (`@Bean` в `TelegramCommandHandlerConfig:241`). Два параллельных чата перезапишут offset друг другу — в Telegram уйдёт неправильный срез HTML или произойдёт `IndexOutOfBoundsException` на ротации.
- Глобальной квоты Telegram (≈30 msg/s на бот) нет в v2. В v1 есть, но v2 от неё отказался.
- `MessageHandlerErrorType.TELEGRAM_DELIVERY_FAILED` устанавливается, но не маппится ни в FSM-переход, ни в локализованное сообщение — наружу ведёт себя как GENERAL.
- `agent-stream-edit-min-interval-ms` в `TelegramProperties` стал misleading: единственные consumer'ы живут в dead-коде.
- `PersistentKeyboardService.sendKeyboard` стал blocking (до ~4с в группе) без отметки в javadoc.
- `TelegramAgentStreamModel` создаёт `new ObjectMapper()` per-request, хотя в Spring уже есть готовый bean.
- Сам факт двух итераций без слияния — индикатор, что архитектура не устоялась.

## Школа мысли 5-5: domain-объект + блокирующий фасад

Идея: «тот, кто шлёт в Telegram, не должен знать про rate limit; bot-фасад блокирует
caller'а до доступного слота, поэтому 429 не может произойти by construction».

### Компоненты

| Компонент | Роль |
|---|---|
| `AssistantTurn` (139 стр.) | domain-объект «один ход агента», single-writer, lifecycle `STREAMING → SETTLED / ERROR`, `setOnChange` callback для подписки view |
| `TelegramAssistantTurnView` (251 стр.) | реконсилит `AssistantTurn` в status bubble + answer bubble[s] на каждый `onChange` |
| `TelegramRateLimitedBot` (238 стр.) | синхронный блокирующий фасад над `TelegramBot`. Каждый `sendMessage`/`editMessage`/`deleteMessage` ждёт per-chat + global slot, потом делает сетевой вызов |

Защита 429: by construction. Путь, который мог бы выпустить burst, не существует —
caller блокируется до тех пор, пока оба окна (per-chat и global) не свободны.
Если ждать дольше `maxAcquireWaitMs` (по умолчанию 60с) — fail-stop, метод
возвращает `null` / `false`, чтобы зависание не корраптило Reactor pipeline.

Квоты:
- private chat (`chatId > 0`) — `privateChatPerSecond`, дефолт 1/s
- group/supergroup (`chatId < 0`) — `groupChatPerMinute`, дефолт 20/min
- per-bot global cap — `globalPerSecond`, дефолт 30/s

### Сильные стороны школы 5-5

- Domain-driven: `AssistantTurn` отражает бизнес-понятие «один ход агента», а не транспортный stream.
- Простота защиты 429: один блокирующий фасад с двумя квотами — нечего собирать из четырёх слоёв.
- Тестируемость на уровне дизайна: `TelegramRateLimitedBot` принимает `LongSupplier clock` + `Sleeper sleeper`, что позволяет virtual time в unit-тестах rate-limit поведения. Это правильный паттерн для time-sensitive кода.
- `TelegramAssistantTurnEndToEndTest` (250 строк) проверяет полный цикл, а не отдельные слои.
- Завершённость: один коммит, без legacy-хвостов в `TelegramMessageHandlerActions`.

### Слабые стороны школы 5-5 (по `docs/review/experiment2_claude.md`)

- **P1 — race в порядке резервации.** `TelegramRateLimitedBot:179`. Per-chat slot бронируется до ожидания глобального; пока caller спит на global queue, per-chat-окно успевает истечь, и следующий вызов снова получает per-chat slot, а потом два реальных вызова уходят back-to-back и могут вызвать тот самый 429, который фасад должен предотвращать. Фикс: резервировать per-chat slot ПОСЛЕ выхода из global wait.
- **P2 — stale answer chunks.** `TelegramAssistantTurnView:180-194`. Если streamed partial answer уже открыл несколько answer-сообщений, а финальный layout короче, цикл редактирует только нужный префикс и не удаляет лишние Telegram-сообщения — оставшиеся куски частичного ответа продолжают висеть в чате после reconcile.
- **P2 — превышение лимита 4096 символов в status bubble.** `TelegramAssistantTurnView:147`. В SHOW_ALL режиме с большим количеством tool-call'ов `renderStatus()` возвращает весь накопленный transcript одним сообщением; финальные ответы режутся по `maxMessageLength`, а статус — нет. По достижении 4096 символов `sendMessage` / `editMessage` фейлятся, и live-status либо никогда не появляется, либо перестаёт обновляться на длинных ходах.
- Блокировка caller'а может задушить event loop, если бот работает в reactive-контексте.

## Сравнение по критериям

| Критерий | 5-3 (v1 + v2) | 5-5 | Победитель |
|---|---|---|---|
| Концептуальная простота | 4 слоя (Model+View+Pacer+Sender) или async-dispatcher с coalescing | 2 узла (`AssistantTurn` + `RateLimitedBot`) | 5-5 |
| Защита от 429 | оптимистическая, через интервалы + retry-after | by-construction блокировка | 5-5 |
| Domain-driven дизайн | Model названа по транспорту (Stream) | `AssistantTurn` — domain-понятие | 5-5 |
| Тестируемость | unit-тесты модели/пейсера ОК, но есть race и dead code | virtual clock+sleeper + E2E test, но есть свои баги | 5-5 |
| Глобальная квота Telegram | нет в v2 / есть в v1 | есть | 5-5 (с v1 наравне) |
| Чистота миграции | v2 оставляет ~600 строк dead code | один коммит, без хвостов | 5-5 |
| Реактивность / non-blocking | v1: async через `CompletableFuture` | блокирующий sync | 5-3 v1 (за счёт сложности) |
| Тяжесть найденных дефектов | CRITICAL race на singleton bean (v2) + dead code | один P1 в reservation order + два P2 | 5-5 (исправимее) |

## Вердикт

**Модель 5-5 лучше.** Это более правильное направление по трём причинам.

1. **Решение цели (429) чище.** Блокирующий фасад с per-chat + global квотами —
   правильная декомпозиция: один компонент знает про лимиты Telegram, остальной
   код просто вызывает методы. В 5-3 эту ответственность размазали между Pacer'ом
   и Sender'ом, а в v2 ещё и от глобальной квоты отказались.
2. **Domain-объект вместо stream-модели.** `AssistantTurn` с lifecycle
   `STREAMING → SETTLED / ERROR` отражает суть задачи. `TelegramAgentStreamModel` —
   абстракция над транспортом, а не над domain.
3. **Завершённость и testability.** Один коммит, virtual-time тесты, end-to-end
   проверка. У 5-3 даже в v2 ещё около 600 строк нужно убрать, чтобы PR стал
   mergeable.

Дефекты 5-5 (P1 race в reservation order, два P2 в view) — точечные, исправляются
в пределах своих файлов и не требуют пересмотра архитектуры. Дефекты 5-3
(выбор архитектуры, dead code в `TelegramMessageHandlerActions`, CRITICAL race в
singleton view) — структурные и требуют больше работы.

### Рекомендация

Если бы стоял выбор «довести до merge», следует продолжать на ветке 5-5:

1. Зафиксить P1 в `TelegramRateLimitedBot:179` — резервировать per-chat slot
   только ПОСЛЕ выхода из ожидания global slot.
2. В `TelegramAssistantTurnView:180-194` удалять или очищать Telegram-сообщения,
   когда `desiredAnswers.size() < answerMessageIds.size()` после reconcile.
3. В `TelegramAssistantTurnView:147` резать status HTML по `maxMessageLength`
   так же, как режутся финальные ответы (с продолжением в дополнительные
   bubble-сообщения или с обрезкой по приоритету).

Это меньше работы и более ценный результат, чем вычистка 5-3.
