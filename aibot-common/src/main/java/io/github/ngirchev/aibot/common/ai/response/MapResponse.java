package io.github.ngirchev.aibot.common.ai.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import io.github.ngirchev.aibot.common.ai.AIGateways;

import java.util.Map;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class MapResponse implements AIResponse {

    private final AIGateways gatewaySource;
    private final Map<String, Object> rawData;

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> rawData = this.rawData();
        return rawData != null ? rawData : Map.of();
    }
}
