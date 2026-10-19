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
package com.ash.drishti.net.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The on-behalf-of token of a forwarded call (DRISHTI_NET.md section 9): who the call is for (user, roles, the embed
 * application if any), the node that made it, the request id, the node it is for, and its expiry (30 s), signed with the
 * cluster key. The receiving node checks it as it checks the call itself, and serves the call as that user: their masks,
 * their kinds, their nodes. A token for another node, or outside its 30 s, is refused.
 */
public final class OnBehalfOf {

    /** How long a token lives: none outlives a key rollover. */
    public static final long TTL_MS = 30_000;

    /** What a token says. {@code exp} is epoch milliseconds. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Claims(String sub, List<String> roles, String app, String origin, String request, String aud, long exp) {
        public Claims {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PURPOSE = "drishti-net-obo\n";

    private OnBehalfOf() {
    }

    /** A token for {@code audience} (the node the call goes to), valid {@link #TTL_MS}. */
    public static String mint(ClusterKey key, String user, List<String> roles, String app, String origin, String request, String audience) {
        Claims c = new Claims(user, roles, app, origin, request, audience, key.clock().millis() + TTL_MS);
        try {
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(c));
            return body + "." + key.macCurrent(PURPOSE + body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The claims of a token meant for {@code self}.
     *
     * @throws SecurityException when it is malformed, not signed with a cluster key, expired or meant for another node
     */
    public static Claims verify(ClusterKey key, String token, String self) {
        if (token == null || token.indexOf('.') < 0) {
            throw new SecurityException("malformed on-behalf-of token");
        }
        String body = token.substring(0, token.lastIndexOf('.'));
        String sig = token.substring(token.lastIndexOf('.') + 1);
        if (key.which(PURPOSE + body, sig) == 0) {
            throw new SecurityException("on-behalf-of token not signed with this cluster's key");
        }
        Claims c;
        try {
            c = JSON.readValue(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8), Claims.class);
        } catch (Exception e) {
            throw new SecurityException("unreadable on-behalf-of token");
        }
        if (c.sub() == null || c.sub().isBlank()) {
            throw new SecurityException("on-behalf-of token names no user");
        }
        if (c.exp() < key.clock().millis()) {
            throw new SecurityException("on-behalf-of token expired (they live 30 s)");
        }
        if (c.exp() > key.clock().millis() + TTL_MS + ClusterKey.WINDOW_MS) {
            throw new SecurityException("on-behalf-of token lives longer than 30 s");
        }
        if (self != null && !self.equals(c.aud())) {
            throw new SecurityException("on-behalf-of token is for node " + c.aud() + ", not this one");
        }
        return c;
    }
}
