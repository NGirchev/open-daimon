package ru.girchev.aibot.common.storage.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.girchev.aibot.common.storage.service.FileStorageService;
import ru.girchev.aibot.common.storage.service.MinioFileStorageService;

/**
 * Автоконфигурация для файлового хранилища.
 * 
 * Активируется при ai-bot.common.storage.enabled=true
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = "ai-bot.common.storage.enabled", havingValue = "true")
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public FileStorageService fileStorageService(StorageProperties storageProperties) {
        log.info("Initializing MinIO FileStorageService with endpoint: {}", 
                storageProperties.getMinio().getEndpoint());
        return new MinioFileStorageService(storageProperties.getMinio());
    }
}
