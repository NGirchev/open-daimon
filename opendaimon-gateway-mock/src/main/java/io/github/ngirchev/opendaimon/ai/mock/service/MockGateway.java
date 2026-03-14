package io.github.ngirchev.opendaimon.ai.mock.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.opendaimon.ai.mock.response.MockResponse;
import io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;

import java.util.*;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.*;

@Slf4j
@RequiredArgsConstructor
public class MockGateway implements AIGateway {

    private static final List<String> MOCK_RESPONSES = List.of(
            "This is a test response from Mock Gateway",
            "Mock Gateway received your request and responds randomly",
            "Hi! I am mock gateway and I just reply with random phrases",
            "Here is a random response for testing the system",
            "Mock Gateway works correctly and processed your request",
            "This is a response from mock gateway to verify functionality",
            "Mock Gateway successfully processed the request and returned a test response"
    );

    private final AIGatewayRegistry aiGatewayRegistry;
    private final Random random = new Random(); // NOSONAR - not used for security, only for random mock response

    @PostConstruct
    public void init() {
        aiGatewayRegistry.registerAiGateway(this);
        log.info("MockGateway registered in AIGatewayRegistry");
    }

    @Override
    public boolean supports(AICommand command) {
        return true; // Supports all commands
    }

    @Override
    public AIResponse generateResponse(AICommand command) {
        log.info("=== MockGateway received AICommand ===");
        log.info("Model types: {}", command.modelCapabilities());
        log.info("Metadata: {}", command.metadata());
        
        if (command.options() instanceof OpenDaimonChatOptions(
                double temp, int maxTokens, String systemRole, String userRole, boolean stream, Map<String, Object> body
        )) {
            log.info("System role: {}", systemRole);
            log.info("User role: {}", userRole);
            log.info("Stream: {}", stream);
            log.info("Temperature: {}", temp);
            log.info("Max tokens: {}", maxTokens);
            log.info("Body overrides: {}", body);
        }
        
        String randomResponse = getRandomResponse();
        log.info("MockGateway responding with: {}", randomResponse);
        
        return createMockResponse(randomResponse);
    }

    @Override
    public AIResponse generateResponse(Map<String, Object> request) {
        log.info("=== MockGateway received Map request ===");
        log.info("Request content: {}", request);
        
        // Log main fields if present
        if (request.containsKey(MESSAGES)) {
            log.info("Messages: {}", request.get(MESSAGES));
        }
        if (request.containsKey(MODEL)) {
            log.info("Model: {}", request.get(MODEL));
        }
        if (request.containsKey(OPTIONS)) {
            log.info("Options: {}", request.get(OPTIONS));
        }
        
        String randomResponse = getRandomResponse();
        log.info("MockGateway responding with: {}", randomResponse);
        
        return createMockResponse(randomResponse);
    }

    private String getRandomResponse() {
        return MOCK_RESPONSES.get(random.nextInt(MOCK_RESPONSES.size()));
    }

    private MockResponse createMockResponse(String content) {
        Map<String, Object> message = new HashMap<>();
        message.put(CONTENT, content);
        
        Map<String, Object> choice = new HashMap<>();
        choice.put(MESSAGE, message);
        
        Map<String, Object> response = new HashMap<>();
        response.put(CHOICES, List.of(choice));
        response.put(MODEL, "mock");  // Add model field for compatibility with extractUsefulData
        
        return new MockResponse(response);
    }
}
