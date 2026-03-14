package io.github.ngirchev.opendaimon.common.ai.response;

import io.github.ngirchev.opendaimon.common.ai.AIGateways;

import java.util.Map;

public interface AIResponse {
    AIGateways gatewaySource();
    Map<String, Object> toMap();
}
