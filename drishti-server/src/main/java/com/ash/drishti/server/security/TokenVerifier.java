/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.security;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies compact HS256 JWTs minted by the console: signature (constant-time), {@code alg}, {@code exp}
 * and {@code sub}. Only HS256 is accepted; {@code none} and every other algorithm are rejected.
 */
public final class TokenVerifier {

    private final byte[] key;
    private final long skewSeconds;
    private final ObjectMapper json = new ObjectMapper();

    public TokenVerifier(String secret, long skewSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("drishti.security.secret must be at least 32 bytes when security is enabled");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.skewSeconds = skewSeconds;
    }

    public Principal verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw deny("malformed token");
        }
        try {
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                throw deny("unsupported token algorithm");
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw deny("bad token signature");
            }
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long exp = claims.path("exp").asLong(0);
            if (exp == 0 || Instant.now().getEpochSecond() > exp + skewSeconds) {
                throw deny("token expired");
            }
            String sub = claims.path("sub").asText("");
            if (sub.isEmpty()) {
                throw deny("token has no subject");
            }
            List<String> roles = new ArrayList<>();
            claims.path("roles").forEach(r -> roles.add(r.asText()));
            return new Principal(sub, List.copyOf(roles));
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw deny("unreadable token");
        }
    }

    byte[] sign(String input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
    }

    /** Mints a token; used by tests and tools (the console mints its own). */
    public String mint(String sub, List<String> roles, long ttlSeconds) {
        try {
            String h = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String c = b64(json.writeValueAsString(java.util.Map.of("sub", sub, "roles", roles,
                    "exp", Instant.now().getEpochSecond() + ttlSeconds)));
            return h + "." + c + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(h + "." + c));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static DrishtiException deny(String why) {
        return new DrishtiException(ErrorCode.UNAUTHENTICATED, why);
    }
}
