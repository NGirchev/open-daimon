# Model Selection in open-daimon (Agent Path)

## Escalation Rule
**If more than 2-3 iterations of problems on the same issue — STOP and ask the user.** Don't keep guessing.

## Two Model Resolution Paths

### 1. Gateway Path (non-agent, `SpringAIGateway`)
- `DefaultAICommandFactory` reads `preferredModelId` from metadata → creates `FixedModelChatAICommand` or `ChatAICommand`
- `SpringAIGateway.resolveModel()` → `SpringAIModelRegistry.getCandidatesByCapabilities()`
- Documented in `SPRING_AI_MODULE.md` (UC-1 through UC-18)

### 2. Agent Path (`DelegatingAgentChatModel`)
- `SpringAgentLoopActions.think()` reads `ctx.getMetadata().get(AICommand.PREFERRED_MODEL_ID_FIELD)` → sets on `ToolCallingChatOptions.model(preferredModelId)`
- `SimpleChainExecutor.buildOptions()` does the same for SIMPLE strategy
- `DelegatingAgentChatModel.call(Prompt)` → `extractPreferredModelId(prompt)` reads from `prompt.getOptions().getModel()` → `resolveModel(preferredModelId)`
- `resolveModel()` calls `registry.getCandidatesByCapabilities(Set.of(CHAT, TOOL_CALLING), preferredModelId)`
- If preferred model is in registry AND has required capabilities → it's moved to first position in candidates list
- If preferred model NOT in registry → silently falls back to default (first candidate by priority/score)

## Preferred Model Feature
- Users can set preferred model via `/model` command in Telegram
- Stored in `TelegramUser.preferredModelId` field
- Read by `UserModelPreferenceService.getPreferredModel(userId)`
- Telegram handler `TelegramMessageHandlerActions.prepareMetadata()` puts it into metadata as `AICommand.PREFERRED_MODEL_ID_FIELD` ("preferredModelId")
- Flows through entire pipeline: metadata → AgentContext → ChatOptions → DelegatingAgentChatModel

## Key Requirement: Model MUST Be Registered in YAML
- `SpringAIModelRegistry` only knows models from `open-daimon.ai.spring-ai.models.list` (yml) + OpenRouter free models (runtime refresh)
- If a model is NOT in `models.list`, the registry won't find it and preferred model silently falls back
- In tests: YAML profile must include the model. Check registry init log: `SpringAIModelRegistry initialized with N models from yml`

## Spring Config Import Gotcha
- `spring.config.import: optional:classpath:parent.yaml` gives the **imported** file **higher** priority
- This means parent's `models.list` OVERWRITES child's `models.list` — Spring Boot list properties are not merged
- Solution: make test YAML self-contained (no import) OR add model to parent YAML

## Manual Test Pattern for Explicit Model
```java
// In AgentRequest — pass model via metadata:
Map<String, String> metadata = new HashMap<>();
metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, "z-ai/glm-4.5v");
AgentRequest request = new AgentRequest(task, conversationId, metadata, maxIter, Set.of(), strategy);

// In E2E through Telegram handler — set on user:
TelegramUser user = telegramUserRepository.findByTelegramId(chatId).orElseThrow();
userModelPreferenceService.setPreferredModel(user.getId(), "z-ai/glm-4.5v");
```

## Debugging Model Selection
1. Check registry log: `SpringAIModelRegistry initialized with N models from yml` — is your model listed?
2. Check `DelegatingAgentChatModel` log: `resolved model='X' (provider=Y, preferred='Z')` — does preferred match resolved?
3. If `preferred != resolved` → model not in registry or lacks required capabilities (CHAT + TOOL_CALLING for agent)

## Stale Bytecode (failsafe)
- `maven-failsafe-plugin` resolves classpath from local Maven repo (`~/.m2/repository/`), NOT from `target/classes`
- After adding new enum values, fields, or methods: run `mvn clean install -DskipTests` before running IT tests
- Symptom: `NoSuchFieldError`, `NoSuchMethodError` on classes that clearly have the field/method in source
