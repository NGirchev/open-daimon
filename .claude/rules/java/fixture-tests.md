---
paths:
  - "opendaimon-app/src/it/java/**/fixture/**"
  - "docs/usecases/**"
---
# Fixture Test Context

## Use case -> fixture test mapping

- `forwarded-message.md` -> `ForwardedMessageFixtureIT`
- `auto-mode-model-selection.md` -> `AutoModeModelSelectionFixtureIT`
- `text-pdf-rag.md` -> `TextPdfRagFixtureIT`
- `image-pdf-vision-cache.md` -> `ImagePdfVisionCacheFixtureIT`
- `agent-image-attachment.md` -> `TelegramAgentImageFixtureIT`

Before modifying fixture tests, read the corresponding use case doc from `docs/usecases/`.

Run fixture tests: `./mvnw clean verify -pl opendaimon-app -am -Pfixture`
If a fixture test fails after your change, investigate and fix before proceeding.
