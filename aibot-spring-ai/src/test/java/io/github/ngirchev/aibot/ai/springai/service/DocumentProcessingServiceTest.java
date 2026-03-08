package io.github.ngirchev.aibot.ai.springai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import io.github.ngirchev.aibot.ai.springai.config.RAGProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for DocumentProcessingService.
 */
@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private RAGProperties ragProperties;

    @Captor
    private ArgumentCaptor<List<Document>> documentsCaptor;

    private DocumentProcessingService documentProcessingService;

    @BeforeEach
    void setUp() {
        RAGProperties.RAGPrompts prompts = new RAGProperties.RAGPrompts();
        prompts.setDocumentExtractErrorPdf("Could not extract text from file \"%s\".");
        prompts.setDocumentExtractErrorDocument("Could not extract text from file \"%s\" (type: %s).");
        prompts.setAugmentedPromptTemplate("Context:\n%s\n\nQuestion: %s");

        lenient().when(ragProperties.getChunkSize()).thenReturn(800);
        lenient().when(ragProperties.getChunkOverlap()).thenReturn(100);
        lenient().when(ragProperties.getPrompts()).thenReturn(prompts);

        documentProcessingService = new DocumentProcessingService(vectorStore, ragProperties);
    }

    @Test
    void processPdf_returnsDocumentId() {
        // Arrange
        byte[] pdfData = createSimplePdfBytes();
        String originalName = "test-document.pdf";

        // Act
        String documentId = documentProcessingService.processPdf(pdfData, originalName);

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        assertFalse(documentId.isBlank(), "Document ID should not be blank");
    }

    @Test
    void processPdf_addsDocumentsToVectorStore() {
        // Arrange
        byte[] pdfData = createSimplePdfBytes();
        String originalName = "test-document.pdf";

        // Act
        documentProcessingService.processPdf(pdfData, originalName);

        // Assert
        verify(vectorStore, times(1)).add(documentsCaptor.capture());
        List<Document> addedDocuments = documentsCaptor.getValue();
        assertNotNull(addedDocuments, "Added documents should not be null");
    }

    @Test
    void processPdf_addsMetadataToChunks() {
        // Arrange
        byte[] pdfData = createSimplePdfBytes();
        String originalName = "test-document.pdf";

        // Act
        String documentId = documentProcessingService.processPdf(pdfData, originalName);

        // Assert
        verify(vectorStore).add(documentsCaptor.capture());
        List<Document> addedDocuments = documentsCaptor.getValue();
        
        if (!addedDocuments.isEmpty()) {
            Document firstDoc = addedDocuments.get(0);
            assertEquals(documentId, firstDoc.getMetadata().get("documentId"));
            assertEquals(originalName, firstDoc.getMetadata().get("originalName"));
            assertEquals("pdf", firstDoc.getMetadata().get("type"));
        }
    }

    @Test
    void deleteDocument_callsVectorStoreDelete() {
        // Arrange
        String documentId = "test-doc-id";

        // Act
        documentProcessingService.deleteDocument(documentId);

        // Assert
        verify(vectorStore, times(1)).delete(List.of(documentId));
    }

    // ========== Tests for processWithTika (all formats except PDF) ==========

    @Test
    void processWithTika_txt_returnsDocumentId() {
        // Arrange
        byte[] txtData = "This is a test text document.\nIt has multiple lines.".getBytes();
        String originalName = "test-document.txt";

        // Act
        String documentId = documentProcessingService.processWithTika(txtData, originalName, "txt");

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        assertFalse(documentId.isBlank(), "Document ID should not be blank");
        verify(vectorStore, times(1)).add(any());
    }

    @Test
    void processWithTika_csv_returnsDocumentId() {
        // Arrange
        byte[] csvData = "Name,Age,City\nJohn,30,New York\nJane,25,Boston".getBytes();
        String originalName = "test-data.csv";

        // Act
        String documentId = documentProcessingService.processWithTika(csvData, originalName, "csv");

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        verify(vectorStore, times(1)).add(any());
    }

    @Test
    void processWithTika_html_returnsDocumentId() {
        // Arrange
        byte[] htmlData = """
                <html>
                <head><title>Test Document</title></head>
                <body>
                    <h1>Test Heading</h1>
                    <p>This is a test paragraph.</p>
                </body>
                </html>
                """.getBytes();
        String originalName = "test-page.html";

        // Act
        String documentId = documentProcessingService.processWithTika(htmlData, originalName, "html");

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        verify(vectorStore, times(1)).add(any());
    }

    @Test
    void processWithTika_md_returnsDocumentId() {
        // Arrange
        byte[] mdData = """
                # Test Document
                
                This is a **markdown** document.
                
                - Item 1
                - Item 2
                
                ```java
                System.out.println("Hello");
                ```
                """.getBytes();
        String originalName = "test-doc.md";

        // Act
        String documentId = documentProcessingService.processWithTika(mdData, originalName, "md");

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        verify(vectorStore, times(1)).add(any());
    }

    @Test
    void processWithTika_json_returnsDocumentId() {
        // Arrange
        byte[] jsonData = """
                {
                    "name": "Test Document",
                    "type": "json",
                    "data": {
                        "value": 123,
                        "items": ["a", "b", "c"]
                    }
                }
                """.getBytes();
        String originalName = "test-data.json";

        // Act
        String documentId = documentProcessingService.processWithTika(jsonData, originalName, "json");

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        verify(vectorStore, times(1)).add(any());
    }

    @Test
    void processWithTika_xml_returnsDocumentId() {
        // Arrange
        byte[] xmlData = """
                <?xml version="1.0" encoding="UTF-8"?>
                <root>
                    <item id="1">Test Item 1</item>
                    <item id="2">Test Item 2</item>
                </root>
                """.getBytes();
        String originalName = "test-data.xml";

        // Act
        String documentId = documentProcessingService.processWithTika(xmlData, originalName, "xml");

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        verify(vectorStore, times(1)).add(any());
    }

    @Test
    void processWithTika_rtf_returnsDocumentId() {
        // Arrange
        // Simple RTF document
        byte[] rtfData = """
                {\\rtf1\\ansi\\deff0
                {\\fonttbl{\\f0 Times New Roman;}}
                \\f0\\fs24 This is a test RTF document.
                }
                """.getBytes();
        String originalName = "test-doc.rtf";

        // Act
        String documentId = documentProcessingService.processWithTika(rtfData, originalName, "rtf");

        // Assert
        assertNotNull(documentId, "Document ID should not be null");
        verify(vectorStore, times(1)).add(any());
    }

    @Test
    void processWithTika_addsCorrectMetadata() {
        // Arrange
        byte[] txtData = "Test content".getBytes();
        String originalName = "test.txt";

        // Act
        String documentId = documentProcessingService.processWithTika(txtData, originalName, "txt");

        // Assert
        verify(vectorStore).add(documentsCaptor.capture());
        List<Document> addedDocuments = documentsCaptor.getValue();
        
        if (!addedDocuments.isEmpty()) {
            Document firstDoc = addedDocuments.get(0);
            assertEquals(documentId, firstDoc.getMetadata().get("documentId"));
            assertEquals(originalName, firstDoc.getMetadata().get("originalName"));
            assertEquals("txt", firstDoc.getMetadata().get("type"));
        }
    }

    /**
     * Creates minimal valid PDF for testing.
     * Simplest PDF that can be read.
     */
    private byte[] createSimplePdfBytes() {
        // Minimal PDF document
        String simplePdf = """
                %PDF-1.4
                1 0 obj
                << /Type /Catalog /Pages 2 0 R >>
                endobj
                2 0 obj
                << /Type /Pages /Kids [3 0 R] /Count 1 >>
                endobj
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
                   /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
                endobj
                4 0 obj
                << /Length 44 >>
                stream
                BT /F1 12 Tf 100 700 Td (Hello World) Tj ET
                endstream
                endobj
                5 0 obj
                << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
                endobj
                xref
                0 6
                0000000000 65535 f\s
                0000000009 00000 n\s
                0000000058 00000 n\s
                0000000115 00000 n\s
                0000000266 00000 n\s
                0000000359 00000 n\s
                trailer
                << /Size 6 /Root 1 0 R >>
                startxref
                434
                %%EOF
                """;
        return simplePdf.getBytes();
    }
}
