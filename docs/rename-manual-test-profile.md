# Rename manual test profile

## What changed

The YAML profile for manual integration tests was renamed:

- **Before**: `application-manual-ollama-e2e.yaml` (profile name: `manual-ollama-e2e`)
- **After**: `application-manual.yaml` (profile name: `manual`)

The profile is shared by all manual tests (PDF/RAG, web tool calling, etc.).

## Files to update

### 1. YAML profile

Rename: `opendaimon-app/src/it/resources/application-manual-ollama-e2e.yaml` -> `application-manual.yaml`

Changes inside the YAML:
- Added `TOOL_CALLING`, `WEB`, `SUMMARIZATION` capabilities to `qwen2.5:3b` model (matching prod config)
- Added debug logging for `SpringAIPromptFactory`, `SpringAIChatService`, `WebTools`, `WebClientLogCustomizer`

### 2. ImagePdfVisionRagOllamaManualIT

`opendaimon-app/src/it/java/.../it/manual/ImagePdfVisionRagOllamaManualIT.java`

Change `@ActiveProfiles`:
```java
// Before:
@ActiveProfiles({"integration-test", "manual-ollama-e2e"})

// After:
@ActiveProfiles({"integration-test", "manual"})
```

No other changes needed in this test.

### 3. WebToolCallingOllamaManualIT (new test)

`opendaimon-app/src/it/java/.../it/manual/WebToolCallingOllamaManualIT.java`

Change `@ActiveProfiles`:
```java
// Current (needs update):
@ActiveProfiles({"integration-test", "manual-ollama"})

// After:
@ActiveProfiles({"integration-test", "manual"})
```

### 4. Delete old file

Delete `opendaimon-app/src/it/resources/application-manual-ollama-e2e.yaml` if it still exists.
Also delete stale `opendaimon-app/target/test-classes/application-manual-ollama-e2e.yaml` (cleaned by `mvn clean`).

## New test: WebToolCallingOllamaManualIT

`opendaimon-app/src/it/java/.../it/manual/WebToolCallingOllamaManualIT.java`

- Uses `@ActiveProfiles({"integration-test", "manual"})` (same shared profile)
- Uses `MockWebServer` to stub HTTP responses for `WebTools` (no `@MockitoBean` on `WebTools` - see note below)
- Verifies that `qwen2.5:3b` invokes Spring AI tool calling when message contains a URL

Run command:
```bash
./mvnw -pl opendaimon-app -am clean test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=WebToolCallingOllamaManualIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dmanual.ollama.e2e=true \
  -Dmanual.ollama.chat-model=qwen2.5:3b
```

## Important: do NOT use @MockitoBean on WebTools

`@MockitoBean` creates a ByteBuddy proxy that loses `@Tool` annotations on methods.
Spring AI's `ChatClient.tools(object)` scans for `@Tool` via reflection and finds nothing on the mock.
Result: tools are silently not registered, model never calls them.

Use `MockWebServer` or a real `WebTools` instance with stubbed HTTP layer instead.
