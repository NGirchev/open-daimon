# Refactoring Plan: Move Vision Capability Detection Before Gateway

## Problem Statement

`ImagePdfVisionRagOllamaManualIT` revealed a critical architecture gap:

1. REGULAR user sends a **CHAT** request with a PDF attachment
2. `DefaultAICommandFactory` creates `ChatAICommand` with `CHAT` capability only (PDF is not IMAGE → no VISION added)
3. `SpringAIGateway` selects a CHAT-only model
4. **Inside the gateway** (`processOneDocumentForRag`, line 697), PDF text extraction fails → `DocumentContentNotExtractableException`
5. Gateway **internally** renders PDF to images and calls a VISION model for OCR (`extractTextFromImagesViaVision`, line 1037)
6. **Result**: REGULAR user gets VISION functionality that should be blocked by priority routing

**Root cause**: The decision "this document requires VISION" happens too late — deep inside `SpringAIGateway`, after model selection already occurred. The priority/capability check in `DefaultAICommandFactory` never gets a chance to block it.

**Secondary problem**: `SpringAIGateway` (1157 lines) has accumulated too much branching logic — document analysis, PDF rendering, vision OCR, RAG indexing — turning a "gateway" into an orchestrator. This violates SRP and makes the code hard to test and reason about.

## Target Architecture

```
BEFORE (current):
  IChatCommand → DefaultAICommandFactory → ChatAICommand(CHAT) → SpringAIGateway
                                                                      ↓
                                                              processOneDocumentForRag()
                                                                      ↓
                                                              PDF has no text? → render to images → VISION OCR ← BUG: bypasses priority

AFTER (target):
  IChatCommand → DefaultAICommandFactory
                      ↓
              IDocumentContentAnalyzer.analyze(pdf) → IMAGE_ONLY → needs VISION
                      ↓
              priority check: REGULAR cannot VISION → UnsupportedModelCapabilityException
              (or VIP/ADMIN: add VISION to required capabilities)
                      ↓
              IDocumentPreprocessor.preprocess(pdf) → renders images, runs OCR, stores RAG
                      ↓
              ChatAICommand(CHAT + VISION, preprocessed attachments, ragDocumentIds)
                      ↓
              SpringAIGateway — simple executor: model selection + chat call
```

## Interfaces in `opendaimon-common`

### 1. `IDocumentContentAnalyzer`

**Package**: `io.github.ngirchev.opendaimon.common.ai.document`

Determines what processing a document attachment requires **before** the gateway call.

```java
public interface IDocumentContentAnalyzer {
    /**
     * Analyzes document content to determine required capabilities.
     * For PDF: checks if text is extractable or if VISION is needed.
     *
     * @param attachment document attachment to analyze
     * @return analysis result with content type and required capabilities
     */
    DocumentAnalysisResult analyze(Attachment attachment);
}
```

### 2. `DocumentAnalysisResult`

**Package**: `io.github.ngirchev.opendaimon.common.ai.document`

```java
public record DocumentAnalysisResult(
    DocumentContentType contentType,
    Set<ModelCapabilities> requiredCapabilities
) {
    /** Document with extractable text — CHAT is sufficient */
    public static DocumentAnalysisResult textExtractable() {
        return new DocumentAnalysisResult(DocumentContentType.TEXT_EXTRACTABLE, Set.of(ModelCapabilities.CHAT));
    }

    /** Image-only document (scanned PDF) — requires VISION for OCR */
    public static DocumentAnalysisResult requiresVision() {
        return new DocumentAnalysisResult(DocumentContentType.IMAGE_ONLY, Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
    }

    /** Unsupported document format */
    public static DocumentAnalysisResult unsupported() {
        return new DocumentAnalysisResult(DocumentContentType.UNSUPPORTED, Set.of());
    }

    public boolean needsVision() {
        return requiredCapabilities.contains(ModelCapabilities.VISION);
    }
}
```

### 3. `DocumentContentType`

**Package**: `io.github.ngirchev.opendaimon.common.ai.document`

```java
public enum DocumentContentType {
    /** Text can be extracted directly (standard PDF, DOCX, TXT, etc.) */
    TEXT_EXTRACTABLE,
    /** No text layer — requires VISION model for OCR (scanned PDFs, image-only PDFs) */
    IMAGE_ONLY,
    /** Document format not supported */
    UNSUPPORTED
}
```

### 4. `IDocumentPreprocessor`

**Package**: `io.github.ngirchev.opendaimon.common.ai.document`

Handles the actual document preprocessing (OCR, RAG indexing) before the gateway call.

