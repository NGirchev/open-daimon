# Experiment 2 — Сравнение моделей: 5-3 vs 5-5

## TL;DR

- **5-3** имеет реальную интеграцию: `TelegramMessageHandlerActions` переписан,
  новые beans (`TelegramAgentStreamModel`, `View`, `ChatPacer`, reliable `Sender`)
  подключены к pipeline. Юзер видит per-chat pacing + retry-after auto-recovery.
  Ветка содержит CRITICAL race в singleton view и около 600 строк мёртвого кода —
  оба дефекта исправимы в существующих файлах.
- **5-5** имеет более чистый design (`AssistantTurn` как domain-объект,
  `TelegramRateLimitedBot` как блокирующий фасад by construction), **но не
  интегрирован**: `git diff fsm-5..fsm-5-5` показывает 0 строк в `command/`,
  в auto-config'ах и в `application*.yml`. Production-flow от Telegram update
  до ответа юзеру в 5-5 идентичен `fsm-5`. Вклад в решение задачи 429 — нулевой.
- **Для merge брать 5-3.** Идеи 5-5 (`AssistantTurn`, blocking facade, virtual-clock
  тесты) подбирать в следующую итерацию архитектуры как отдельную работу.

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

### Решающий факт: 5-5 не интегрирован в production pipeline

Это главное, что нужно понимать про 5-5 при оценке вклада. Все три новых класса
существуют только как изолированные файлы. Проверка по `git diff fsm-5..fsm-5-5`:

| Что должно быть изменено для интеграции | Что фактически изменено |
|---|---|
| `TelegramMessageHandlerActions.java` (центр pipeline) | 0 строк |
| `TelegramAutoConfig.java` / `TelegramCommandHandlerConfig.java` / `TelegramServiceConfig.java` | 0 строк |
| `application.yml` / `application-test.yml` / `application-integration-test.yaml` | 0 строк |
| `TelegramProperties.java` | +35 строк (новый nested класс `RateLimit`) |
| Новые классы (`AssistantTurn`, `TelegramAssistantTurnView`, `TelegramRateLimitedBot`) | +628 строк |
| Тесты | +751 строка |

`TelegramRateLimitedBot` ни в одном `@Bean` не создаётся, `AssistantTurn` нигде не
инстанцируется в горячем пути, `TelegramAssistantTurnView` не подключён к
`onChange` ни одного реального `AssistantTurn`. Production-flow от Telegram update
до ответа юзеру в 5-5 идентичен `fsm-5`: всё ещё работают `handleAgentStreamEvent`,
tentative-bubble логика, старый `TelegramMessageSender` без rate-limit.

Юзер, отправивший сообщение боту на 5-5, увидит ровно то же поведение и получит
429 при тех же условиях, что на базе `fsm-5`. Фактический вклад 5-5 в решение
исходной задачи — **нулевой**.

Маркер от автора: в `TODO.md` на ветке 5-5 явная заметка
*«Out of scope. Telegram outbound-queue refactor (`fsm-5-5-assistant-turn-view`)
— orthogonal, do not mix the two in one PR»* — то есть автор сам классифицирует
эту ветку как незавершённый refactor, а не как готовый PR.

Регрессионный риск ненулевой: `TelegramProperties.RateLimit` помечен `@Validated`
с `@Min/@Max`, поэтому если оператор пропишет в `application.yml`
`globalPerSecond: 99`, приложение упадёт на старте — несмотря на то, что лимит
никем не используется в runtime.

### Компоненты (как написаны, не как подключены)

| Компонент | Роль |
|---|---|
| `AssistantTurn` (139 стр.) | domain-объект «один ход агента», single-writer, lifecycle `STREAMING → SETTLED / ERROR`, `setOnChange` callback для подписки view |
| `TelegramAssistantTurnView` (251 стр.) | реконсилит `AssistantTurn` в status bubble + answer bubble[s] на каждый `onChange` |
| `TelegramRateLimitedBot` (238 стр.) | синхронный блокирующий фасад над `TelegramBot`. Каждый `sendMessage`/`editMessage`/`deleteMessage` ждёт per-chat + global slot, потом делает сетевой вызов |

