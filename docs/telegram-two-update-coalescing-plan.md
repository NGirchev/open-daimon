# Telegram Two-Update Coalescing Plan

## Summary

Implement coalescing for Telegram split user intents (`first short text` + `second linked forward/media`)
so the bot sends one response instead of two.

## Progress Checklist

- [x] SA-1: Add `TelegramMessageCoalescingService` with pending-first buffer + timeout flush
- [x] SA-2: Integrate coalescing pre-step in `TelegramBot.onUpdateReceived`
- [x] SA-3: Implement merge rules (same chat/user, wait window, explicit link required)
- [x] SA-4: Build merged user text payload (`firstText + "\n\n" + secondUserText`)
- [x] SA-5: Add coalescing logs (wait/merge/no-merge/timeout)
- [x] SA-6: Add properties under `open-daimon.telegram.message-coalescing`
- [x] SA-7: Cover new behavior with unit tests
- [x] SA-8: Update `TELEGRAM_MODULE.md` behavior reference

## Configuration

- [x] `open-daimon.telegram.message-coalescing.enabled=true`
- [x] `open-daimon.telegram.message-coalescing.wait-window-ms=1200`
- [x] `open-daimon.telegram.message-coalescing.max-leading-text-length=160`
- [x] `open-daimon.telegram.message-coalescing.allow-media-second-message=true`
- [x] `open-daimon.telegram.message-coalescing.require-explicit-link=true`

## Verification

- [x] `mvn clean compile`
- [x] `mvn test -pl opendaimon-telegram -am -Dtest=TelegramMessageCoalescingServiceTest,TelegramPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`
- [x] `mvn clean test -pl opendaimon-telegram` (environment issue in this workspace: Mockito inline ByteBuddy self-attach)