```java
public interface IDocumentPreprocessor {
    /**
     * Preprocesses a document for the AI pipeline:
     * - Text-extractable: extract text, index in RAG
     * - Image-only: render to images, OCR via VISION, index in RAG
     *
     * @param attachment document attachment
     * @param userQuery  user's question (for RAG relevance)
     * @param analysisResult result from IDocumentContentAnalyzer
     * @return preprocessing result with RAG document IDs and relevant chunks
     */
    DocumentPreprocessingResult preprocess(Attachment attachment, String userQuery, DocumentAnalysisResult analysisResult);
}
```

### 5. `DocumentPreprocessingResult`

**Package**: `io.github.ngirchev.opendaimon.common.ai.document`

```java
public record DocumentPreprocessingResult(
    String documentId,
    List<String> relevantChunks,
    /** Image attachments from PDF rendering (if vision OCR failed, used as fallback) */
    List<Attachment> imageAttachments,
    /** True if vision OCR succeeded and images are no longer needed */
    boolean visionExtractionSucceeded
) {
    public static DocumentPreprocessingResult empty() {
        return new DocumentPreprocessingResult(null, List.of(), List.of(), false);
    }
}
```

## Implementation Phases

### Phase 1: Create interfaces and models in `opendaimon-common`

**Files to create** (package `io.github.ngirchev.opendaimon.common.ai.document`):

| File | Type | Description |
|------|------|-------------|
| `IDocumentContentAnalyzer.java` | interface | Analyzes document → determines required capabilities |
| `DocumentAnalysisResult.java` | record | Analysis output: content type + required capabilities |
| `DocumentContentType.java` | enum | TEXT_EXTRACTABLE, IMAGE_ONLY, UNSUPPORTED |
| `IDocumentPreprocessor.java` | interface | Preprocesses documents (OCR, RAG indexing) |
| `DocumentPreprocessingResult.java` | record | Preprocessing output: documentId, chunks, images |

**No changes to existing files in this phase.**

### Phase 2: Implement `IDocumentContentAnalyzer` in `opendaimon-spring-ai`

**File**: `SpringDocumentContentAnalyzer.java` in `io.github.ngirchev.opendaimon.ai.springai.service`

Logic extracted from `SpringAIGateway`:
- `extractDocumentType()` (lines 799-812) — determine document type
- `DOCUMENT_TYPE_MAPPINGS` (lines 756-788) — MIME/extension mapping
- `DocumentTypeMapping` record (lines 817-832)
- For PDFs: attempt text extraction via `DocumentProcessingService.processPdf()` in **dry-run mode** (check-only, no VectorStore write)
  - If `DocumentContentNotExtractableException` → return `DocumentAnalysisResult.requiresVision()`
  - If text extracted → return `DocumentAnalysisResult.textExtractable()`
- For non-PDF documents: always return `textExtractable()` (Tika handles them)

**Key decision**: The analyzer needs to detect image-only PDFs **without** indexing into VectorStore. Options:
- **Option A**: Add `boolean dryRun` parameter to `DocumentProcessingService.processPdf()` — just validate, don't store
- **Option B**: Create a lightweight `PdfTextDetector` that only checks if PDFBox can extract text (no chunking/embedding)

**Recommended: Option B** — cleaner separation, avoids muddying `DocumentProcessingService`.

```java
/**
 * Lightweight check: can PDFBox extract meaningful text from this PDF?
 * Does NOT index into VectorStore.
 */
@Component
public class PdfTextDetector {
    public boolean hasExtractableText(byte[] pdfData) {
        // Use same logic as DocumentProcessingService.processPdf()
        // but only check text presence, don't chunk/embed
    }
}
```

### Phase 3: Implement `IDocumentPreprocessor` in `opendaimon-spring-ai`

**File**: `SpringDocumentPreprocessor.java` in `io.github.ngirchev.opendaimon.ai.springai.service`

Logic extracted from `SpringAIGateway.processOneDocumentForRag()` (lines 681-741):
- Text-extractable PDF: `documentProcessingService.processPdf()` → RAG indexing
- Image-only PDF:
  - `renderPdfToImageAttachments()` (lines 928-972)
  - `extractTextFromImagesViaVision()` (lines 1037-1095)
  - `documentProcessingService.processExtractedText()`
- Non-PDF: `documentProcessingService.processWithTika()`
- RAG relevance search: `fileRagService.findRelevantContext()`

**Also extract from gateway**:
- `renderPdfToImageAttachments()` (lines 928-972)
- `preprocessPdfPageForVisionOcr()` (lines 983-990)
- `autoContrastGray()` (lines 992-1022)
- `extractTextFromImagesViaVision()` (lines 1037-1095)
- `stripModelInternalTokens()` (lines 1101-1105)
- `isLikelyCompleteVisionExtraction()` (lines 1107-1109)
- `VISION_EXTRACTION_MAX_ATTEMPTS` and `VISION_EXTRACTION_LIKELY_COMPLETE_MIN_CHARS` constants

