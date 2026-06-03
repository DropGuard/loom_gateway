package com.gateway.exception;

/**
 * Base exception for all gateway-specific exceptions.
 */
public abstract class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
