package io.github.ngirchev.aibot.common.ai.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import io.github.ngirchev.aibot.common.ai.AIGateways;

import java.util.Map;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class SpringAIStreamResponse implements AIResponse {

    private final Flux<ChatResponse> chatResponse;

    @Override
    public AIGateways gatewaySource() {
        return AIGateways.SPRINGAI;
    }

    @Override
    public Map<String, Object> toMap() {
        throw new UnsupportedOperationException("Not implemented");
    }
}
