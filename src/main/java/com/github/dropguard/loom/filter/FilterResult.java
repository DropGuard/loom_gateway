package com.github.dropguard.loom.filter;

import io.vertx.core.http.HttpServerResponse;

/** Result of a filter execution. */
public record FilterResult(boolean continueChain, int statusCode, String message) {
    /** Continue to next filter */
    public static final FilterResult CONTINUE = new FilterResult(true, 0, null);

    /** Short-circuit with response */
    public static FilterResult stop(int statusCode, String message) {
        return new FilterResult(false, statusCode, message);
    }

    /** Write response and stop chain */
    public void writeResponse(HttpServerResponse response) {
        if (!continueChain && !response.ended()) {
            response.setStatusCode(statusCode).end(message);
        }
    }
}
