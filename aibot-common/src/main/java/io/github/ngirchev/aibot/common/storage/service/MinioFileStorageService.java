package io.github.ngirchev.aibot.common.storage.service;

import io.minio.*;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.aibot.common.storage.config.StorageProperties;
import io.github.ngirchev.aibot.common.storage.model.FileMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;

/**
 * FileStorageService implementation for MinIO storage.
 */
@Slf4j
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioFileStorageService(StorageProperties.MinioProperties minioProperties) {
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
        this.bucket = minioProperties.getBucket();
        
        initializeBucket();
    }

    /**
     * Initializes bucket if it does not exist.
     */
    private void initializeBucket() {
        try {
            boolean bucketExists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build()
            );
            if (!bucketExists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build()
                );
                log.info("Created MinIO bucket: {}", bucket);
            } else {
                log.info("MinIO bucket already exists: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Error initializing MinIO bucket: {}", bucket, e);
            throw new RuntimeException("Failed to initialize MinIO bucket", e);
        }
    }

    @Override
    public FileMetadata save(String key, byte[] data, String contentType, String originalName) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(new ByteArrayInputStream(data), data.length, -1)
                            .contentType(contentType)
                            .build()
            );
            
            log.debug("Saved file to MinIO: key={}, size={}, contentType={}", key, data.length, contentType);
            
            return new FileMetadata(
                    key,
                    contentType,
                    originalName,
                    data.length,
                    OffsetDateTime.now()
            );
        } catch (ErrorResponseException | InsufficientDataException | InternalException |
                 InvalidKeyException | InvalidResponseException | IOException |
                 NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("Error saving file to MinIO: key={}", key, e);
            throw new RuntimeException("Failed to save file to MinIO", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try (var response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .build()
        )) {
            byte[] data = response.readAllBytes();
            log.debug("Retrieved file from MinIO: key={}, size={}", key, data.length);
            return data;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                log.warn("File not found in MinIO: key={}", key);
                throw new RuntimeException("File not found: " + key, e);
            }
            log.error("Error getting file from MinIO: key={}", key, e);
            throw new RuntimeException("Failed to get file from MinIO", e);
        } catch (InsufficientDataException | InternalException | InvalidKeyException |
                 InvalidResponseException | IOException | NoSuchAlgorithmException |
                 ServerException | XmlParserException e) {
            log.error("Error getting file from MinIO: key={}", key, e);
            throw new RuntimeException("Failed to get file from MinIO", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .build()
            );
            log.debug("Deleted file from MinIO: key={}", key);
        } catch (ErrorResponseException | InsufficientDataException | InternalException |
                 InvalidKeyException | InvalidResponseException | IOException |
                 NoSuchAlgorithmException | ServerException | XmlParserException e) {
            log.error("Error deleting file from MinIO: key={}", key, e);
            throw new RuntimeException("Failed to delete file from MinIO", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            log.error("Error checking file existence in MinIO: key={}", key, e);
            throw new RuntimeException("Failed to check file existence in MinIO", e);
        } catch (InsufficientDataException | InternalException | InvalidKeyException |
                 InvalidResponseException | IOException | NoSuchAlgorithmException |
                 ServerException | XmlParserException e) {
            log.error("Error checking file existence in MinIO: key={}", key, e);
            throw new RuntimeException("Failed to check file existence in MinIO", e);
        }
    }
}
