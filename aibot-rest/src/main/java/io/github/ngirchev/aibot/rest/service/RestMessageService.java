package io.github.ngirchev.aibot.rest.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.model.*;
import io.github.ngirchev.aibot.common.service.AIBotMessageService;
import io.github.ngirchev.aibot.rest.model.RestUser;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for REST messages.
 * Creates Message entity for REST API requests.
 * Replaces RestApiUserRequestService.
 */
@RequiredArgsConstructor
public class RestMessageService {

    private final AIBotMessageService messageService;
    private final RestUserService restUserService;
    private final CoreCommonProperties coreCommonProperties;
    
    /**
     * Saves USER message from REST user with conversation thread.
     * Automatically gets or creates active thread and role for the user.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     */
    @Transactional
    public AIBotMessage saveUserMessage(
            RestUser user,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            HttpServletRequest request) {
        
        // Get or create assistant role for user via RestUserService
        String roleContent = assistantRoleContent != null 
                ? assistantRoleContent 
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = restUserService.getOrCreateAssistantRole(user, roleContent);
        
        // Prepare REST-specific metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("client_ip", getClientIp(request));
        metadata.put("user_agent", request.getHeader("User-Agent"));
        metadata.put("endpoint", request.getRequestURI());
        
        // Use base MessageService to save message
        return messageService.saveUserMessage(
                user, 
                content, 
                requestType, 
                assistantRole, 
                metadata);
    }
    
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Gets client_ip from message metadata (for REST)
     */
    public String getClientIpFromMetadata(AIBotMessage message) {
        if (message.getMetadata() == null) {
            return null;
        }
        Object clientIp = message.getMetadata().get("client_ip");
        return clientIp != null ? clientIp.toString() : null;
    }

    /**
     * Gets user_agent from message metadata (for REST)
     */
    public String getUserAgentFromMetadata(AIBotMessage message) {
        if (message.getMetadata() == null) {
            return null;
        }
        Object userAgent = message.getMetadata().get("user_agent");
        return userAgent != null ? userAgent.toString() : null;
    }

    /**
     * Gets endpoint from message metadata (for REST)
     */
    public String getEndpointFromMetadata(AIBotMessage message) {
        if (message.getMetadata() == null) {
            return null;
        }
        Object endpoint = message.getMetadata().get("endpoint");
        return endpoint != null ? endpoint.toString() : null;
    }
}

