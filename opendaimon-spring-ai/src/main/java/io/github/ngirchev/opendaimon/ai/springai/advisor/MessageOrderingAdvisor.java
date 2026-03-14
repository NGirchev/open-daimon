package io.github.ngirchev.opendaimon.ai.springai.advisor;

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
        int firstNonSystemIndex = findFirstNonSystemIndex(messages);
        List<Message> systemMessagesFromHistory = new ArrayList<>();
        List<Message> systemMessagesCurrent = new ArrayList<>();
        List<Message> nonSystemMessages = new ArrayList<>();
        splitMessagesByType(messages, firstNonSystemIndex, systemMessagesFromHistory, systemMessagesCurrent, nonSystemMessages);
        if ((systemMessagesFromHistory.isEmpty() && systemMessagesCurrent.isEmpty()) || nonSystemMessages.isEmpty()) {
            log.debug("No reordering needed: systemMessagesFromHistory={}, systemMessagesCurrent={}, nonSystemMessages={}",
                    systemMessagesFromHistory.size(), systemMessagesCurrent.size(), nonSystemMessages.size());
            return request;
        }
        List<Message> reorderedMessages = buildReorderedMessages(systemMessagesCurrent, systemMessagesFromHistory, nonSystemMessages);
        log.debug("Reordered messages: {} current system messages, {} system from history, then {} non-system messages",
                systemMessagesCurrent.size(), systemMessagesFromHistory.size(), nonSystemMessages.size());
        return request.mutate()
                .prompt(prompt.mutate().messages(reorderedMessages).build())
                .build();
    }

    private static int findFirstNonSystemIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof SystemMessage)) {
                return i;
            }
        }
        return -1;
    }

    private static void splitMessagesByType(List<Message> messages, int firstNonSystemIndex,
            List<Message> systemMessagesFromHistory, List<Message> systemMessagesCurrent, List<Message> nonSystemMessages) {
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof SystemMessage) {
                if (firstNonSystemIndex == -1 || i < firstNonSystemIndex) {
                    systemMessagesFromHistory.add(message);
                } else {
                    systemMessagesCurrent.add(message);
                }
            } else {
                nonSystemMessages.add(message);
            }
        }
    }

    private static List<Message> buildReorderedMessages(
            List<Message> systemMessagesCurrent, List<Message> systemMessagesFromHistory, List<Message> nonSystemMessages) {
        List<Message> reordered = new ArrayList<>();
        reordered.addAll(systemMessagesCurrent);
        reordered.addAll(systemMessagesFromHistory);
        reordered.addAll(nonSystemMessages);
        return reordered;
    }
}
