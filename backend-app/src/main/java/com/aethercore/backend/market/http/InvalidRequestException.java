package com.aethercore.backend.market.http;

/**
 * Domain-specific exception thrown when HTTP request input (header, path variable, payload)
 * fails canonical format or validation rules. Narrowed to prevent internal IllegalArgumentException
 * from being conflated with client input errors.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
