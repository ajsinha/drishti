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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.FileSwap;
import com.ash.drishti.server.bi.plan.SecuritySignature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Repeated heavy questions become rollup suggestions (docs/architecture/BI_RUNS.md section 8.3a, the product owner's rule). The run log is counted
 * per <b>shape</b>: the model, the grain (the fields, sorted) and the filters' shape (field and operator, never a value). A shape asked at least
 * {@code suggest.min-runs} times within {@code suggest.window} as an ad-hoc heavy run, with a median of at least {@code suggest.min-seconds}, is a
 * suggestion with the rollup drafted for review. An administrator accepts it (the rollup is added to the model's draft, which follows the normal
 * review) or dismisses it. Once the model has a rollup at that grain the suggestion says {@code built}: those questions are now known heavy.
 *
 * <p>The decisions are kept in {@code <store.dir>/suggestions.json} (written beside its name and moved into place). Names and counts only.
 */
public final class RunSuggestions {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One shape of question. */
    public record Shape(String model, List<String> grain, List<String> filters, List<String> measures) {
        String id() {
            return "sg-" + SecuritySignature.sha(model + "|" + grain + "|" + filters).substring(0, 12);
        }
    }

    /** A suggestion: the shape, how often and how long, its status, and the rollup drafted. */
    public record Suggestion(String id, Shape shape, int runs, double medianSeconds, String status, Map<String, Object> rollup, String decidedBy,
                             String item) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("model", shape.model());
            m.put("grain", shape.grain());
            m.put("filters", shape.filters());
            m.put("runs", runs);
            m.put("medianSeconds", Math.round(medianSeconds * 10) / 10.0);
            m.put("status", status);
            m.put("rollup", rollup);
            m.put("yaml", yaml(rollup));
            long s = Math.round(medianSeconds);
            m.put("words", "asked " + runs + " times in the window, about " + (s >= 120 ? Math.round(s / 60.0) + " min" : s + " s")
                    + " each: make it a rollup refreshed at load");
            m.put("decidedBy", decidedBy);
            m.put("item", item);
            return m;
        }
    }

    /** Adds the drafted rollup to the model's draft as the administrator; the model item's id, or empty when the model cannot be edited (a pack's). */
    @FunctionalInterface
    public interface Drafter {
        Optional<String> draft(com.ash.drishti.server.security.Principal admin, String model, Map<String, Object> rollup);
    }

    private final RunsProperties.Suggest settings;
    private final Path file;
    private final Map<String, Map<String, String>> decisions = new ConcurrentHashMap<>();
    private volatile Drafter drafter = (p, m, r) -> Optional.empty();

    /** No model has the rollup yet (tests, and accept and dismiss, which decide on the counts). */
    public static final BiPredicate<String, Map<String, Object>> NONE_BUILT = (m, r) -> false;

    public RunSuggestions(RunsProperties.Suggest settings, Path dir) {
        this.settings = settings;
        this.file = dir == null ? null : dir.resolve("suggestions.json");
        load();
    }

    public void drafter(Drafter d) {
        this.drafter = d == null ? (p, m, r) -> Optional.empty() : d;
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Object> m = JSON.readValue(file.toFile(), Map.class);
            m.forEach((k, v) -> {
                if (v instanceof Map<?, ?> x) {
                    Map<String, String> d = new LinkedHashMap<>();
                    x.forEach((a, b) -> d.put(String.valueOf(a), b == null ? null : String.valueOf(b)));
                    decisions.put(k, d);
                }
            });
        } catch (IOException | RuntimeException e) {
            // an unreadable file: the decisions start again; the counts come from the run log anyway
        }
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = FileSwap.temp(file);
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new java.util.TreeMap<>(decisions));
            FileSwap.replace(tmp, file);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file + ": " + e.getMessage(), e);
        }
    }

    /** The shape of a run's question, when it is a model query (else empty: a SQL statement or a report visual is not counted). */
    static Optional<Shape> shape(Run r) {
        Object q = RunJson.parse(r.question());
        if (!(q instanceof Map<?, ?> m) || !"mq".equals(m.get("type")) || !(m.get("mq") instanceof Map<?, ?> mq)) {
            return Optional.empty();
        }
        String model = String.valueOf(m.get("model"));
        Map<?, ?> select = mq.get("select") instanceof Map<?, ?> s ? s : Map.of();
        TreeSet<String> grain = new TreeSet<>();
        if (select.get("fields") instanceof List<?> f) {
            f.forEach(x -> grain.add(String.valueOf(x)));
        }
        List<String> measures = new ArrayList<>();
        if (select.get("measures") instanceof List<?> ms) {
            ms.forEach(x -> measures.add(String.valueOf(x)));
        }
        TreeSet<String> filters = new TreeSet<>();
        if (mq.get("where") instanceof List<?> w) {
            for (Object o : w) {
                if (o instanceof Map<?, ?> c) {
                    filters.add(c.get("field") + " " + (c.get("op") == null ? "eq" : c.get("op")));
                }
            }
        }
        return Optional.of(new Shape(model, List.copyOf(grain), List.copyOf(filters), measures));
    }

    /** The rollup drafted for a shape (BI_DESIGN 6.1 suggested aggregations): the fact alias, the grain's columns, the measures asked. */
    static Map<String, Object> draft(Shape s, Collection<String> measures) {
        Map<String, Integer> tables = new LinkedHashMap<>();
        List<String> by = new ArrayList<>();
        for (String f : s.grain()) {
            String[] parts = f.split("\\.");
            if (parts.length >= 2) {
                tables.merge(parts[0], 1, Integer::sum);
                if (!by.contains(parts[1])) {
                    by.add(parts[1]);
                }
            } else if (!by.contains(f)) {
                by.add(f);
            }
        }
        String table = tables.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "suggested_" + String.join("_", by).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", ""));
        r.put("table", table);
        r.put("by", by);
        r.put("measures", List.copyOf(new java.util.LinkedHashSet<>(measures)));
        return r;
    }

    /** The drafted rollup as one line of a model's {@code rollups:} list. */
    static String yaml(Map<String, Object> r) {
        if (r == null) {
            return null;
        }
        return "- { name: " + r.get("name") + ", table: " + r.get("table") + ", by: " + list(r.get("by")) + ", measures: " + list(r.get("measures")) + " }";
    }

    private static String list(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return "[" + String.join(", ", out) + "]";
    }

    /** The suggestions now, from the run log: shapes past both thresholds, most asked first. */
    public List<Suggestion> of(Collection<Run> log, Instant now, BiPredicate<String, Map<String, Object>> built) {
        Instant from = now.minus(settings.window());
        Map<String, List<Run>> byShape = new LinkedHashMap<>();
        Map<String, Shape> shapes = new LinkedHashMap<>();
        for (Run r : log) {
            if (r.tier() != Tier.AD_HOC_HEAVY || r.submittedAt() == null || r.submittedAt().isBefore(from)
                    || r.state() != RunState.DONE && r.state() != RunState.REUSED && r.state() != RunState.EXPIRED) {
                continue;
            }
            shape(r).ifPresent(s -> {
                shapes.putIfAbsent(s.id(), s);
                byShape.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(r);
            });
        }
        List<Suggestion> out = new ArrayList<>();
        byShape.forEach((id, runs) -> {
            List<Double> secs = new ArrayList<>();
            for (Run r : runs) {
                if (r.startedAt() != null && r.finishedAt() != null && r.reuseOf() == null) {
                    secs.add(Duration.between(r.startedAt(), r.finishedAt()).toMillis() / 1000.0);
                }
            }
            secs.sort(Double::compare);
            double median = secs.isEmpty() ? 0 : RunEstimator.percentile(secs, 0.5);
            if (runs.size() < settings.minRuns() || median < settings.minSeconds()) {
                return;
            }
            Shape s = shapes.get(id);
            TreeSet<String> measures = new TreeSet<>();
            runs.forEach(r -> shape(r).ifPresent(x -> measures.addAll(x.measures())));
            Map<String, Object> rollup = draft(s, measures);
            Map<String, String> d = decisions.getOrDefault(id, Map.of());
            String status = built.test(s.model(), rollup) ? "built" : d.getOrDefault("status", "suggested");
            out.add(new Suggestion(id, s, runs.size(), median, status, rollup, d.get("by"), d.get("item")));
        });
        out.sort(Comparator.comparingInt(Suggestion::runs).reversed().thenComparing(Suggestion::id));
        return out;
    }

    /**
     * Accepts a suggestion: the rollup is added to the model's draft (reviewed and published as any change), and the suggestion says {@code accepted}
     * with the item. A model that cannot be edited here (a pack's, a file's) is accepted with the YAML to add by hand.
     */
    public Suggestion accept(String id, Collection<Run> log, Instant now, com.ash.drishti.server.security.Principal admin) {
        Suggestion s = of(log, now, NONE_BUILT).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> gone(id));
        Optional<String> item = drafter.draft(admin, s.shape().model(), s.rollup());
        Map<String, String> d = new LinkedHashMap<>();
        d.put("status", "accepted");
        d.put("by", admin.user());
        d.put("at", now.toString());
        d.put("item", item.orElse(null));
        decisions.put(id, d);
        save();
        return new Suggestion(id, s.shape(), s.runs(), s.medianSeconds(), "accepted", s.rollup(), admin.user(), item.orElse(null));
    }

    /** Dismisses a suggestion: it stays listed as {@code dismissed} and is not offered again. */
    public Suggestion dismiss(String id, Collection<Run> log, Instant now, String by) {
        Suggestion s = of(log, now, NONE_BUILT).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> gone(id));
        Map<String, String> d = new LinkedHashMap<>();
        d.put("status", "dismissed");
        d.put("by", by);
        d.put("at", now.toString());
        decisions.put(id, d);
        save();
        return new Suggestion(id, s.shape(), s.runs(), s.medianSeconds(), "dismissed", s.rollup(), by, null);
    }

    private static DrishtiException gone(String id) {
        return new DrishtiException(ErrorCode.BI_NOT_FOUND, "no rollup suggestion '" + id + "' now (the run log no longer passes the thresholds)");
    }

    /**
     * Adds one rollup line to a model body, keeping its comments: under its {@code rollups:} key when there is one, else as a new key at the end.
     */
    public static String withRollup(String body, Map<String, Object> rollup) {
        String line = "  " + yaml(rollup);
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean done = false;
        for (int i = 0; i < lines.length; i++) {
            if (!done && lines[i].matches("^rollups:\\s*\\[\\s*]\\s*(#.*)?$")) {
                lines[i] = "rollups:";                                          // an empty flow list becomes the block list the line joins
            } else if (lines[i].startsWith("rollups:") && !lines[i].matches("^rollups:\\s*(#.*)?$")) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "this model writes its rollups on one line: add the suggested rollup by hand: " + yaml(rollup));
            }
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
            if (!done && lines[i].matches("^rollups:\\s*(#.*)?$")) {
                out.append(line).append('\n');
                done = true;
            }
        }
        if (!done) {
            String s = out.toString();
            return s + (s.endsWith("\n") ? "" : "\n") + "rollups:\n" + line + "\n";
        }
        return out.toString();
    }
}
