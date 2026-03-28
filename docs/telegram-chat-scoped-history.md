# Telegram Chat-Scoped History and Inline UX Plan

## Summary
This plan defines a single business logic for Telegram bot interactions, with transport-specific handling:
- `message` channel (mentions/replies/commands) uses shared history scoped to `chat/group`.
- `inline_query` is not used as a dialog channel and returns an explicit instruction to use mention/reply instead.
- Progress is tracked via checklist items so work can continue across AI sessions.

## Final Decisions
- History scope for Telegram dialog is `chat.id` (group/private chat), not user id.
- Group trigger policy is `mention/reply/command only`.
- Inline is intentionally non-dialog and should return a clear user guidance message.
- Group thread control (`/history`, `/threads`, `/newthread`) is allowed for any member who passes access control.

## Progress Checklist
- [ ] Add thread scope fields to `conversation_thread` (`scope_kind`, `scope_id`) with indexes.
- [ ] Add Flyway migration for scope fields and backfill legacy rows with `scope_kind=USER`.
- [ ] Extend thread selection service to resolve active thread by `(scope_kind, scope_id)`.
- [ ] Update Telegram message flow to map all dialog requests to `TELEGRAM_CHAT` scope using `message.chat.id`.
- [ ] Keep non-Telegram channels on existing user-scoped behavior.
- [ ] Add group filter: process only mention/reply/command, ignore other group messages.
- [ ] Add mention normalization: remove self-mention `@<bot_username>` before AI call.
- [ ] Add fallback when normalized message is empty (no AI call).
- [ ] Add dedicated inline handler that always returns guidance via `AnswerInlineQuery`.
- [ ] Add i18n keys for inline-disabled guidance in `telegram_en.properties` and `telegram_ru.properties`.
- [ ] Ensure inline updates are no longer logged as unsupported warnings.
- [ ] Update `/history`, `/threads`, `/newthread` handlers to work with chat-scoped thread ownership.
- [ ] Update `opendaimon-telegram/TELEGRAM_MODULE.md` with new behavior and use cases.
- [ ] Add/adjust unit tests for routing, scope resolution, inline guidance, and group command behavior.
- [ ] Run compile and target tests for affected modules.

## Acceptance Criteria
- Group conversation memory is shared across participants through the same `chat.id` thread.
- Mention/reply/command in groups consistently use the same active group thread.
- Inline usage shows a clear, localized instruction to use mention/reply in chat.
- No ambiguity remains between inline transport and dialog business behavior.

## Notes
- Telegram `inline_query` does not provide `chat_id`, so chat-scoped memory cannot be reliably implemented for inline.
- If future product requirements change, inline can be reintroduced as stateless utility mode.