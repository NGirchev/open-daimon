package ru.girchev.aibot.it.springai;

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
import ru.girchev.aibot.ai.springai.config.SpringAIFlywayConfig;
import ru.girchev.aibot.ai.springai.service.SpringAIGateway;
import ru.girchev.aibot.common.ai.ModelType;
import ru.girchev.aibot.common.ai.command.ChatAICommand;
import ru.girchev.aibot.common.ai.response.AIResponse;
import ru.girchev.aibot.common.ai.response.SpringAIResponse;
import ru.girchev.aibot.common.ai.response.SpringAIStreamResponse;
import ru.girchev.aibot.common.config.CoreFlywayConfig;
import ru.girchev.aibot.common.config.CoreJpaConfig;
import ru.girchev.aibot.common.service.AIUtils;
import ru.girchev.aibot.test.TestDatabaseConfiguration;

import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.model.AttachmentType;

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
import static ru.girchev.aibot.common.ai.LlmParamNames.MAX_PRICE;

/**
 * Интеграционный тест для модуля aibot-spring-ai с реальными вызовами OpenRouter API.
 * 
 * <p><b>Цель:</b> Протестировать модуль aibot-spring-ai целиком без моков - 
 * реальные бины, реальная БД, реальный API.
 * 
 * <p>Этот тест проверяет работу SpringAIGateway с реальным OpenRouter API.
 * Тест по умолчанию отключен (@Disabled), так как требует реального API ключа.
 * 
 * <p>Для запуска теста:
 * <ol>
 *   <li>Убедитесь что файл .env содержит OPENROUTER_KEY с вашим API ключом</li>
 *   <li>Удалите @Disabled с нужного теста или со всего класса</li>
 *   <li>Запустите тест</li>
 * </ol>
 * 
 * <p>Примечание: тесты используют бесплатную модель (openrouter/auto с max_price=0),
 * поэтому при наличии API ключа они не должны стоить денег.
 */
@Slf4j
@Disabled("Требует реальный OPENROUTER_KEY. Удалите @Disabled для локального запуска.")
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
        "ai-bot.common.conversation-context.enabled=false",
        "ai-bot.ai.spring-ai.mock=false"
})
class SpringAIGatewayOpenRouterIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    @Autowired
    private SpringAIGateway springAIGateway;

    /**
     * Тест синхронного вызова OpenRouter через SpringAIGateway.
     * Отправляет простой запрос и проверяет, что получен непустой ответ.
     */
    @Test
    void testGenerateResponse_callMode() {
        // Arrange
        String systemRole = "You are a helpful assistant. Answer briefly in one sentence.";
        String userMessage = "What is 2 + 2?";
        
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelType.AUTO, ModelType.CHAT),
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
     * Тест стримингового вызова OpenRouter через SpringAIGateway.
     * Отправляет запрос в режиме stream и проверяет, что получен поток ответов.
     */
    @Test
    void testGenerateResponse_streamMode() {
        // Arrange
        String systemRole = "You are a helpful assistant. Answer briefly.";
        String userMessage = "Tell me a very short joke in one sentence.";
        
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelType.AUTO, ModelType.CHAT),
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
        
        // Обрабатываем стрим и собираем ответ
        StringBuilder collectedResponse = new StringBuilder();
        log.info("Streaming response: ");
        
        ChatResponse finalResponse = AIUtils.processStreamingResponse(
                streamResponse.chatResponse(),
                text -> {
                    collectedResponse.append(text);
                    // Выводим чанки в реальном времени
                    try {
                        System.out.write(text.getBytes(StandardCharsets.UTF_8));
                        System.out.flush();
                    } catch (IOException e) {
                        log.warn("Error writing to console: {}", e.getMessage());
                    }
                },
                Duration.ofMinutes(2)
        );
        
        System.out.println(); // Перевод строки после стрима
        
        // Проверяем финальный ответ
        assertNotNull(finalResponse, "Final ChatResponse should not be null");
        String responseText = collectedResponse.toString();
        assertFalse(responseText.isBlank(), "Collected response should not be blank");
        
        log.info("Full response: {}", responseText);
        log.info("=== Stream mode test completed successfully ===");
    }

    /**
     * Тест отправки изображения через multimodal API.
     * Создает простое тестовое изображение, отправляет его с запросом на описание,
     * и проверяет, что модель ответила описанием.
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
                Set.of(ModelType.AUTO, ModelType.CHAT, ModelType.VISION),
                0.7,
                200,
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
     * Создает простое тестовое изображение 100x100 пикселей с цветными квадратами.
     * Это минимальное изображение для проверки multimodal API.
     */
    private byte[] createTestImage() throws IOException {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        // Рисуем цветные квадраты для визуального разнообразия
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
            "ru.girchev.aibot.telegram.config.TelegramAutoConfig",
            "ru.girchev.aibot.rest.config.RestAutoConfig",
            "ru.girchev.aibot.ui.config.UIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
    })
    static class TestConfig {
    }
}
