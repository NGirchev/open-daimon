# Refactor: Move RAG documentId from thread memoryBullets to message metadata

## Problem

RAG documentId is stored in `ConversationThread.memoryBullets` as a custom-format string:
```
[RAG:documentId:abc123:filename:report.pdf]
```

This is wrong because:
- The file is attached to a **message**, not to the thread
- memoryBullets has its own purpose (conversation memory/summary)
- Custom string format requires manual parsing (`extractRagDocumentIds`)
- Stale thread objects can overwrite memoryBullets (the bug we hit with scoped threads in v13)

## Target

Store RAG documentId in `OpenDaimonMessage.metadata` (jsonb) on the USER message that had the attachment:
```json
{
  "ragDocumentId": "abc123",
  "ragFilename": "report.pdf"
}
```

## Changes

### 1. SpringAIGateway — first message (store)

**Current**: `storeDocumentIdsInThread()` → finds thread by threadKey → writes to `thread.memoryBullets` → `threadRepo.save(thread)`

**New**: Return processed documentIds from `processRagIfEnabled()` up to the handler. Handler saves them into `OpenDaimonMessage.metadata` of the USER message that was just created.

Alternative: gateway writes directly to message metadata if it has access to the message ID via `AICommand.metadata`.

### 2. SpringAIGateway — follow-up (read)

**Current**: `processFollowUpRagIfAvailable()` → finds thread → `extractRagDocumentIds(thread.getMemoryBullets())` → fetches chunks

**New**: Query messages by thread where metadata contains `ragDocumentId` → collect documentIds → fetch chunks.

Option A — gateway queries messages directly:
```java
List<OpenDaimonMessage> messagesWithRag = messageRepository
    .findByThreadAndMetadataContaining(thread, "ragDocumentId");
```

Option B — handler resolves documentIds from message history and passes them via `AICommand.metadata`:
```java
metadata.put("ragDocumentIds", "abc123,def456");
```

Option B keeps gateway decoupled from message repository.

### 3. Remove from memoryBullets

- Delete `storeDocumentIdsInThread()` method
- Delete `extractRagDocumentIds()` method
- Delete `RAG_BULLET_PREFIX`, `RAG_BULLET_FILENAME_SEPARATOR` constants
- Remove RAG-related entries from memoryBullets in existing threads (migration or lazy cleanup)

### 4. Update tests

- `SpringAIGatewayDocumentRagTest` — assertions checking memoryBullets → check message metadata
- `ImagePdfVisionRagOllamaManualIT` — assertions checking `thread.getMemoryBullets()` → check user message metadata
- Fixture tests if affected

### 5. Migration

Optional: DB migration to move existing RAG entries from `conversation_thread.memory_bullets` to corresponding `open_daimon_message.metadata`. Or just let old threads lose RAG context (acceptable for local Ollama use case).

## Benefits

- documentId is where the file is — on the message
- No more stale thread overwrite bug (the root cause of the v13 regression)
- No custom string format parsing
- memoryBullets is free for its intended use
