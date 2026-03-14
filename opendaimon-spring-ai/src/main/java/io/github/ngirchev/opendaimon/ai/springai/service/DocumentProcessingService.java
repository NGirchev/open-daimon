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

import java.util.List;
import java.util.UUID;

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
        List<Document> documents = reader.read();
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
        
        // 1. Extract: read document via TikaDocumentReader
        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(documentData));
        List<Document> documents = reader.read();
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
