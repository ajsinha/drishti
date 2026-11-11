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
package com.ash.drishti.common;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

/**
 * How long to wait before trying again after a failure: exponential backoff with jitter, the one policy every part of
 * Drishti that retries shares. Settings are {@code drishti.retry.*} with a per-area override
 * ({@code drishti.retry.<area>.*}, areas: packs, connectors, ai, mcp):
 * <pre>
 *   enabled          true     retrying at all
 *   initial          5s       wait before the first retry
 *   factor           2.0      each further wait is this many times the one before
 *   max              5m       no wait is longer than this
 *   jitter           0.2      each wait varies by up to this fraction either way, so many failing things do not retry together
 *   permanent-after  3        a failure that waiting cannot fix (bad settings, refused credentials) seen this many times
 *                             in a row is treated as permanent: it is retried only every {@code max}
 * </pre>
 * Spring-free: values are read through a plain lookup function.
 */
public record RetryPolicy(boolean enabled, Duration initial, double factor, Duration max, double jitter, int permanentAfter) {

    public RetryPolicy {
        if (initial == null || initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("drishti.retry.initial must be a positive duration");
        }
        if (max == null || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("drishti.retry.max must not be shorter than drishti.retry.initial");
        }
        if (factor < 1.0) {
            throw new IllegalArgumentException("drishti.retry.factor must be at least 1");
        }
        if (jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("drishti.retry.jitter must be between 0 and 1");
        }
        if (permanentAfter < 1) {
            throw new IllegalArgumentException("drishti.retry.permanent-after must be at least 1");
        }
    }

    /** The shipped defaults: 5 seconds, doubling, up to 5 minutes, 20 percent jitter, permanent after 3. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(true, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(5), 0.2, 3);
    }

    /** Reads {@code drishti.retry.*} and then {@code drishti.retry.<area>.*} (the area wins) through {@code lookup}. */
    public static RetryPolicy of(Function<String, String> lookup, String area) {
        RetryPolicy d = defaults();
        return new RetryPolicy(
                bool(lookup, area, "enabled", d.enabled),
                duration(lookup, area, "initial", d.initial),
                number(lookup, area, "factor", d.factor),
                duration(lookup, area, "max", d.max),
                number(lookup, area, "jitter", d.jitter),
                (int) number(lookup, area, "permanent-after", d.permanentAfter));
    }

    private static String value(Function<String, String> lookup, String area, String key) {
        String v = area == null ? null : lookup.apply("drishti.retry." + area + "." + key);
        if (v == null || v.isBlank()) {
            v = lookup.apply("drishti.retry." + key);
        }
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static boolean bool(Function<String, String> l, String area, String key, boolean dflt) {
        String v = value(l, area, key);
        return v == null ? dflt : Boolean.parseBoolean(v);
    }

    private static double number(Function<String, String> l, String area, String key, double dflt) {
        String v = value(l, area, key);
        try {
            return v == null ? dflt : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("drishti.retry." + key + " is not a number: " + v);
        }
    }

    private static Duration duration(Function<String, String> l, String area, String key, Duration dflt) {
        String v = value(l, area, key);
        return v == null ? dflt : parse(v);
    }

    /** Durations as 500ms, 5s, 2m, 1h (or ISO-8601); a bare number is seconds. */
    public static Duration parse(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        try {
            if (t.startsWith("p")) {
                return Duration.parse(t);
            }
            if (t.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2).strip()));
            }
            char u = t.charAt(t.length() - 1);
            if (Character.isDigit(u)) {
                return Duration.ofSeconds(Long.parseLong(t));
            }
            long n = Long.parseLong(t.substring(0, t.length() - 1).strip());
            return switch (u) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default -> throw new IllegalArgumentException("unit");
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a duration (use 500ms, 5s, 2m, 1h): " + text);
        }
    }

    /**
     * The wait before retry number {@code attempt} (1 for the first retry), without jitter: {@code initial * factor^(attempt-1)},
     * never more than {@code max}.
     */
    public Duration baseDelay(int attempt) {
        double ms = initial.toMillis() * Math.pow(factor, Math.max(0, attempt - 1));
        return ms >= max.toMillis() || Double.isInfinite(ms) ? max : Duration.ofMillis((long) ms);
    }

    /** The wait with jitter applied; {@code random} is a number in [0,1) (0.5 means no jitter). */
    public Duration delay(int attempt, boolean permanent, double random) {
        Duration base = permanent ? max : baseDelay(attempt);
        double spread = (random * 2 - 1) * jitter;
        long ms = Math.round(base.toMillis() * (1 + spread));
        return Duration.ofMillis(Math.max(1, Math.min(ms, max.toMillis())));
    }

    /**
     * Whether an error is one waiting will not fix: bad settings or refused credentials. A heuristic over the exception type
     * and message; a caller that knows better says so itself.
     */
    public static boolean looksPermanent(Throwable e) {
        return e instanceof IllegalArgumentException || looksPermanent(e == null ? null : e.getMessage());
    }

    public static boolean looksPermanent(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("401") || m.contains("403") || m.contains("unauthori") || m.contains("forbidden") || m.contains("authentication failed")
                || m.contains("access denied") || m.contains("invalid credentials") || m.contains("bad credentials") || m.contains("invalid api key")
                || m.contains("incorrect api key") || m.contains("is not set") || m.contains("no plugin named") || m.contains("has a syntax error");
    }
}
