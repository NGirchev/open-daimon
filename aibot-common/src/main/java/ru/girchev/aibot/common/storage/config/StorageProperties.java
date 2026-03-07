package ru.girchev.aibot.common.storage.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Конфигурация файлового хранилища.
 * 
 * Все значения обязательны и должны быть указаны в application.yml.
 * По правилам проекта дефолтные значения НЕ задаются в коде.
 */
@ConfigurationProperties(prefix = "ai-bot.common.storage")
@Validated
@Getter
@Setter
public class StorageProperties {

    /**
     * Включить файловое хранилище (feature flag).
     */
    @NotNull(message = "enabled обязателен")
    private Boolean enabled;

    /**
     * Настройки MinIO хранилища.
     */
    @NotNull(message = "minio конфигурация обязательна")
    @Valid
    private MinioProperties minio;

    @Getter
    @Setter
    public static class MinioProperties {

        /**
         * URL endpoint MinIO сервера (например, http://localhost:9000).
         */
        @NotBlank(message = "endpoint обязателен")
        private String endpoint;

        /**
         * Access key для аутентификации в MinIO.
         */
        @NotBlank(message = "accessKey обязателен")
        private String accessKey;

        /**
         * Secret key для аутентификации в MinIO.
         */
        @NotBlank(message = "secretKey обязателен")
        private String secretKey;

        /**
         * Имя bucket для хранения файлов.
         */
        @NotBlank(message = "bucket обязателен")
        private String bucket;

        /**
         * Время жизни файлов в часах (TTL).
         */
        @NotNull(message = "ttlHours обязателен")
        @Min(value = 1, message = "ttlHours должен быть >= 1")
        private Integer ttlHours;
    }
}
