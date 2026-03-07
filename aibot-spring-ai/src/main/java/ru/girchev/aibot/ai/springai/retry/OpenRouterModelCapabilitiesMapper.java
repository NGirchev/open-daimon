package ru.girchev.aibot.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;
import ru.girchev.aibot.common.ai.ModelCapabilities;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Маппинг возможностей модели из ответа OpenRouter API (GET /v1/models) в наш enum {@link ModelCapabilities}.
 * Используется только для моделей, приходящих из OpenRouter (например, free-модели).
 * Для моделей из application.yml возможности не перезаписываются — берутся из конфига.
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
     * Строит множество наших capabilities по объекту модели из OpenRouter (элемент data[] из /v1/models).
     *
     * @param modelNode объект модели из ответа API (не null)
     * @return множество ModelCapabilities; пустое при невалидном modelNode
     */
    public static Set<ModelCapabilities> fromOpenRouterModel(JsonNode modelNode) {
        return fromOpenRouterModel(modelNode, false);
    }

    /**
     * Строит множество наших capabilities по объекту модели из OpenRouter.
     *
     * @param modelNode объект модели из ответа API (не null)
     * @param free если true, в множество добавляется {@link ModelCapabilities#FREE} (для бесплатных моделей OpenRouter)
     * @return множество ModelCapabilities; пустое при невалидном modelNode
     */
    public static Set<ModelCapabilities> fromOpenRouterModel(JsonNode modelNode, boolean free) {
        if (modelNode == null || modelNode.isMissingNode() || modelNode.isNull()) {
            return Set.of();
        }
        Set<ModelCapabilities> out = EnumSet.noneOf(ModelCapabilities.class);

        if (free) {
            out.add(ModelCapabilities.FREE);
        }

        // CHAT — все модели в списке чатовые
        out.add(ModelCapabilities.CHAT);

        // MODERATION — в OpenRouter все модели проходят модерацию
        out.add(ModelCapabilities.MODERATION);

        // SUMMARIZATION — любая чатовая модель может саммаризировать
        out.add(ModelCapabilities.SUMMARIZATION);

        boolean toolsSupported = hasToolsSupport(modelNode);
        if (toolsSupported) {
            out.add(ModelCapabilities.TOOL_CALLING);
            // WEB в OpenRouter — это tool_calling, мы передаём веб-тулы как tools
            out.add(ModelCapabilities.WEB);
        }

        if (hasVisionSupport(modelNode)) {
            out.add(ModelCapabilities.VISION);
        }

        if (hasEmbeddingSupport(modelNode)) {
            out.add(ModelCapabilities.EMBEDDING);
            // RERANK — переранжирование после vector search; модели с embedding обычно подходят
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
