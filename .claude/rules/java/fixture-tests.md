---
paths:
  - "opendaimon-app/src/it/java/**/fixture/**"
  - "docs/usecases/**"
---
# Fixture Test Context

@docs/usecases/forwarded-message.md
@docs/usecases/auto-mode-model-selection.md
@docs/usecases/text-pdf-rag.md
@docs/usecases/image-pdf-vision-cache.md

## Use case -> fixture test mapping

- `forwarded-message.md` -> `ForwardedMessageFixtureIT`
- `auto-mode-model-selection.md` -> `AutoModeModelSelectionFixtureIT`
- `text-pdf-rag.md` -> `TextPdfRagFixtureIT`
- `image-pdf-vision-cache.md` -> `ImagePdfVisionCacheFixtureIT`

Run fixture tests: `./mvnw clean verify -pl opendaimon-app -am -Pfixture`
If a fixture test fails after your change, investigate and fix before proceeding.
