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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings of BI runs, read from their own file {@code config/runs/runs.yaml} (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 15; the pointer is
 * {@code drishti.bi.runs.file} in {@code application.yaml}). Every key has its default here, so a missing file or key means the default. Spans are
 * written {@code 2s}, {@code 15m}, {@code 4h}; sizes {@code 2GB}, {@code 512MB}.
 */
public record RunsProperties(boolean enabled, Inline inline, PlannerSettings planner, Duration confirmTtl, Queue queue, int workers,
                             Duration maxDuration, Engine engine, Store store, Retention retention, Restart restart, Lease lease,
                             Duration cancelGrace, String workspaceVisibility, Promote promote, List<String> notifyOn, Suggest suggest,
                             String origin) {

    public record Inline(Duration inlineWait, Duration maxWait) {}

    public record Queue(int perUser, int max) {}

    public record Engine(long memoryLimitBytes, int threads, long maxSpillBytes, long maxReadRows, double preferExternalAboveSeconds) {}

    public record Store(Path dir, long maxBytes, long maxBytesPerUser, long maxResultRows, long maxResultBytes, double minSeconds,
                        boolean keepInteractive) {}

    public record Retention(int days, int maxDays, Duration sweep) {}

    public record Restart(boolean resumeRunning, int maxAttempts, boolean requeueChangedModel) {}

    public record Lease(Duration renew, Duration ttl) {}

    public record Promote(boolean enabled, int versionsKeep) {}

    public record Suggest(int minRuns, Duration window, double minSeconds) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern SPAN = Pattern.compile("(?i)\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h|d)?\\s*");
    private static final Pattern SIZE = Pattern.compile("(?i)\\s*(\\d+(?:\\.\\d+)?)\\s*(b|kb|mb|gb|tb)?\\s*");

    /** The defaults of the shipped file, with the store under {@code dataDir}. */
    public static RunsProperties defaults(Path dataDir) {
        return from(Map.of(), dataDir, UnaryOperator.identity(), "default");
    }

    /**
     * The settings in {@code file} (absent: the defaults). {@code resolve} expands placeholders such as {@code ${drishti.data.dir}} in string
     * values (the Spring environment's, when wired).
     */
    public static RunsProperties load(Path file, Path dataDir, UnaryOperator<String> resolve) {
        if (file == null || !Files.isRegularFile(file)) {
            return from(Map.of(), dataDir, resolve, "default");
        }
        try {
            Map<String, Object> m = YAML.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            return from(m == null ? Map.of() : m, dataDir, resolve, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the BI runs settings " + file + ": " + e.getMessage(), e);
        }
    }

    static RunsProperties from(Map<String, Object> m, Path dataDir, UnaryOperator<String> resolve, String origin) {
        Node r = new Node(m, resolve);
        Node in = r.at("inline");
        Node ia = r.at("interactive");
        Node cf = r.at("confirm");
        Node en = r.at("engine");
        Node es = r.at("estimate");
        Node rates = es.at("rates");
        Node st = r.at("store");
        Node rt = r.at("retention");
        Node rs = r.at("restart");
        Node ls = r.at("lease");
        Node pr = r.at("promote");
        Node sg = r.at("suggest");
        PlannerSettings p = new PlannerSettings(ia.num("max-seconds", 10), ia.lng("max-rows", 50_000_000L), (int) ia.num("max-build-dates", 2),
                cf.num("above-seconds", 60), cf.lng("above-rows", 500_000_000L), cf.bool("unknown", true), en.lng("max-read-rows", 5_000_000_000L),
                es.num("low-factor", 0.5), es.num("high-factor", 2.0), (int) es.num("min-samples", 20), rates.num("scan-rows-per-second", 50_000_000),
                rates.num("build-rows-per-second", 300_000), rates.num("record-rows-per-second", 2_000), es.num("overhead-seconds", 0.05),
                (int) es.num("bytes-per-row", 32));
        String dir = st.str("dir", null);
        Path storeDir = dir == null || dir.isBlank() ? dataDir.resolve("bi-runs") : Path.of(dir);
        Duration wait = r.at("inline").span("wait", Duration.ofSeconds(2));
        Duration maxWait = in.span("max-wait", Duration.ofSeconds(10));
        return new RunsProperties(r.bool("enabled", true), new Inline(wait.compareTo(maxWait) > 0 ? maxWait : wait, maxWait), p,
                cf.span("ttl", Duration.ofMinutes(15)), new Queue((int) r.at("queue").num("per-user", 5), (int) r.at("queue").num("max", 200)),
                Math.max(1, (int) r.num("workers", 2)), r.at("run").span("max-duration", Duration.ofHours(4)),
                new Engine(en.size("memory-limit", 2L << 30), Math.max(1, (int) en.num("threads", 2)), en.size("max-spill", 20L << 30),
                        en.lng("max-read-rows", 5_000_000_000L), en.num("prefer-external-above-seconds", 0)),
                new Store(storeDir.toAbsolutePath().normalize(), st.size("max-bytes", 20L << 30), st.size("max-bytes-per-user", 2L << 30),
                        st.lng("max-result-rows", 50_000_000L), st.size("max-result-bytes", 2L << 30), st.num("min-seconds", 1),
                        st.bool("keep-interactive", true)),
                new Retention((int) rt.num("days", 30), (int) rt.num("max-days", 365), rt.span("sweep", Duration.ofHours(1))),
                new Restart(rs.bool("resume-running", true), Math.max(1, (int) rs.num("max-attempts", 2)), rs.bool("requeue-changed-model", false)),
                new Lease(ls.span("renew", Duration.ofSeconds(10)), ls.span("ttl", Duration.ofSeconds(45))), r.at("cancel").span("grace", Duration.ofSeconds(5)),
                visibility(r.str("workspace-visibility", "owner-and-shared")), new Promote(pr.bool("enabled", true), (int) pr.num("versions-keep", 5)),
                r.list("notify", "on", List.of("done", "failed")),
                new Suggest((int) sg.num("min-runs", 5), sg.span("window", Duration.ofDays(7)), sg.num("min-seconds", 60)), origin);
    }

    private static String visibility(String v) {
        if (!"owner-and-shared".equals(v) && !"members".equals(v)) {
            throw new IllegalArgumentException("runs.yaml workspace-visibility is owner-and-shared or members, not '" + v + "'");
        }
        return v;
    }

    /** A span such as {@code 2s}, {@code 250ms}, {@code 15m}, {@code 4h}, {@code 7d}; a bare number is seconds. */
    public static Duration span(String text) {
        Matcher mt = SPAN.matcher(text == null ? "" : text);
        if (!mt.matches()) {
            throw new IllegalArgumentException("not a span (2s, 15m, 4h): '" + text + "'");
        }
        double n = Double.parseDouble(mt.group(1));
        String u = mt.group(2) == null ? "s" : mt.group(2).toLowerCase(Locale.ROOT);
        long ms = (long) (n * switch (u) {
            case "ms" -> 1;
            case "m" -> 60_000;
            case "h" -> 3_600_000;
            case "d" -> 86_400_000;
            default -> 1_000;
        });
        return Duration.ofMillis(ms);
    }

    /** A size such as {@code 2GB}, {@code 768MB}; a bare number is bytes. */
    public static long size(String text) {
        Matcher mt = SIZE.matcher(text == null ? "" : text);
        if (!mt.matches()) {
            throw new IllegalArgumentException("not a size (2GB, 512MB): '" + text + "'");
        }
        double n = Double.parseDouble(mt.group(1));
        String u = mt.group(2) == null ? "b" : mt.group(2).toLowerCase(Locale.ROOT);
        return (long) (n * switch (u) {
            case "kb" -> 1L << 10;
            case "mb" -> 1L << 20;
            case "gb" -> 1L << 30;
            case "tb" -> 1L << 40;
            default -> 1L;
        });
    }

    /** What the console needs ({@code GET /api/v1/bi/runs/settings}). */
    public Map<String, Object> console() {
        return Map.of("enabled", enabled, "inlineWait", inline.inlineWait().toString(), "maxWait", inline.maxWait().toString(), "confirmAboveSeconds",
                planner.confirmAboveSeconds(), "retentionDays", retention.days(), "eventsUrl", "/api/v1/bi/runs/events");
    }

    /** One level of the YAML map, with typed reads that fall back to the default. */
    private record Node(Map<?, ?> m, UnaryOperator<String> resolve) {
        Node at(String key) {
            Object v = m.get(key);
            return new Node(v instanceof Map<?, ?> x ? x : Map.of(), resolve);
        }

        String str(String key, String dflt) {
            Object v = m.get(key);
            return v == null ? dflt : resolve.apply(String.valueOf(v));
        }

        double num(String key, double dflt) {
            Object v = m.get(key);
            return v instanceof Number n ? n.doubleValue() : v == null ? dflt : Double.parseDouble(resolve.apply(String.valueOf(v)).strip());
        }

        long lng(String key, long dflt) {
            Object v = m.get(key);
            return v instanceof Number n ? n.longValue() : v == null ? dflt : Long.parseLong(resolve.apply(String.valueOf(v)).strip().replace("_", ""));
        }

        boolean bool(String key, boolean dflt) {
            Object v = m.get(key);
            return v instanceof Boolean b ? b : v == null ? dflt : Boolean.parseBoolean(resolve.apply(String.valueOf(v)).strip());
        }

        Duration span(String key, Duration dflt) {
            Object v = m.get(key);
            return v == null ? dflt : v instanceof Number n ? Duration.ofMillis((long) (n.doubleValue() * 1000)) : RunsProperties.span(resolve.apply(String.valueOf(v)));
        }

        long size(String key, long dflt) {
            Object v = m.get(key);
            return v == null ? dflt : v instanceof Number n ? n.longValue() : RunsProperties.size(resolve.apply(String.valueOf(v)));
        }

        List<String> list(String section, String key, List<String> dflt) {
            Object v = at(section).m().get(key);
            return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : dflt;
        }
    }
}
