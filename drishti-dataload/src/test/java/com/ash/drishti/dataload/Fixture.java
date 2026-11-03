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
package com.ash.drishti.dataload;

import com.ash.drishti.lakeload.LakeLoad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A data folder, a pack and the inbox's settings under one temporary folder; a signal that records; a stand-in loader that writes the
 * summary the real one would (its exit code chosen by the test) and counts its runs.
 */
final class Fixture {

    static final ObjectMapper JSON = new ObjectMapper();
    static final ZoneId ZONE = ZoneOffset.UTC;

    final Path base;
    final Path root;
    final Path lake;
    final Path rules;
    final Path pack;
    Clock clock = Clock.fixed(Instant.parse("2026-11-03T15:00:00Z"), ZONE);
    final List<Signal.Landed> signals = new CopyOnWriteArrayList<>();
    final List<Path> loads = new CopyOnWriteArrayList<>();
    DataLoadSettings settings;
    /** What the stand-in loader answers for a load file's input file names (default: loaded, exit 0). */
    Function<List<String>, Integer> exit = files -> LakeLoad.OK;

    Fixture(Path base) throws IOException {
        this.base = base;
        this.root = base.resolve("data/dataload");
        this.lake = base.resolve("data/delta");
        this.rules = base.resolve("config/dataload.yaml");
        this.pack = base.resolve("packs/demo-pack");
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.yaml"), """
                pack: demo-pack
                connectors:
                  trade-lake:
                    plugin: delta
                    kinds: [trade]
                    settings:
                      domain: demo
                      layout:
                        trade:
                          columns: [TRADE_ID, DESK]
                ingest:
                  trade:
                    key: TRADE_ID
                    date: BUSINESS_DATE
                """);
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, """
                dataload:
                  rules:
                    - match: "trades-*"
                      pack: demo-pack
                      kind: trade
                    - match: "named-*"
                      pack: demo-pack
                      kind: trade
                      date-from: name
                  unmatched: fail
                """);
        settings = new DataLoadSettings(true, root.toString(), Duration.ofMinutes(10), 30, 500, 50.0, "256m", Duration.ofMinutes(5), 0.0, false, 30,
                lake.toString(), rules.toString(), false);
    }

    Path inbox() {
        return root.resolve("inbox");
    }

    Routes.Packs packs() {
        return name -> name.equals("demo-pack") ? new Routes.PackInfo(pack, List.of("trade", "position")) : null;
    }

    /** A data file in the inbox, old enough to be stable, with its marker ({@code marker} null: none; "" an empty one). */
    Path drop(String name, String content, String marker) throws IOException {
        Path f = inbox().resolve(name);
        Files.writeString(f, content);
        if (marker != null) {
            Files.writeString(inbox().resolve(name + Inbox.READY), marker);
        }
        age(f, marker == null ? null : inbox().resolve(name + Inbox.READY));
        return f;
    }

    void age(Path... files) throws IOException {
        for (Path p : files) {
            if (p != null) {
                Files.setLastModifiedTime(p, FileTime.from(clock.instant().minusSeconds(3600)));
            }
        }
    }

    static String rows(String date, String... ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            b.append("{\"TRADE_ID\":\"").append(id).append("\",\"DESK\":\"RATES\",\"BUSINESS_DATE\":\"").append(date).append("\",\"N\":1}\n");
        }
        return b.toString();
    }

    Signal signal() {
        return signals::add;
    }

    /** The stand-in loader: reads the load file, writes the summary a load of its files would, answers {@link #exit}. */
    LoadRunner fakeLoader() {
        return (config, extra, log) -> {
            loads.add(config);
            try {
                JsonNode cfg = new YAMLMapper().readTree(Files.readString(config));
                List<String> names = new ArrayList<>();
                long docs = 0;
                java.util.TreeMap<String, Long> perDate = new java.util.TreeMap<>();
                for (JsonNode f : cfg.path("source").path("files")) {
                    Path p = Path.of(f.asText());
                    names.add(p.getFileName().toString());
                    for (String line : Files.readAllLines(p)) {
                        if (line.isBlank()) {
                            continue;
                        }
                        docs++;
                        String d = cfg.path("source").has("as-of") ? cfg.path("source").path("as-of").asText()
                                : JSON.readTree(line).path("BUSINESS_DATE").asText("2026-11-02");
                        perDate.merge(d, 1L, Long::sum);
                    }
                }
                int code = extra.contains("--dry-run") ? LakeLoad.OK : exit.apply(names);
                ObjectNode s = JSON.createObjectNode().put("run", "fake-" + loads.size()).put("exitCode", code)
                        .put("status", code == 0 ? "ok" : code == 2 ? "partial" : code == 3 ? "locked" : "failed")
                        .put("dryRun", extra.contains("--dry-run")).put("table", cfg.path("target").path("lake").asText() + "/demo/trade")
                        .put("documents", docs).put("accepted", code <= 2 ? docs : 0).put("rejected", code == 2 ? 1 : 0);
                if (code == 1) {
                    s.put("error", "line 3: not JSON (" + names + ")");
                }
                if ((code == 0 || code == 2) && !extra.contains("--dry-run")) {
                    ObjectNode dates = s.putObject("commit").putObject("dates");
                    long[] v = {10};
                    perDate.forEach((d, n) -> dates.putObject(d).put("version", v[0]++).put("rows", n));
                }
                Files.writeString(Path.of(cfg.path("report").path("summary").asText()), s.toPrettyString());
                Files.writeString(log, "loader: " + names + "\n" + (s.has("error") ? "lakeload: " + s.path("error").asText() + "\n" : ""));
                return new LoadRunner.Result(code, false, s.path("error").asText(""));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    Cycle cycle(LoadRunner runner, Cycle.Faults faults) {
        return new Cycle(settings, "node-a", clock, ZONE, packs(), runner, signal(), () -> null, faults);
    }

    Cycle.Report run() {
        return cycle(fakeLoader(), null).run("test");
    }

    /** The files under {@code dir}, relative to the inbox's root, sorted ("outbox/2026-11-03/<run>/x" with the run as RUN). */
    List<String> tree(String dir) throws IOException {
        Path d = root.resolve(dir);
        if (!Files.exists(d)) {
            return List.of();
        }
        try (var s = Files.walk(d)) {
            return s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .map(p -> p.replaceAll("/\\d{8}-\\d{6}-[0-9a-f]{4}/", "/RUN/")).sorted().toList();
        }
    }

    JsonNode ledgerLines() throws IOException {
        var arr = JSON.createArrayNode();
        new Ledger(root.resolve("ledger"), clock).recent(1000).reversed().forEach(arr::add);
        return arr;
    }
}
