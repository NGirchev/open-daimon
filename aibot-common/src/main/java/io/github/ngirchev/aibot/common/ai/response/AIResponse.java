package io.github.ngirchev.aibot.common.ai.response;

import io.github.ngirchev.aibot.common.ai.AIGateways;

import java.util.Map;

public interface AIResponse {
    AIGateways gatewaySource();
    Map<String, Object> toMap();
}
