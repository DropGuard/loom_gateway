import http from "k6/http";
import { check } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.1.0/index.js";
import crypto from "k6/crypto";
import encoding from "k6/encoding";

const BASE = __ENV.GATEWAY_URL || "http://gateway:8080";
const JWT_SECRET = "bench-secret-key-that-is-at-least-32-chars!";

// --- Custom Metrics ---
const openLatency = new Trend("open_latency", true);
const authLatency = new Trend("auth_latency", true);
const cacheLatency = new Trend("cache_latency", true);

const openErrors = new Rate("open_errors");
const authErrors = new Rate("auth_errors");
const cacheErrors = new Rate("cache_errors");

const openReqs = new Counter("open_reqs");
const authReqs = new Counter("auth_reqs");
const cacheReqs = new Counter("cache_reqs");

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

// Pre-generate one token per VU during init
const token = signJwt("bench-user");

// --- Scenarios (Time-Decoupled & Single-Variable Isolated) ---
export const options = {
    discardResponseBodies: true,
    summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "p(99.9)", "max"],
    scenarios: {
        // Stage 0: Extended Warmup (30s) - Full JVM C2 JIT compilation & pool warmup
        warmup: {
            executor: "constant-vus",
            vus: 20,
            duration: "30s",
            exec: "warmupEndpoint",
        },
        // Stage 1: Pure Open Proxy (30s)
        open_proxy: {
            executor: "constant-vus",
            vus: 100,
            duration: "30s",
            startTime: "35s",
            exec: "openEndpoint",
        },
        // Stage 2: Pure JWT Auth Proxy (30s)
        auth_proxy: {
            executor: "constant-vus",
            vus: 100,
            duration: "30s",
            startTime: "70s",
            exec: "authEndpoint",
        },
        // Stage 3: Pure Cache Read Path (30s)
        cache_hit: {
            executor: "constant-vus",
            vus: 100,
            duration: "30s",
            startTime: "105s",
            exec: "cacheEndpoint",
        },
    },
};

export function warmupEndpoint() {
    http.get(`${BASE}/bench/open/data`);
    http.get(`${BASE}/bench/auth/data`, {
        headers: { Authorization: `Bearer ${token}` },
    });
    http.get(`${BASE}/bench/cache/data`);
}

export function openEndpoint() {
    const res = http.get(`${BASE}/bench/open/data`);
    openLatency.add(res.timings.duration);
    openReqs.add(1);
    const isError = res.status !== 200;
    openErrors.add(isError);
    check(res, { "open 200": (r) => r.status === 200 });
}

export function authEndpoint() {
    const res = http.get(`${BASE}/bench/auth/data`, {
        headers: { Authorization: `Bearer ${token}` },
    });
    authLatency.add(res.timings.duration);
    authReqs.add(1);
    const isError = res.status !== 200;
    authErrors.add(isError);
    check(res, { "auth 200": (r) => r.status === 200 });
}

export function cacheEndpoint() {
    const res = http.get(`${BASE}/bench/cache/data`);
    cacheLatency.add(res.timings.duration);
    cacheReqs.add(1);
    const isError = res.status !== 200;
    cacheErrors.add(isError);
    check(res, { "cache 200": (r) => r.status === 200 });
}

export function handleSummary(data) {
    const scenarioDurationSec = 30.0;
    const openCount = data.metrics.open_reqs ? data.metrics.open_reqs.values.count : 0;
    const authCount = data.metrics.auth_reqs ? data.metrics.auth_reqs.values.count : 0;
    const cacheCount = data.metrics.cache_reqs ? data.metrics.cache_reqs.values.count : 0;

    const result = {
        gateway: __ENV.GATEWAY_NAME || "unknown",
        open: {
            latency: data.metrics.open_latency ? data.metrics.open_latency.values : {},
            errors: data.metrics.open_errors ? data.metrics.open_errors.values.rate : 0,
            reqs: openCount,
            rps: openCount / scenarioDurationSec,
        },
        auth: {
            latency: data.metrics.auth_latency ? data.metrics.auth_latency.values : {},
            errors: data.metrics.auth_errors ? data.metrics.auth_errors.values.rate : 0,
            reqs: authCount,
            rps: authCount / scenarioDurationSec,
        },
        cache: {
            latency: data.metrics.cache_latency ? data.metrics.cache_latency.values : {},
            errors: data.metrics.cache_errors ? data.metrics.cache_errors.values.rate : 0,
            reqs: cacheCount,
            rps: cacheCount / scenarioDurationSec,
        },
        http_reqs: data.metrics.http_reqs ? data.metrics.http_reqs.values : {},
    };

    const filename = `/results/${result.gateway}.json`;
    return {
        stdout: textSummary(data),
        [filename]: JSON.stringify(result, null, 2),
    };
}
