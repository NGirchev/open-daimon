package io.github.ngirchev.aibot.ai.ui.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for UI module
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai-bot.ui")
public class UIProperties {

    /**
     * Whether UI module is enabled
     */
    private Boolean enabled = false;
}

