---
name: RAG storage refactor — from thread memoryBullets to message metadata
description: Decision to move RAG documentId storage from ConversationThread.memoryBullets to OpenDaimonMessage.metadata
type: project
---

RAG documentIds are now stored in `OpenDaimonMessage.metadata["ragDocumentIds"]` (comma-separated string) on the USER message that had the document attachment, not in `ConversationThread.memoryBullets`.

**Why:** memoryBullets has its own purpose (conversation summary). The old custom-format string `[RAG:documentId:...:filename:...]` was fragile and caused a v13 regression when stale thread objects overwrote memoryBullets.

**How to apply:**
- `SpringAIGateway` no longer depends on `ConversationThreadRepository`. After processing documents, it writes `ragDocumentIds` and `ragFilenames` into the mutable `AICommand.metadata` map.
- The **handler** (e.g. `MessageTelegramCommandHandler`) must call `OpenDaimonMessageService.updateRagMetadata(userMessage, documentIds, filenames)` after the gateway call to persist the ids.
- For follow-up messages, the handler must call `OpenDaimonMessageService.findRagDocumentIds(thread)` and inject the result into `AICommand.metadata["ragDocumentIds"]` before calling the gateway.
- `AICommand.RAG_DOCUMENT_IDS_FIELD = "ragDocumentIds"` and `AICommand.RAG_FILENAMES_FIELD = "ragFilenames"` are the constants to use.
- The handler update (wiring gateway output → message metadata → follow-up injection) is a **pending TODO** — not yet done.