### Phase 4: Refactor `DefaultAICommandFactory`

**Changes to `DefaultAICommandFactory.java`**:

1. **Add dependency**: `IDocumentContentAnalyzer` (optional — null when RAG disabled)

2. **New method**: `analyzeDocumentCapabilities(List<Attachment>)`
   ```java
   private Set<ModelCapabilities> analyzeDocumentCapabilities(List<Attachment> attachments) {
       if (documentContentAnalyzer == null) return Set.of();
       return attachments.stream()
           .filter(Attachment::isDocument)
           .map(documentContentAnalyzer::analyze)
           .filter(DocumentAnalysisResult::needsVision)
           .findFirst()
           .map(DocumentAnalysisResult::requiredCapabilities)
           .orElse(Set.of());
   }
   ```

3. **Modify `addVisionIfNeeded()`**: Also check document analysis results
   ```java
   // Current: only checks IMAGE attachments
   // New: also checks PDF content analysis
   private Set<ModelCapabilities> addVisionIfNeeded(Set<ModelCapabilities> baseTypes, List<Attachment> attachments) {
       boolean hasImages = attachments.stream()
               .anyMatch(a -> a.type() == AttachmentType.IMAGE);
       Set<ModelCapabilities> documentCaps = analyzeDocumentCapabilities(attachments);

       if (hasImages || documentCaps.contains(ModelCapabilities.VISION)) {
           Set<ModelCapabilities> withVision = new HashSet<>(baseTypes);
           withVision.add(ModelCapabilities.VISION);
           return withVision;
       }
       return baseTypes;
   }
   ```

4. **Priority enforcement works automatically**: Once VISION is in `requiredCapabilities`, the tier routing determines if REGULAR users have access. If REGULAR tier's `requiredCapabilities` doesn't include VISION and no VISION-capable model is in their pool → `UnsupportedModelCapabilityException` is thrown at gateway model selection.

### Phase 5: Refactor `SpringAIGateway`

**Remove from `SpringAIGateway`** (~400 lines):

| Lines | Method/Code | Moves to |
|-------|-------------|----------|
| 505-568 | `processRagIfEnabled()` | Orchestration moves to a new `DocumentPipelineOrchestrator` or stays simplified |
| 681-741 | `processOneDocumentForRag()` | `SpringDocumentPreprocessor` |
| 756-832 | `DOCUMENT_TYPE_MAPPINGS`, `extractDocumentType()`, `DocumentTypeMapping` | `SpringDocumentContentAnalyzer` |
| 928-972 | `renderPdfToImageAttachments()` | `SpringDocumentPreprocessor` |
| 983-1022 | `preprocessPdfPageForVisionOcr()`, `autoContrastGray()` | `SpringDocumentPreprocessor` |
| 1037-1095 | `extractTextFromImagesViaVision()` | `SpringDocumentPreprocessor` (or dedicated `VisionOcrService`) |
| 1101-1109 | `stripModelInternalTokens()`, `isLikelyCompleteVisionExtraction()` | `SpringDocumentPreprocessor` |

**Keep in `SpringAIGateway`** (~750 lines → cleaner):
- Model selection logic (`executeChatWithOptions`)
- Message building (`createMessages`, `messageFromMap`, `parseContentParts`)
- User message creation (`createUserMessage`, `toMedia`)
- System message handling (`addSystemAndUserMessagesIfNeeded` — simplified)
- Attachment context building (`buildAttachmentContextMessage`)
- Follow-up RAG (`processFollowUpRagIfAvailable` — still needs FileRAGService)
- RAG query building (`buildRagAugmentedQuery`)
- Helper methods (`resolveUserPriority`, `hasUserMedia`, `preferTextOnlyModelsForTextPayload`, `countMatchingCaps`)

**Simplify `processRagIfEnabled()`**: Instead of doing full document processing, receive already-preprocessed results via `AICommand.metadata()` or a new field in `ChatAICommand`.

### Phase 6: Wire preprocessing into the call chain

The preprocessing needs to run **after** `DefaultAICommandFactory` determines capabilities but **before** `SpringAIGateway.generateResponse()`.

**Option A — Preprocessing in factory** (recommended):
- `DefaultAICommandFactory` injects both `IDocumentContentAnalyzer` and `IDocumentPreprocessor`
- After analysis, if document needs processing, call `IDocumentPreprocessor.preprocess()`
- Store results (documentIds, preprocessed attachments) in `AICommand.metadata()` or new fields
- Pro: All pre-gateway logic in one place
- Con: Factory does more than "create command" (but it already does priority lookups)

