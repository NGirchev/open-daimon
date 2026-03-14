package io.github.ngirchev.opendaimon.rest.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.*;

/**
 * Shared logic for REST chat handlers (message and stream).
 * Reduces duplication between RestChatMessageCommandHandler and RestChatStreamMessageCommandHandler.
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public class RestChatHandlerSupport {

    private static final String LOG_ERROR_REST_REQUEST = "Error processing REST API request";
    private static final String LOG_FAILED_SERIALIZE_METADATA = "Failed to serialize metadata: {}";

    private final ObjectMapper objectMapper;
    private final MessageLocalizationService messageLocalizationService;
    private final OpenDaimonMessageService messageService;

    public static String getRequestLanguage(RestChatCommand command) {
        return command.request() != null && command.request().getLocale() != null
                ? command.request().getLocale().getLanguage() : "ru";
    }

    public static Map<String, String> buildMetadata(ConversationThread thread, String assistantRoleContentFromRole,
                                                   Long assistantRoleId, Long userId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(THREAD_KEY_FIELD, thread.getThreadKey());
        metadata.put(ASSISTANT_ROLE_ID_FIELD, assistantRoleId.toString());
        metadata.put(USER_ID_FIELD, userId.toString());
        metadata.put(ROLE_FIELD, assistantRoleContentFromRole);
        return metadata;
    }

    public RuntimeException handleProcessingError(RestChatCommand command,
                                                  OpenDaimonMessage userMessage,
                                                  Set<ModelCapabilities> modelCapabilities,
                                                  Exception e) {
        if (AIUtils.shouldLogWithoutStacktrace(e)) {
            log.error("{}: {}", LOG_ERROR_REST_REQUEST, AIUtils.getRootCauseMessage(e));
        } else {
            log.error(LOG_ERROR_REST_REQUEST, e);
        }
        Set<ModelCapabilities> caps = modelCapabilities.isEmpty() ? Set.of(ModelCapabilities.CHAT) : modelCapabilities;
        Map<String, Object> errorMetadata = createErrorMetadata(caps, e);
        String errorDataJson = serializeToJson(errorMetadata);
        String lang = getRequestLanguage(command);
        String errorMessage = messageLocalizationService.getMessage("rest.error.processing", lang, e.getMessage());
        if (userMessage != null) {
            String errorRoleContent = userMessage.getAssistantRole() != null
                    ? userMessage.getAssistantRole().getContent()
                    : null;
            messageService.saveAssistantErrorMessage(
                    userMessage.getUser(),
                    errorMessage,
                    modelCapabilities.toString(),
                    errorRoleContent,
                    errorDataJson);
        }
        return new RuntimeException(errorMessage, e);
    }

    public static Map<String, Object> createErrorMetadata(Set<ModelCapabilities> modelCapabilities, Exception error) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("model", modelCapabilities.toString());
        metadata.put("errorType", error.getClass().getSimpleName());
        metadata.put("errorMessage", error.getMessage());
        metadata.put("timestamp", System.currentTimeMillis());
        return metadata;
    }

    public String serializeToJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn(LOG_FAILED_SERIALIZE_METADATA, e.getMessage());
            return null;
        }
    }
}
