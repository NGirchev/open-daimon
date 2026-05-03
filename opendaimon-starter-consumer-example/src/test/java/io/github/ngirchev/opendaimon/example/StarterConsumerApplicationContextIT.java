package io.github.ngirchev.opendaimon.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.github.ngirchev.opendaimon.rest.controller.SessionController;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "open-daimon.ai.spring-ai.mock=true")
class StarterConsumerApplicationContextIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void applicationContextStartsWithStarterRestAndMicrometerFallback() {
        assertThat(applicationContext.getBean(MeterRegistry.class))
                .isNotNull();
        assertThat(applicationContext.getBean(OpenDaimonMeterRegistry.class))
                .isNotNull();
        assertThat(applicationContext.getBean(SessionController.class))
                .isNotNull();
    }
}
