package ru.girchev.aibot.common.service;

import ru.girchev.aibot.common.config.CoreCommonProperties;

import java.util.List;

/**
 * Утилита для оценки количества токенов в тексте.
 * Использует эвристику: 1 токен ≈ N символов (из конфигурации).
 * 
 * TODO: В будущем можно интегрировать tiktoken или другую библиотеку для точного подсчета
 */
public class TokenCounter {
    
    private final CoreCommonProperties coreCommonProperties;
    
    public TokenCounter(CoreCommonProperties coreCommonProperties) {
        this.coreCommonProperties = coreCommonProperties;
    }
    
    /**
     * Оценивает количество токенов в тексте
     * 
     * @param text текст для оценки
     * @return приблизительное количество токенов
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int charsPerToken = coreCommonProperties.getManualConversationHistory().getTokenEstimationCharsPerToken();
        return (int) Math.ceil((double) text.length() / charsPerToken);
    }
    
    /**
     * Оценивает общее количество токенов в списке текстов
     * 
     * @param texts список текстов для оценки
     * @return суммарное приблизительное количество токенов
     */
    public int estimateTokens(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        return texts.stream()
            .mapToInt(this::estimateTokens)
            .sum();
    }
}

