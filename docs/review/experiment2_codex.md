Готов выдать review. Лимит API в порядке, подтверждённые проблемы найдены.

★ Insight ─────────────────────────────────────
- В этом PR одновременно выполняются две вещи: интродукция чистой провайдер-нейтральной model+view архитектуры                                                               
  (TelegramAgentStreamModel/TelegramAgentStreamView/TelegramChatPacer) и удаление вызова старой системы. Однако сам код старой системы не удалён — около 600 строк в             
  TelegramMessageHandlerActions стали мёртвыми.
- В Spring @Bean-методы создают singleton по умолчанию, поэтому любое мутабельное поле в bean-классе становится разделяемым между чатами. Это легко пропустить в коде, который
  раньше держал состояние в request-scoped MessageHandlerContext.
- Добавление новой константы в enum не делает её user-visible — нужно ещё связать её с FSM-переходом или error-рендером, иначе она просто заполняется и игнорируется.          
  ─────────────────────────────────────────────────

Review PR #28 (Stream By Codex) против fsm-5

Что делает PR

Один коммит 6cf4af5, +1186 / −466 строк. Перепиливает Telegram-вьюшку агентского стрима:

1. Вводит провайдер-нейтральную модель TelegramAgentStreamModel (буферы статуса, candidate-ответа и confirmed-ответа), которую кормят AgentStreamEvent-ы.
2. Вводит TelegramAgentStreamView — рендерит снапшоты модели в Telegram (status-сообщение редактируется в месте; answer-сообщение создаётся только после
   FINAL_ANSWER/MAX_ITERATIONS).
3. Вводит per-chat пейсер TelegramChatPacer (tryReserve / reserve) для соблюдения rate-limit Telegram (1с в private, 3с в группах по умолчанию).
4. Добавляет «надёжные» send/edit (sendHtmlReliableAndGetId, editHtmlReliable) с распарсиванием retry_after из 429 в TelegramMessageSender.
5. Меняет PersistentKeyboardService.sendKeyboard — теперь он тоже резервирует слот пейсера.
6. Поведенчески: больше нет «спекулятивного» tentative-bubble; partial answer держится только в Java-модели и попадает в чат как final только после подтверждения.

  ---                                                                                                                                                                            
CRITICAL

1. TelegramAgentStreamView.statusRenderedOffset — гонка между чатами на singleton bean

opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramAgentStreamView.java:22

