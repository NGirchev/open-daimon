# Telegram streaming — rate limits (editMessageText)

## Observed incident (2026-04-17 16:01:45)

```
ERROR i.g.n.o.telegram.TelegramBot - Error editing message
TelegramApiRequestException: [429] Too Many Requests: retry after 262
```

Caused by very frequent `editMessageText` calls during agent answer streaming.
Bot API responded with a 262-second cool-down.

## Root cause

Agent streaming pipeline emits one `editMessageText` per paragraph block produced
by `ParagraphBatcher` in `TelegramMessageHandlerActions.emitAgentAnswerBlock`.
With `ParagraphBatcher.minParagraphLength = 300`, a fast LLM stream can fire
many edits per second on the same chat — Bot API throttles at roughly
1 edit/sec per chat, and bursts trigger long retry windows.

## Relevant files

- `opendaimon-common/src/main/java/io/github/ngirchev/opendaimon/common/service/ParagraphBatcher.java`
  — controls block size via `minParagraphLength` (currently 300 chars).
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/command/handler/impl/fsm/TelegramMessageHandlerActions.java`
  — `emitAgentAnswerBlock` calls `messageSender.editHtml` on every block with no throttle.
- `opendaimon-telegram/src/main/java/io/github/ngirchev/opendaimon/telegram/command/handler/impl/fsm/MessageHandlerContext.java`
  — owns the per-session `ParagraphBatcher`.

## Guidance for future changes

- Telegram Bot API: ~1 message edit per second per chat. Bursts can cost 60s–5min retry.
- Do NOT reduce `minParagraphLength` below 300 without a throttle.
- Any future "stream more responsively" request must include debounce/throttle in
  `emitAgentAnswerBlock` (min interval between edits per chat, e.g. 1–2s) or
  coalesce pending blocks into a single edit when the previous edit is still in
  flight or was too recent.
- When a 429 with `retry after N` is received, back off for at least N seconds
  for that chat — do not retry edits in the meantime.
- Consider surfacing a per-chat "last edit timestamp" on `MessageHandlerContext`
  if throttling is added.

## Design tradeoff to remember

Smaller blocks = more responsive UI = more 429 risk.
Bigger blocks = safer but less "streaming" feel.
Current value (300) was chosen for perceived streaming, but real-world runs
show it's too aggressive for fast Ollama models. Safer default when throttling
is unavailable: larger `minParagraphLength` (e.g. 600–800) or at minimum, a
1s client-side debounce on edits.
