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
package com.ash.drishti.flight;

import com.ash.drishti.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;

/**
 * Checks every call's token at the handshake (Q3) with the server's token services ({@link FlightBackend#authenticate}). A client sends
 * {@code authorization: Bearer <API token>} (Java, Go, C#, R) or Basic with the token as the password ({@code pyarrow}'s
 * {@code authenticate_basic_token}, any user name). The answer carries {@code authorization: Bearer <session>}, a short-lived session id
 * the client sends from then on, so the token services are not asked on every call. {@code x-drishti-on-behalf-of} names the person an
 * application acts for (an on-behalf-of token only). Never a browser cookie.
 */
public final class TokenAuthenticator implements CallHeaderAuthenticator {

    public static final String AUTHORIZATION = "authorization";
    public static final String ON_BEHALF_OF = "x-drishti-on-behalf-of";
    static final String SESSION_PREFIX = "dfs_";

    private final FlightBackend backend;
    private final Duration ttl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** The live session of a token (by its hash, never the token): a client that sends the token on every call gets the same one. */
    private final Map<String, String> byToken = new ConcurrentHashMap<>();

    private record Session(FlightBackend.Caller caller, Instant expires) {}

    public TokenAuthenticator(FlightBackend backend, Duration ttl, Clock clock) {
        this.backend = backend;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public AuthResult authenticate(CallHeaders headers) {
        String auth = headers.get(AUTHORIZATION);
        if (auth == null || auth.isBlank()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "no token: send authorization: Bearer <API token>").toFlight();
        }
        String token;
        if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = auth.substring(7).trim();
        } else if (auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the Basic credentials are not base64").toFlight();
            }
            int colon = decoded.indexOf(':');
            token = colon < 0 ? decoded : decoded.substring(colon + 1);
        } else {
            throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "authorization is Bearer <API token>").toFlight();
        }
        Instant now = clock.instant();
        if (token.startsWith(SESSION_PREFIX)) {
            Session s = sessions.get(token);
            if (s == null || s.expires.isBefore(now)) {
                sessions.remove(token);
                throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the session ended: send the API token again").toFlight();
            }
            return result(token, null);
        }
        String key = hash(token) + "|" + (headers.get(ON_BEHALF_OF) == null ? "" : headers.get(ON_BEHALF_OF));
        String known = byToken.get(key);
        if (known != null) {
            Session s = sessions.get(known);
            if (s != null && !s.expires.isBefore(now)) {
                return result(known, known);
            }
            byToken.remove(key);
        }
        FlightBackend.Caller caller;
        try {
            caller = backend.authenticate(token, headers.get(ON_BEHALF_OF));
        } catch (FlightRefusal r) {
            throw r.toFlight();
        }
        if (caller == null) {
            throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the token is not known, expired or revoked").toFlight();
        }
        sessions.values().removeIf(s -> s.expires.isBefore(now));
        byte[] b = new byte[24];
        random.nextBytes(b);
        String id = SESSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        sessions.put(id, new Session(caller, now.plus(ttl)));
        byToken.values().removeIf(v -> !sessions.containsKey(v));
        byToken.put(key, id);
        return result(id, id);
    }

    /** The caller of a session (the peer identity of a call). */
    public FlightBackend.Caller caller(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        if (s == null || s.expires.isBefore(clock.instant())) {
            throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the session ended: send the API token again");
        }
        return s.caller;
    }

    /** Ends every session of a token (the token was revoked). */
    public void revoke(String tokenId) {
        sessions.values().removeIf(s -> tokenId.equals(s.caller.tokenId()));
        byToken.values().removeIf(v -> !sessions.containsKey(v));
    }

    private static String hash(String token) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AuthResult result(String peer, String issue) {
        return new AuthResult() {
            @Override
            public String getPeerIdentity() {
                return peer;
            }

            @Override
            public void appendToOutgoingHeaders(CallHeaders out) {
                if (issue != null && !out.containsKey(AUTHORIZATION)) {
                    out.insert(AUTHORIZATION, "Bearer " + issue);
                }
            }
        };
    }
}
