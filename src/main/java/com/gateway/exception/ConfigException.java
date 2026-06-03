package com.gateway.exception;

/**
 * Thrown when configuration is invalid or missing.
 */
public class ConfigException extends GatewayException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
