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
package com.ash.drishti.server.feeds;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * The data feeds as the server sees them (docs/guides/EXTERNAL_DATA.md section 6): the feed files of {@code config/feeds/} and the run
 * ledger {@code <data>/datafeeds/ledger.jsonl} that every run appends to, whoever started it (cron, systemd, Task Scheduler, the command line or
 * Run now). The feeds themselves run in Python ({@code tools/drishti.py feed run}); the server only reads what they wrote, starts a run on
 * an administrator's Run now, and keeps the gauge {@code drishti.datafeed.failed} (tag {@code feed}: 1 when the feed's last run failed or
 * was refused) that the platform alert rule {@code data-feed-failed} watches, so a failure opens an alert in the one alert engine and a
 * later success closes it.
 */
public final class DataFeeds {

    private static final Logger LOG = LoggerFactory.getLogger(DataFeeds.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    /** At most this much of the ledger's end is read: years of daily runs fit easily. */
    static final long LEDGER_TAIL_BYTES = 4L << 20;

    private final Path config;
    private final Path data;
    private final Path root;
    private final String python;
    private final MultiGauge failed;
    private ScheduledExecutorService sampler;

    /**
     * @param config the feed files' folder ({@code config/feeds})
     * @param data the data folder ({@code drishti.data.dir}): the ledger is {@code <data>/datafeeds/ledger.jsonl}
     * @param root the installation's folder, where {@code tools/drishti.py} is (Run now)
     * @param python the Python Run now starts (blank: the console's {@code drishti-console/.venv}, else python3)
     */
    public DataFeeds(Path config, Path data, Path root, String python, MeterRegistry registry) {
        this.config = config;
        this.data = data;
        this.root = root;
        this.python = python == null ? "" : python.trim();
        this.failed = registry == null ? null
                : MultiGauge.builder("drishti.datafeed.failed").description("1 when a data feed's last run failed or was refused").register(registry);
    }

    /** Samples the gauge now and every {@code seconds}. */
    public void start(long seconds) {
        sample();
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "datafeeds-sampler");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleWithFixedDelay(this::sample, seconds, seconds, TimeUnit.SECONDS);
    }

    /** Stops the sampler (the server's shutdown). */
    public void stop() {
        if (sampler != null) {
            sampler.shutdownNow();
        }
    }

    Path ledgerPath() {
        return data.resolve("datafeeds").resolve("ledger.jsonl");
    }

