package io.github.ngirchev.opendaimon.common.ai.response;

import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAIResponseTest {

    @Test
    void gatewaySource_returnsSpringAI() {
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("hi"))))
                .metadata(ChatResponseMetadata.builder().build())
                .build();
        SpringAIResponse response = new SpringAIResponse(chatResponse);

        assertEquals(AIGateways.SPRINGAI, response.gatewaySource());
        assertEquals(chatResponse, response.chatResponse());
    }

    @Test
    void toMap_throwsUnsupportedOperationException() {
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("hi"))))
                .metadata(ChatResponseMetadata.builder().build())
                .build();
        SpringAIResponse response = new SpringAIResponse(chatResponse);

        assertThrows(UnsupportedOperationException.class, response::toMap);
    }
}
