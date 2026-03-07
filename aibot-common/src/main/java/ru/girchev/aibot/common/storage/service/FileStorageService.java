package ru.girchev.aibot.common.storage.service;

import ru.girchev.aibot.common.storage.model.FileMetadata;

/**
 * Интерфейс для работы с файловым хранилищем.
 * Предоставляет базовые операции CRUD для файлов.
 */
public interface FileStorageService {

    /**
     * Сохраняет файл в хранилище.
     *
     * @param key уникальный ключ файла
     * @param data содержимое файла
     * @param contentType MIME тип файла
     * @param originalName оригинальное имя файла
     * @return метаданные сохраненного файла
     */
    FileMetadata save(String key, byte[] data, String contentType, String originalName);

    /**
     * Получает содержимое файла по ключу.
     *
     * @param key ключ файла
     * @return содержимое файла в виде массива байт
     * @throws RuntimeException если файл не найден
     */
    byte[] get(String key);

    /**
     * Удаляет файл из хранилища.
     *
     * @param key ключ файла
     */
    void delete(String key);

    /**
     * Проверяет существование файла в хранилище.
     *
     * @param key ключ файла
     * @return true если файл существует
     */
    boolean exists(String key);
}
