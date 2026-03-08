package io.github.ngirchev.aibot.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Maps model capabilities from OpenRouter API response (GET /v1/models) to our {@link ModelCapabilities} enum.
 * Used only for models from OpenRouter (e.g. free models).
 * For models from application.yml capabilities are not overwritten — taken from config.
 */
public final class OpenRouterModelCapabilitiesMapper {

    private static final String SUPPORTED_PARAMETERS = "supported_parameters";
    private static final String CAPABILITIES = "capabilities";
    private static final String ARCHITECTURE = "architecture";
    private static final String INPUT_MODALITIES = "input_modalities";
    private static final String IMAGE = "image";
    private static final String TOOLS = "tools";
    private static final String TOOL_CHOICE = "tool_choice";
    private static final String VISION = "vision";
    private static final String EMBEDDING = "embedding";
    private static final String RESPONSE_FORMAT = "response_format";
    private static final String JSON_SCHEMA = "json_schema";
    private static final String STRUCTURED_OUTPUT = "structured_output";

    private OpenRouterModelCapabilitiesMapper() {
    }

    /**
     * Builds our capabilities set from OpenRouter model object (data[] element from /v1/models).
     *
     * @param modelNode model object from API response (not null)
     * @return ModelCapabilities set; empty for invalid modelNode
     */
    public static Set<ModelCapabilities> fromOpenRouterModel(JsonNode modelNode) {
        return fromOpenRouterModel(modelNode, false);
    }

    /**
     * Builds our capabilities set from OpenRouter model object.
     *
     * @param modelNode model object from API response (not null)
     * @param free if true, {@link ModelCapabilities#FREE} is added (for free OpenRouter models)
     * @return ModelCapabilities set; empty for invalid modelNode
     */
    public static Set<ModelCapabilities> fromOpenRouterModel(JsonNode modelNode, boolean free) {
        if (modelNode == null || modelNode.isMissingNode() || modelNode.isNull()) {
            return Set.of();
        }
        Set<ModelCapabilities> out = EnumSet.noneOf(ModelCapabilities.class);

        if (free) {
            out.add(ModelCapabilities.FREE);
        }

        // CHAT — all models in list are chat models
        out.add(ModelCapabilities.CHAT);

        // MODERATION — in OpenRouter all models go through moderation
        out.add(ModelCapabilities.MODERATION);

        // SUMMARIZATION — any chat model can summarize
        out.add(ModelCapabilities.SUMMARIZATION);

        boolean toolsSupported = hasToolsSupport(modelNode);
        if (toolsSupported) {
            out.add(ModelCapabilities.TOOL_CALLING);
            // WEB in OpenRouter is tool_calling; we pass web tools as tools
            out.add(ModelCapabilities.WEB);
        }

        if (hasVisionSupport(modelNode)) {
            out.add(ModelCapabilities.VISION);
        }

        if (hasEmbeddingSupport(modelNode)) {
            out.add(ModelCapabilities.EMBEDDING);
            // RERANK — reranking after vector search; embedding models usually fit
            out.add(ModelCapabilities.RERANK);
        }

        if (hasStructuredOutputSupport(modelNode)) {
            out.add(ModelCapabilities.STRUCTURED_OUTPUT);
        }

        return Set.copyOf(out);
    }

    private static boolean hasToolsSupport(JsonNode modelNode) {
        JsonNode supportedParams = modelNode.path(SUPPORTED_PARAMETERS);
        if (supportedParams.isArray()) {
            for (JsonNode n : supportedParams) {
                String v = n.asText("");
                String lower = v.toLowerCase(Locale.ROOT);
                if (lower.contains(TOOLS) || lower.contains(TOOL_CHOICE)) {
                    return true;
                }
            }
        }
        JsonNode capabilities = modelNode.path(CAPABILITIES);
        if (capabilities.isObject()) {
            JsonNode tools = capabilities.get(TOOLS);
            if (tools != null && tools.asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasVisionSupport(JsonNode modelNode) {
        JsonNode arch = modelNode.path(ARCHITECTURE);
        if (arch.isObject()) {
            JsonNode inputMod = arch.path(INPUT_MODALITIES);
            if (inputMod.isArray()) {
                for (JsonNode m : inputMod) {
                    if (IMAGE.equalsIgnoreCase(m.asText(""))) {
                        return true;
                    }
                }
            }
        }
        JsonNode capabilities = modelNode.path(CAPABILITIES);
        if (capabilities.isObject()) {
            JsonNode vision = capabilities.get(VISION);
            if (vision != null && vision.asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEmbeddingSupport(JsonNode modelNode) {
        JsonNode capabilities = modelNode.path(CAPABILITIES);
        if (capabilities.isObject()) {
            JsonNode emb = capabilities.get(EMBEDDING);
            if (emb != null && emb.asBoolean(false)) {
                return true;
            }
        }
        JsonNode supportedParams = modelNode.path(SUPPORTED_PARAMETERS);
        if (supportedParams.isArray()) {
            for (JsonNode n : supportedParams) {
                if (EMBEDDING.equalsIgnoreCase(n.asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasStructuredOutputSupport(JsonNode modelNode) {
        JsonNode supportedParams = modelNode.path(SUPPORTED_PARAMETERS);
        if (supportedParams.isArray()) {
            for (JsonNode n : supportedParams) {
                String v = n.asText("").toLowerCase(Locale.ROOT);
                if (v.contains(RESPONSE_FORMAT) || v.contains(JSON_SCHEMA) || v.contains(STRUCTURED_OUTPUT)) {
                    return true;
                }
            }
        }
        JsonNode capabilities = modelNode.path(CAPABILITIES);
        if (capabilities.isObject()) {
            JsonNode so = capabilities.get(STRUCTURED_OUTPUT);
            if (so != null && so.asBoolean(false)) {
                return true;
            }
        }
        return false;
    }
}
