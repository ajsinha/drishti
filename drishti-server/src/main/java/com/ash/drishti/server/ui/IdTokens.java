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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque tokens for masked ids (docs/architecture/UI_BOARD.md 7.2, decision 14.1 #10): where the id a selection would carry is masked for the person, the
 * out pin carries a token instead; only the same person resolves it back to the id, and only for a while. Nothing of the id is in the token.
 */
public final class IdTokens {

    public static final String PREFIX = "tok_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Held(String user, String id, long until) {}

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public IdTokens(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /** A new token standing for {@code id}, resolvable by {@code user} only. */
    public String issue(String user, String id) {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        String t = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        long now = clock.millis();
        held.put(t, new Held(user, id, now + ttl.toMillis()));
        if (held.size() % 512 == 0) {
            held.values().removeIf(h -> h.until < now);
        }
        return t;
    }

    public static boolean isToken(Object v) {
        return v instanceof String s && s.startsWith(PREFIX);
    }

    /** The id behind {@code token}. @throws DrishtiException DRS-2074 for anyone but its person, or once it has expired */
    public String resolve(String user, String token) {
        Held h = held.get(token);
        if (h == null || !h.user.equals(user) || h.until < clock.millis()) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, "this selection cannot be opened by " + user);
        }
        return h.id;
    }
}
