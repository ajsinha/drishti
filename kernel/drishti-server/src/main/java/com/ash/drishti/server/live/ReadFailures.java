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
package com.ash.drishti.server.live;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Why a kind's source last failed a live table's read, in the source's own words made safe to show (LIVE_TABLES 11, G4 LS3): the engine
 * answers a failed read {@code 503 rdp.unavailable} in its own words; the protocol mount adds these, so a reader learns what the source
 * said (no VaR inputs on this server, a connector that is down) as the old engine told them. Made safe: one line of at most
 * {@value #MAX} characters, an absolute file path cut to its file name, a credential in a URL or a {@code password=} / {@code token:}
 * value replaced by {@code ***}. A successful read clears the kind; a failure older than {@link #FRESH_MS} is not told.
 */
public final class ReadFailures {

    /** The longest message told. */
    public static final int MAX = 300;

    /** How long a failure is told after it happened. */
    public static final long FRESH_MS = 120_000;

    private static final Pattern URL_USER = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|authorization|credentials?)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)");
    private static final Pattern PATH = Pattern.compile("(?<![\\w.:/])(?:[A-Za-z]:[\\\\/]|/)(?:[^\\s/\\\\:'\"]+[\\\\/])+([^\\s/\\\\:'\",;)]*)");

    private record Said(String text, long at) {}

    private final Map<String, Said> byKind = new ConcurrentHashMap<>();
    private final Clock clock;

    public ReadFailures() {
        this(Clock.systemUTC());
    }

    public ReadFailures(Clock clock) {
        this.clock = clock;
    }

    /** A read of the kind failed with this (null: it read, the kind's failure is cleared). */
    public void failed(String kind, Throwable e) {
        if (kind == null) {
            return;
        }
        if (e == null) {
            byKind.remove(kind);
            return;
        }
        String text = safe(e);
        if (text != null) {
            byKind.put(kind, new Said(text, clock.millis()));
        }
    }

    /** The kind's last failure, made safe, when it is recent. */
    public Optional<String> of(String kind) {
        Said s = kind == null ? null : byKind.get(kind);
        return s == null || clock.millis() - s.at() > FRESH_MS ? Optional.empty() : Optional.of(s.text());
    }

    /** An exception's words made safe to show: its own, else its cause's (a wrapper's text names only the cause's class). */
    public static String safe(Throwable e) {
        Throwable t = e;
        while (t != null && (t.getMessage() == null || t.getMessage().isBlank()
                || t.getCause() != null && t.getMessage().equals(t.getCause().toString()))) {
            t = t.getCause();
        }
        return t == null ? null : safe(t.getMessage());
    }

    /** Words made safe to show (see the class). */
    public static String safe(String words) {
        if (words == null || words.isBlank()) {
            return null;
        }
        String s = words.replaceAll("[\\r\\n\\t]+", " ").strip();
        s = URL_USER.matcher(s).replaceAll("$1***@");
        s = SECRET.matcher(s).replaceAll("$1$2***");
        s = PATH.matcher(s).replaceAll("$1");
        return s.length() > MAX ? s.substring(0, MAX - 1) + "…" : s;
    }
}
