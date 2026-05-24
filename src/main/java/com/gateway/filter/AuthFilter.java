package com.gateway.filter;

import com.gateway.model.RouteConfig.AuthConfig;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.text.ParseException;
import java.util.Date;
import java.util.List;

/**
 * Validates authentication based on route configuration.
 * Supports "jwt" (HMAC signature verification) and "api-key" (header matching).
 */
@Singleton
public class AuthFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(AuthFilter.class);

    @Override
    public int order() {
        return 100;
    }

    @Override
    public FilterResult filter(FilterContext context) {
        AuthConfig auth = context.route() != null ? context.route().auth() : null;
        if (auth == null || !auth.required()) {
            return FilterResult.CONTINUE;
        }

        String type = auth.type() != null ? auth.type().toLowerCase() : "";
        return switch (type) {
            case "jwt" -> validateJwt(context, auth);
            case "api-key" -> validateApiKey(context, auth);
            default -> {
                LOG.warnf("Unknown auth type '%s' for route '%s'", type, context.route().id());
                yield FilterResult.CONTINUE;
            }
        };
    }

    private FilterResult validateJwt(FilterContext context, AuthConfig auth) {
        HttpServerRequest request = context.request();
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return FilterResult.stop(401, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        String secret = auth.secret();
        if (secret == null || secret.isEmpty()) {
            LOG.error("JWT secret is not configured for route");
            return FilterResult.stop(500, "Internal configuration error");
        }

        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(secret);

            if (!signedJWT.verify(verifier)) {
                return FilterResult.stop(401, "Invalid JWT signature");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            Date now = new Date();

            // Check expiration
            if (claims.getExpirationTime() != null && now.after(claims.getExpirationTime())) {
                return FilterResult.stop(401, "JWT Token expired");
            }

            // Check not-before
            if (claims.getNotBeforeTime() != null && now.before(claims.getNotBeforeTime())) {
                return FilterResult.stop(401, "JWT Token not yet valid");
            }

            // Pass claims to context for downstream use
            context.setAttribute("claims", claims.getClaims());

        } catch (ParseException e) {
            return FilterResult.stop(401, "Invalid JWT format");
        } catch (JOSEException e) {
            LOG.error("JWT verification failed", e);
            return FilterResult.stop(500, "Internal authentication error");
        }

        return FilterResult.CONTINUE;
    }

    private FilterResult validateApiKey(FilterContext context, AuthConfig auth) {
        HttpServerRequest request = context.request();
        String apiKey = request.getHeader("X-API-Key");

        if (apiKey == null || apiKey.isEmpty()) {
            return FilterResult.stop(401, "Missing API Key");
        }

        List<String> validKeys = auth.apiKeys();
        if (validKeys == null || !validKeys.contains(apiKey)) {
            return FilterResult.stop(401, "Invalid API Key");
        }

        return FilterResult.CONTINUE;
    }
}
