package io.github.ngirchev.aibot.common.ai.command;

import io.github.ngirchev.aibot.common.ai.ModelCapabilities;

import java.util.Map;
import java.util.Set;

public interface AICommand {
    String ROLE_FIELD = "role";
    String THREAD_KEY_FIELD = "threadKey";
    String ASSISTANT_ROLE_ID_FIELD = "assistantRoleId";
    String USER_ID_FIELD = "userId";

    Set<ModelCapabilities> modelCapabilities();
    Map<String, String> metadata();
    <T extends AICommandOptions> T options();
}