Задуманная защита 429: by construction. Путь, который мог бы выпустить burst, не
существует — caller блокируется до тех пор, пока оба окна (per-chat и global) не
свободны. Если ждать дольше `maxAcquireWaitMs` (по умолчанию 60с) — fail-stop,
метод возвращает `null` / `false`, чтобы зависание не корраптило Reactor pipeline.

Квоты (валидируются при старте, не используются в runtime):
- private chat (`chatId > 0`) — `privateChatPerSecond`, дефолт 1/s
- group/supergroup (`chatId < 0`) — `groupChatPerMinute`, дефолт 20/min
- per-bot global cap — `globalPerSecond`, дефолт 30/s

### Сильные стороны (только как design)

- Domain-driven: `AssistantTurn` отражает бизнес-понятие «один ход агента», а не транспортный stream.
- Простота защиты 429: один блокирующий фасад с двумя квотами — нечего собирать из четырёх слоёв.
- Тестируемость на уровне дизайна: `TelegramRateLimitedBot` принимает `LongSupplier clock` + `Sleeper sleeper`, что позволяет virtual time в unit-тестах rate-limit поведения. Переиспользуемый паттерн.
- `TelegramAssistantTurnEndToEndTest` (250 строк) драйвит реальный стек View+RateLimitedBot+AssistantTurn на mocked `TelegramBot`. «End-to-end» здесь — относительно стека из трёх классов, а не относительно production pipeline.

### Слабые стороны школы 5-5

- **Главное:** ничего из перечисленного не подключено в `TelegramMessageHandlerActions`. Не «дефект кода», а «PR не сделан до конца».
- **P1 — race в порядке резервации** (`TelegramRateLimitedBot:179`, по `experiment2_claude.md`). Per-chat slot бронируется до ожидания глобального; пока caller спит на global queue, per-chat-окно успевает истечь, и следующий вызов снова получает per-chat slot — два реальных вызова уходят back-to-back и могут вызвать 429. Фикс: резервировать per-chat slot ПОСЛЕ выхода из global wait. Дефект существует только в задуманном пути, в production не проявляется (потому что путь не подключён).
- **P2 — stale answer chunks** (`TelegramAssistantTurnView:180-194`). Если streamed partial answer уже открыл несколько answer-сообщений, а финальный layout короче, цикл редактирует только нужный префикс и не удаляет лишние Telegram-сообщения. В production не проявляется (view не используется).
- **P2 — превышение лимита 4096 символов в status bubble** (`TelegramAssistantTurnView:147`). В SHOW_ALL режиме `renderStatus()` возвращает весь transcript одним сообщением. Финальные ответы режутся по `maxMessageLength`, статус — нет. В production не проявляется.
- Блокировка caller'а может задушить event loop, если бот работает в reactive-контексте.

## Сравнение по критериям

Сравнение разделено на два измерения: «как design» (если бы оба PR были одинаково
интегрированы) и «как PR» (фактический вклад в продукт). Это разделение
существенно — потому что у 5-5 design без интеграции, и победители в двух
таблицах разные.

### A. Design (концептуальный)

| Критерий | 5-3 (v1 + v2) | 5-5 | Победитель |
|---|---|---|---|
| Концептуальная простота | 4 слоя (Model+View+Pacer+Sender) или async-dispatcher с coalescing | 2 узла (`AssistantTurn` + `RateLimitedBot`) | 5-5 |
| Защита от 429 (как задумано) | оптимистическая, через интервалы + retry-after | by-construction блокировка | 5-5 |
| Domain-driven дизайн | Model названа по транспорту (Stream) | `AssistantTurn` — domain-понятие | 5-5 |
| Глобальная квота Telegram (как задумано) | нет в v2 / есть в v1 | есть | 5-5 (с v1 наравне) |
| Тест-паттерны | стандартные unit-тесты | virtual clock+sleeper в `TelegramRateLimitedBot` | 5-5 |
| Реактивность / non-blocking | v1: async через `CompletableFuture` | блокирующий sync | 5-3 v1 |

В измерении «design» 5-5 действительно сильнее. Но это не закрывает задачу.

### B. PR (фактический вклад)

