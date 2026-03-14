package io.github.ngirchev.opendaimon.common.service;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;

import java.util.Map;

public interface AIGateway {
    boolean supports(AICommand command);
    AIResponse generateResponse(AICommand command);
    AIResponse generateResponse(Map<String, Object> request);
}
