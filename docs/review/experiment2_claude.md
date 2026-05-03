• The new rate limiter can still violate per-chat Telegram quotas under global saturation, and the assistant turn view leaves stale answer messages and can exceed Telegram's
status-message size limit. These are user-visible correctness issues in the added functionality.

Full review comments:

- [P1] Reserve the chat slot at the actual send time — opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramRateLimitedBot.java:179
  When the global quota is saturated, this advances nextAllowedAtMs before awaitGlobalSlot() may block. A first request can wait in the global queue and then send much later,
  while the next request for the same private/group chat immediately passes the per-chat check because the interval expired during the global wait, so two actual Telegram calls
  can still be emitted back-to-back and trigger the 429 this facade is meant to prevent.
- [P2] Remove stale answer chunks when the final layout shrinks — opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramAssistantTurnView.java:180-194
  If a streamed partial answer has already opened multiple answer messages and a later consolidated FINAL_ANSWER is shorter, or the turn enters ERROR, desiredAnswers.size() can
  become smaller than answerMessageIds.size(). This loop only edits/sends the desired prefix and never deletes or clears the extra Telegram messages, leaving stale partial
  chunks visible after the final reconcile.
- [P2] Keep the status bubble within Telegram's message limit — opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/service/TelegramAssistantTurnView.java:147
  In SHOW_ALL mode, or with many/large tool calls, renderStatus() returns the entire accumulated transcript as one Telegram message while only final answers are split by
  maxMessageLength. Once the status HTML exceeds Telegram's 4096-character limit, sendMessage/editMessage fails and the live status either never appears or stops updating for
  long turns.