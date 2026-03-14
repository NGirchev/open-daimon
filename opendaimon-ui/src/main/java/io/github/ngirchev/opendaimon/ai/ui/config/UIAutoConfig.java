package io.github.ngirchev.opendaimon.ai.ui.config;

import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.ngirchev.opendaimon.ai.ui.controller.PageController;
import io.github.ngirchev.opendaimon.ai.ui.controller.UIAuthController;
import io.github.ngirchev.opendaimon.rest.service.RestUserService;

/**
 * Auto-configuration for UI module.
 * Depends on RestAutoConfig for REST API.
 * Active only when UI module is enabled (open-daimon.ui.enabled=true).
 */
@AutoConfiguration
@AutoConfigureAfter(name = "io.github.ngirchev.opendaimon.rest.config.RestAutoConfig")
@EnableConfigurationProperties(UIProperties.class)
@ConditionalOnProperty(name = "open-daimon.ui.enabled", havingValue = "true")
public class UIAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public PageController pageController() {
        return new PageController();
    }

    @Bean
    @ConditionalOnMissingBean
    public UIAuthController uiAuthController(RestUserService restUserService,
                                            MessageLocalizationService messageLocalizationService) {
        return new UIAuthController(restUserService, messageLocalizationService);
    }
}
