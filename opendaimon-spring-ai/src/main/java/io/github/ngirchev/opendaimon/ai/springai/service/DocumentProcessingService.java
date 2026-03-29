package io.github.ngirchev.opendaimon.ai.springai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Service for processing documents (PDF, DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT, etc.) via ETL pipeline.
 *
 * <p>ETL pipeline:
 * <ol>
 *   <li><b>Extract:</b> PagePdfDocumentReader (PDF) or TikaDocumentReader (other formats)</li>
 *   <li><b>Transform:</b> TokenTextSplitter for chunking</li>
 *   <li><b>Load:</b> VectorStore with embeddings</li>
 * </ol>
 *
 * <p>Each processed document gets a unique documentId stored in chunk metadata for search filtering.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final VectorStore vectorStore;
    private final RAGProperties ragProperties;

    /**
     * Processes PDF via PagePdfDocumentReader (PDFBox) and stores chunks in VectorStore.
     *
     * <p>PDF uses PagePdfDocumentReader (PDFBox) rather than Tika for better text extraction.
     *
     * @param pdfData PDF bytes
     * @param originalName original file name (for metadata)
     * @return documentId for later search
     */
    public String processPdf(byte[] pdfData, String originalName) {
        String documentId = UUID.randomUUID().toString();
        
        log.info("Processing PDF '{}' with documentId={} (using PagePdfDocumentReader/PDFBox)", originalName, documentId);
        
        // 1. Extract: read PDF via PagePdfDocumentReader (Apache PDFBox)
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                new ByteArrayResource(pdfData),
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1) // One page per Document
                        .build()
        );
        List<Document> documents = normalizeWhitespace(reader.read());
        logExtractedText(originalName, "PDF", documents);
        log.debug("Extracted {} pages from PDF", documents.size());

        // 2. Transform: split into chunks
        TokenTextSplitter splitter = new TokenTextSplitter(
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                5,     // minChunkSizeChars
                10000, // maxNumChunks
                true   // keepSeparator
        );
        List<Document> chunks = splitter.split(documents);
        log.debug("Split into {} chunks", chunks.size());

        if (chunks.isEmpty()) {
            String msg = String.format(ragProperties.getPrompts().getDocumentExtractErrorPdf(), originalName);
            log.warn("PDF '{}' produced no text chunks (e.g. image-only or empty pages). Throwing.", originalName);
            throw new DocumentContentNotExtractableException(msg);
        }
        
        // 3. Add metadata
        chunks.forEach(doc -> {
            doc.getMetadata().put("documentId", documentId);
            doc.getMetadata().put("originalName", originalName);
            doc.getMetadata().put("type", "pdf");
        });

        // 4. Load: save to VectorStore (embeddings generated automatically)
        vectorStore.add(chunks);
        
        log.info("Processed PDF '{}': {} chunks saved with documentId={}", 
                originalName, chunks.size(), documentId);
        
        return documentId;
    }

    /**
     * Processes document via TikaDocumentReader (DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT, etc.)
     * and stores chunks in VectorStore.
     *
     * @param documentData document bytes
     * @param originalName original file name (for metadata)
     * @param documentType document type (docx, doc, xls, xlsx, ppt, pptx, txt, etc.)
     * @return documentId for later search
     */
    public String processWithTika(byte[] documentData, String originalName, String documentType) {
        String documentId = UUID.randomUUID().toString();
        
        log.info("Processing document '{}' (type: {}) with documentId={}", originalName, documentType, documentId);
        
        // 1. Extract: read document via TikaDocumentReader.
        // Apache POI (used by Tika) may throw AssertionError on corrupted embedded objects
        // (e.g. EMF images in DOC files). On failure, retry with EmptyParser in ParseContext
        // to skip embedded content while still extracting the main document text.
        List<Document> documents;
        try {
            TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(documentData));
            documents = normalizeWhitespace(reader.read());
        } catch (AssertionError e) {
            log.warn("Tika/POI failed on embedded objects in '{}', retrying without embedded parsing: {}",
                    originalName, e.getMessage());
            documents = normalizeWhitespace(readWithTikaSkippingEmbedded(documentData, originalName));
        }
        logExtractedText(originalName, documentType, documents);
        log.debug("Extracted {} document(s) from {}", documents.size(), documentType);

        // 2. Transform: split into chunks
        TokenTextSplitter splitter = new TokenTextSplitter(
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                5,     // minChunkSizeChars
                10000, // maxNumChunks
                true   // keepSeparator
        );
        List<Document> chunks = splitter.split(documents);
        log.debug("Split into {} chunks", chunks.size());

        if (chunks.isEmpty()) {
            String msg = String.format(ragProperties.getPrompts().getDocumentExtractErrorDocument(), originalName, documentType);
            log.warn("Document '{}' (type: {}) produced no text chunks. Throwing.", originalName, documentType);
            throw new DocumentContentNotExtractableException(msg);
        }

        // 3. Add metadata
        chunks.forEach(doc -> {
            doc.getMetadata().put("documentId", documentId);
            doc.getMetadata().put("originalName", originalName);
            doc.getMetadata().put("type", documentType);
        });
        
        // 4. Load: save to VectorStore (embeddings generated automatically)
        vectorStore.add(chunks);

        log.info("Processed document '{}' (type: {}): {} chunks saved with documentId={}",
                originalName, documentType, chunks.size(), documentId);
        
        return documentId;
    }

    /**
     * Processes pre-extracted text (e.g. from vision model) and stores chunks in VectorStore.
     *
     * <p>Used when PDF has no text layer: vision model extracts text from page images,
     * and this method indexes that text for RAG, just like a normal text-based PDF.
     *
     * @param extractedText text extracted by vision model
     * @param originalName  original file name (for metadata)
     * @return documentId for later search
     */
    public String processExtractedText(String extractedText, String originalName) {
        String documentId = UUID.randomUUID().toString();

        log.info("Processing vision-extracted text for '{}' with documentId={} (length={})",
                originalName, documentId, extractedText.length());
        log.debug("Vision-extracted text content for '{}': [{}]", originalName, extractedText);

        Document document = new Document(extractedText);

        TokenTextSplitter splitter = new TokenTextSplitter(
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                5,     // minChunkSizeChars
                10000, // maxNumChunks
                true   // keepSeparator
        );
        List<Document> chunks = splitter.split(List.of(document));
        log.debug("Split vision-extracted text into {} chunks", chunks.size());

        if (chunks.isEmpty()) {
            log.warn("Vision-extracted text for '{}' produced no chunks after splitting", originalName);
            return null;
        }

        chunks.forEach(doc -> {
            doc.getMetadata().put("documentId", documentId);
            doc.getMetadata().put("originalName", originalName);
            doc.getMetadata().put("type", "pdf-vision");
        });

        vectorStore.add(chunks);

        log.info("Processed vision-extracted text for '{}': {} chunks saved with documentId={}",
                originalName, chunks.size(), documentId);

        return documentId;
    }

    /**
     * Fallback: parse document with raw Tika API, skipping embedded objects (images, OLE).
     * Used when standard TikaDocumentReader fails on corrupted embedded content (e.g. EMF in DOC).
     */
    private List<Document> readWithTikaSkippingEmbedded(byte[] data, String originalName) {
        try (InputStream stream = new ByteArrayInputStream(data)) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            context.set(Parser.class, new EmptyParser());

            parser.parse(stream, handler, metadata, context);

            String text = handler.toString();
            if (text == null || text.isBlank()) {
                return List.of();
            }
            Document doc = new Document(text);
            doc.getMetadata().put(TikaDocumentReader.METADATA_SOURCE, originalName);
            return List.of(doc);
        } catch (Exception e) {
            log.error("Fallback Tika parsing also failed for '{}': {}", originalName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Collapses runs of whitespace (spaces, tabs) within each line into a single space.
     * Preserves line breaks so that paragraph structure is not lost during chunking.
     * Returns a new list since {@link Document} text is immutable.
     */
    private static List<Document> normalizeWhitespace(List<Document> documents) {
        return documents.stream()
                .map(doc -> {
                    String text = doc.getText();
                    if (text == null || text.isEmpty()) {
                        return doc;
                    }
                    String normalized = text.lines()
                            .map(line -> line.replaceAll("[ \\t]+", " ").trim())
                            .filter(line -> !line.isEmpty())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    var cleanMetadata = new java.util.HashMap<>(doc.getMetadata());
                    cleanMetadata.values().removeIf(java.util.Objects::isNull);
                    return new Document(doc.getId(), normalized, cleanMetadata);
                })
                .toList();
    }

    private static final int EXTRACTED_TEXT_SAMPLE_MAX_LEN = 300;

    /**
     * Logs size and sample of extracted text (for diagnosing empty/image-only PDFs).
     */
    private void logExtractedText(String originalName, String documentType, List<Document> documents) {
        int totalChars = 0;
        StringBuilder sample = new StringBuilder();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text != null) {
                totalChars += text.length();
                if (sample.length() < EXTRACTED_TEXT_SAMPLE_MAX_LEN) {
                    String oneLine = text.replaceAll("\\s+", " ").trim();
                    sample.append(oneLine, 0, Math.min(oneLine.length(), EXTRACTED_TEXT_SAMPLE_MAX_LEN - sample.length()));
                    if (sample.length() >= EXTRACTED_TEXT_SAMPLE_MAX_LEN) {
                        sample.append("...");
                    }
                }
            }
        }
        log.debug("Extracted text from '{}' ({}): pages={}, totalChars={}, sample='{}'",
                originalName, documentType, documents.size(), totalChars, sample.length() > 0 ? sample : "");
        if (totalChars == 0) {
            log.warn("Document '{}' (type: {}) has no extractable text (e.g. image-only). RAG will not provide context.",
                    originalName, documentType);
        }
    }

    /**
     * Deletes all chunks of the document from VectorStore.
     *
     * @param documentId document identifier
     */
    public void deleteDocument(String documentId) {
        log.info("Deleting document with documentId={}", documentId);
        vectorStore.delete(List.of(documentId));
    }
}
