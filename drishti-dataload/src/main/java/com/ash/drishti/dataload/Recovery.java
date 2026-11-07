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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash safety (DATA_ARCHITECTURE.md section 9.7): every step can be repeated. Before a cycle looks at the inbox, every {@code inprocess/<run>/}
 * folder a stopped server left is finished by what the ledger and the loader's own files say:
 *
 * <ul>
 *   <li>a file only claimed: when its load's summary (the loader writes it after its commits) says it loaded, the ledger is completed, the
 *       file moved and the signal sent, so nothing is loaded twice; otherwise it goes back to the inbox;</li>
 *   <li>a file the ledger says was loaded, skipped as a duplicate or failed, still in {@code inprocess/}: it is moved;</li>
 *   <li>signals written but not all sent ({@code signals.jsonl}): sent (the server records each pack, kind, date and batch once).</li>
 * </ul>
 * A date's commit replaces that date's rows of the table, so even a file loaded again after a crash in the loader's own write leaves the
 * same data.
 */
public final class Recovery {

    private static final Logger LOG = LoggerFactory.getLogger(Recovery.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The signals of a run not yet sent, one JSON line each. */
    static final String SIGNALS = "signals.jsonl";
    /** The loader's files of a run (load files, summaries, logs, rejects) start with this. */
    static final String LOAD = "load-";

    private final Claims claims;
    private final Ledger ledger;
    private final Outbox outbox;
    private final Signal signal;

    public Recovery(Claims claims, Ledger ledger, Outbox outbox, Signal signal) {
        this.claims = claims;
        this.ledger = ledger;
        this.outbox = outbox;
        this.signal = signal;
    }

    /** Finishes every run a stopped server left in {@code inprocess/}; returns what became of each file. */
    public List<Cycle.FileOutcome> run() throws IOException {
        List<Cycle.FileOutcome> out = new ArrayList<>();
        if (!Files.isDirectory(claims.inprocess())) {
            return out;
        }
        List<Path> runs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(claims.inprocess(), Files::isDirectory)) {
            ds.forEach(runs::add);
        }
        runs.sort(Path::compareTo);
        for (Path dir : runs) {
            String run = dir.getFileName().toString();
            Map<String, Done> loaded = completedLoads(dir);
            for (Path f : dataFiles(dir)) {
                String name = f.getFileName().toString();
                Path marker = f.resolveSibling(name + Inbox.READY);
                Path m = Files.exists(marker) ? marker : null;
                JsonNode last = ledger.last(run, name);
                String state = last == null ? Ledger.CLAIMED : last.path("outcome").asText();
                ObjectNode report = JSON.createObjectNode().put("file", name).put("run", run).put("recovered", true);
                switch (state) {
                    case Ledger.LOADED, Ledger.DUPLICATE -> {
                        report.put("outcome", state).set("ledger", last);
                        outbox.done(run, f, m, report);
                        out.add(new Cycle.FileOutcome(name, state, "moved after a restart", null, null, Map.of(), 0, 0));
                    }
                    case Ledger.FAILED -> {
                        report.put("outcome", state).put("reason", last.path("reason").asText()).set("ledger", last);
                        outbox.failed(run, f, m, report);
                        out.add(new Cycle.FileOutcome(name, state, last.path("reason").asText(), null, null, Map.of(), 0, 0));
                    }
                    case Ledger.RETURNED, Ledger.LOCKED -> {
                        outbox.back(f, m);
                        out.add(new Cycle.FileOutcome(name, state, "back in the inbox", null, null, Map.of(), 0, 0));
                    }
                    default -> {
                        Done d = loaded.get(f.toAbsolutePath().normalize().toString());
                        if (d != null) {
                            String sha = Ledger.sha256(f);
                            ObjectNode fields = Cycle.loadedFields(d.summary(), d.route(), sha, Files.size(f));
                            ledger.write(run, name, Ledger.LOADED, fields.put("recovered", true));
                            addPending(dir, landed(d.summary(), d.route().path("pack").asText(), d.route().path("kind").asText(),
                                    d.route().path("batch").asText(), d.files()));
                            report.put("outcome", Ledger.LOADED).put("sha256", sha).set("load", d.summary());
                            outbox.done(run, f, m, report);
                            out.add(new Cycle.FileOutcome(name, Ledger.LOADED, "its load had committed before the server stopped: completed, not loaded "
                                    + "again", d.route().path("pack").asText(), d.route().path("kind").asText(), Cycle.versions(d.summary()),
                                    d.summary().path("accepted").asLong(), d.summary().path("rejected").asLong()));
                        } else {
                            ledger.write(run, name, Ledger.RETURNED, JSON.createObjectNode().put("reason",
                                    "the server stopped before its load finished: back in the inbox"));
                            outbox.back(f, m);
                            out.add(new Cycle.FileOutcome(name, Ledger.RETURNED, "the server stopped before its load finished: back in the inbox", null,
                                    null, Map.of(), 0, 0));
                        }
                    }
                }
            }
            finish(run);
        }
        if (!out.isEmpty()) {
            LOG.info("data inbox: {} file(s) left by a stopped server were finished", out.size());
        }
        return out;
    }

