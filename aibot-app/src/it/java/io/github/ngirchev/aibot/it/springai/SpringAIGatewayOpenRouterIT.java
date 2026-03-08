package io.github.ngirchev.aibot.it.springai;

import io.github.ngirchev.dotenv.DotEnvLoader;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIFlywayConfig;
import io.github.ngirchev.aibot.ai.springai.service.SpringAIGateway;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.ChatAICommand;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.common.service.AIUtils;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import io.github.ngirchev.aibot.common.model.Attachment;
import io.github.ngirchev.aibot.common.model.AttachmentType;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.ngirchev.aibot.common.ai.LlmParamNames.MAX_PRICE;
import static io.github.ngirchev.aibot.common.ai.ModelCapabilities.*;

/**
 * Integration test for aibot-spring-ai with real OpenRouter API calls.
 *
 * <p><b>Goal:</b> Test aibot-spring-ai end-to-end without mocks —
 * real beans, real DB, real API.
 *
 * <p>This test verifies SpringAIGateway with real OpenRouter API.
 * Test is disabled by default (@Disabled) as it requires a real API key.
 *
 * <p>To run the test:
 * <ol>
 *   <li>Ensure .env contains OPENROUTER_KEY with your API key</li>
 *   <li>Remove @Disabled from the test or the whole class</li>
 *   <li>Run the test</li>
 * </ol>
 *
 * <p>Note: tests use a free model (openrouter/auto with max_price=0),
 * so with an API key they should not incur cost.
 */
