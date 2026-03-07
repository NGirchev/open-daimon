package ru.girchev.aibot.common.exception;

/**
 * Исключение при невозможности извлечь текст из приложенного документа (PDF, DOCX и др.).
 * Например: image-only PDF, пустые страницы. Сообщение предназначено для показа пользователю.
 */
public class DocumentContentNotExtractableException extends RuntimeException {

    public DocumentContentNotExtractableException(String message) {
        super(message);
    }

    public DocumentContentNotExtractableException(String message, Throwable cause) {
        super(message, cause);
    }
}
