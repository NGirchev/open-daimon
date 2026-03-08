package io.github.ngirchev.aibot.ai.springai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

/**
 * Advisor to reorder messages in the prompt.
 *
 * Fixes known Spring AI issue (#4170) where MessageChatMemoryAdvisor
 * adds history before System messages.
 *
 * This advisor runs after MessageChatMemoryAdvisor and reorders messages:
 * 1. System messages first — including current System and summary from history
 * 2. Other messages in original order (history + new user message)
 *
 * Correct order: System -> summary(System) -> History -> User
 */
@Slf4j
public class MessageOrderingAdvisor implements BaseAdvisor {
    
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100; // Runs after MessageChatMemoryAdvisor
    
    @Override
    public String getName() {
        return "MessageOrderingAdvisor";
    }
    
    @Override
    public int getOrder() {
        return ORDER;
    }
    
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return reorderMessages(request);
    }
    
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // Do not change response, return as is
        return response;
    }
    
    /**
     * Reorders messages in request: System messages first, then the rest.
     */
    private ChatClientRequest reorderMessages(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions();
        
        if (messages == null || messages.isEmpty()) {
            log.debug("No messages to reorder");
            return request;
        }
        
        // Split messages by type and find boundary between history and current messages
        List<Message> systemMessagesFromHistory = new ArrayList<>(); // System from history (summary)
        List<Message> systemMessagesCurrent = new ArrayList<>(); // Current System messages
        List<Message> nonSystemMessages = new ArrayList<>();
        
        // Find first non-System message — boundary between history and current messages
        int firstNonSystemIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof SystemMessage)) {
                firstNonSystemIndex = i;
                break;
            }
        }
        
        // Split System messages: those before first non-System are from history, rest are current
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof SystemMessage) {
                if (firstNonSystemIndex == -1 || i < firstNonSystemIndex) {
                    // System messages before first non-System are from history (summary)
                    systemMessagesFromHistory.add(message);
                } else {
                    // System messages after first non-System are current
                    systemMessagesCurrent.add(message);
                }
            } else {
                nonSystemMessages.add(message);
            }
        }
        
        // If no System or no non-System messages, return as is
        if ((systemMessagesFromHistory.isEmpty() && systemMessagesCurrent.isEmpty()) || nonSystemMessages.isEmpty()) {
            log.debug("No reordering needed: systemMessagesFromHistory={}, systemMessagesCurrent={}, nonSystemMessages={}", 
                    systemMessagesFromHistory.size(), systemMessagesCurrent.size(), nonSystemMessages.size());
            return request;
        }
        
        // Build correct order:
        // 1. Current System messages first — added via promptBuilder.system()
        // 2. System messages from history (summary) — added via MessageChatMemoryAdvisor
        // 3. Other messages in original order (history + new user message)
        List<Message> reorderedMessages = new ArrayList<>();
        reorderedMessages.addAll(systemMessagesCurrent); // Current System first
        reorderedMessages.addAll(systemMessagesFromHistory); // Summary from history second
        reorderedMessages.addAll(nonSystemMessages); // Rest of messages
        
        log.debug("Reordered messages: {} current system messages, {} system from history, then {} non-system messages",
                systemMessagesCurrent.size(), systemMessagesFromHistory.size(), nonSystemMessages.size());
        
        // Create new prompt with reordered messages
        Prompt newPrompt = prompt.mutate()
                .messages(reorderedMessages)
                .build();
        
        // Create new request with new prompt
        return request.mutate()
                .prompt(newPrompt)
                .build();
    }
}
