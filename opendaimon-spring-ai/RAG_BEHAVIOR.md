# RAG Behavior Documentation

This document describes the expected behavior of the RAG (Retrieval-Augmented Generation) system.

## Business Scenarios

### Scenario 1: PDF with Text Layer

**Description:** User uploads a PDF document that contains selectable text.

**Expected Behavior:**

1. **Request 1 (with PDF attachment):**
   - System extracts text from PDF using PDFBox
   - Text is chunked into smaller pieces (configurable chunkSize/chunkOverlap)
   - Each chunk is converted to an embedding vector using the configured embedding model
   - Vectors are stored in the VectorStore (SimpleVectorStore in-memory)
   - Augmented prompt is created with relevant chunks
   - LLM processes the request with document context

2. **Request 2 (follow-up, no attachments):**
   - User sends a follow-up question
   - System performs semantic search in VectorStore
   - Relevant chunks are retrieved based on similarity
   - Augmented prompt is created with retrieved context
   - LLM processes the request with context from the original PDF

**Debug Logs Expected:**
```
RAG: Processing new document attachments, chunking and indexing to VectorStore
RAG: Performing semantic search in VectorStore (topK=3, threshold=0.7)
RAG: Semantic search found N relevant chunks
```

### Scenario 2: PDF without Text Layer (Image-only/Scanned)

**Description:** User uploads a PDF that is a scanned document or photo (no selectable text).

**Expected Behavior:**

1. **Request 1 (with PDF attachment):**
   - System attempts to extract text from PDF
   - `DocumentContentNotExtractableException` is thrown (no text layer)
   - System falls back to Vision mode:
     - PDF pages are rendered as images
     - Images are sent to Vision-capable LLM
     - LLM extracts text/information from images
   - **Critical:** The extracted information is included in the response AND saved to chat history
   - When user continues conversation, LLM can see the context from history

2. **Request 2 (follow-up, no attachments):**
   - User sends a follow-up question
   - System checks for attachments (none)
   - System searches VectorStore for previous documents
   - **If VectorStore is empty (no text was extracted):**
     - System returns original query without RAG context
     - **But** LLM has context from chat history (previous response with extracted info)
   - LLM processes request using conversation history

**Debug Logs Expected:**
```
RAG: Processing new document attachments, chunking and indexing to VectorStore
PDF 'file.pdf' has no text layer, rendering pages as images for vision model
Added N image attachment(s) from PDF 'file.pdf' for vision model
RAG: No relevant context found in documents  # because VectorStore is empty
```

### Scenario 3: Follow-up Query with VectorStore

**Description:** User continues conversation about previously uploaded document.

**Expected Behavior:**

1. **Request (follow-up, no new attachments):**
   - System detects no attachments in current request
   - System searches VectorStore for relevant context
   - Semantic search uses embedding model to find similar chunks
   - Relevant chunks are added to prompt
   - LLM responds with context-aware answer

**Debug Logs Expected:**
```
RAG: No attachments in current request, checking for follow-up search (VectorStore)
RAG: Performing semantic search in VectorStore (topK=3, threshold=0.7)
RAG: Semantic search found N relevant chunks
```

## Configuration

### Embedding Model Selection

The embedding model is selected at application startup from `open-daimon.ai.spring-ai.models.list`:

```yaml
models:
  list:
    - name: "qwen3.5:4b"
      capabilities: [CHAT, VISION]
      provider-type: OLLAMA
      priority: 1
    - name: "nomic-embed-text:v1.5"
      capabilities: [EMBEDDING]
      provider-type: OLLAMA
      priority: 1
```

**Selection Algorithm:**
1. Find all models with EMBEDDING capability
2. Sort by index of EMBEDDING in capabilities list (lower index = higher priority)
3. Tie-break by priority field (lower number = higher priority)

**Debug Logs Expected:**
```
RAG: Available models with EMBEDDING capability: [nomic-embed-text:v1.5(provider=OLLAMA,priority=1)]
RAG: Selected embedding model 'nomic-embed-text:v1.5' (provider=OLLAMA, priority=1)
```

## Troubleshooting

### Issue: Embedding model not found (404)

**Symptom:** HTTP 404 error when trying to use embedding model.

**Cause:** The configured embedding model is not pulled in Ollama.

**Solution:**
```bash
ollama pull nomic-embed-text:v1.5
# or
ollama pull qwen3-embedding:8b
```

### Issue: VectorStore is empty on follow-up

**Symptom:** Follow-up queries return no context.

**Cause:** Either:
1. Original PDF had no text layer (scanned document)
2. Text extraction failed
3. Embedding model failed to process chunks

**Debug:**
- Check logs for "No relevant context found in documents"
- Verify embedding model is working: `ollama run nomic-embed-text "test text"`

## Test Fixtures

Test fixtures are located in `src/test/resources/fixtures/rag/`:

- `text-based.pdf` - PDF with selectable text layer
- `image-only.pdf` - Scanned PDF (image-only, no text layer)

## See Also

- [SPRING_AI_MODULE.md](./SPRING_AI_MODULE.md) - Spring AI module overview
- [RAGAutoConfig.java](./src/main/java/.../config/RAGAutoConfig.java) - RAG auto-configuration
- [FileRAGService.java](./src/main/java/.../rag/FileRAGService.java) - RAG service implementation
