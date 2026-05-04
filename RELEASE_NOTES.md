# OpenDaimon 1.1.0 Release Notes

Draft prepared from `master` after merging `fsm` (`1b0934b`, `fsm (#19)`).

## Highlights

- Added the new FSM-based agent runtime as the main architectural change of this release.
- Added the ReAct agent cycle: think, call tools, observe results, iterate, and produce a final answer.
- Added Telegram agent-mode streaming with separate progress/status rendering and final-answer delivery.
- Added per-user Telegram `/mode` and `/thinking` controls.
- Added `opendaimon-spring-boot-starter` as the recommended integration path for external Spring Boot applications.
- Added Telegram group/supergroup chat settings, trusted group access, and chat-scoped command menu reconciliation.
- Added an admin Web UI and REST admin APIs for users, conversations, messages, and attachments.
- Changed the project license to Apache License 2.0.
- Added ArchUnit guardrails, expanded integration/manual test coverage, and module behavior documentation.

## User-Facing Changes

### Telegram

- Agent mode can now be controlled per user with `/mode`.
- Reasoning visibility can now be controlled per user with `/thinking`:
  - `SHOW_ALL` keeps reasoning in the final status transcript.
  - `HIDE_REASONING` shows transient reasoning while tools run, then keeps only tool/observation blocks.
  - `SILENT` suppresses thinking-related rendering entirely.
- Telegram agent responses use a two-message flow: an editable status/progress message and a dedicated final-answer message.
- Final answers are chunked by Telegram HTML payload size instead of raw markdown length.
- Group and supergroup chats can be represented as shared chat owners, so language, model, role, agent mode, thinking mode, and recent-model settings can be scoped to the group.
- `/model` now groups large model lists into categories:
  - `Recent`
  - `Local / Ollama`
  - `Vision`
  - `Free`
  - `All`
- `/model` now includes a recent-model category backed by persistent `user_recent_model` rows.
- Dialog-style Telegram menus now have localized cancel/close buttons, including language, role, bugreport, threads, mode, and thinking menus.
- Telegram command availability is covered by centralized feature-toggle constants.

### REST and Web UI

- Added admin endpoints under `/api/v1/admin/**`:
  - `/api/v1/admin/me`
  - `/api/v1/admin/users`
  - `/api/v1/admin/conversations`
  - `/api/v1/admin/conversations/{id}`
  - `/api/v1/admin/conversations/{id}/messages`
  - `/api/v1/admin/messages/{id}`
  - `/api/v1/admin/messages/{messageId}/attachment`
- Added an `/admin` page backed by the new admin APIs.
- Admin access is protected by session-based admin authentication.
- Existing session chat endpoints remain under `/api/v1/session`.

## Agent Runtime

- FSM is now the central shape of the agent execution path. The agent loop has explicit states and events instead of a single opaque model call.
- ReAct is the primary added agent cycle:
  - build the prompt and agent context;
  - stream model thinking/status events;
  - parse tool calls;
  - execute tools;
  - classify observations;
  - repeat until a final answer, cancellation, failure, or max-iteration fallback.
- Added the common agent contracts in `opendaimon-common`:
  - `AgentRequest`, `AgentResult`, `AgentExecutor`, `AgentStrategy`
  - stream events, loop state, tool results, and FSM state/action contracts
  - persisted agent execution and step entities
- Added Spring AI agent execution in `opendaimon-spring-ai`:
  - `ReActAgentExecutor`
  - `SimpleChainExecutor`
  - `PlanAndExecuteAgentExecutor`
  - `StrategyDelegatingAgentExecutor`
  - `AgentPromptBuilder`
  - `AgentTextSanitizer`
  - raw tool-call parsing and tool observation classification
- Added bounded streaming behavior with timeout and non-streaming fallback.
- Added final-answer cleanup so reasoning/tool-call tags are stripped before user-visible delivery and chat-memory persistence.
- Added URL liveness checking and dead-link stripping for final agent answers.
- Added defensive `fetch_url` and `web_search` behavior for unreadable pages, empty tool arguments, and structured failure reasons.
- Added image-attachment propagation through the agent path so vision-capable models keep image context across tool calls.

## Packaging and Integration

