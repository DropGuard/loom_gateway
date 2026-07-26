import http from "k6/http";
import { check } from "k6";
import { Trend, Rate } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.1.0/index.js";
import crypto from "k6/crypto";
import encoding from "k6/encoding";

const BASE = __ENV.GATEWAY_URL || "http://gateway:8080";
const JWT_SECRET = "bench-secret-key-that-is-at-least-32-chars!";

const openLatency = new Trend("open_latency", true);
const authLatency = new Trend("auth_latency", true);
const cacheLatency = new Trend("cache_latency", true);
const openErrors = new Rate("open_errors");
const authErrors = new Rate("auth_errors");
const cacheErrors = new Rate("cache_errors");

// --- JWT (HS256) via k6/crypto ---

function signJwt(subject) {
    const header = encoding.b64encode(
        JSON.stringify({ alg: "HS256", typ: "JWT" }),
        "rawurl"
    );
    const payload = encoding.b64encode(
        JSON.stringify({
            sub: subject,
            iss: "k6-bench",
            iat: Math.floor(Date.now() / 1000),
            exp: Math.floor(Date.now() / 1000) + 3600,
        }),
        "rawurl"
    );
    const data = `${header}.${payload}`;
    const hasher = crypto.createHMAC("sha256", JWT_SECRET);
    hasher.update(data);
    const sig = hasher.digest("base64rawurl");
    return `${data}.${sig}`;
}

// --- Scenarios ---

export const options = {
    scenarios: {
        warmup: {
            executor: "constant-vus",
            vus: 10,
            duration: "10s",
            exec: "warmupEndpoint",
        },
        test: {
            executor: "constant-vus",
            vus: 100,
            duration: "60s",
            startTime: "12s",
            exec: "bothEndpoints",
        },
        cache: {
            // Repeated identical GETs to /bench/cache/data. After the first
            // request per key, both gateways serve from cache — this measures
            // the cache-served read path (no upstream round-trip).
            executor: "constant-vus",
            vus: 100,
            duration: "60s",
            startTime: "12s",
            exec: "cacheEndpoint",
        },
    },
};

// Pre-generate one token per VU (init context runs once per VU)
const token = signJwt("bench-user");

export function warmupEndpoint() {
    http.get(`${BASE}/bench/open/data`);
    http.get(`${BASE}/bench/auth/data`, {
        headers: { Authorization: `Bearer ${token}` },
    });
}

export function openEndpoint() {
    const res = http.get(`${BASE}/bench/open/data`);
    openLatency.add(res.timings.duration);
    openErrors.add(res.status !== 200);
    check(res, { "open 200": (r) => r.status === 200 });
}

export function bothEndpoints() {
    const openRes = http.get(`${BASE}/bench/open/data`);
    openLatency.add(openRes.timings.duration);
    openErrors.add(openRes.status !== 200);
    check(openRes, { "open 200": (r) => r.status === 200 });

    const authRes = http.get(`${BASE}/bench/auth/data`, {
        headers: { Authorization: `Bearer ${token}` },
    });
    authLatency.add(authRes.timings.duration);
    authErrors.add(authRes.status !== 200);
    check(authRes, { "auth 200": (r) => r.status === 200 });
}

export function cacheEndpoint() {
    const res = http.get(`${BASE}/bench/cache/data`);
    cacheLatency.add(res.timings.duration);
    cacheErrors.add(res.status !== 200);
    check(res, { "cache 200": (r) => r.status === 200 });
}

export function handleSummary(data) {
    const result = {
        gateway: __ENV.GATEWAY_NAME || "unknown",
        open_latency: data.metrics.open_latency.values,
        auth_latency: data.metrics.auth_latency.values,
        cache_latency: data.metrics.cache_latency.values,
        open_errors: data.metrics.open_errors.values,
        auth_errors: data.metrics.auth_errors.values,
        cache_errors: data.metrics.cache_errors.values,
        http_reqs: data.metrics.http_reqs.values,
    };
    const filename = `/results/${result.gateway}.json`;
    return {
        stdout: textSummary(data),
        [filename]: JSON.stringify(result, null, 2),
    };
}
