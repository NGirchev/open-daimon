package io.github.ngirchev.opendaimon.common.storage.service;

import io.github.ngirchev.opendaimon.common.storage.model.FileMetadata;

/**
 * Interface for file storage operations.
 * Provides basic CRUD operations for files.
 */
public interface FileStorageService {

    /**
     * Saves file to storage.
     *
     * @param key unique file key
     * @param data file content
     * @param contentType file MIME type
     * @param originalName original file name
     * @return metadata of saved file
     */
    FileMetadata save(String key, byte[] data, String contentType, String originalName);

    /**
     * Gets file content by key.
     *
     * @param key file key
     * @return file content as byte array
     * @throws RuntimeException if file not found
     */
    byte[] get(String key);

    /**
     * Deletes file from storage.
     *
     * @param key file key
     */
    void delete(String key);

    /**
     * Checks if file exists in storage.
     *
     * @param key file key
     * @return true if file exists
     */
    boolean exists(String key);
}
