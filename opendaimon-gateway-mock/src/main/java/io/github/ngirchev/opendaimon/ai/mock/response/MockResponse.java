package io.github.ngirchev.opendaimon.ai.mock.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;

import java.util.Map;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class MockResponse implements AIResponse {

    private final Map<String, Object> rawData;

    @Override
    public AIGateways gatewaySource() {
        return AIGateways.MOCK;
    }

    @Override
    public Map<String, Object> toMap() {
        return rawData != null ? rawData : Map.of();
    }
}
