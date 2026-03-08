package io.github.ngirchev.aibot.common.service;

import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;

import java.util.Map;

public interface AIGateway {
    boolean supports(AICommand command);
    AIResponse generateResponse(AICommand command);
    AIResponse generateResponse(Map<String, Object> request);
}
