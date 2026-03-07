package ru.girchev.aibot.ai.springai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import ru.girchev.aibot.ai.springai.config.RAGProperties;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Сервис для RAG (Retrieval-Augmented Generation).
 * 
 * <p>Отвечает за:
 * <ul>
 *   <li>Поиск релевантных чанков в VectorStore по запросу</li>
 *   <li>Создание augmented prompt с контекстом из документа</li>
 * </ul>
 * 
 * <p>Используется для обогащения запросов пользователя контекстом
 * из загруженных PDF документов.
 */
@Slf4j
@RequiredArgsConstructor
public class RAGService {

    private final VectorStore vectorStore;
    private final RAGProperties ragProperties;

    /**
     * Находит релевантные чанки для запроса из конкретного документа.
     * 
     * @param query текст запроса пользователя
     * @param documentId идентификатор документа (полученный при processPdf)
     * @return список релевантных документов, отсортированных по similarity
     */
    public List<Document> findRelevantContext(String query, String documentId) {
        log.debug("Searching for relevant context: query='{}', documentId={}", 
                truncateForLog(query), documentId);
        
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .filterExpression(filterBuilder.eq("documentId", documentId).build())
                .build();
        
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        
        log.info("Found {} relevant chunks for documentId={}", results.size(), documentId);
        
        return results;
    }

    /**
     * Находит релевантные чанки для запроса из всех документов.
     * 
     * @param query текст запроса пользователя
     * @return список релевантных документов, отсортированных по similarity
     */
    public List<Document> findRelevantContext(String query) {
        log.debug("Searching for relevant context across all documents: query='{}'", 
                truncateForLog(query));
        
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .build();
        
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        
        log.info("Found {} relevant chunks across all documents", results.size());
        
        return results;
    }

    /**
     * Создает augmented prompt с контекстом из документа.
     * 
     * <p>Если контекст пустой, возвращает оригинальный запрос без изменений.
     * 
     * @param userQuery запрос пользователя
     * @param context список релевантных документов
     * @return обогащённый промпт с контекстом
     */
    public String createAugmentedPrompt(String userQuery, List<Document> context) {
        if (context == null || context.isEmpty()) {
            log.debug("No context provided, returning original query");
            return userQuery;
        }
        
        String contextText = context.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        
        String augmentedPrompt = String.format("""
                Based on the following context from the document, answer the user's question.
                If the context doesn't contain relevant information to answer the question,
                say that you couldn't find the answer in the provided documents.
                
                Context:
                %s
                
                Question: %s
                """, contextText, userQuery);
        
        log.debug("Created augmented prompt with {} characters of context", contextText.length());
        
        return augmentedPrompt;
    }

    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
