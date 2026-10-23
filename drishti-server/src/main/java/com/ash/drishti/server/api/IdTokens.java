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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque tokens for what a person selected on a Sutra Next screen (SUTRA_NEXT.md 7 and 15; ported from the UI Board's IdTokens): a
 * click's payload, which may hold an id that is masked for the person, stays on the server; the browser holds a random token that only
 * the same person resolves, on the same screen, and only for a while (each use renews it). Nothing of the payload is in the token.
 * Held in this node's memory: a token issued by one node is not known to another (a screen's requests go to the node that drew it).
 * Thread-safe.
 */
public final class IdTokens {

    public static final String PREFIX = "tok_";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The most tokens held; past it the expired ones are swept, and failing that the oldest half. */
    static final int MAX_HELD = 100_000;

    private record Held(String user, String scope, Object value, long until) {}

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public IdTokens(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /** A new token standing for {@code value}, resolvable by {@code user} within {@code scope} (the screen: kind and id) only. */
    public String issue(String user, String scope, Object value) {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        String t = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        long now = clock.millis();
        held.put(t, new Held(user, scope, value, now + ttl.toMillis()));
        if (held.size() % 512 == 0 || held.size() > MAX_HELD) {
            held.values().removeIf(h -> h.until < now);
            if (held.size() > MAX_HELD) {
                held.entrySet().stream().sorted(java.util.Comparator.comparingLong(e -> e.getValue().until)).limit(held.size() / 2)
                        .map(Map.Entry::getKey).toList().forEach(held::remove);
            }
        }
        return t;
    }

    public static boolean isToken(Object v) {
        return v instanceof String s && s.startsWith(PREFIX);
    }

    /**
     * What {@code token} stands for.
     *
     * @throws DrishtiException {@code DRS-5002} for anyone but its person, on another screen, or once it has expired
     */
    public Object resolve(String user, String scope, String token) {
        Held h = token == null ? null : held.get(token);
        long now = clock.millis();
        if (h == null || !h.user.equals(user) || !h.scope.equals(scope) || h.until < now) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "this selection cannot be opened by " + user + " here (it belongs to another person or "
                    + "screen, or it has expired: pick the row again)");
        }
        held.put(token, new Held(h.user, h.scope, h.value, now + ttl.toMillis()));     // in use: renewed
        return h.value;
    }
}
