# Refactoring: Move Vision Capability Detection Before Gateway

## Status: Completed

This refactoring has been fully implemented. The sections below reflect the final state of the implementation, including components added beyond the original plan.

---

## Problem Statement

`ImagePdfVisionRagOllamaManualIT` revealed a critical architecture gap:

1. REGULAR user sends a **CHAT** request with a PDF attachment
2. `DefaultAICommandFactory` created `ChatAICommand` with `CHAT` capability only (PDF is not IMAGE → no VISION added)
3. `SpringAIGateway` selected a CHAT-only model
4. **Inside the gateway** (`processOneDocumentForRag`), PDF text extraction failed → `DocumentContentNotExtractableException`
5. Gateway **internally** rendered PDF to images and called a VISION model for OCR (`extractTextFromImagesViaVision`)
6. **Result**: REGULAR user got VISION functionality that should have been blocked by priority routing

**Root cause**: The decision "this document requires VISION" happened too late — deep inside `SpringAIGateway`, after model selection had already occurred. The priority/capability check in `DefaultAICommandFactory` never got a chance to block it.

**Secondary problem**: `SpringAIGateway` (1167 lines) had accumulated too much branching logic — document analysis, PDF rendering, vision OCR, RAG indexing — turning a "gateway" into an orchestrator. This violated SRP and made the code hard to test and reason about.

---

## Implemented Architecture

```
BEFORE:
  IChatCommand → DefaultAICommandFactory → ChatAICommand(CHAT) → SpringAIGateway
                                                                      ↓
                                                              processOneDocumentForRag()
                                                                      ↓
                                                              PDF has no text? → render to images → VISION OCR ← BUG: bypasses priority

AFTER:
  IChatCommand
      ↓
  AIRequestPipeline.prepareCommand()
      ├── SpringDocumentOrchestrator.orchestrate()
      │       ├── IDocumentContentAnalyzer → PdfTextDetector → IMAGE_ONLY or TEXT_EXTRACTABLE
      │       ├── SpringDocumentPreprocessor.preprocess() → renders images, runs OCR, indexes RAG
      │       └── stores documentIds in command metadata; builds augmented query
      │
      ▼
  DefaultAICommandFactory.createCommand()
      │  Sees IMAGE attachments (from PDF rendering if OCR failed) → adds VISION capability
      │  priority check: REGULAR cannot use VISION → UnsupportedModelCapabilityException
      │  (or VIP/ADMIN: VISION in required capabilities → VISION model selected)
      ▼
  OrchestratedChatCommand(augmentedUserText, preprocessedAttachments)
      ↓
  SpringAIGateway — thin executor: model selection + chat call only
```

---

## Implemented Components

### New interfaces in `opendaimon-common`

| Interface | Package | Role |
|-----------|---------|------|
| `AIRequestPipeline` | `common.ai.pipeline` | Entry point for handlers; wraps orchestrate → factory |
| `IDocumentOrchestrator` | `common.ai.document` | Coordinates document preprocessing + RAG + follow-up RAG |
| `IDocumentPreprocessor` | `common.ai.document` | ETL preprocessing (OCR, RAG indexing) before gateway call |
| `IDocumentContentAnalyzer` | `common.ai.document` | Analyzes document → determines required capabilities |
| `OrchestratedChatCommand` | `common.ai.command` | Wrapper command substituting userText and attachments after orchestration |
| `DocumentAnalysisResult` | `common.ai.document` | Analysis output: content type + required capabilities |
| `DocumentContentType` | `common.ai.document` | `TEXT_EXTRACTABLE`, `IMAGE_ONLY`, `UNSUPPORTED` |
| `DocumentPreprocessingResult` | `common.ai.document` | Preprocessing output: documentId, chunks, image attachments |

### New implementations in `opendaimon-spring-ai`

| Class | Role |
|-------|------|
| `SpringDocumentOrchestrator` | Orchestrates preprocessing + RAG; extracted from `SpringAIGateway` |
| `SpringDocumentPreprocessor` | PDF rendering, vision OCR, Tika text extraction, RAG indexing; extracted from `SpringAIGateway` |
| `SpringDocumentContentAnalyzer` | MIME/extension type detection; extracted from `SpringAIGateway.extractDocumentType()` |
| `PdfTextDetector` | Lightweight PDFBox text presence check; no chunking/embedding |

---

## What Moved Where

| Original location in `SpringAIGateway` | Moved to |
|----------------------------------------|----------|
| `processRagIfEnabled()` | `SpringDocumentOrchestrator.orchestrate()` |
| `processFollowUpRagIfAvailable()` | `SpringDocumentOrchestrator.processFollowUpRagIfAvailable()` |
| `buildRagAugmentedQuery()` | `SpringDocumentOrchestrator` |
| `storeDocumentIdsInCommandMetadata()` | `SpringDocumentOrchestrator` |
| `processOneDocumentForRag()` | `SpringDocumentPreprocessor.preprocess()` |
| `renderPdfToImageAttachments()` | `SpringDocumentPreprocessor` |
| `extractTextFromImagesViaVision()` | `SpringDocumentPreprocessor` |
| `preprocessPdfPageForVisionOcr()`, `autoContrastGray()` | `SpringDocumentPreprocessor` |
| `stripModelInternalTokens()`, `isLikelyCompleteVisionExtraction()` | `SpringDocumentPreprocessor` |
| `extractDocumentType()`, `DOCUMENT_TYPE_MAPPINGS`, `DocumentTypeMapping` | `SpringDocumentContentAnalyzer` |

---

## Key Behavioral Changes

1. **Document orchestration happens before factory** — `AIRequestPipeline.prepareCommand()` runs `SpringDocumentOrchestrator` first, then delegates to `DefaultAICommandFactory`. The factory sees already-preprocessed attachments.

2. **VISION detection fixed** — `DefaultAICommandFactory` adds VISION capability when it sees IMAGE attachments (either from the original request or from PDF rendering if OCR fallback left images). Priority enforcement now works end-to-end: REGULAR users are blocked before model selection.

3. **Factory receives preprocessed state** — if PDF rendering succeeded but OCR failed, the factory sees IMAGE attachments and adds VISION. The gateway then selects a VISION-capable model to send the images directly.

4. **Follow-up RAG stays in orchestrator** — `SpringDocumentOrchestrator.processFollowUpRagIfAvailable()` handles follow-up queries, not the gateway.

5. **SpringAIGateway is thin** (~500 lines, was 1167):
   - Model selection (capabilities + priority)
   - Message building (system + user + media)
   - Chat execution (stream/call)
   - No document processing, no RAG logic

---

## Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| PDF analysis adds latency to every PDF request | MEDIUM | `PdfTextDetector` is lightweight — only reads first few pages, no embedding |
| Breaking existing fixture tests | HIGH | `./mvnw clean verify -pl opendaimon-app -am -Pfixture` run after each phase |
| `IDocumentContentAnalyzer` unavailable when RAG disabled | LOW | Pipeline skips orchestration when RAG is disabled; factory falls back to image-only detection |
| Circular dependency: factory → analyzer → DocumentProcessingService | MEDIUM | `PdfTextDetector` is standalone (PDFBox only), no spring-ai dependencies |
| Vision OCR needs model registry access | LOW | `SpringDocumentPreprocessor` injects `SpringAIModelRegistry` directly |
