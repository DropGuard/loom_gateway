package com.github.dropguard.loom.testutil;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.net.HostAndPort;

import java.util.Set;

public class MockResponse implements HttpServerResponse {
    public int statusCode = 200;
    public String body;
    public boolean ended = false;
    private final MultiMap headers = new HeadersMultiMap();

    // --- Methods used by tests and filters ---
    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public HttpServerResponse setStatusCode(int code) {
        this.statusCode = code;
        return this;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public boolean ended() {
        return ended;
    }

    @Override
    public Future<Void> end(String chunk) {
        this.body = chunk;
        this.ended = true;
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end(Buffer chunk) {
        this.body = chunk.toString();
        this.ended = true;
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
        this.ended = true;
        return Future.succeededFuture();
    }

    // --- All remaining abstract methods (no-op stubs) ---
    @Override
    public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        return this;
    }

    @Override
    public HttpServerResponse setWriteQueueMaxSize(int maxSize) {
        return this;
    }

    @Override
    public HttpServerResponse drainHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public String getStatusMessage() {
        return null;
    }

    @Override
    public HttpServerResponse setStatusMessage(String msg) {
        return this;
    }

    @Override
    public HttpServerResponse setChunked(boolean b) {
        return this;
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public HttpServerResponse putHeader(String name, String value) {
        headers.set(name, value);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        headers.set(name, value);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(String name, Iterable<String> values) {
        return this;
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
        return this;
    }

    @Override
    public MultiMap trailers() {
        return null;
    }

    @Override
    public HttpServerResponse putTrailer(String name, String value) {
        return this;
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
        return this;
    }

    @Override
    public HttpServerResponse putTrailer(String name, Iterable<String> values) {
        return this;
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> values) {
        return this;
    }

    @Override
    public HttpServerResponse closeHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public HttpServerResponse endHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public Future<Void> writeHead() {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> write(String chunk, String enc) {
        return Future.succeededFuture();
    }

    @Override
    public void write(String chunk, String enc, Handler<AsyncResult<Void>> handler) {}

    @Override
    public Future<Void> write(String chunk) {
        return Future.succeededFuture();
    }

    @Override
    public void write(String chunk, Handler<AsyncResult<Void>> handler) {}

    @Override
    public Future<Void> write(Buffer data) {
        return Future.succeededFuture();
    }

    @Override
    public HttpServerResponse writeContinue() {
        return this;
    }

    @Override
    public Future<Void> writeEarlyHints(MultiMap headers) {
        return Future.succeededFuture();
    }

    @Override
    public void writeEarlyHints(MultiMap headers, Handler<AsyncResult<Void>> handler) {}

    @Override
    public void end(String chunk, Handler<AsyncResult<Void>> handler) {
        this.body = chunk;
        this.ended = true;
    }

    @Override
    public Future<Void> end(String chunk, String enc) {
        this.ended = true;
        return Future.succeededFuture();
    }

    @Override
    public void end(String chunk, String enc, Handler<AsyncResult<Void>> handler) {
        this.ended = true;
    }

    @Override
    public void end(Buffer chunk, Handler<AsyncResult<Void>> handler) {
        this.ended = true;
    }

    @Override
    public Future<Void> sendFile(String filename, long offset, long length) {
        return Future.succeededFuture();
    }

    @Override
    public HttpServerResponse sendFile(
            String filename, long offset, long length, Handler<AsyncResult<Void>> handler) {
        return this;
    }

    @Override
    public void close() {}

    @Override
    public boolean closed() {
        return false;
    }

    @Override
    public boolean headWritten() {
        return false;
    }

    @Override
    public HttpServerResponse headersEndHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public HttpServerResponse bodyEndHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public long bytesWritten() {
        return 0;
    }

    @Override
    public int streamId() {
        return 0;
    }

    @Override
    public Future<HttpServerResponse> push(
            HttpMethod method, String host, String path, MultiMap headers) {
        return Future.succeededFuture(this);
    }

    @Override
    public Future<HttpServerResponse> push(
            HttpMethod method, HostAndPort authority, String path, MultiMap headers) {
        return Future.succeededFuture(this);
    }

    @Override
    public boolean reset(long code) {
        return false;
    }

    @Override
    public HttpServerResponse writeCustomFrame(int type, int flags, Buffer payload) {
        return this;
    }

    @Override
    public HttpServerResponse addCookie(Cookie cookie) {
        return this;
    }

    @Override
    public Cookie removeCookie(String name, boolean invalidate) {
        return null;
    }

    @Override
    public Set<Cookie> removeCookies(String name, boolean invalidate) {
        return Set.of();
    }

    @Override
    public Cookie removeCookie(String name, String domain, String path, boolean invalidate) {
        return null;
    }

    @Override
    public boolean writeQueueFull() {
        return false;
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        this.ended = true;
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {}
}
