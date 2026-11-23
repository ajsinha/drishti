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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What the change detection keeps, as one JSONL file per kind and business date under {@code <dataDir>/homes}: {@code snapshot} (the
 * followed measures at the cut-off), {@code kpi} (the KPI figures, the sparklines' history), {@code event} and {@code driver}. JSONL is the
 * data standard; the tables {@code measure_snapshot}, {@code change_event} and {@code change_driver} of ROLE_HOME_SUTRAS.md 4.2 are these
 * files read as tables. A day's snapshot and drivers are replaced whole (atomic move); events are appended.
 */
public final class HomeStore {

    /** Guards the blocking work below (a ReentrantLock: synchronized would pin a virtual thread). */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    private final Path root;
    private final int keepDays;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public HomeStore(Path dataDir, int keepDays) {
        this.root = dataDir.resolve("homes");
        this.keepDays = keepDays;
    }

    private Path file(String kind, LocalDate d) {
        return root.resolve(kind + "-" + d + ".jsonl");
    }

    private <T> void replace(String kind, LocalDate d, List<T> rows) {
        try {
            Files.createDirectories(root);
            Path tmp = com.ash.drishti.common.TempFiles.createFile(root, kind + "-", ".part");   // beside the target: one atomic move
            try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (T r : rows) {
                    w.write(json.writeValueAsString(r));
                    w.write('\n');
                }
            }
            Files.move(tmp, file(kind, d), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> List<T> read(String kind, LocalDate d, Class<T> type) {
        Path f = file(kind, d);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            List<T> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(json.readValue(line, type));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------- snapshots

    /** The followed measures at the cut-off of {@code d}: replaces the day's. */
    public void putSnapshot(LocalDate d, Instant cutoff, List<HomeData.MeasureValue> values) {
        List<ObjectNode> rows = new ArrayList<>();
        for (HomeData.MeasureValue v : values) {
            rows.add(json.createObjectNode().put("SUBJECT_KIND", v.subjectKind()).put("SUBJECT", v.subject()).put("MEASURE", v.measure())
                    .put("VALUE", v.value()).put("CUTOFF", cutoff.toString()).put("BUSINESS_DATE", d.toString()));
        }
        replace("snapshot", d, rows);
        prune(d);
    }

    public List<HomeData.MeasureValue> snapshot(LocalDate d) {
        List<HomeData.MeasureValue> out = new ArrayList<>();
        for (ObjectNode n : read("snapshot", d, ObjectNode.class)) {
            out.add(new HomeData.MeasureValue(n.path("SUBJECT_KIND").asText(), n.path("SUBJECT").asText(), n.path("MEASURE").asText(),
                    n.path("VALUE").asDouble()));
        }
        return out;
    }

    public boolean hasSnapshot(LocalDate d) {
        return Files.isRegularFile(file("snapshot", d));
    }

    // ----------------------------------------------------------------------------------------------------------- KPI history

    public void putKpis(LocalDate d, List<HomeData.KpiPoint> points) {
        replace("kpi", d, points.stream().map(p -> json.createObjectNode().put("LABEL", p.label()).put("DATE", d.toString()).put("VALUE", p.value()))
                .toList());
    }

    public List<HomeData.KpiPoint> kpis(LocalDate d) {
        return read("kpi", d, ObjectNode.class).stream().map(n -> new HomeData.KpiPoint(n.path("LABEL").asText(), d, n.path("VALUE").asDouble())).toList();
    }

    // ----------------------------------------------------------------------------------------------------------- events

    public void addEvent(LocalDate d, HomeData.Event e) {
        lock.lock();
        try {
            try {
                Files.createDirectories(root);
                ObjectNode n = json.createObjectNode().put("SUBJECT_KIND", e.subjectKind()).put("SUBJECT", e.subject()).put("KIND", e.kind())
                        .put("AT", e.at().toString()).put("TEXT", e.text());
                Files.writeString(file("event", d), json.writeValueAsString(n) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }

        } finally {
            lock.unlock();
        }
    }

    /** Events of the day {@code d} at or after {@code since}. */
    public List<HomeData.Event> events(LocalDate d, Instant since) {
        List<HomeData.Event> out = new ArrayList<>();
        for (ObjectNode n : read("event", d, ObjectNode.class)) {
            Instant at = Instant.parse(n.path("AT").asText());
            if (!at.isBefore(since)) {
                out.add(new HomeData.Event(n.path("SUBJECT_KIND").asText(), n.path("SUBJECT").asText(), n.path("KIND").asText(), at, n.path("TEXT").asText()));
            }
        }
        return out;
    }

    // ----------------------------------------------------------------------------------------------------------- drivers

    public void putDrivers(LocalDate d, List<HomeData.Driver> drivers) {
        replace("driver", d, drivers.stream().map(x -> json.createObjectNode().put("SUBJECT_KIND", x.subjectKind()).put("SUBJECT", x.subject())
                .put("MEASURE", x.measure()).put("DRIVER", x.driver()).put("DELTA", x.delta())).toList());
    }

    public List<HomeData.Driver> drivers(LocalDate d) {
        return read("driver", d, ObjectNode.class).stream().map(n -> new HomeData.Driver(n.path("SUBJECT_KIND").asText(), n.path("SUBJECT").asText(),
                n.path("MEASURE").asText(), n.path("DRIVER").asText(), n.path("DELTA").asDouble())).toList();
    }

    /** Files of days older than {@code keepDays} before {@code newest} are removed. */
    private void prune(LocalDate newest) {
        LocalDate cut = newest.minusDays(keepDays);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.list(root)) {
            for (Path f : s.toList()) {
                String n = f.getFileName().toString();
                if (n.endsWith(".jsonl") && n.length() >= 16) {
                    try {
                        if (LocalDate.parse(n.substring(n.length() - 16, n.length() - 6)).isBefore(cut)) {
                            Files.deleteIfExists(f);
                        }
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // not one of ours
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
