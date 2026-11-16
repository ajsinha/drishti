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
package com.ash.drishti.rachana.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How often a panel is fetched again (RACHANA_REFERENCE.md 9): {@code refresh: 30} or {@code refresh: { every: 30, user-can-pause: true }}, on a
 * panel, a use, or the whole Sutra (then on every top-level panel without its own). The console re-fetches only the refreshing panels, never
 * faster than its {@code views.refresh_min_seconds} and never faster than {@code every}: a viewer may slow a panel down or pause it, not
 * speed it up.
 *
 * @param every seconds between fetches, 1 to {@value #MAX}
 * @param userCanPause whether the viewer may pause it (default true)
 */
public record Refresh(int every, boolean userCanPause) {

    public static final String KEY = "refresh";
    public static final int MAX = 86_400;
    static final String EVERY = "every";
    static final String PAUSE = "user-can-pause";

    public Refresh {
        if (every < 1 || every > MAX) {
            throw new IllegalArgumentException("refresh every " + every + " s");
        }
    }

    /** As a panel option: {@code {every: 30, user-can-pause: true}}. */
    public Map<String, Object> asOption() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(EVERY, (long) every);
        m.put(PAUSE, userCanPause);
        return m;
    }

    /** A panel option back as a Refresh; empty when it is absent or unreadable (the Sutra would not have loaded). */
    public static Optional<Refresh> fromOption(Object raw) {
        if (raw instanceof Number n && whole(n) && n.longValue() >= 1 && n.longValue() <= MAX) {
            return Optional.of(new Refresh(n.intValue(), true));
        }
        if (raw instanceof Map<?, ?> m && m.get(EVERY) instanceof Number n && whole(n) && n.longValue() >= 1 && n.longValue() <= MAX) {
            return Optional.of(new Refresh(n.intValue(), !(m.get(PAUSE) instanceof Boolean b) || b));
        }
        return Optional.empty();
    }

    /** What is wrong with a written {@code refresh:}, in words, or empty when it reads. */
    public static Optional<String> problem(Object raw) {
        if (raw instanceof Number n) {
            return whole(n) && n.longValue() >= 1 && n.longValue() <= MAX ? Optional.empty()
                    : Optional.of("'refresh' is a whole number of seconds, 1 to " + MAX + ", not " + n);
        }
        if (raw instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                if (!EVERY.equals(k) && !PAUSE.equals(k)) {
                    return Optional.of("'refresh' takes every and user-can-pause, not '" + k + "'");
                }
            }
            if (!(m.get(EVERY) instanceof Number n) || !whole(n) || n.longValue() < 1 || n.longValue() > MAX) {
                return Optional.of("'refresh.every' is a whole number of seconds, 1 to " + MAX);
            }
            if (m.containsKey(PAUSE) && !(m.get(PAUSE) instanceof Boolean)) {
                return Optional.of("'refresh.user-can-pause' is true or false");
            }
            return Optional.empty();
        }
        return Optional.of("'refresh' is a whole number of seconds (refresh: 30) or { every: 30, user-can-pause: true }, not '" + raw + "'");
    }

    private static boolean whole(Number n) {
        return n instanceof Long || n instanceof Integer;
    }
}
