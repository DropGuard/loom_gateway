package com.gateway.testutil;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.util.*;

/**
 * Shared mock HttpServerRequest for unit tests.
 */
public class MockRequest implements HttpServerRequest {
    public final Map<String, String> headers = new HashMap<>();
    public HttpMethod method = HttpMethod.GET;
    public String uri = "/api/data";
    public String path = "/api/data";

    @Override public String getHeader(String name) { return headers.get(name); }
    @Override public String getHeader(CharSequence name) { return headers.get(name.toString()); }
    @Override public MultiMap headers() { return null; }
    @Override public HttpMethod method() { return method; }
    @Override public String uri() { return uri; }
    @Override public String path() { return path; }
    @Override public String query() { return null; }
    @Override public String scheme() { return null; }
    @Override public HostAndPort authority() { return null; }
    @Override public HostAndPort authority(boolean includeDefaultPort) { return null; }
    @Override public String host() { return null; }
    @Override public long bytesRead() { return 0; }
    @Override public HttpServerResponse response() { return null; }
    @Override public HttpVersion version() { return null; }
    @Override public String absoluteURI() { return null; }
    @Override public String getParamsCharset() { return null; }
    @Override public boolean isExpectMultipart() { return false; }
    @Override public boolean isEnded() { return false; }
    @Override public boolean isSSL() { return false; }
    @Override public int streamId() { return 0; }
    @Override public int cookieCount() { return 0; }
    @Override public MultiMap params(boolean decode) { return null; }
    @Override public MultiMap formAttributes() { return null; }
    @Override public String getFormAttribute(String s) { return null; }
    @Override public X509Certificate[] peerCertificateChain() { return new X509Certificate[0]; }
    @Override public SSLSession sslSession() { return null; }
    @Override public SocketAddress remoteAddress() { return null; }
    @Override public SocketAddress localAddress() { return null; }
    @Override public Cookie getCookie(String s) { return null; }
    @Override public Cookie getCookie(String s, String s1, String s2) { return null; }
    @Override public Set<Cookie> cookies(String s) { return Set.of(); }
    @Override public Set<Cookie> cookies() { return Set.of(); }
    @Override public Map<String, Cookie> cookieMap() { return Map.of(); }
    @Override public HttpServerRequest exceptionHandler(Handler<Throwable> h) { return null; }
    @Override public HttpServerRequest handler(Handler<Buffer> h) { return null; }
    @Override public HttpServerRequest pause() { return null; }
    @Override public HttpServerRequest resume() { return null; }
    @Override public HttpServerRequest fetch(long amount) { return null; }
    @Override public HttpServerRequest endHandler(Handler<Void> h) { return null; }
    @Override public HttpServerRequest setParamsCharset(String s) { return null; }
    @Override public HttpServerRequest setExpectMultipart(boolean b) { return null; }
    @Override public HttpServerRequest uploadHandler(Handler<HttpServerFileUpload> h) { return null; }
    @Override public HttpServerRequest customFrameHandler(Handler<HttpFrame> h) { return null; }
    @Override public HttpServerRequest streamPriorityHandler(Handler<StreamPriority> h) { return null; }
    @Override public Future<Buffer> body() { return null; }
    @Override public Future<Void> end() { return null; }
    @Override public Future<NetSocket> toNetSocket() { return null; }
    @Override public Future<ServerWebSocket> toWebSocket() { return null; }
    @Override public HttpConnection connection() { return null; }
    @Override public io.netty.handler.codec.DecoderResult decoderResult() { return null; }
}
