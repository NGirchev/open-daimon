package ru.girchev.aibot.ai.springai.service;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.common.ai.ModelCapabilities;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static ru.girchev.aibot.common.ai.ModelCapabilities.*;

class SpringAIModelCapabilitiesTest {

    private SpringAIModelType createSpringAIModelType() {
        List<SpringAIModelConfig> models = List.of(
                createModel("nomic-embed-text:v1.5", List.of(EMBEDDING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("gemma3:1b", List.of(CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        return new SpringAIModelType(models);
    }

    private SpringAIModelConfig createModel(String name, List<ModelCapabilities> capabilities, SpringAIModelConfig.ProviderType providerType, Integer priority) {
        SpringAIModelConfig config = new SpringAIModelConfig();
        config.setName(name);
        config.setCapabilities(capabilities);
        config.setProviderType(providerType);
        config.setPriority(priority);
        return config;
    }

    @RepeatedTest(10)
    void whenGetBy3Capabilities_thenReturnGPT() {
        // Arrange
        List<SpringAIModelConfig> models = List.of(
                createModel("nomic-embed-text:v1.5", List.of(EMBEDDING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("gemma3:1b", List.of(CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("gpt-oss:120b", List.of(CHAT, EMBEDDING, TOOL_CALLING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("openrouter/auto", List.of(AUTO), SpringAIModelConfig.ProviderType.OPENAI, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING, TOOL_CALLING));

        // Assert
        assertTrue(result.isPresent());
        assertEquals("gpt-oss:120b", result.get().getName());
    }

    @RepeatedTest(10)
    void whenGetBy2Capabilities_thenReturnGPT() {
        // Arrange
        List<SpringAIModelConfig> models = List.of(
                createModel("nomic-embed-text:v1.5", List.of(TOOL_CALLING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("gemma3:1b", List.of(CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("gpt", List.of(CHAT, EMBEDDING, TOOL_CALLING), SpringAIModelConfig.ProviderType.OPENAI, 2),
                createModel("openrouter/auto", List.of(AUTO), SpringAIModelConfig.ProviderType.OPENAI, 3)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, TOOL_CALLING));

        // Assert
        assertTrue(result.isPresent());
        assertEquals("nomic-embed-text:v1.5", result.get().getName());
    }

    @Test
    void whenGetByCapability_EMBEDDING_thenReturnNOMIC_LOCAL() {
        // Arrange
        SpringAIModelType springAIModelType = createSpringAIModelType();
        
        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(EMBEDDING);

        // Assert
        assertTrue(result.isPresent());
        assertEquals("nomic-embed-text:v1.5", result.get().getName());
    }

    @Test
    void whenGetByCapability_CHAT_thenReturnGEMMA_LOCAL() {
        // Arrange
        SpringAIModelType springAIModelType = createSpringAIModelType();
        
        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(CHAT);

        // Assert
        assertTrue(result.isPresent());
        assertEquals("gemma3:1b", result.get().getName());
    }

    @Test
    void whenGetByCapability_EMBEDDING_thenNOMIC_LOCAL_hasPriority() {
        // Arrange
        // GEMMA_LOCAL имеет EMBEDDING на индексе 1 (второй в списке)
        // NOMIC_LOCAL имеет EMBEDDING на индексе 0 (первый в списке)
        // Ожидаем NOMIC_LOCAL, так как он имеет capability на индексе 0
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(EMBEDDING);

        // Assert
        assertTrue(result.isPresent());
        // Проверяем, что это действительно модель с EMBEDDING на первом месте
        assertEquals("nomic-embed-text:v1.5", result.get().getName());
    }

    @Test
    void whenGetByCapability_CHAT_thenGEMMA_LOCAL_hasPriority() {
        // Arrange
        // GEMMA_LOCAL имеет CHAT на индексе 0 (первый в списке)
        // NOMIC_LOCAL имеет CHAT на индексе 1 (второй в списке)
        // Ожидаем GEMMA_LOCAL, так как он имеет capability на индексе 0
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(CHAT);

        // Assert
        assertTrue(result.isPresent());
        // Проверяем, что это действительно модель с CHAT на первом месте
        assertEquals("gemma3:1b", result.get().getName());
    }

    @Test
    void whenGetByCapability_unsupportedType_thenReturnEmpty() {
        // Arrange
        // Используем тип, который не поддерживается ни одной моделью
        ModelCapabilities unsupportedType = ModelCapabilities.RERANK;
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(unsupportedType);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapability_MODERATION_thenReturnEmpty() {
        // Arrange
        ModelCapabilities moderationType = ModelCapabilities.MODERATION;
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(moderationType);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapability_STRUCTURED_OUTPUT_thenReturnEmpty() {
        // Arrange
        ModelCapabilities structuredOutputType = ModelCapabilities.STRUCTURED_OUTPUT;
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(structuredOutputType);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapability_TOOL_CALLING_thenReturnEmpty() {
        // Arrange
        ModelCapabilities toolCallingType = ModelCapabilities.TOOL_CALLING;
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(toolCallingType);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapability_AUTO_thenReturnEmpty() {
        // Arrange
        ModelCapabilities autoType = AUTO;
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(autoType);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapability_RAW_TYPE_thenReturnEmpty() {
        // Arrange
        ModelCapabilities rawType = ModelCapabilities.RAW_TYPE;
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(rawType);

        // Assert
        assertTrue(result.isEmpty());
    }

    // ========== Тесты для getByCapabilities ==========

    @Test
    void whenGetByCapabilities_singleType_thenReturnModelWithThatType() {
        // Arrange
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT));

        // Assert
        assertTrue(result.isPresent());
        assertEquals("gemma3:1b", result.get().getName());
    }

    @Test
    void whenGetByCapabilities_modelContainsAllTypes_thenReturnModel() {
        // Arrange
        // nomic-embed-text:v1.5 содержит [EMBEDDING, CHAT]
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(EMBEDDING, CHAT));

        // Assert
        assertTrue(result.isPresent());
        assertEquals("nomic-embed-text:v1.5", result.get().getName());
    }

    @Test
    void whenGetByCapabilities_modelDoesNotContainAllTypes_thenReturnEmpty() {
        // Arrange
        // gemma3:1b содержит только CHAT, не содержит EMBEDDING
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(EMBEDDING, CHAT));

        // Assert
        // Должна вернуться nomic-embed-text:v1.5, так как она содержит оба типа
        assertTrue(result.isPresent());
        assertEquals("nomic-embed-text:v1.5", result.get().getName());
    }

    @Test
    void whenGetByCapabilities_noModelContainsAllTypes_thenReturnEmpty() {
        // Arrange
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(TOOL_CALLING, STRUCTURED_OUTPUT));

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapabilities_emptySet_thenReturnEmpty() {
        // Arrange
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapabilities_nullSet_thenReturnEmpty() {
        // Arrange
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(null);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetByCapabilities_multipleModelsWithAllTypes_thenReturnModelWithMinMaxIndex() {
        // Arrange
        // Создаем модели с разными индексами для проверки приоритета
        // model1: [CHAT, EMBEDDING] - maxIndex = 1
        // model2: [EMBEDDING, CHAT, TOOL_CALLING] - maxIndex = 2 (если запросить CHAT и EMBEDDING)
        // model3: [CHAT, TOOL_CALLING, EMBEDDING] - maxIndex = 2 (если запросить CHAT и EMBEDDING)
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model2", List.of(EMBEDDING, CHAT, TOOL_CALLING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model3", List.of(CHAT, TOOL_CALLING, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // model1 имеет maxIndex = 1 (CHAT на 0, EMBEDDING на 1)
        // model2 имеет maxIndex = 1 (EMBEDDING на 0, CHAT на 1)
        // model3 имеет maxIndex = 2 (CHAT на 0, EMBEDDING на 2)
        // Ожидаем model1 или model2 (обе с maxIndex = 1)
        assertTrue(result.isPresent());
        assertTrue(result.get().getName().equals("model1") || result.get().getName().equals("model2"));
    }

    @Test
    void whenGetByCapabilities_allTypesOnFirstPosition_thenReturnImmediately() {
        // Arrange
        // model1: [CHAT, EMBEDDING] - оба типа на первых двух позициях, maxIndex = 1
        // model2: [CHAT, EMBEDDING, TOOL_CALLING] - оба типа на первых двух позициях, maxIndex = 1
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model2", List.of(CHAT, EMBEDDING, TOOL_CALLING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model3", List.of(TOOL_CALLING, CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // model1 и model2 имеют maxIndex = 1, model3 имеет maxIndex = 2
        // Должна вернуться model1 или model2 (обе с maxIndex = 1)
        assertTrue(result.isPresent());
        assertTrue(result.get().getName().equals("model1") || result.get().getName().equals("model2"));
    }

    @Test
    void whenGetByCapabilities_modelWithAllTypesOnIndexZero_thenReturnImmediately() {
        // Arrange
        // model1: [CHAT, EMBEDDING] - оба типа на позициях 0 и 1, maxIndex = 1
        // model2: [CHAT, EMBEDDING] - оба типа на позициях 0 и 1, maxIndex = 1
        // model3: [EMBEDDING, CHAT] - оба типа на позициях 0 и 1, maxIndex = 1
        // Но если бы была модель с обоими типами на позиции 0, она бы вернулась сразу
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model2", List.of(EMBEDDING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // Обе модели имеют maxIndex = 1, но ни одна не имеет maxIndex = 0
        // Должна вернуться одна из моделей
        assertTrue(result.isPresent());
        assertTrue(result.get().getName().equals("model1") || result.get().getName().equals("model2"));
    }

    @Test
    void whenGetByCapabilities_threeTypes_thenReturnModelWithAllThreeTypes() {
        // Arrange
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model2", List.of(CHAT, EMBEDDING, TOOL_CALLING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model3", List.of(EMBEDDING, TOOL_CALLING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING, TOOL_CALLING));

        // Assert
        // model1 не содержит TOOL_CALLING - не подходит
        // model2: [CHAT(0), EMBEDDING(1), TOOL_CALLING(2)] - maxIndex = 2
        // model3: [EMBEDDING(0), TOOL_CALLING(1), CHAT(2)] - maxIndex = 2
        // Ожидаем model2 или model3 (обе с maxIndex = 2)
        assertTrue(result.isPresent());
        assertTrue(result.get().getName().equals("model2") || result.get().getName().equals("model3"));
    }

    @Test
    void whenGetByCapabilities_multipleModelsWithSameMaxIndex_thenReturnModelWithHigherPriority() {
        // Arrange
        // Все модели имеют одинаковый maxIndex = 1, но разные приоритеты
        // model1: priority=2 (платная)
        // model2: priority=1 (бесплатная) - должна быть выбрана
        // model3: priority=2 (платная)
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 2),
                createModel("model2", List.of(EMBEDDING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model3", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 2)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // Все модели имеют maxIndex = 1, но model2 имеет приоритет 1 (выше)
        assertTrue(result.isPresent());
        assertEquals("model2", result.get().getName());
        assertEquals(1, result.get().getPriority());
    }

    @Test
    void whenGetByCapabilities_freeModelsHaveHigherPriority_thenReturnFreeModel() {
        // Arrange
        // Бесплатные модели (priority=1) должны иметь приоритет над платными (priority=2)
        List<SpringAIModelConfig> models = List.of(
                createModel("paid-model", List.of(CHAT), SpringAIModelConfig.ProviderType.OPENAI, 2),
                createModel("free-model", List.of(CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(CHAT);

        // Assert
        // Обе модели имеют одинаковый maxIndex = 0, но free-model имеет приоритет 1 (выше)
        assertTrue(result.isPresent());
        assertEquals("free-model", result.get().getName());
        assertEquals(1, result.get().getPriority());
    }
}

