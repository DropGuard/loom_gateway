package com.github.dropguard.loom.exception;

/** Thrown when the upstream service is unreachable, times out, or returns an invalid response. */
public class UpstreamException extends GatewayException {

    private final int statusCode;

    public UpstreamException(String message) {
        super(message);
        this.statusCode = 502;
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 502;
    }

    public UpstreamException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public UpstreamException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
