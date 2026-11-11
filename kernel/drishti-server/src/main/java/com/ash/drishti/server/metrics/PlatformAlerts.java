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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform alerts (OPERATIONS_GUIDE.md section 13.6, CLUSTER_GUIDE.md 8.5): the rules of {@code drishti.metrics.alerts.file} evaluated
 * against this server's own meters every {@code sample-interval}, without Prometheus. A series that breaks its rule for the rule's
 * {@code for} opens an alert (logged, counted in {@code drishti.alerts.open}, and told to the {@code notify-roles} in their inbox); it closes
 * when the series is back within the threshold or gone. The file is read again when it changes. Each member of a cluster evaluates its own
 * meters, and its alerts name it. This is the alert engine's platform source: the engine ({@code AlertService}) lists these alerts with every
 * other alert and acknowledges, snoozes and assigns them; a snoozed series opens without telling anyone. {@code GET /api/v1/admin/alerts}
 * shows the rules, the open and the recent ones beside everyone's alerts.
 */
public final class PlatformAlerts {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformAlerts.class);

    /** One alert, open or closed: the rule, the series' tags, its line, the value and threshold when it opened (or last), when. */
    public record Alert(String rule, Map<String, String> tags, String severity, String summary, double value, double threshold, String member,
            Instant openedAt, Instant closedAt) {

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule);
            m.put("tags", tags);
            m.put("severity", severity);
            m.put("summary", summary);
            m.put("value", Double.isFinite(value) ? value : null);
            m.put("threshold", threshold);
            m.put("member", member);
            m.put("openedAt", openedAt);
            m.put("closedAt", closedAt);
            return m;
        }
    }

    private static final class Series {
        Instant pendingSince;
        Alert open;
        final Deque<double[]> samples = new ArrayDeque<>();      // {epoch millis, raw value} for increase
    }

    private final MeterRegistry registry;
    private final Path file;
    private final boolean enabled;
    private final int keep;
    private final String member;
    private final Clock clock;
    private final Consumer<Alert> onOpen;
    private final Map<String, Series> series = new HashMap<>();
    private final Deque<Alert> recent = new ArrayDeque<>();
    private PlatformRule.Loaded loaded = new PlatformRule.Loaded(List.of(), List.of(), false);
    private long fileStamp = Long.MIN_VALUE;
    private Instant evaluatedAt;

    public PlatformAlerts(MeterRegistry registry, MetricsProperties.Alerts props, String member, Clock clock, Consumer<Alert> onOpen) {
        this.registry = registry;
        this.file = Path.of(props.file());
        this.enabled = props.enabled();
        this.keep = props.keep();
        this.member = member;
        this.clock = clock;
        this.onOpen = onOpen;
        for (String severity : List.of("info", "warn", "critical")) {
            io.micrometer.core.instrument.Gauge.builder("drishti.alerts.open", this, a -> a.openCount(severity)).tag("severity", severity)
                    .description("platform alerts open now on this member").register(registry);
        }
    }

    /** One pass over every rule (the scheduler's thread). */
    public synchronized void evaluate() {
        if (!enabled) {
            return;
        }
        reloadIfChanged();
        Instant now = clock.instant();
        evaluatedAt = now;
        Set<String> live = new HashSet<>();
        for (PlatformRule rule : loaded.rules()) {
            Map<Map<String, String>, Double> values;
            try {
                values = values(rule, now);
            } catch (RuntimeException e) {
                LOG.debug("platform alert {}: {}", rule.name(), e.toString());
                continue;
            }
            for (Map.Entry<Map<String, String>, Double> v : values.entrySet()) {
                String key = rule.name() + "|" + v.getKey();
                live.add(key);
                step(rule, key, v.getKey(), v.getValue(), now);
            }
        }
        for (Map.Entry<String, Series> e : series.entrySet()) {          // a series gone (a cache dropped, a source removed): its alert closes
            if (!live.contains(e.getKey()) && e.getValue().open != null) {
                close(e.getValue(), now);
            }
        }
        series.entrySet().removeIf(e -> !live.contains(e.getKey()) && e.getValue().open == null && e.getValue().samples.isEmpty());
    }

    private void step(PlatformRule rule, String key, Map<String, String> tags, double v, Instant now) {
        Series s = series.computeIfAbsent(key, k -> new Series());
        double value = rule.function().equals("increase") ? increase(s, v, now, rule.window()) : v;
        if (!Double.isFinite(value) || !rule.breached(value)) {
            s.pendingSince = null;
            if (s.open != null) {
                close(s, now);
            }
            return;
        }
        if (s.pendingSince == null) {
            s.pendingSince = now;
        }
        if (s.open == null && !now.isBefore(s.pendingSince.plus(rule.forDuration()))) {
            s.open = new Alert(rule.name(), tags, rule.severity(), line(rule, tags, value), value, rule.threshold(), member, now, null);
            LOG.warn("platform alert {} ({}) opened on {}: {}", rule.name(), rule.severity(), member, s.open.summary());
            try {
                if (!quiet.test(s.open)) {                                // snoozed in the alert engine: open, listed, but nobody is told
                    onOpen.accept(s.open);
                }
            } catch (RuntimeException e) {
                LOG.warn("platform alert {}: could not tell the administrators: {}", rule.name(), e.getMessage());
            }
        }
    }

    private void close(Series s, Instant now) {
        Alert a = s.open;
        s.open = null;
        s.pendingSince = null;
        Alert closed = new Alert(a.rule(), a.tags(), a.severity(), a.summary(), a.value(), a.threshold(), a.member(), a.openedAt(), now);
        LOG.info("platform alert {} closed on {} after {}", a.rule(), member, Duration.between(a.openedAt(), now));
        recent.addFirst(closed);
        while (recent.size() > keep) {
            recent.removeLast();
        }
    }

    private static double increase(Series s, double raw, Instant now, Duration window) {
        long t = now.toEpochMilli();
        s.samples.addLast(new double[] {t, raw});
        while (s.samples.size() > 1 && s.samples.peekFirst()[0] < t - window.toMillis()) {
            s.samples.removeFirst();
        }
        double first = s.samples.peekFirst()[1];
        return raw >= first ? raw - first : raw;                         // a counter that went back (a restart, a purge) counts from zero
    }

    /** The value of each series of a rule now (one series, no tags, when aggregated). */
    Map<Map<String, String>, Double> values(PlatformRule rule, Instant now) {
        List<String> flat = new ArrayList<>();
        rule.tags().forEach((k, v) -> {
            flat.add(k);
            flat.add(v);
        });
        Collection<Meter> metered = registry.find(rule.metric()).tags(flat.toArray(String[]::new)).meters();
        Collection<Meter> divisors = rule.divideBy() == null ? List.of() : registry.find(rule.divideBy()).meters();
        Map<Map<String, String>, Double> out = new LinkedHashMap<>();
        if (!rule.aggregate().equals("each")) {
            double agg = rule.aggregate().equals("sum") ? 0 : Double.NaN;
            for (Meter m : metered) {
                double v = value(m);
                if (Double.isFinite(v)) {
                    agg = rule.aggregate().equals("sum") ? agg + v : Double.isNaN(agg) ? v : Math.max(agg, v);
                }
            }
            if (metered.isEmpty() || Double.isNaN(agg)) {
                return out;
            }
            if (rule.divideBy() != null) {
                double d = divisors.stream().mapToDouble(PlatformAlerts::value).filter(Double::isFinite).sum();
                if (!(d > 0)) {
                    return out;
                }
                agg = agg / d;
            }
            out.put(Map.of(), agg);
            return out;
        }
        for (Meter m : metered) {
            Map<String, String> tags = tags(m);
            double v = value(m);
            if (rule.divideBy() != null) {
                double d = divisor(divisors, tags);
                v = d > 0 ? v / d : Double.NaN;
            }
            if (Double.isFinite(v)) {
                out.put(tags, v);
            }
        }
        return out;
    }

    /** The divisor series with the numerator's own tags, else one whose tags all appear on it (one with no tags); NaN when there is none. */
    private static double divisor(Collection<Meter> divisors, Map<String, String> tags) {
        Meter subset = null;
        for (Meter d : divisors) {
            Map<String, String> dt = tags(d);
            if (dt.equals(tags)) {
                return value(d);
            }
            if (subset == null && tags.entrySet().containsAll(dt.entrySet())) {
                subset = d;
            }
        }
        return subset != null ? value(subset) : Double.NaN;
    }

    /** A gauge's value, a counter's or timer's count. */
    static double value(Meter m) {
        double count = Double.NaN;
        for (Measurement x : m.measure()) {
            if (x.getStatistic() == Statistic.VALUE) {
                return x.getValue();
            }
            if (x.getStatistic() == Statistic.COUNT && Double.isNaN(count)) {
                count = x.getValue();
            }
        }
        return count;
    }

    private static Map<String, String> tags(Meter m) {
        Map<String, String> t = new TreeMap<>();
        for (Tag tag : m.getId().getTags()) {
            t.put(tag.getKey(), tag.getValue());
        }
        return t;
    }

    static String line(PlatformRule rule, Map<String, String> tags, double value) {
        String s = rule.summary().replace("{value}", format(rule.format(), value)).replace("{threshold}", format(rule.format(), rule.threshold()));
        for (Map.Entry<String, String> t : tags.entrySet()) {
            s = s.replace("{" + t.getKey() + "}", t.getValue());
        }
        return s;
    }

    static String format(String how, double v) {
        return switch (how) {
            case "percent" -> Math.round(v * 1000) / 10.0 + "%";
            case "bytes" -> bytes(v);
            case "seconds" -> v < 1 ? Math.round(v * 1000) + " ms" : significant(v) + " s";
            default -> significant(v);
        };
    }

    private static String bytes(double v) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (Math.abs(v) >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return significant(v) + " " + units[i];
    }

    private static String significant(double v) {
        return new BigDecimal(v).round(new MathContext(3)).stripTrailingZeros().toPlainString();
    }

    private void reloadIfChanged() {
        long stamp;
        try {
            stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (java.io.IOException e) {
            stamp = -1;
        }
        if (stamp != fileStamp) {
            fileStamp = stamp;
            loaded = PlatformRule.load(file);
            loaded.problems().forEach(p -> LOG.warn("platform alert rules {}: {}", file, p));
            Set<String> names = new HashSet<>(loaded.rules().stream().map(PlatformRule::name).toList());
            Instant now = clock.instant();
            for (Map.Entry<String, Series> e : series.entrySet()) {     // a rule removed: its alerts close
                if (!names.contains(e.getKey().substring(0, e.getKey().indexOf('|'))) && e.getValue().open != null) {
                    close(e.getValue(), now);
                }
            }
            series.keySet().removeIf(k -> !names.contains(k.substring(0, k.indexOf('|'))));
        }
    }

    private volatile java.util.function.Predicate<Alert> quiet = a -> false;

    /** The alert engine's snooze: an alert that opens while its series is snoozed tells nobody (set once at start-up). */
    public void quiet(java.util.function.Predicate<Alert> q) {
        this.quiet = q;
    }

    /** The series an alert belongs to (its rule and tags): what a snooze holds across re-openings. */
    public static String key(Alert a) {
        return a.rule() + "|" + new TreeMap<>(a.tags());
    }

    /** The open alerts and the recent closed ones, for the alert engine's one list (acknowledge, snooze, assign). */
    public synchronized List<Alert> openAndRecent() {
        List<Alert> out = new ArrayList<>(open());
        out.addAll(recent);
        return out;
    }

    /** The open alerts, most severe first. */
    public synchronized List<Alert> open() {
        List<String> order = List.of("critical", "warn", "info");
        return series.values().stream().map(s -> s.open).filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.<Alert>comparingInt(a -> order.indexOf(a.severity())).thenComparing(Alert::openedAt)).toList();
    }

    synchronized double openCount(String severity) {
        return series.values().stream().filter(s -> s.open != null && s.open.severity().equals(severity)).count();
    }

    /** {@code GET /api/v1/admin/alerts}: the rules, the open alerts and the recent closed ones. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("member", member);
        m.put("file", file.toString());
        m.put("found", loaded.found());
        m.put("problems", loaded.problems());
        m.put("evaluatedAt", evaluatedAt);
        m.put("rules", loaded.rules().stream().map(PlatformAlerts::rule).toList());
        m.put("open", open().stream().map(Alert::describe).toList());
        m.put("recent", recent.stream().map(Alert::describe).toList());
        return m;
    }

    /** What Admin, Health shows: the open alerts and whether the rules could be read. */
    public synchronized Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("rules", loaded.rules().size());
        m.put("found", loaded.found());
        m.put("problems", loaded.problems());
        m.put("open", open().stream().map(Alert::describe).toList());
        return m;
    }

    private static Map<String, Object> rule(PlatformRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name());
        m.put("metric", r.metric());
        m.put("tags", r.tags());
        m.put("divideBy", r.divideBy());
        m.put("aggregate", r.aggregate());
        m.put("function", r.function());
        m.put("window", r.function().equals("increase") ? r.window().toString() : null);
        m.put("condition", (r.above() != null ? "above " : "below ") + format(r.format(), r.threshold()));
        m.put("for", r.forDuration().toString());
        m.put("severity", r.severity());
        m.put("summary", r.summary());
        return m;
    }
}
