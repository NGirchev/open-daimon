package ru.girchev.aibot.ai.springai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import ru.girchev.aibot.ai.springai.config.RAGProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit тесты для RAGService.
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private RAGProperties ragProperties;

    @Captor
    private ArgumentCaptor<SearchRequest> searchRequestCaptor;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        lenient().when(ragProperties.getTopK()).thenReturn(5);
        lenient().when(ragProperties.getSimilarityThreshold()).thenReturn(0.7);
        
        ragService = new RAGService(vectorStore, ragProperties);
    }

    @Test
    void findRelevantContext_withDocumentId_usesFilter() {
        // Arrange
        String query = "What is the main topic?";
        String documentId = "doc-123";
        List<Document> expectedResults = List.of(
                new Document("Content chunk 1", Map.of("documentId", documentId)),
                new Document("Content chunk 2", Map.of("documentId", documentId))
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(expectedResults);

        // Act
        List<Document> results = ragService.findRelevantContext(query, documentId);

        // Assert
        assertEquals(expectedResults, results);
        verify(vectorStore).similaritySearch(searchRequestCaptor.capture());
        
        SearchRequest capturedRequest = searchRequestCaptor.getValue();
        assertEquals(query, capturedRequest.getQuery());
        assertEquals(5, capturedRequest.getTopK());
        assertNotNull(capturedRequest.getFilterExpression());
    }

    @Test
    void findRelevantContext_withoutDocumentId_searchesAllDocuments() {
        // Arrange
        String query = "General question";
        List<Document> expectedResults = List.of(
                new Document("Result 1"),
                new Document("Result 2")
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(expectedResults);

        // Act
        List<Document> results = ragService.findRelevantContext(query);

        // Assert
        assertEquals(expectedResults, results);
        verify(vectorStore).similaritySearch(searchRequestCaptor.capture());
        
        SearchRequest capturedRequest = searchRequestCaptor.getValue();
        assertEquals(query, capturedRequest.getQuery());
        assertNull(capturedRequest.getFilterExpression());
    }

    @Test
    void findRelevantContext_returnsEmptyList_whenNoResults() {
        // Arrange
        String query = "No matching content";
        String documentId = "doc-456";
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        // Act
        List<Document> results = ragService.findRelevantContext(query, documentId);

        // Assert
        assertTrue(results.isEmpty());
    }

    @Test
    void createAugmentedPrompt_includesContext() {
        // Arrange
        String userQuery = "What is the main topic?";
        List<Document> context = List.of(
                new Document("This document discusses AI technology."),
                new Document("Machine learning is a subset of AI.")
        );

        // Act
        String augmentedPrompt = ragService.createAugmentedPrompt(userQuery, context);

        // Assert
        assertNotNull(augmentedPrompt);
        assertTrue(augmentedPrompt.contains(userQuery));
        assertTrue(augmentedPrompt.contains("AI technology"));
        assertTrue(augmentedPrompt.contains("Machine learning"));
        assertTrue(augmentedPrompt.contains("Context:"));
    }

    @Test
    void createAugmentedPrompt_returnsOriginalQuery_whenContextIsEmpty() {
        // Arrange
        String userQuery = "What is the main topic?";
        List<Document> emptyContext = Collections.emptyList();

        // Act
        String result = ragService.createAugmentedPrompt(userQuery, emptyContext);

        // Assert
        assertEquals(userQuery, result);
    }

    @Test
    void createAugmentedPrompt_returnsOriginalQuery_whenContextIsNull() {
        // Arrange
        String userQuery = "What is the main topic?";

        // Act
        String result = ragService.createAugmentedPrompt(userQuery, null);

        // Assert
        assertEquals(userQuery, result);
    }

    @Test
    void createAugmentedPrompt_separatesContextChunks() {
        // Arrange
        String userQuery = "Question";
        List<Document> context = List.of(
                new Document("First chunk"),
                new Document("Second chunk"),
                new Document("Third chunk")
        );

        // Act
        String augmentedPrompt = ragService.createAugmentedPrompt(userQuery, context);

        // Assert
        // Проверяем, что чанки разделены
        assertTrue(augmentedPrompt.contains("---"));
        assertTrue(augmentedPrompt.contains("First chunk"));
        assertTrue(augmentedPrompt.contains("Second chunk"));
        assertTrue(augmentedPrompt.contains("Third chunk"));
    }
}