| Критерий | 5-3 | 5-5 | Победитель |
|---|---|---|---|
| **Интеграция в production pipeline** | **есть** (`TelegramMessageHandlerActions` переписан, новые beans подключены) | **нет** (новые классы изолированы, 0 изменений в `command/` и autoconfig) | **5-3** |
| Реальная защита от 429 у юзера | ChatPacer per-chat + retry-after | как в `fsm-5`: только debounce, без global quota и retry-after | 5-3 |
| Что увидит юзер после merge | новый pipeline (status / candidate / confirmed) | то же поведение, что в `fsm-5` | 5-3 |
| Тяжесть оставшейся работы до merge | удалить ~600 строк dead code + зафиксить race в singleton view | реализовать интеграцию с нуля + зафиксить P1/P2 + переписать существующие тесты на новую модель | 5-3 |
| Регрессионный риск | мёртвые ветви в `TelegramMessageHandlerActions` могут сломать компиляцию при правках контекста | `TelegramProperties.RateLimit` валидируется при старте без потребителя — невалидный конфиг роняет приложение | сравнимо |
| Тяжесть найденных дефектов в активном пути | CRITICAL race в singleton view (`statusRenderedOffset`) | дефекты P1/P2 существуют только в неподключённом коде, в проде не проявляются | 5-3 (CRITICAL живой), 5-5 (всё дремлет) |

В измерении «PR» 5-5 даёт нулевой вклад в решение задачи. 5-3 — реальный, но
дефектный.

## Вердикт

**Для merge брать 5-3.** Решение исходной задачи (429) у пользователя
улучшается только на этой ветке. У 5-5 — концептуально более чистый дизайн,
но как вклад в продукт это spike: 1623 строки лежат на полке, юзер не видит
никаких изменений по сравнению с `fsm-5`.

Если оценивать по критерию «вклад в решение исходной задачи»:

- 5-3: дефектная, но реально работающая интеграция с per-chat pacing и retry-after.
- 5-5: design без подключения. Балл за вклад — ноль; балл за дизайн — высокий, но
  его нельзя обналичить, не сделав отдельную работу по интеграции.

Это не отменяет того, что концептуально 5-5 правильнее. Но «правильнее как идея»
не равно «полезнее как PR». При следующей итерации архитектуры стоит подобрать
из 5-5 концепции (`AssistantTurn` как domain-объект, блокирующий бот by
construction, virtual-clock-тесты) и применить их в новой ветке поверх вычищенного
5-3 — но это уже отдельная работа, не часть merge-окна для текущего фикса 429.

### Рекомендация

Двигаться на 5-3 в следующем порядке.

1. **Удалить dead code** в `TelegramMessageHandlerActions`: старое дерево
   `handleAgentStreamEvent`, `handlePartialAnswer`, `promoteTentativeAnswer`,
   `editTentativeAnswer`, `rollbackAndAppendToolCall`, `forceFinalAnswerEdit`
   и связанные. Удалить тесты, которые их валидируют через reflection
   (`TelegramMessageHandlerActionsTentativeEditTest`).
2. **Зафиксить CRITICAL race** в `TelegramAgentStreamView.statusRenderedOffset`:
   вынести поле из singleton-bean в `MessageHandlerContext` (request-scoped) или
   в саму `TelegramAgentStreamModel`. View должен стать stateless.
3. **Удалить misleading-настройку** `agent-stream-edit-min-interval-ms` из
   `TelegramProperties` и всех `application*.yml` после удаления dead-code,
   потому что её consumer'ы живут только в удаляемых ветках.
4. **Решить судьбу** `MessageHandlerErrorType.TELEGRAM_DELIVERY_FAILED`: либо
   связать его с FSM-переходом и локализованным сообщением, либо удалить.
5. **Прокинуть `ObjectMapper`** через конструктор `TelegramAgentStreamModel`
   вместо `new ObjectMapper()` per-request.

5-5 на этом этапе можно либо удалить как `spike` (если идеи будут реализованы
в новой ветке), либо оставить как reference для следующей итерации архитектуры.
Самостоятельной merge-ценности у неё нет.

5-3 после этих фиксов закрывает исходную задачу 429 на уровне per-chat pacing
+ retry-after, что заметно лучше базы `fsm-5`. Глобальная квота Telegram
(≈30 msg/s на бот) при этом остаётся незакрытой — это отдельный TODO,
кандидат на следующую итерацию (где как раз можно подобрать `TelegramRateLimitedBot`
из 5-5 как готовый компонент).
