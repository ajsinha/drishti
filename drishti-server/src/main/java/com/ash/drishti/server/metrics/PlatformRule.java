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
package com.ash.drishti.server.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;

/**
 * One platform alert rule (OPERATIONS.md section 13.6): a metric of this server's registry, optionally divided by another, compared with a
 * threshold for a time. Each series (each set of tags) of the metric is a separate alert unless {@code aggregate} sums or takes the most.
 *
 * @param name the rule's name, unique in the file
 * @param metric the Micrometer meter name ({@code drishti.cache.bytes}, {@code jvm.gc.live.data.size})
 * @param tags only the series with these tags
 * @param divideBy a second meter: the value is metric ÷ it: its series with the same tags, else one whose tags all
 *     appear on the metric's (a divisor with no tags divides every series); a series with no divisor is not evaluated (with {@code aggregate}:
 *     the sum of the divisor's series)
 * @param aggregate {@code each} (default), {@code sum} or {@code max} over the series
 * @param function {@code value} (a gauge's value or a counter's count, default) or {@code increase} (a counter's rise over {@code window})
 * @param window the span of {@code increase} (1 h)
 * @param above fire when the value is above this (exactly one of {@code above} and {@code below})
 * @param below fire when the value is below this
 * @param forDuration how long the condition must hold before the alert opens (0: at once)
 * @param severity {@code info}, {@code warn} or {@code critical}
 * @param summary the alert's line; {@code {value}}, {@code {threshold}} and each tag's name in braces ({@code {cache}}) are filled in
 * @param format how {@code {value}} and {@code {threshold}} are written: {@code number}, {@code percent}, {@code bytes} or {@code seconds}
 */
public record PlatformRule(String name, String metric, Map<String, String> tags, String divideBy, String aggregate, String function,
        Duration window, Double above, Double below, Duration forDuration, String severity, String summary, String format) {

    static final Set<String> SEVERITIES = Set.of("info", "warn", "critical");
    static final Set<String> AGGREGATES = Set.of("each", "sum", "max");
    static final Set<String> FUNCTIONS = Set.of("value", "increase");
    static final Set<String> FORMATS = Set.of("number", "percent", "bytes", "seconds");

    /** True when {@code v} breaks the rule. */
    public boolean breached(double v) {
        return above != null ? v > above : v < below;
    }

    public double threshold() {
        return above != null ? above : below;
    }

    /** The rules of a file and the problems found in it (a bad rule is left out, the others kept); no file: no rules. */
    public record Loaded(List<PlatformRule> rules, List<String> problems, boolean found) {}

    public static Loaded load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new Loaded(List.of(), List.of(), false);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse(new Yaml().load(in));
        } catch (IOException | RuntimeException e) {
            return new Loaded(List.of(), List.of(file.getFileName() + ": " + e.getMessage()), true);
        }
    }

    static Loaded parse(Object root) {
        List<PlatformRule> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Object rules = root instanceof Map<?, ?> m ? m.get("rules") : null;
        if (!(rules instanceof Map<?, ?> byName)) {
            return new Loaded(out, rules == null ? problems : List.of("rules: a map of rule name to rule"), true);
        }
        for (Map.Entry<?, ?> e : byName.entrySet()) {
            String name = String.valueOf(e.getKey());
            try {
                if (e.getValue() instanceof Map<?, ?> r) {
                    PlatformRule rule = of(name, r);
                    if (rule != null) {
                        out.add(rule);
                    }
                } else {
                    problems.add(name + ": a rule is a map");
                }
            } catch (IllegalArgumentException ex) {
                problems.add(name + ": " + ex.getMessage());
            }
        }
        return new Loaded(List.copyOf(out), List.copyOf(problems), true);
    }

    /** One rule from its map; null when it is switched off ({@code enabled: false}). */
    static PlatformRule of(String name, Map<?, ?> r) {
        if (Boolean.FALSE.equals(r.get("enabled"))) {
            return null;
        }
        String metric = text(r.get("metric"));
        if (metric == null) {
            throw new IllegalArgumentException("metric is required");
        }
        Double above = number(r.get("above"), "above");
        Double below = number(r.get("below"), "below");
        if ((above == null) == (below == null)) {
            throw new IllegalArgumentException("give exactly one of above and below");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        if (r.get("tags") instanceof Map<?, ?> t) {
            t.forEach((k, v) -> tags.put(String.valueOf(k), String.valueOf(v)));
        }
        String aggregate = oneOf(r.get("aggregate"), "each", AGGREGATES, "aggregate");
        String function = oneOf(r.get("function"), "value", FUNCTIONS, "function");
        String severity = oneOf(r.get("severity"), "warn", SEVERITIES, "severity");
        String format = oneOf(r.get("format"), "number", FORMATS, "format");
        Duration window = duration(r.get("window"), Duration.ofHours(1));
        Duration hold = duration(r.get("for"), Duration.ZERO);
        String summary = text(r.get("summary"));
        return new PlatformRule(name, metric, Map.copyOf(tags), text(r.get("divide-by")), aggregate, function, window, above, below, hold,
                severity, summary == null ? name + ": {value}" : summary, format);
    }

    private static String text(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }

    private static Double number(Object o, String key) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a number: " + o);
        }
    }

    private static String oneOf(Object o, String dflt, Set<String> allowed, String key) {
        String v = text(o);
        if (v == null) {
            return dflt;
        }
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(key + " is one of " + allowed.stream().sorted().toList() + ", not " + v);
        }
        return v;
    }

    private static Duration duration(Object o, Duration dflt) {
        String v = text(o);
        if (v == null) {
            return dflt;
        }
        try {
            Duration d = DurationStyle.detectAndParse(v);
            if (d.isNegative()) {
                throw new IllegalArgumentException("a duration cannot be negative: " + v);
            }
            return d;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a duration (15s, 10m, 1h): " + v);
        }
    }
}
