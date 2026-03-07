package ru.girchev.aibot.ai.springai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import ru.girchev.aibot.ai.springai.config.RAGProperties;

import java.util.List;
import java.util.UUID;

/**
 * Сервис для обработки документов (PDF, DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT и др.) через ETL Pipeline.
 * 
 * <p>ETL Pipeline:
 * <ol>
 *   <li><b>Extract:</b> Читаем документ с помощью PagePdfDocumentReader (PDF) или TikaDocumentReader (все остальные форматы)</li>
 *   <li><b>Transform:</b> Разбиваем на чанки с помощью TokenTextSplitter</li>
 *   <li><b>Load:</b> Сохраняем в VectorStore с embeddings</li>
 * </ol>
 * 
 * <p>Каждый обработанный документ получает уникальный documentId,
 * который сохраняется в metadata чанков для последующей фильтрации при поиске.
 */
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final VectorStore vectorStore;
    private final RAGProperties ragProperties;

    /**
     * Обрабатывает PDF документ через PagePdfDocumentReader (PDFBox) и сохраняет чанки в VectorStore.
     * 
     * <p><b>Важно:</b> PDF обрабатывается через специализированный PagePdfDocumentReader (PDFBox),
     * а не через TikaDocumentReader, для лучшего качества извлечения текста из PDF.
     * 
     * @param pdfData байты PDF документа
     * @param originalName оригинальное имя файла (для metadata)
     * @return documentId для последующего поиска по этому документу
     */
    public String processPdf(byte[] pdfData, String originalName) {
        String documentId = UUID.randomUUID().toString();
        
        log.info("Processing PDF '{}' with documentId={} (using PagePdfDocumentReader/PDFBox)", originalName, documentId);
        
        // 1. Extract: Читаем PDF через PagePdfDocumentReader (использует Apache PDFBox)
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                new ByteArrayResource(pdfData),
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1) // Каждая страница как отдельный Document
                        .build()
        );
        List<Document> documents = reader.read();
        log.debug("Extracted {} pages from PDF", documents.size());
        
        // 2. Transform: Разбиваем на чанки
        TokenTextSplitter splitter = new TokenTextSplitter(
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                5,     // minChunkSizeChars
                10000, // maxNumChunks
                true   // keepSeparator
        );
        List<Document> chunks = splitter.split(documents);
        log.debug("Split into {} chunks", chunks.size());
        
        // 3. Добавляем metadata
        chunks.forEach(doc -> {
            doc.getMetadata().put("documentId", documentId);
            doc.getMetadata().put("originalName", originalName);
            doc.getMetadata().put("type", "pdf");
        });
        
        // 4. Load: Сохраняем в VectorStore (автоматически генерируются embeddings)
        vectorStore.add(chunks);
        
        log.info("Processed PDF '{}': {} chunks saved with documentId={}", 
                originalName, chunks.size(), documentId);
        
        return documentId;
    }

    /**
     * Обрабатывает документ через TikaDocumentReader (DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT и др.)
     * и сохраняет чанки в VectorStore.
     * 
     * @param documentData байты документа
     * @param originalName оригинальное имя файла (для metadata)
     * @param documentType тип документа (docx, doc, xls, xlsx, ppt, pptx, txt и т.д.)
     * @return documentId для последующего поиска по этому документу
     */
    public String processWithTika(byte[] documentData, String originalName, String documentType) {
        String documentId = UUID.randomUUID().toString();
        
        log.info("Processing document '{}' (type: {}) with documentId={}", originalName, documentType, documentId);
        
        // 1. Extract: Читаем документ через TikaDocumentReader
        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(documentData));
        List<Document> documents = reader.read();
        log.debug("Extracted {} document(s) from {}", documents.size(), documentType);
        
        // 2. Transform: Разбиваем на чанки
        TokenTextSplitter splitter = new TokenTextSplitter(
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                5,     // minChunkSizeChars
                10000, // maxNumChunks
                true   // keepSeparator
        );
        List<Document> chunks = splitter.split(documents);
        log.debug("Split into {} chunks", chunks.size());
        
        // 3. Добавляем metadata
        chunks.forEach(doc -> {
            doc.getMetadata().put("documentId", documentId);
            doc.getMetadata().put("originalName", originalName);
            doc.getMetadata().put("type", documentType);
        });
        
        // 4. Load: Сохраняем в VectorStore (автоматически генерируются embeddings)
        vectorStore.add(chunks);
        
        log.info("Processed document '{}' (type: {}): {} chunks saved with documentId={}", 
                originalName, documentType, chunks.size(), documentId);
        
        return documentId;
    }

    /**
     * Удаляет все чанки документа из VectorStore.
     * 
     * @param documentId идентификатор документа
     */
    public void deleteDocument(String documentId) {
        log.info("Deleting document with documentId={}", documentId);
        vectorStore.delete(List.of(documentId));
    }
}