    /** A run's end: its pending signals sent, the loader's files kept beside its outbox, its in-process folder removed when empty. */
    public int finish(String run) throws IOException {
        Path dir = claims.run(run);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int sent = sendPending(dir);
        List<Path> rest = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(rest::add);
        }
        boolean data = false;
        for (Path p : rest) {
            String n = p.getFileName().toString();
            if (n.startsWith(LOAD)) {
                Path to = outbox.folder(true, run).resolve("loads");
                Files.createDirectories(to);
                Claims.move(p, to.resolve(n));
            } else if (!n.equals(SIGNALS)) {
                data = true;
            }
        }
        if (!data && !Files.exists(dir.resolve(SIGNALS))) {
            Files.deleteIfExists(dir);
        }
        return sent;
    }

    /** The data files of a run's folder (not markers, not the loader's files, not the signals). */
    static List<Path> dataFiles(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.endsWith(Inbox.READY) && !n.startsWith(LOAD) && !n.equals(SIGNALS) && !Files.isDirectory(p)) {
                    out.add(p);
                }
            }
        }
        out.sort(Path::compareTo);
        return out;
    }

    /** A load whose summary says it committed: its summary, its route and the files it read. */
    record Done(JsonNode summary, JsonNode route, List<String> files) {}

    /** The loads of a run that ended loaded (exit 0 or 2), by each input file's absolute path. */
    static Map<String, Done> completedLoads(Path dir) throws IOException {
        Map<String, Done> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, LOAD + "*.route.json")) {
            for (Path routeFile : ds) {
                String base = routeFile.getFileName().toString().replace(".route.json", "");
                Path summary = dir.resolve(base + ".summary.json");
                Path config = dir.resolve(base + ".yaml");
                if (!Files.isRegularFile(summary) || !Files.isRegularFile(config)) {
                    continue;
                }
                JsonNode s;
                try {
                    s = JSON.readTree(summary.toFile());
                } catch (IOException e) {
                    continue;                                                 // written half: the load did not finish
                }
                int code = s.path("exitCode").asInt(1);
                if ((code != 0 && code != 2) || s.path("dryRun").asBoolean()) {
                    continue;
                }
                JsonNode route = JSON.readTree(routeFile.toFile());
                List<String> names = new ArrayList<>();
                List<String> paths = new ArrayList<>();
                new YAMLMapper().readTree(Files.readString(config)).path("source").path("files").forEach(f -> {
                    paths.add(Path.of(f.asText()).toAbsolutePath().normalize().toString());
                    names.add(Path.of(f.asText()).getFileName().toString());
                });
                for (String p : paths) {
                    out.put(p, new Done(s, route, names));
                }
            }
        }
        return out;
    }

    /** The signals one load's summary makes: one per business date it committed. */
    static List<Signal.Landed> landed(JsonNode summary, String pack, String kind, String batch, List<String> files) {
        List<Signal.Landed> out = new ArrayList<>();
        JsonNode dates = summary.path("commit").path("dates");
        int n = dates.size();
        dates.fields().forEachRemaining(e -> out.add(new Signal.Landed(pack, kind, LocalDate.parse(e.getKey()), e.getValue().path("rows").asLong(),
                n == 1 ? summary.path("rejected").asLong() : null, batch, files)));
        return out;
    }

    /** Appends signals to the run's pending file (written before the files move, so a restart still sends them). */
    static void addPending(Path dir, List<Signal.Landed> signals) throws IOException {
        if (signals.isEmpty()) {
            return;
        }
        StringBuilder b = new StringBuilder();
        for (Signal.Landed l : signals) {
            b.append(JSON.writeValueAsString(Map.of("pack", l.pack(), "kind", l.kind(), "date", l.date().toString(), "rows", l.rows(),
                    "rejected", l.rejected() == null ? -1 : l.rejected(), "batch", l.batch(), "files", l.files()))).append('\n');
        }
        try (var ch = java.nio.channels.FileChannel.open(dir.resolve(SIGNALS), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(b.toString().getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
    }

    /** Sends a run's pending signals; the file goes when every one was sent or refused. Returns how many were sent. */
    int sendPending(Path dir) throws IOException {
        Path f = dir.resolve(SIGNALS);
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return 0;
        }
        int sent = 0;
        List<String> keep = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : lines) {
            if (line.isBlank() || !seen.add(line)) {
                continue;
            }
            JsonNode n;
            try {
                n = JSON.readTree(line);
            } catch (IOException e) {
                continue;                                                    // a line cut short: its load is completed again by its summary
            }
            List<String> files = new ArrayList<>();
            n.path("files").forEach(x -> files.add(x.asText()));
            long rej = n.path("rejected").asLong(-1);
            Signal.Landed l = new Signal.Landed(n.path("pack").asText(), n.path("kind").asText(), LocalDate.parse(n.path("date").asText()),
                    n.path("rows").asLong(), rej < 0 ? null : rej, n.path("batch").asText(), files);
            try {
                signal.landed(l);
                sent++;
            } catch (IllegalArgumentException e) {
                LOG.warn("data inbox: the server refused the signal {} {} {}: {} (not sent again)", l.pack(), l.kind(), l.date(), e.getMessage());
            } catch (RuntimeException e) {
                LOG.warn("data inbox: the signal {} {} {} could not be sent: {} (sent again at the next cycle)", l.pack(), l.kind(), l.date(),
                        e.getMessage());
                keep.add(line);
            }
        }
        if (keep.isEmpty()) {
            Files.deleteIfExists(f);
        } else {
            Files.write(f, keep, StandardCharsets.UTF_8);
        }
        return sent;
    }
}