**Option B — Preprocessing in handler layer**:
- Handler (e.g. `TelegramChatCommandHandler`) calls analyzer + preprocessor before gateway
- Pro: Factory stays pure
- Con: Logic duplicated across Telegram/REST/UI handlers

**Option C — New orchestrator service in common**:
- `AIRequestOrchestrator` wraps factory + preprocessor + gateway calls
- Handlers call orchestrator instead of factory+gateway separately
- Pro: Clean separation, no duplication
- Con: New abstraction layer

**Recommendation**: **Option A** for initial implementation (simplest change), with path to Option C if complexity grows.

### Phase 7: Update tests

1. **Unit tests for `IDocumentContentAnalyzer`**:
   - Text-extractable PDF → `TEXT_EXTRACTABLE`
   - Image-only PDF → `IMAGE_ONLY`
   - DOCX/TXT/etc. → `TEXT_EXTRACTABLE`
   - Unknown MIME type → `UNSUPPORTED`

2. **Unit tests for `DefaultAICommandFactory`** (update existing):
   - PDF attachment → `IDocumentContentAnalyzer` returns `IMAGE_ONLY` → VISION in capabilities
   - REGULAR user + image-only PDF → verify VISION is requested (will be blocked at model selection)
   - VIP user + image-only PDF → VISION in required capabilities

3. **Unit tests for `SpringDocumentPreprocessor`**:
   - Text PDF → extract + RAG index
   - Image PDF → render + OCR + RAG index
   - OCR failure → fallback to image attachments

4. **Integration test**: `ImagePdfVisionRagOllamaManualIT` should now work correctly:
   - REGULAR user blocked from image-only PDF processing (if REGULAR tier doesn't allow VISION)
   - VIP/ADMIN user gets full pipeline

## Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| PDF analysis adds latency to every PDF request | MEDIUM | `PdfTextDetector` is lightweight — only reads first few pages, no embedding |
| Breaking existing tests | HIGH | Run `./mvnw clean verify -pl opendaimon-app -am -Pfixture` after each phase |
| `IDocumentContentAnalyzer` unavailable when RAG disabled | LOW | Make it `@Nullable` in factory, skip analysis when null (same as current behavior) |
| Circular dependency: factory → analyzer → DocumentProcessingService | MEDIUM | `PdfTextDetector` is standalone (PDFBox only), no spring-ai dependencies |
| Vision OCR needs model registry access | LOW | `SpringDocumentPreprocessor` injects `SpringAIModelRegistry` directly |

## File Change Summary

### New files (Phase 1-3):

| Module | File | Type |
|--------|------|------|
| common | `common/ai/document/IDocumentContentAnalyzer.java` | interface |
| common | `common/ai/document/DocumentAnalysisResult.java` | record |
| common | `common/ai/document/DocumentContentType.java` | enum |
| common | `common/ai/document/IDocumentPreprocessor.java` | interface |
| common | `common/ai/document/DocumentPreprocessingResult.java` | record |
| spring-ai | `springai/service/PdfTextDetector.java` | class |
| spring-ai | `springai/service/SpringDocumentContentAnalyzer.java` | class |
| spring-ai | `springai/service/SpringDocumentPreprocessor.java` | class |

### Modified files (Phase 4-6):

| File | Changes |
|------|---------|
| `DefaultAICommandFactory.java` | Add `IDocumentContentAnalyzer` dependency, modify `addVisionIfNeeded()` |
| `SpringAIGateway.java` | Remove ~400 lines of document processing logic |
| Spring configuration class | Register new beans (`PdfTextDetector`, `SpringDocumentContentAnalyzer`, `SpringDocumentPreprocessor`) |

### Test files:

| File | Changes |
|------|---------|
| `PdfTextDetectorTest.java` | NEW — unit tests for text detection |
| `SpringDocumentContentAnalyzerTest.java` | NEW — unit tests |
| `SpringDocumentPreprocessorTest.java` | NEW — unit tests |
| `DefaultAICommandFactoryTest.java` | UPDATE — add PDF analysis scenarios |
| `ImagePdfVisionRagOllamaManualIT.java` | UPDATE — verify correct priority enforcement |

## Implementation Order

1. Phase 1: interfaces in common (no breaking changes)
2. Phase 2: `PdfTextDetector` + `SpringDocumentContentAnalyzer` (no breaking changes)
3. Phase 3: `SpringDocumentPreprocessor` (no breaking changes)
4. Phase 4: `DefaultAICommandFactory` refactor (behavior change — VISION now detected for PDFs)
5. Phase 5: `SpringAIGateway` cleanup (remove extracted code)
6. Phase 6: Wire up + configuration
7. Phase 7: Tests

**After each phase**: `./mvnw clean compile -pl opendaimon-common,opendaimon-spring-ai,opendaimon-app` to verify compilation.
