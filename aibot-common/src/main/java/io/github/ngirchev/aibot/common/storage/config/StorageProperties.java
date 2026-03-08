package io.github.ngirchev.aibot.common.storage.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * File storage configuration.
 *
 * All values are required and must be specified in application.yml.
 * Per project rules, default values are NOT set in code.
 */
@ConfigurationProperties(prefix = "ai-bot.common.storage")
@Validated
@Getter
@Setter
public class StorageProperties {

    /**
     * Enable file storage (feature flag).
     */
    @NotNull(message = "enabled is required")
    private Boolean enabled;

    /**
     * MinIO storage settings.
     */
    @NotNull(message = "minio configuration is required")
    @Valid
    private MinioProperties minio;

    @Getter
    @Setter
    public static class MinioProperties {

        /**
         * MinIO server endpoint URL (e.g. http://localhost:9000).
         */
        @NotBlank(message = "endpoint is required")
        private String endpoint;

        /**
         * Access key for MinIO authentication.
         */
        @NotBlank(message = "accessKey is required")
        private String accessKey;

        /**
         * Secret key for MinIO authentication.
         */
        @NotBlank(message = "secretKey is required")
        private String secretKey;

        /**
         * Bucket name for file storage.
         */
        @NotBlank(message = "bucket is required")
        private String bucket;

        /**
         * Time-to-live for files in hours (TTL).
         */
        @NotNull(message = "ttlHours is required")
        @Min(value = 1, message = "ttlHours must be >= 1")
        private Integer ttlHours;
    }
}
