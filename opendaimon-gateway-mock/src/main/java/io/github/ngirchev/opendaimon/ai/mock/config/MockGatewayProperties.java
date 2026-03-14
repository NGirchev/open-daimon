package io.github.ngirchev.opendaimon.ai.mock.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "open-daimon.ai.gateway-mock")
@Validated
@Getter
@Setter
public class MockGatewayProperties {
    
    /**
     * Enables/disables Mock Gateway.
     * Default is false (disabled).
     */
    private Boolean enabled = false;
}