@Slf4j
@Disabled("Requires real OPENROUTER_KEY. Remove @Disabled for local run.")
@SpringBootTest(
        classes = SpringAIGatewayOpenRouterIT.TestConfig.class,
        properties = {
                "spring.main.banner-mode=off"
        }
)
@ActiveProfiles("integration-test")
@Import({
        TestDatabaseConfiguration.class,
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        SpringAIFlywayConfig.class
})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.ai.model.ollama.autoconfigure.OllamaAutoConfiguration," +
                "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration",
        "ai-bot.common.bulkhead.enabled=false",
        "ai-bot.common.manual-conversation-history.enabled=false",
        "ai-bot.ai.spring-ai.mock=false"
})
class SpringAIGatewayOpenRouterIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    @Autowired
    private SpringAIGateway springAIGateway;

    /**
     * Test synchronous OpenRouter call via SpringAIGateway.
     * Sends a simple request and verifies a non-empty response.
     */
    @Test
    void testGenerateResponse_callMode() {
        // Arrange
        String systemRole = "You are a helpful assistant. Answer briefly in one sentence.";
        String userMessage = "What is 2 + 2?";
        
        ChatAICommand command = new ChatAICommand(
                Set.of(AUTO, CHAT),
                0.7,
                100,
                systemRole,
                userMessage,
                false,
                new HashMap<>(),
                createBodyWithMaxPrice()
        );

        log.info("=== Testing OpenRouter call mode ===");
        log.info("System: {}", systemRole);
        log.info("User: {}", userMessage);

        // Act
        AIResponse response = springAIGateway.generateResponse(command);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertInstanceOf(SpringAIResponse.class, response, "Response should be SpringAIResponse");
        
        SpringAIResponse springAIResponse = (SpringAIResponse) response;
        ChatResponse chatResponse = springAIResponse.chatResponse();
        assertNotNull(chatResponse, "ChatResponse should not be null");
        assertNotNull(chatResponse.getResult(), "Result should not be null");
        assertNotNull(chatResponse.getResult().getOutput(), "Output should not be null");
        
        String responseText = chatResponse.getResult().getOutput().getText();
        assertNotNull(responseText, "Response text should not be null");
        assertFalse(responseText.isBlank(), "Response text should not be blank");
        
        log.info("Response: {}", responseText);
        log.info("=== Call mode test completed successfully ===");
    }

    /**
     * Test streaming OpenRouter call via SpringAIGateway.
     * Sends a stream request and verifies a response stream is received.
     */
    @Test
    void testGenerateResponse_streamMode() {
        // Arrange
        String systemRole = "You are a helpful assistant. Answer briefly.";
        String userMessage = "Tell me a very short joke in one sentence.";
        
        ChatAICommand command = new ChatAICommand(
                Set.of(AUTO, CHAT),
                0.7,
                150,
                systemRole,
                userMessage,
                true,
                new HashMap<>(),
                createBodyWithMaxPrice()
        );

        log.info("=== Testing OpenRouter stream mode ===");
        log.info("System: {}", systemRole);
        log.info("User: {}", userMessage);

        // Act
        AIResponse response = springAIGateway.generateResponse(command);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertInstanceOf(SpringAIStreamResponse.class, response, "Response should be SpringAIStreamResponse");
        
        SpringAIStreamResponse streamResponse = (SpringAIStreamResponse) response;
        assertNotNull(streamResponse.chatResponse(), "Flux should not be null");
        
        // Process stream and collect response
        StringBuilder collectedResponse = new StringBuilder();
        log.info("Streaming response: ");
        
        ChatResponse finalResponse = AIUtils.processStreamingResponse(
                streamResponse.chatResponse(),
                text -> {
                    collectedResponse.append(text);
                    // Output chunks in real time
                    try {
                        System.out.write(text.getBytes(StandardCharsets.UTF_8));
                        System.out.flush();
                    } catch (IOException e) {
                        log.warn("Error writing to console: {}", e.getMessage());
                    }
                },
                Duration.ofMinutes(2)
        );
        
        System.out.println(); // Newline after stream

        // Verify final response
        assertNotNull(finalResponse, "Final ChatResponse should not be null");
        String responseText = collectedResponse.toString();
        assertFalse(responseText.isBlank(), "Collected response should not be blank");
        
        log.info("Full response: {}", responseText);
        log.info("=== Stream mode test completed successfully ===");
    }

    /**
     * Test sending an image via multimodal API.
     * Creates a simple test image, sends it with a description request,
     * and verifies the model returns a description.
     */
    @Test
    void testGenerateResponse_withImageAttachment() throws IOException {
        // Arrange
        byte[] imageData = createTestImage();
        Attachment imageAttachment = new Attachment(
                "test-key",
                "image/png",
                "test-image.png",
                imageData.length,
                AttachmentType.IMAGE,
                imageData
        );
        
        ChatAICommand command = new ChatAICommand(
                Set.of(AUTO, CHAT, VISION),
                0.7,
                200,
                null,
                "You are a helpful assistant.",
                "What do you see in this image? Describe briefly in one sentence.",
                false,
                new HashMap<>(),
                createBodyWithMaxPrice(),
                List.of(imageAttachment)
        );

        log.info("=== Testing OpenRouter with image attachment (multimodal) ===");

        // Act
        AIResponse response = springAIGateway.generateResponse(command);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertInstanceOf(SpringAIResponse.class, response, "Response should be SpringAIResponse");
        
        SpringAIResponse springAIResponse = (SpringAIResponse) response;
        ChatResponse chatResponse = springAIResponse.chatResponse();
        assertNotNull(chatResponse, "ChatResponse should not be null");
        assertNotNull(chatResponse.getResult(), "Result should not be null");
        
        String responseText = chatResponse.getResult().getOutput().getText();
        assertNotNull(responseText, "Response text should not be null");
        assertFalse(responseText.isBlank(), "Response text should not be blank");
        
        log.info("Vision response: {}", responseText);
        log.info("=== Multimodal test completed successfully ===");
    }

    /**
     * Creates a simple 100x100 test image with colored squares.
     * Minimal image for verifying multimodal API.
     */
    private byte[] createTestImage() throws IOException {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        // Draw colored squares for visual variety
        g2d.setColor(Color.RED);
        g2d.fillRect(0, 0, 50, 50);
        
        g2d.setColor(Color.GREEN);
        g2d.fillRect(50, 0, 50, 50);
        
        g2d.setColor(Color.BLUE);
        g2d.fillRect(0, 50, 50, 50);
        
        g2d.setColor(Color.YELLOW);
        g2d.fillRect(50, 50, 50, 50);
        
        g2d.dispose();
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private Map<String, Object> createBodyWithMaxPrice() {
        Map<String, Object> body = new HashMap<>();
        body.put(MAX_PRICE, Map.of("prompt", 0.0, "completion", 0.0));
        return body;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "io.github.ngirchev.aibot.telegram.config.TelegramAutoConfig",
            "io.github.ngirchev.aibot.rest.config.RestAutoConfig",
            "io.github.ngirchev.aibot.ui.config.UIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
    })
    static class TestConfig {
    }
}