    /** The feed files: name, title, lake, load route, schedule, licence, enabled; in name order. A file that does not read is listed with its problem. */
    public List<Map<String, Object>> feeds() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(config)) {
            return out;
        }
        try (Stream<Path> files = Files.list(config)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                String name = f.getFileName().toString().replaceFirst("\\.yaml$", "");
                m.put("feed", name);
                try {
                    Object doc = new Yaml().load(Files.readString(f));
                    if (doc instanceof Map<?, ?> d) {
                        m.put("title", str(d.get("title")));
                        m.put("lake", str(d.get("lake")));
                        m.put("load", d.get("load") == null ? "java" : str(d.get("load")));
                        m.put("enabled", !Boolean.FALSE.equals(d.get("enabled")));
                        Map<?, ?> s = d.get("schedule") instanceof Map<?, ?> x ? x : Map.of();
                        m.put("schedule", (s.get("days") == null ? "daily" : str(s.get("days"))) + " " + (s.get("at") == null ? "06:30" : str(s.get("at"))));
                        Map<?, ?> lic = d.get("licence") instanceof Map<?, ?> x ? x : Map.of();
                        m.put("licence", str(lic.get("name")));
                        m.put("licenceUrl", str(lic.get("url")));
                        m.put("internalOnly", Boolean.TRUE.equals(lic.get("internal-only")) || "internal-only".equals(lic.get("redistribution")));
                        Map<?, ?> ds = d.get("datasets") instanceof Map<?, ?> x ? x : Map.of();
                        m.put("datasets", ds.keySet().stream().map(String::valueOf).toList());
                    } else {
                        m.put("problem", "not a YAML mapping");
                    }
                } catch (IOException | RuntimeException e) {
                    m.put("problem", "does not read: " + e.getMessage());
                }
                out.add(m);
            }
        } catch (IOException e) {
            LOG.warn("data feeds: {} does not list: {}", config, e.getMessage());
        }
        return out;
    }

    /** The ledger's entries, oldest first (its last {@link #LEDGER_TAIL_BYTES}); a line that does not read is skipped. */
    public List<Map<String, Object>> entries() {
        Path p = ledgerPath();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(p)) {
            return out;
        }
        String text;
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - LEDGER_TAIL_BYTES);
            byte[] b = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(b);
            text = new String(b, StandardCharsets.UTF_8);
            if (from > 0) {
                int nl = text.indexOf('\n');
                text = nl < 0 ? "" : text.substring(nl + 1);
            }
        } catch (IOException e) {
            LOG.warn("data feeds: the ledger {} does not read: {}", p, e.getMessage());
            return out;
        }
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = JSON.readValue(line, Map.class);
                out.add(e);
            } catch (IOException | RuntimeException e) {
                // a line cut short by a crash: skipped
            }
        }
        return out;
    }

    /**
     * Admin → Data loads → Feeds: per feed its file's facts and, from the ledger, the last run, the last success with its rows and business
     * date, the duration, the failures since the last success and the last error; the last {@code runs} runs of every feed, newest first.
     */
    public Map<String, Object> status(int runs) {
        List<Map<String, Object>> ledger = entries();
        Map<String, List<Map<String, Object>>> by = new TreeMap<>();
        for (Map<String, Object> e : ledger) {
            if (!"dry-run".equals(e.get("status"))) {
                by.computeIfAbsent(str(e.get("feed")), k -> new ArrayList<>()).add(e);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> f : feeds()) {
            Map<String, Object> r = new LinkedHashMap<>(f);
            r.putAll(summary(by.getOrDefault(str(f.get("feed")), List.of())));
            r.put("running", Files.exists(data.resolve("datafeeds").resolve(str(f.get("feed"))).resolve("run.lock")));
            rows.add(r);
        }
        List<Map<String, Object>> recent = new ArrayList<>(ledger.subList(Math.max(0, ledger.size() - Math.max(0, runs)), ledger.size()));
        java.util.Collections.reverse(recent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feeds", rows);
        out.put("runs", recent);
        out.put("ledger", ledgerPath().toString());
        out.put("config", config.toString());
        out.put("failing", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("failed"))).count());
        return out;
    }

    /** One feed's runs (oldest first) summed up: what Admin → Feeds and the gauge show. */
    static Map<String, Object> summary(List<Map<String, Object>> runs) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        Map<String, Object> ok = null;
        int sinceOk = 0;
        for (Map<String, Object> e : runs) {
            if ("ok".equals(e.get("status"))) {
                ok = e;
                sinceOk = 0;
            } else if (isFailure(e)) {
                sinceOk++;
            }
        }
        m.put("lastRun", last == null ? null : last.get("at"));
        m.put("lastStatus", last == null ? null : last.get("status"));
        m.put("lastExit", last == null ? null : last.get("exit"));
        m.put("lastTrigger", last == null ? null : last.get("trigger"));
        m.put("seconds", last == null ? null : last.get("seconds"));
        m.put("lastSuccess", ok == null ? null : ok.get("at"));
        m.put("rows", ok == null ? null : ok.get("rows"));
        m.put("businessDate", ok == null ? null : ok.get("businessDate"));
        m.put("failuresSinceSuccess", sinceOk);
        m.put("failed", last != null && isFailure(last));
        m.put("lastError", last != null && !"ok".equals(last.get("status")) ? last.get("error") : null);
        return m;
    }

    private static boolean isFailure(Map<String, Object> e) {
        Object s = e.get("status");
        return "failed".equals(s) || "refused".equals(s) || "partial".equals(s);
    }

    /** One sample of {@code drishti.datafeed.failed}: a series per feed with a run in the ledger. */
    public void sample() {
        if (failed == null) {
            return;
        }
        try {
            Map<String, List<Map<String, Object>>> by = new TreeMap<>();
            for (Map<String, Object> e : entries()) {
                if (!"dry-run".equals(e.get("status")) && !"locked".equals(e.get("status"))) {
                    by.computeIfAbsent(str(e.get("feed")), k -> new ArrayList<>()).add(e);
                }
            }
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : by.entrySet()) {
                boolean bad = Boolean.TRUE.equals(summary(e.getValue()).get("failed"));
                rows.add(MultiGauge.Row.of(Tags.of("feed", e.getKey()), bad ? 1 : 0));
            }
            failed.register(rows, true);
        } catch (RuntimeException e) {
            LOG.warn("data feeds: the sample failed: {}", e.getMessage());
        }
    }

    /**
     * Run now: {@code tools/drishti.py feed run <feed> --trigger admin} started in the background with the server's data folder; its output
     * goes to {@code <data>/datafeeds/<feed>/admin-run.log} and its result to the ledger like any run. While the feed's lock is held, nothing
     * starts and the answer says so.
     */
    public Map<String, Object> runNow(String feed, String user) throws IOException {
        if (feed == null || !NAME.matcher(feed).matches() || !Files.isRegularFile(config.resolve(feed + ".yaml"))) {
            throw new IllegalArgumentException("no data feed '" + feed + "' in " + config);
        }
        Path folder = data.resolve("datafeeds").resolve(feed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feed", feed);
        out.put("by", user);
        if (Files.exists(folder.resolve("run.lock"))) {
            out.put("started", false);
            out.put("skipped", "a run of " + feed + " is in progress (" + folder.resolve("run.lock") + ")");
            return out;
        }
        Path script = root.resolve("tools").resolve("drishti.py");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("Run now needs the tools of the installation: no " + script + " (drishti.feeds.root)");
        }
        Files.createDirectories(folder);
        Path log = folder.resolve("admin-run.log");
        ProcessBuilder pb = new ProcessBuilder(pythonCommand(), script.toString(), "feed", "run", feed, "--trigger", "admin",
                "--config", config.toAbsolutePath().normalize().toString())     // the feed files the server reads, not the command line's default
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.environment().put("DRISHTI_DATA_DIR", data.toAbsolutePath().normalize().toString());
        Files.writeString(log, "---- " + Instant.now() + " Run now by " + user + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        Process p = pb.start();
        p.onExit().thenRun(this::sample);
        out.put("started", true);
        out.put("pid", p.pid());
        out.put("log", log.toString());
        return out;
    }

    String pythonCommand() {
        if (!python.isEmpty()) {
            return python;
        }
        boolean win = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path venv = root.resolve("drishti-console").resolve(".venv").resolve(win ? "Scripts" : "bin").resolve(win ? "python.exe" : "python");
        if (Files.isRegularFile(venv)) {
            return venv.toString();
        }
        return win ? "python" : "python3";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
