package io.github.ngirchev.aibot.ai.ui.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.ngirchev.aibot.ai.ui.controller.PageController;
import io.github.ngirchev.aibot.ai.ui.controller.UIAuthController;
import io.github.ngirchev.aibot.rest.config.RestAutoConfig;
import io.github.ngirchev.aibot.rest.service.RestAuthorizationService;
import io.github.ngirchev.aibot.rest.service.RestUserService;

/**
 * Auto-configuration for UI module.
 * Depends on RestAutoConfig for REST API.
 * Active only when UI module is enabled (ai-bot.ui.enabled=true).
 */
@AutoConfiguration
@AutoConfigureAfter(name = "io.github.ngirchev.aibot.rest.config.RestAutoConfig")
@EnableConfigurationProperties(UIProperties.class)
@ConditionalOnProperty(name = "ai-bot.ui.enabled", havingValue = "true")
public class UIAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public PageController pageController() {
        return new PageController();
    }

    @Bean
    @ConditionalOnMissingBean
    public UIAuthController uiAuthController(RestUserService restUserService,
                                            io.github.ngirchev.aibot.common.service.MessageLocalizationService messageLocalizationService) {
        return new UIAuthController(restUserService, messageLocalizationService);
    }
}