- Added `opendaimon-spring-boot-starter` with Spring Boot auto-configuration imports and low-priority OpenDaimon defaults. External consumers can use it to get OpenDaimon defaults without importing module configuration manually.
- Added `opendaimon-starter-consumer-example` to verify external consumer classpaths and starter behavior.
- Release preparation now targets `1.1.0` through the Maven `revision` property.
- The release workflow deploys with `-Drevision=<version>` and no longer rewrites POM versions during the workflow.
- Added Maven flatten support for CI-friendly versions.
- Docker logging is configured with rotation.
- Changed the project license to Apache License 2.0 and added the supporting `NOTICE` and `TRADEMARKS.md` files.

## Database Migrations

New core migrations:

- `V10__Create_agent_execution_tables.sql`
- `V11__Improve_agent_execution_tables.sql`
- `V12__Add_agent_mode_to_user.sql`
- `V13__Add_thinking_preserve_enabled_to_user.sql`
- `V14__Replace_thinking_preserve_with_thinking_mode.sql`
- `V15__Add_user_recent_model_table.sql`

New Telegram migrations:

- `V2__Add_menu_version_hash_to_telegram_user.sql`
- `V3__Create_telegram_group_table.sql`

Migration notes:

- `User.thinkingPreserveEnabled` is replaced by `User.thinkingMode`.
- Existing `thinking_preserve_enabled = true` users migrate to `SHOW_ALL`.
- Existing `thinking_preserve_enabled = false` or `NULL` users migrate to `HIDE_REASONING`.
- No existing user is migrated to `SILENT`; it is opt-in through `/thinking`.

## Public API and Compatibility Notes

This release includes public module and package-surface changes. Downstream applications that import OpenDaimon internals should review these before upgrading.

- `opendaimon-rest` moved:
  - `io.github.ngirchev.opendaimon.rest.handler.RestChatCommand`
    to `io.github.ngirchev.opendaimon.rest.command.RestChatCommand`
  - `io.github.ngirchev.opendaimon.rest.handler.RestChatCommandType`
    to `io.github.ngirchev.opendaimon.rest.command.RestChatCommandType`
- `opendaimon-telegram` moved:
  - `io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider`
    to `io.github.ngirchev.opendaimon.telegram.command.TelegramSupportedCommandProvider`
- `User` now exposes `agentModeEnabled` and `thinkingMode`.
- New public model/repository surface includes `ThinkingMode`, `UserRecentModel`, `UserRecentModelRepository`, `TelegramGroup`, and `TelegramGroupRepository`.
- External consumers can now prefer `opendaimon-spring-boot-starter` for default Spring Boot wiring, while still adding optional delivery modules such as `opendaimon-rest` explicitly.

## Configuration Notes

- New module toggle: `open-daimon.agent.enabled`.
- New agent settings include:
  - `open-daimon.agent.max-iterations`
  - `open-daimon.agent.stream-timeout-seconds`
  - `open-daimon.agent.tools.http-api.enabled`
- New Telegram command toggles include:
  - `open-daimon.telegram.commands.mode-enabled`
  - `open-daimon.telegram.commands.thinking-enabled`
- New Telegram cache toggle:
  - `open-daimon.telegram.cache.redis-enabled`
- New Spring AI URL-check settings include:
  - `open-daimon.ai.spring-ai.url-check.enabled`
  - `open-daimon.ai.spring-ai.url-check.timeout-ms`
  - `open-daimon.ai.spring-ai.url-check.max-urls-per-answer`
  - `open-daimon.ai.spring-ai.url-check.cache-ttl-minutes`

## Verification Before Tagging

Recommended release gate:

```bash
./mvnw clean verify
```

Recommended targeted checks if the full gate fails and needs narrowing:

```bash
./mvnw clean compile
./mvnw -pl opendaimon-common test
./mvnw -pl opendaimon-spring-ai test
./mvnw -pl opendaimon-telegram test
./mvnw -pl opendaimon-rest test
./mvnw -pl opendaimon-app failsafe:integration-test failsafe:verify
./mvnw -pl opendaimon-spring-boot-starter,opendaimon-rest -am install -DskipTests -DskipITs -DskipIT
mvn -f opendaimon-starter-consumer-example/pom.xml test
```

Release deploy command:

```bash
./mvnw -B -Drevision=1.1.0 clean deploy -P release,central -DskipTests
```
