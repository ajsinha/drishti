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
package com.ash.drishti.compute;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Reads values out of a parsed YAML mapping with the names of the keys in every error: durations ({@code 250ms}, {@code 30s}, {@code 5m},
 * {@code 1h}), sizes ({@code 512MB}, {@code 4GB}), numbers, switches and lists. Placeholders ({@code ${VAR:default}}) are resolved by the
 * resolver given (the server passes its environment's), so a worker count can come from an environment variable per node.
 */
public final class ConfigValues {

    private final Map<String, Object> map;
    private final String where;
    private final UnaryOperator<String> resolve;

    public ConfigValues(Map<String, Object> map, String where, UnaryOperator<String> resolve) {
        this.map = map == null ? Map.of() : map;
        this.where = where;
        this.resolve = resolve == null ? UnaryOperator.identity() : resolve;
    }

    public String where() {
        return where;
    }

    public boolean has(String key) {
        return map.containsKey(key) && map.get(key) != null;
    }

    public java.util.Set<String> keys() {
        return map.keySet();
    }

    /** The text of a key, placeholders resolved; {@code dflt} when absent or blank. */
    public String text(String key, String dflt) {
        Object v = map.get(key);
        if (v == null) {
            return dflt;
        }
        String s = resolve.apply(String.valueOf(v)).trim();
        return s.isEmpty() ? dflt : s;
    }

    public boolean flag(String key, boolean dflt) {
        String s = text(key, null);
        if (s == null) {
            return dflt;
        }
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s);
        }
        throw new IllegalArgumentException(where + "." + key + " is true or false, not '" + s + "'");
    }

    public int integer(String key, int dflt, int min, int max) {
        String s = text(key, null);
        if (s == null) {
            return dflt;
        }
        try {
            int v = Integer.parseInt(s.replace("_", ""));
            if (v < min || v > max) {
                throw new IllegalArgumentException(where + "." + key + " is " + v + ": it must be between " + min + " and " + max);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + "." + key + " is a whole number, not '" + s + "'");
        }
    }

    public double decimal(String key, double dflt, double min, double max) {
        String s = text(key, null);
        if (s == null) {
            return dflt;
        }
        try {
            double v = Double.parseDouble(s);
            if (v < min || v > max) {
                throw new IllegalArgumentException(where + "." + key + " is " + v + ": it must be between " + min + " and " + max);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + "." + key + " is a number, not '" + s + "'");
        }
    }

    public Duration duration(String key, Duration dflt) {
        String s = text(key, null);
        return s == null ? dflt : duration(s, where + "." + key);
    }

    public long bytes(String key, long dflt) {
        String s = text(key, null);
        return s == null ? dflt : bytes(s, where + "." + key);
    }

    /** A list of texts (a YAML list or one comma-separated text). */
    public List<String> list(String key) {
        Object v = map.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(x -> {
                if (x != null && !resolve.apply(String.valueOf(x)).isBlank()) {
                    out.add(resolve.apply(String.valueOf(x)).trim());
                }
            });
        } else if (v != null) {
            for (String x : resolve.apply(String.valueOf(v)).split(",")) {
                if (!x.isBlank()) {
                    out.add(x.trim());
                }
            }
        }
        return out;
    }

    /** A nested mapping (empty when absent); an error when the key holds something else. */
    @SuppressWarnings("unchecked")
    public ConfigValues section(String key) {
        Object v = map.get(key);
        String at = where.isEmpty() ? key : where + "." + key;
        if (v == null) {
            return new ConfigValues(Map.of(), at, resolve);
        }
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(at + " is a mapping");
        }
        return new ConfigValues((Map<String, Object>) v, at, resolve);
    }

    public Object raw(String key) {
        return map.get(key);
    }

    public static Duration duration(String s, String what) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2).trim()));
            }
            long n = Long.parseLong(t.substring(0, t.length() - 1).trim());
            return switch (t.charAt(t.length() - 1)) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default -> throw new NumberFormatException();
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(what + " is a duration such as 250ms, 30s, 5m or 1h, not '" + s + "'");
        }
    }

    public static long bytes(String s, String what) {
        String t = s.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        long unit = 1;
        String n = t;
        for (String[] u : new String[][] {{"TB", "1099511627776"}, {"GB", "1073741824"}, {"MB", "1048576"}, {"KB", "1024"}, {"B", "1"}}) {
            if (t.endsWith(u[0])) {
                unit = Long.parseLong(u[1]);
                n = t.substring(0, t.length() - u[0].length());
                break;
            }
        }
        try {
            return Math.round(Double.parseDouble(n) * unit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is a size such as 512MB or 4GB, not '" + s + "'");
        }
    }
}
