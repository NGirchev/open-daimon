package io.github.ngirchev.opendaimon.ai.ui.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for UI module
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "open-daimon.ui")
public class UIProperties {

    /**
     * Whether UI module is enabled
     */
    private Boolean enabled = false;
}