public final class TelegramAgentStreamView {                                                                                                                                   
...                                                                                                                                                                        
private int statusRenderedOffset;

Bean регистрируется как обычный @Bean в TelegramCommandHandlerConfig.java:241 → singleton. Поле statusRenderedOffset мутабельно, не volatile, не synchronized, и используется в
flushStatus() для вычисления среза fullHtml.substring(statusRenderedOffset). Два параллельных агентских запроса в разных чатах перезапишут друг другу offset, и в одном из  
чатов в Telegram уйдёт неправильный кусок HTML (или IndexOutOfBounds в пограничном случае). При редактировании сообщения после ротации это особенно вредно — offset обновляется
в момент рассечения, и сосед может получить не свой остаток.

Фикс: перенести statusRenderedOffset в MessageHandlerContext (request-scoped) или в саму TelegramAgentStreamModel. View должен быть stateless.
   
---                                                                                                                                                                            
HIGH

2. ~600 строк мёртвого кода в TelegramMessageHandlerActions

После замены вызова handleAgentStreamEvent на handleAgentStreamModelEvent (TelegramMessageHandlerActions.java:415) старое дерево не имеет ни одного caller-а в production:

- handleAgentStreamEvent (488)
- handlePartialAnswer (575)
- containsToolMarker, handleEmbeddedToolMarker, tailAsPlainOverlay
- applyUpdate ветки для legacy RenderedUpdate типов
- promoteTentativeAnswer (919), editTentativeAnswer (948), forceFinalAnswerEdit (987), rollbackAndAppendToolCall (996)
- finalizeAfterStream (1022), handleStreamError (1035)
- editStatusThrottled (1051), pacedForceFlushStatus (905), appendToolCallBlock, replaceTrailingThinkingLineWithEscaped, appendToStatusBuffer, rotateStatusIfNeeded
- Поле agentStreamRenderer и инжекция TelegramAgentStreamRenderer (а с ним и сам @Bean telegramAgentStreamRenderer)

Единственный «живой» обращающийся к этому хвосту код — TelegramMessageHandlerActionsTentativeEditTest, который вызывает editTentativeAnswer через reflection, плюс             
@Disabled-нутый TwoMessageOrchestration. Это значит:

- Зрительное покрытие тестами обманчивое — тесты проверяют поведение, которого больше нет в горячем пути.
- Любая правка контекста ломает компиляцию dead-кода и тратит ревью-время.
- Поведение agentStreamEditMinIntervalMs (см. ниже) тоже становится фантомным.

Согласно AGENTS.md («не оставлять half-finished implementations»), это надо убрать в этом же PR — либо уже переключаемся на model+view, либо оставляем переключатель и тогда   
это не dead-код.

3. Тесты на now-orphaned поведение продолжают исполняться

TelegramMessageHandlerActionsTentativeEditTest использует reflection (getDeclaredMethod("editTentativeAnswer", ...)) и валидирует rollback-механизм, который из реального event
flow больше не вызывается. Смесь зелёных тестов на мёртвое поведение и зелёных на новое создаёт ложную уверенность. Удалить вместе с пунктом 2.
                                                                                                                                                                                 
---                                                                                                                                          
MEDIUM

4. MessageHandlerErrorType.TELEGRAM_DELIVERY_FAILED устанавливается, но нигде не обрабатывается

grep по всему репо находит ровно два места: определение enum и setErrorType(...) в TelegramMessageHandlerActions:431. Ни FSM, ни error-renderer не маппят                      
TELEGRAM_DELIVERY_FAILED ни в локализованное сообщение, ни в специальную ветку — наружу ведёт себя как GENERAL. Либо допишите обработку (например, лог + повторная попытка /   
уведомление пользователю), либо удалите enum-значение. Кстати, при этом ещё и RuntimeException TelegramDeliveryFailedException бросается-ловится только присвоением полю, без  
выкидывания вверх.

5. agent-stream-edit-min-interval-ms стало misleading

В TelegramProperties.java:115-125 javadoc теперь утверждает, что параметр контролирует «UX phase pacing between structural agent stream transitions». Но все его три consumer-а
живут только в dead-коде из пункта 2 (pacedForceFlushStatus, editStatusThrottled, editTentativeAnswer). После очистки dead-кода — снести и эту настройку из
TelegramProperties, application.yml, application-test.yml, application-integration-test.yaml. Иначе оператор копипастит в конфиги мёртвую ручку.

6. PersistentKeyboardService.sendKeyboard теперь блокирующий

Был «отправь-и-залогируй-если-упало». Стал блокирующим до defaultAcquireTimeoutMs + intervalMs(chatId) (в группе ≈ 4000 мс). Тест                                              
sendKeyboard_waitsOneChatPacingIntervalAfterStreamBeforeSkipping это фиксирует, но в javadoc метода ничего нет. Добавьте короткое примечание про блокировку и про то, что
keyboard может быть пропущен после долгой стрим-сессии.

7. TelegramAgentStreamModel создаёт ObjectMapper per-request

TelegramAgentStreamModel.java:34-38 — конструктор по-умолчанию делает new ObjectMapper(). В Spring уже есть бин ObjectMapper (рендерер в нём же используется). Прокидывайте    
через конструктор TelegramMessageHandlerActions → модель. ObjectMapper тяжёл и thread-safe — нет смысла плодить.
                                                                                                                                                                                 
---                                                                                                                                          
LOW

8. TelegramChatPacerImpl.ChatSlot.notifyAll() на success-path

В tryReserve/reserve после успешного захвата слот сразу делает notifyAll(). Но waiter-ы при пробуждении проверят nowMs < nextAllowedAtMs (которое только что отодвинули вперёд)
и снова уйдут в wait. Wake-up здесь чистая трата CPU — notifyAll() уместен только если вы измените логику на «отдавать слот»/«отменять резерв». Удалить или добавить          
комментарий «for future cancel-aware path».

9. TelegramMessageSender.sleepForRetryAfterIfPossible блокирует поток внутри Reactor concatMap

Сейчас всё стекает в blockLast() в FSM-thread, поэтому Reactor worker не пинятся. Но Thread.sleep(retryAfterMs) до 5 с — комментарий бы тут не помешал, иначе при будущем      
переходе на честный non-blocking pipeline случайно подвесим scheduler.

10. TelegramMessageSender.parseRetryAfterSeconds объявлен public

Используется только внутри класса. Сделать private чтобы не плодить API surface.

11. TelegramAgentStreamModel.applyThinking дважды строит statusHtml.toString()

TelegramAgentStreamModel.java:107 — две инвокации toString() подряд, на больших буферах это аллокации. Сохраните результат в локалку.

12. TelegramAutoConfig.java — пустая строка перед }

Единственное изменение в файле — добавление пустой строки. Уберите шум из diff.

13. Style-несоответствие

TelegramChatPacerImpl создаётся как public class без final, без @RequiredArgsConstructor (хотя у TelegramChatPacer единственная зависимость — это TelegramProperties). Соседние
Impl-классы используют @RequiredArgsConstructor. Согласно AGENTS.md → Service Layer, это рекомендованный стиль.
                                                                                                                                                                                 
---                                                                                                                                          
Хорошее

- Чистое разделение model/view. TelegramAgentStreamModel тестируется без Telegram-моков; TelegramAgentStreamModelTest покрывает три ключевых сценария (candidate→confirmed,
  pre-tool fold, provider-neutrality). Это правильный инвариант — раньше тесты лежали через mock-edges Telegram-bot-а.
- Per-chat пейсер правильно отделён от per-request состояния, корректно использует ConcurrentHashMap.computeIfAbsent + synchronized per slot — race-condition нет.
- Обработка retry_after из TelegramApiRequestException.getParameters().getRetryAfter() с fallback на regex — прагматично; парс retry-after из текста ошибки реально нужен,     
  потому что telegrambots иногда не заполняет parameters.
- Документация (TELEGRAM_MODULE.md) обновлена синхронно с поведением — соответствует правилу из AGENTS.md.
- YAML-конфиги добавлены во все три профиля (main / test / integration-test). С Validated+@NotNull пропуск конфига сразу упадёт на старте, не молчком.

  ---                                                                                                                                                                            
Вердикт

Block. Пункт 1 (CRITICAL race на singleton bean) — реальный баг для конкурентного нагрузочного сценария. Пункты 2-3 (HIGH dead-code/dead-tests) — нужны в этом же PR, иначе  
следующий ревью утонет в шуме. Остальное — можно фоллоу-ап.

После фикса 1-3 и подчистки 4-5 PR можно мёрджить — архитектурно изменение правильное и улучшает тестируемость.    