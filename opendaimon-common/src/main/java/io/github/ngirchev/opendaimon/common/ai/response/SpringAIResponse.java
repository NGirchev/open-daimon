package io.github.ngirchev.opendaimon.common.ai.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import org.springframework.ai.chat.model.ChatResponse;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;

import java.util.Map;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class SpringAIResponse implements AIResponse {

    private final ChatResponse chatResponse;

    @Override
    public AIGateways gatewaySource() {
        return AIGateways.SPRINGAI;
    }

    @Override
    public Map<String, Object> toMap() {
        throw new UnsupportedOperationException("Not implemented");
    }
}
