package io.github.ngirchev.opendaimon.rest.exception;

/**
 * Exception thrown on authorization failure
 */
public class UnauthorizedException extends RuntimeException {
    
    public UnauthorizedException(String message) {
        super(message);
    }
    
    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}

