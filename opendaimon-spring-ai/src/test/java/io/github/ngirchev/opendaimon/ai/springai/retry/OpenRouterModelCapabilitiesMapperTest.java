package io.github.ngirchev.opendaimon.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenRouterModelCapabilitiesMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fromOpenRouterModel_nullReturnsEmpty() throws Exception {
        assertTrue(OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(null).isEmpty());
    }

    @Test
    void fromOpenRouterModel_emptyNodeReturnsBaseCapabilities() throws Exception {
        JsonNode node = objectMapper.readTree("{}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.CHAT));
        assertTrue(caps.contains(ModelCapabilities.MODERATION));
        assertTrue(caps.contains(ModelCapabilities.SUMMARIZATION));
    }

    @Test
    void fromOpenRouterModel_freeTrueAddsFree() throws Exception {
        JsonNode node = objectMapper.readTree("{}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node, true);
        assertTrue(caps.contains(ModelCapabilities.FREE));
    }

    @Test
    void fromOpenRouterModel_freeFalseOmitsFree() throws Exception {
        JsonNode node = objectMapper.readTree("{}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node, false);
        assertFalse(caps.contains(ModelCapabilities.FREE));
    }

    @Test
    void fromOpenRouterModel_supportedParametersWithToolsAddsToolCalling() throws Exception {
        JsonNode node = objectMapper.readTree("{\"supported_parameters\":[\"tools\",\"temperature\"]}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.TOOL_CALLING));
        assertTrue(caps.contains(ModelCapabilities.WEB));
    }

    @Test
    void fromOpenRouterModel_supportedParametersWithToolChoiceAddsToolCalling() throws Exception {
        JsonNode node = objectMapper.readTree("{\"supported_parameters\":[\"tool_choice\"]}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.TOOL_CALLING));
    }

    @Test
    void fromOpenRouterModel_capabilitiesToolsTrueAddsToolCalling() throws Exception {
        JsonNode node = objectMapper.readTree("{\"capabilities\":{\"tools\":true}}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.TOOL_CALLING));
    }

    @Test
    void fromOpenRouterModel_architectureInputModalitiesImageAddsVision() throws Exception {
        JsonNode node = objectMapper.readTree("{\"architecture\":{\"input_modalities\":[\"text\",\"image\"]}}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.VISION));
    }

    @Test
    void fromOpenRouterModel_capabilitiesVisionTrueAddsVision() throws Exception {
        JsonNode node = objectMapper.readTree("{\"capabilities\":{\"vision\":true}}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.VISION));
    }

    @Test
    void fromOpenRouterModel_capabilitiesEmbeddingTrueAddsEmbedding() throws Exception {
        JsonNode node = objectMapper.readTree("{\"capabilities\":{\"embedding\":true}}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.EMBEDDING));
        assertTrue(caps.contains(ModelCapabilities.RERANK));
    }

    @Test
    void fromOpenRouterModel_supportedParametersEmbeddingAddsEmbedding() throws Exception {
        JsonNode node = objectMapper.readTree("{\"supported_parameters\":[\"embedding\"]}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.EMBEDDING));
    }

    @Test
    void fromOpenRouterModel_supportedParametersResponseFormatAddsStructuredOutput() throws Exception {
        JsonNode node = objectMapper.readTree("{\"supported_parameters\":[\"response_format\"]}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.STRUCTURED_OUTPUT));
    }

    @Test
    void fromOpenRouterModel_capabilitiesStructuredOutputTrueAddsStructuredOutput() throws Exception {
        JsonNode node = objectMapper.readTree("{\"capabilities\":{\"structured_output\":true}}");
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node);
        assertTrue(caps.contains(ModelCapabilities.STRUCTURED_OUTPUT));
    }

    @Test
    void fromOpenRouterModel_fullCapabilitiesReturnsAll() throws Exception {
        String json = """
                {
                  "supported_parameters": ["tools", "response_format"],
                  "architecture": {"input_modalities": ["text", "image"]},
                  "capabilities": {"tools": true, "vision": true, "embedding": true, "structured_output": true}
                }
                """;
        JsonNode node = objectMapper.readTree(json);
        Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(node, true);
        assertTrue(caps.contains(ModelCapabilities.FREE));
        assertTrue(caps.contains(ModelCapabilities.CHAT));
        assertTrue(caps.contains(ModelCapabilities.TOOL_CALLING));
        assertTrue(caps.contains(ModelCapabilities.WEB));
        assertTrue(caps.contains(ModelCapabilities.VISION));
        assertTrue(caps.contains(ModelCapabilities.EMBEDDING));
        assertTrue(caps.contains(ModelCapabilities.RERANK));
        assertTrue(caps.contains(ModelCapabilities.STRUCTURED_OUTPUT));
    }
}
