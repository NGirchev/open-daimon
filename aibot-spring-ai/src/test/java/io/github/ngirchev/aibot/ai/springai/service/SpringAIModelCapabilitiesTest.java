package io.github.ngirchev.aibot.ai.springai.service;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.ngirchev.aibot.common.ai.ModelCapabilities.*;

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
        // GEMMA_LOCAL has EMBEDDING at index 1 (second in list)
        // NOMIC_LOCAL has EMBEDDING at index 0 (first in list)
        // Expect NOMIC_LOCAL as it has the capability at index 0
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(EMBEDDING);

        // Assert
        assertTrue(result.isPresent());
        // Verify it is the model with EMBEDDING in first position
        assertEquals("nomic-embed-text:v1.5", result.get().getName());
    }

    @Test
    void whenGetByCapability_CHAT_thenGEMMA_LOCAL_hasPriority() {
        // Arrange
        // GEMMA_LOCAL has CHAT at index 0 (first in list)
        // NOMIC_LOCAL has CHAT at index 1 (second in list)
        // Expect GEMMA_LOCAL as it has the capability at index 0
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(CHAT);

        // Assert
        assertTrue(result.isPresent());
        // Verify it is the model with CHAT in first position
        assertEquals("gemma3:1b", result.get().getName());
    }

    @Test
    void whenGetByCapability_unsupportedType_thenReturnEmpty() {
        // Arrange
        // Use a type not supported by any model
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

    // ========== Tests for getByCapabilities ==========

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
        // nomic-embed-text:v1.5 contains [EMBEDDING, CHAT]
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
        // gemma3:1b has only CHAT, does not contain EMBEDDING
        SpringAIModelType springAIModelType = createSpringAIModelType();

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(EMBEDDING, CHAT));

        // Assert
        // Should return nomic-embed-text:v1.5 as it contains both types
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
        // Create models with different indices to verify priority
        // model1: [CHAT, EMBEDDING] - maxIndex = 1
        // model2: [EMBEDDING, CHAT, TOOL_CALLING] - maxIndex = 2 (when requesting CHAT and EMBEDDING)
        // model3: [CHAT, TOOL_CALLING, EMBEDDING] - maxIndex = 2 (when requesting CHAT and EMBEDDING)
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model2", List.of(EMBEDDING, CHAT, TOOL_CALLING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model3", List.of(CHAT, TOOL_CALLING, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // model1 has maxIndex = 1 (CHAT at 0, EMBEDDING at 1)
        // model2 has maxIndex = 1 (EMBEDDING at 0, CHAT at 1)
        // model3 has maxIndex = 2 (CHAT at 0, EMBEDDING at 2)
        // Expect model1 or model2 (both with maxIndex = 1)
        assertTrue(result.isPresent());
        assertTrue(result.get().getName().equals("model1") || result.get().getName().equals("model2"));
    }

    @Test
    void whenGetByCapabilities_allTypesOnFirstPosition_thenReturnImmediately() {
        // Arrange
        // model1: [CHAT, EMBEDDING] - both types at first two positions, maxIndex = 1
        // model2: [CHAT, EMBEDDING, TOOL_CALLING] - both types at first two positions, maxIndex = 1
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model2", List.of(CHAT, EMBEDDING, TOOL_CALLING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model3", List.of(TOOL_CALLING, CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // model1 and model2 have maxIndex = 1, model3 has maxIndex = 2
        // Should return model1 or model2 (both with maxIndex = 1)
        assertTrue(result.isPresent());
        assertTrue(result.get().getName().equals("model1") || result.get().getName().equals("model2"));
    }

    @Test
    void whenGetByCapabilities_modelWithAllTypesOnIndexZero_thenReturnImmediately() {
        // Arrange
        // model1: [CHAT, EMBEDDING] - both types at positions 0 and 1, maxIndex = 1
        // model2: [CHAT, EMBEDDING] - both types at positions 0 and 1, maxIndex = 1
        // model3: [EMBEDDING, CHAT] - both types at positions 0 and 1, maxIndex = 1
        // If there were a model with both types at position 0, it would be returned immediately
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model2", List.of(EMBEDDING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // Both models have maxIndex = 1, neither has maxIndex = 0
        // Should return one of the models
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
        // model1 does not contain TOOL_CALLING - not a match
        // model2: [CHAT(0), EMBEDDING(1), TOOL_CALLING(2)] - maxIndex = 2
        // model3: [EMBEDDING(0), TOOL_CALLING(1), CHAT(2)] - maxIndex = 2
        // Expect model2 or model3 (both with maxIndex = 2)
        assertTrue(result.isPresent());
        assertTrue(result.get().getName().equals("model2") || result.get().getName().equals("model3"));
    }

    @Test
    void whenGetByCapabilities_multipleModelsWithSameMaxIndex_thenReturnModelWithHigherPriority() {
        // Arrange
        // All models have the same maxIndex = 1 but different priorities
        // model1: priority=2 (paid)
        // model2: priority=1 (free) - should be selected
        // model3: priority=2 (paid)
        List<SpringAIModelConfig> models = List.of(
                createModel("model1", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 2),
                createModel("model2", List.of(EMBEDDING, CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1),
                createModel("model3", List.of(CHAT, EMBEDDING), SpringAIModelConfig.ProviderType.OLLAMA, 2)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapabilities(Set.of(CHAT, EMBEDDING));

        // Assert
        // All models have maxIndex = 1, but model2 has priority 1 (higher)
        assertTrue(result.isPresent());
        assertEquals("model2", result.get().getName());
        assertEquals(1, result.get().getPriority());
    }

    @Test
    void whenGetByCapabilities_freeModelsHaveHigherPriority_thenReturnFreeModel() {
        // Arrange
        // Free models (priority=1) should have priority over paid (priority=2)
        List<SpringAIModelConfig> models = List.of(
                createModel("paid-model", List.of(CHAT), SpringAIModelConfig.ProviderType.OPENAI, 2),
                createModel("free-model", List.of(CHAT), SpringAIModelConfig.ProviderType.OLLAMA, 1)
        );
        SpringAIModelType springAIModelType = new SpringAIModelType(models);

        // Act
        Optional<SpringAIModelConfig> result = springAIModelType.getByCapability(CHAT);

        // Assert
        // Both models have the same maxIndex = 0, but free-model has priority 1 (higher)
        assertTrue(result.isPresent());
        assertEquals("free-model", result.get().getName());
        assertEquals(1, result.get().getPriority());
    }
}

