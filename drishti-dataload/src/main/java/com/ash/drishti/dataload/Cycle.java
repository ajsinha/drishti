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

import com.ash.drishti.lakeload.FileDates;
import com.ash.drishti.lakeload.LakeLoad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run of the inbox (DATALOAD_INBOX.md section 4): the owner only, not while busy protection says the server is short, under the folder
 * lock; the stopped runs finished ({@link Recovery}); the whole batch of ready, stable files found ({@link Inbox}) and claimed at once into
 * {@code inprocess/<run>/} ({@link Claims}; files that become ready meanwhile wait for the next cycle), duplicates skipped by SHA-256 ({@link Ledger}), routed ({@link Routes}) and loaded, the files of one table in one
 * loader run ({@link LoaderProcess}), one table after another; recorded, moved with their reports ({@link Outbox}) and, last, the server
 * told ({@link Signal}). Every step is written to the ledger before it is acted on.
 */
public final class Cycle {

    private static final Logger LOG = LoggerFactory.getLogger(Cycle.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);

    /** What became of one file. */
    public record FileOutcome(String file, String outcome, String reason, String pack, String kind, Map<String, Long> versions, long rows, long rejected,
            String dateNote) {

        /** An outcome without a note on dates taken from the file's name. */
        public FileOutcome(String file, String outcome, String reason, String pack, String kind, Map<String, Long> versions, long rows, long rejected) {
            this(file, outcome, reason, pack, kind, versions, rows, rejected, null);
        }
    }

    /**
     * One cycle: its run id, what started it, when, its state ({@code done}, {@code skipped}, {@code failed}), why it was skipped or failed,
     * each file's outcome, the notices (a table locked three cycles in a row) and how many signals were sent.
     */
    public record Report(String run, String trigger, Instant started, Instant finished, String state, String why, List<FileOutcome> files,
            List<String> notices, int signals) {}

    /** A test's way to stop the "server" after a step ({@code claimed}, {@code loaded}, {@code recorded}, {@code moved}). */
    @FunctionalInterface
    public interface Faults {
        void at(String step);

        Faults NONE = s -> { };
    }

    /** One file claimed for this run. */
    private record Claimed(String name, Path file, Path marker, String sha, long size, ReadyNote note, Routes.Route route) {}

    private final DataLoadSettings settings;
    private final Path root;
    private final String node;
    private final Clock clock;
    private final ZoneId zone;
    private final Routes.Packs packs;
    private final LoadRunner runner;
    private final Signal signal;
    private final Supplier<String> busy;
    private final Faults faults;
    private int loads;

    /**
     * @param busy why the server is too busy for a load now (busy protection, the disk's floor), or null
     */
    public Cycle(DataLoadSettings settings, String node, Clock clock, ZoneId zone, Routes.Packs packs, LoadRunner runner, Signal signal,
            Supplier<String> busy, Faults faults) {
        this.settings = settings;
        this.root = Path.of(settings.dir());
        this.node = node;
        this.clock = clock;
        this.zone = zone;
        this.packs = packs;
        this.runner = runner;
        this.signal = signal;
        this.busy = busy == null ? () -> null : busy;
        this.faults = faults == null ? Faults.NONE : faults;
    }

    /**
     * A new run id: its start time (UTC) and node, {@code 20261103T120000Z-drishti-main} (visible, sortable); {@code -2}, {@code -3} when a
     * run of the same second has a folder already.
     */
    String newRun() {
        String id = RUN.format(clock.instant()) + "-" + node.replaceAll("[^A-Za-z0-9._-]", "-");
        String day = java.time.LocalDate.now(clock.withZone(zone)).toString();
        String out = id;
        for (int n = 2; Files.exists(root.resolve("inprocess").resolve(out)) || Files.exists(root.resolve("outbox").resolve(day).resolve(out))
                || Files.exists(root.resolve("failed").resolve(day).resolve(out)); n++) {
            out = id + "-" + n;
        }
        return out;
    }

    /** What a cycle due while {@code holder} holds the lock says: {@code skipped: run <id> still loading (12 min)}. */
    static String stillLoading(JsonNode holder, Instant now) {
        if (holder == null) {
            return "another run holds the inbox lock";
        }
        long min = 0;
        try {
            min = Math.max(0, Duration.between(Instant.parse(holder.path("since").asText()), now).toMinutes());
        } catch (RuntimeException e) {
            // an unreadable time: the minutes are left out
        }
        String run = holder.path("run").asText("");
        return "run " + (run.isEmpty() ? "of " + holder.path("node").asText("?") : run) + " still loading (" + min + " min)";
    }

    /** Runs the cycle; {@code trigger} says who asked ({@code schedule}, {@code by a.sinha}). */
    public Report run(String trigger) {
        Instant t0 = clock.instant();
        String run = newRun();
        if (!settings.owner()) {
            return skipped(run, trigger, t0, "this node is not the inbox's owner (drishti.dataload.owner: false)");
        }
        String why = busy.get();
        if (why != null) {
            return skipped(run, trigger, t0, "busy protection: " + why + "; the next cycle tries again");
        }
        Claims claims = new Claims(root, clock);
        Duration stale = settings.interval().multipliedBy(3);
        Duration beat = Duration.ofMillis(Math.max(1000, Math.min(60_000, settings.interval().toMillis() / 3)));
        Claims.Lock lock;
        try {
            Files.createDirectories(root.resolve("inbox"));
            lock = claims.lock(run, node, stale, beat);
        } catch (IOException e) {
            return skipped(run, trigger, t0, "the folder lock could not be taken: " + e.getMessage());
        }
        if (lock == null) {
            return skipped(run, trigger, t0, stillLoading(claims.holder(), clock.instant()));
        }
        List<FileOutcome> files = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        int sent = 0;
        try (lock) {
            Ledger ledger = new Ledger(root.resolve("ledger"), clock);
            Outbox outbox = new Outbox(root, clock, zone);
            Recovery recovery = new Recovery(claims, ledger, outbox, signal);
            files.addAll(recovery.run());
            Routes routes;
            try {
                routes = Routes.read(Path.of(settings.rulesFile()), settings.lake(), packs);
            } catch (IOException | IllegalArgumentException e) {
                return new Report(run, trigger, t0, clock.instant(), "skipped", "the routing rules cannot be read: " + e.getMessage(), files, notices, 0);
            }
            Inbox.Found found = new Inbox(outbox.inbox()).look(clock.instant(), Duration.ofSeconds(settings.stableSeconds()), settings.maxFiles(),
                    settings.maxBytes());
            for (Inbox.Refused r : found.refused()) {
                ledger.write(run, r.file().getFileName().toString(), Ledger.FAILED, JSON.createObjectNode().put("reason", r.reason()));
                outbox.failed(run, r.file(), r.marker(), report(r.file().getFileName().toString(), run, Ledger.FAILED).put("reason", r.reason()));
                files.add(new FileOutcome(r.file().getFileName().toString(), Ledger.FAILED, r.reason(), null, null, Map.of(), 0, 0));
            }
            // the whole batch claimed in one step, before anything is read: a file is in the inbox or in this run's folder, never both
            List<Path[]> claimed = new ArrayList<>();
            for (Inbox.Ready r : found.ready()) {
                ledger.write(run, r.name(), Ledger.CLAIMED, JSON.createObjectNode().put("size", r.size()).put("node", node));
                claimed.add(claims.claim(r, run));
            }
            Map<String, List<Claimed>> groups = new LinkedHashMap<>();
            for (int i = 0; i < claimed.size(); i++) {
                Inbox.Ready r = found.ready().get(i);
                Claimed cl = examine(run, r.name(), claimed.get(i)[0], claimed.get(i)[1], r.size(), ledger, outbox, routes, files);
                if (cl != null) {
                    groups.computeIfAbsent(cl.route().group(), k -> new ArrayList<>()).add(cl);
                }
            }
            Plan plan = groups.isEmpty() ? null : new Plan(claims.run(run), run, t0, groups.values());
            faults.at("claimed");
            int t = 0;
            for (List<Claimed> g : groups.values()) {
                t++;
                if (!lock.held()) {
                    for (Claimed c : g) {
                        giveBack(run, c, Ledger.RETURNED, "the folder lock was lost (another runner took it): back in the inbox", ledger, outbox, files);
                    }
                    plan.ended(t, files);
                    continue;
                }
                plan.loading(t);
                load(run, g, ledger, outbox, files, notices);
                plan.ended(t, files);
            }
            sent = recovery.finish(run);
            outbox.readyAll(run, found.readyAll());
            int purged = outbox.purge(settings.keepOutboxDays());
            if (purged > 0) {
                LOG.info("data inbox: {} outbox and failed day folder(s) older than {} days removed", purged, settings.keepOutboxDays());
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("data inbox: run {} stopped: {}", run, e.toString());
            return new Report(run, trigger, t0, clock.instant(), "failed", e.getMessage() == null ? e.toString() : e.getMessage(), files, notices, sent);
        }
        notices.forEach(n -> LOG.warn("data inbox: {}", n));
        return new Report(run, trigger, t0, clock.instant(), "done", files.isEmpty() ? "nothing ready" : null, files, notices, sent);
    }

    private Report skipped(String run, String trigger, Instant t0, String why) {
        LOG.info("data inbox: cycle skipped: {}", why);
        return new Report(run, trigger, t0, clock.instant(), "skipped", why, List.of(), List.of(), 0);
    }

    /** A claimed file's checks before it may load: its checksum, a duplicate, its marker, its route. Null when it was handled here. */
    private Claimed examine(String run, String name, Path file, Path marker, long size, Ledger ledger, Outbox outbox, Routes routes,
            List<FileOutcome> files) throws IOException {
        String sha = Ledger.sha256(file);
        JsonNode before = ledger.loaded(sha);
        if (before != null && !settings.reloadDuplicates()) {
            String why = "already loaded in run " + before.path("run").asText() + " on " + before.path("at").asText() + " (" + before.path("file").asText()
                    + ", the same SHA-256): not loaded again";
            ledger.write(run, name, Ledger.DUPLICATE, JSON.createObjectNode().put("sha256", sha).put("size", size).put("reason", why)
                    .put("alreadyRun", before.path("run").asText()));
            outbox.done(run, file, marker, report(name, run, Ledger.DUPLICATE).put("sha256", sha).put("reason", why));
            files.add(new FileOutcome(name, Ledger.DUPLICATE, why, null, null, Map.of(), 0, 0));
            return null;
        }
        ReadyNote note;
        Routes.Route route;
        try {
            note = ReadyNote.read(marker);
            String rowForm = RowForms.refusal(file);                       // loader rows, lake rows: no reader in the loader, refused
            if (rowForm != null) {
                throw new Routes.Unrouted(rowForm);
            }
            if (note.sha256() != null && !note.sha256().equals(sha)) {
                throw new Routes.Unrouted("the SHA-256 in its .ready file is not the file's (" + sha + "): it may have been cut short or changed");
            }
            route = routes.route(name, note);
        } catch (Routes.Unrouted | IllegalArgumentException e) {
            fail(run, name, file, marker, sha, size, e.getMessage(), null, ledger, outbox, files);
            return null;
        }
        return new Claimed(name, file, marker, sha, size, note, route);
    }

    /** One table's files through the loader; on a failure of several files, each again alone, so one bad file fails alone. */
    private void load(String run, List<Claimed> group, Ledger ledger, Outbox outbox, List<FileOutcome> files, List<String> notices) throws IOException {
        List<Claimed> go = new ArrayList<>();
        for (Claimed c : group) {
            if (c.note().rows() == null) {
                go.add(c);
                continue;
            }
            // rows named in the .ready file: the loader reads the file first without writing (a dry run), and its count must match
            Loaded check = runLoader(run, List.of(c), List.of("--dry-run"), "check");
            long docs = check.summary() == null ? -1 : check.summary().path("documents").asLong(-1);
            if (check.result().code() == LakeLoad.FAILED || docs != c.note().rows()) {
                fail(run, c.name(), c.file(), c.marker(), c.sha(), c.size(), check.result().code() == LakeLoad.FAILED && docs < 0
                        ? "its check failed before loading: " + reason(check) : "its .ready file says " + c.note().rows() + " rows, the file holds "
                        + docs + " documents: not loaded", check.summary(), ledger, outbox, files);
                continue;
            }
            go.add(c);
        }
        if (go.isEmpty()) {
            return;
        }
        Loaded l = runLoader(run, go, List.of(), "");
        faults.at("loaded");
        int code = l.result().code();
        if (l.result().timedOut()) {
            for (Claimed c : go) {
                giveBack(run, c, Ledger.RETURNED, l.result().detail() + ": back in the inbox", ledger, outbox, files);
            }
            return;
        }
        if (code == LakeLoad.LOCKED) {
            for (Claimed c : go) {
                int times = ledger.lockedTimes(c.sha()) + 1;
                giveBack(run, c, Ledger.LOCKED, "the table is locked by another load (" + reason(l) + "): back in the inbox, tried again next cycle",
                        ledger, outbox, files);
                if (times >= 3) {
                    notices.add(c.name() + ": its table " + c.route().pack() + "/" + c.route().kind() + " has been locked " + times
                            + " cycles in a row; another load holds it (see the lake's _drishti-lake/locks)");
                }
            }
            return;
        }
        boolean committed = l.summary() != null && l.summary().path("commit").path("dates").size() > 0;
        if (code == LakeLoad.FAILED && go.size() > 1 && !committed) {
            for (Claimed c : go) {
                load(run, List.of(c), ledger, outbox, files, notices);
            }
            return;
        }
        List<Signal.Landed> landed = committed ? Recovery.landed(l.summary(), go.get(0).route().pack(), go.get(0).route().kind(), l.batch(),
                go.stream().map(Claimed::name).toList()) : List.of();
        if (code == LakeLoad.FAILED) {
            Recovery.addPending(l.dir(), landed);
            for (Claimed c : go) {
                fail(run, c.name(), c.file(), c.marker(), c.sha(), c.size(), reason(l) + (committed ? " (the dates committed before the failure "
                        + "stay, and the server is told of them)" : ""), l.summary(), ledger, outbox, files);
            }
            return;
        }
        ObjectNode routeNode = routeNode(go.get(0).route(), l.batch());
        for (Claimed c : go) {
            ledger.write(run, c.name(), Ledger.LOADED, loadedFields(l.summary(), routeNode, c.sha(), c.size()));
        }
        Recovery.addPending(l.dir(), landed);
        faults.at("recorded");
        for (Claimed c : go) {
            ObjectNode rep = report(c.name(), run, Ledger.LOADED).put("sha256", c.sha()).put("size", c.size());
            rep.set("route", routeNode);
            rep.set("filesInLoad", JSON.valueToTree(go.stream().map(Claimed::name).toList()));
            if (l.summary() != null) {
                rep.set("load", l.summary());
            }
            outbox.done(run, c.file(), c.marker(), rep);
            files.add(new FileOutcome(c.name(), Ledger.LOADED, code == LakeLoad.PARTIAL ? "loaded with rejects" : null, c.route().pack(),
                    c.route().kind(), versions(l.summary()), l.summary() == null ? 0 : l.summary().path("accepted").asLong(),
                    l.summary() == null ? 0 : l.summary().path("rejected").asLong(), dateNote(l.summary(), c.name())));
        }
        faults.at("moved");
    }

    /** One loader run: its load file, its route beside it (for a restart), its summary when it wrote one. */
    private record Loaded(LoadRunner.Result result, JsonNode summary, Path dir, String batch) {}

    private Loaded runLoader(String run, List<Claimed> files, List<String> extra, String what) throws IOException {
        Path dir = new Claims(root, clock).run(run);
        int n = ++loads;
        String base = Recovery.LOAD + n + (what.isEmpty() ? "" : "-" + what);
        Path config = dir.resolve(base + ".yaml");
        Path summary = dir.resolve(base + ".summary.json");
        Routes.Route r = files.get(0).route();
        String batch = "inbox:" + run + "#" + n;
        LoaderProcess.writeConfig(config, r, files.stream().map(Claimed::file).toList(), summary, dir.resolve(base + ".rejects.jsonl"),
                settings.rejectMaxPercent(), null);
        if (what.isEmpty()) {
            Files.writeString(dir.resolve(base + ".route.json"), routeNode(r, batch).toPrettyString());
        }
        LoadRunner.Result res = runner.run(config, extra, dir.resolve(base + ".log"));
        JsonNode s = null;
        if (Files.isRegularFile(summary)) {
            try {
                s = JSON.readTree(summary.toFile());
            } catch (IOException e) {
                s = null;
            }
        }
        return new Loaded(res, s, dir, batch);
    }

    private static String reason(Loaded l) {
        String e = l.summary() == null ? null : l.summary().path("error").asText(null);
        if (e != null && !e.isBlank()) {
            return e;
        }
        String d = l.result().detail() == null ? "" : l.result().detail().strip();
        String last = d.isEmpty() ? "" : d.substring(d.lastIndexOf('\n') + 1);
        return last.isEmpty() ? "the loader ended with exit code " + l.result().code() : last;
    }

    private void fail(String run, String name, Path file, Path marker, String sha, long size, String why, JsonNode summary, Ledger ledger, Outbox outbox,
            List<FileOutcome> files) throws IOException {
        ledger.write(run, name, Ledger.FAILED, JSON.createObjectNode().put("sha256", sha).put("size", size).put("reason", why));
        ObjectNode rep = report(name, run, Ledger.FAILED).put("sha256", sha).put("size", size).put("reason", why);
        if (summary != null) {
            rep.set("load", summary);
        }
        outbox.failed(run, file, marker, rep);
        files.add(new FileOutcome(name, Ledger.FAILED, why, null, null, Map.of(), 0, 0));
    }

    private void giveBack(String run, Claimed c, String outcome, String why, Ledger ledger, Outbox outbox, List<FileOutcome> files) throws IOException {
        ledger.write(run, c.name(), outcome, JSON.createObjectNode().put("sha256", c.sha()).put("size", c.size()).put("reason", why));
        outbox.back(c.file(), c.marker());
        files.add(new FileOutcome(c.name(), outcome, why, c.route().pack(), c.route().kind(), Map.of(), 0, 0));
    }

    private ObjectNode report(String name, String run, String outcome) {
        return JSON.createObjectNode().put("file", name).put("run", run).put("node", node).put("at", clock.instant().toString()).put("outcome", outcome);
    }

    private static ObjectNode routeNode(Routes.Route r, String batch) {
        ObjectNode n = JSON.createObjectNode().put("pack", r.pack()).put("kind", r.kind()).put("lake", r.lake()).put("format", r.format()).put("by", r.by())
                .put("batch", batch);
        n.put("connector", r.connector()).put("table", r.table()).put("dateField", r.dateField()).put("asOf", r.asOf() == null ? null : r.asOf().toString())
                .put("dateFrom", r.dateFrom()).put("dateBy", r.dateBy()).put("mode", r.mode());
        return n;
    }

    /**
     * The loader's words for the rows of {@code name} (or of its archive's members) whose own date field said another date than the one
     * taken from the name ({@code fileDates} of its summary); null when there were none.
     */
    static String dateNote(JsonNode summary, String name) {
        if (summary == null) {
            return null;
        }
        List<String> notes = new ArrayList<>();
        String field = summary.path("date").asText(null);
        for (JsonNode f : summary.path("fileDates")) {
            String file = f.path("file").asText();
            long n = f.path("otherDateRows").asLong();
            if (n > 0 && (file.equals(name) || file.startsWith(name + "!/"))) {
                notes.add(FileDates.otherDateNote(n, file, field, LocalDate.parse(f.path("date").asText())));
            }
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    /** The ledger's {@code loaded} line: the file's checksum and size, the route, the table, each date's version and rows. */
    static ObjectNode loadedFields(JsonNode summary, JsonNode route, String sha, long size) {
        ObjectNode n = JSON.createObjectNode().put("sha256", sha).put("size", size);
        n.set("route", route);
        if (summary != null) {
            n.put("table", summary.path("table").asText()).put("documents", summary.path("documents").asLong())
                    .put("accepted", summary.path("accepted").asLong()).put("rejected", summary.path("rejected").asLong())
                    .put("loaderRun", summary.path("run").asText());
            n.set("dates", summary.path("commit").path("dates"));
            if (summary.path("fileDates").size() > 0) {
                n.set("fileDates", summary.path("fileDates"));
            }
        }
        return n;
    }

    /** Each committed date's Delta version (or Iceberg snapshot sequence), from a summary. */
    static Map<String, Long> versions(JsonNode summary) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (summary != null) {
            summary.path("commit").path("dates").fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().path("version").asLong()));
        }
        return out;
    }

    /**
     * The run's plan, {@code inprocess/<run>/load-plan.json} (the Admin screen's Loading column): the files claimed and the tables they load
     * into, one after another, each {@code waiting}, {@code loading}, {@code committed}, {@code failed} or {@code returned}, with its start,
     * end, versions, rows and rejects. Rewritten whole (a temporary {@code .part} renamed over it) at each table's start and end, and moved
     * with the loader's files to {@code outbox/<day>/<run>/loads/} at the run's end. A file of facts only: nothing reads it to decide.
     */
    final class Plan {

        static final String FILE = Recovery.LOAD + "plan.json";

        private final Path file;
        private final ObjectNode doc;

        /** The files that load (a duplicate or a refused file of the run is already in the outbox or failed/), by table. */
        Plan(Path dir, String run, Instant started, java.util.Collection<List<Claimed>> groups) {
            this.file = dir.resolve(FILE);
            doc = JSON.createObjectNode().put("run", run).put("node", node).put("startedAt", started.toString());
            var fs = doc.putArray("files");
            for (List<Claimed> g : groups) {
                for (Claimed c : g) {
                    fs.addObject().put("file", c.name()).put("size", c.size());
                }
            }
            var ts = doc.putArray("tables");
            int n = 0;
            for (List<Claimed> g : groups) {
                Routes.Route r = g.get(0).route();
                ObjectNode t = ts.addObject().put("n", ++n).put("pack", r.pack()).put("kind", r.kind()).put("connector", r.connector())
                        .put("table", r.table()).put("state", "waiting");
                var names = t.putArray("files");
                long bytes = 0;
                for (Claimed c : g) {
                    names.addObject().put("file", c.name()).put("size", c.size());
                    bytes += c.size();
                }
                t.put("bytes", bytes);
            }
            save();
        }

        private ObjectNode table(int n) {
            return (ObjectNode) doc.path("tables").get(n - 1);
        }

        void loading(int n) {
            table(n).put("state", "loading").put("startedAt", clock.instant().toString());
            save();
        }

        /** A table's end, from what became of its files: committed when any loaded, else failed, else returned to the inbox. */
        void ended(int n, List<FileOutcome> outcomes) {
            ObjectNode t = table(n);
            java.util.Set<String> mine = new java.util.HashSet<>();
            t.path("files").forEach(f -> mine.add(f.path("file").asText()));
            int loaded = 0;
            int failed = 0;
            long rows = 0;
            long rejected = 0;
            Map<String, Long> versions = new java.util.TreeMap<>();
            String reason = null;
            List<String> dateNotes = new ArrayList<>();
            for (FileOutcome o : outcomes) {
                if (!mine.contains(o.file())) {
                    continue;
                }
                if (Ledger.LOADED.equals(o.outcome())) {
                    loaded++;
                    if (o.dateNote() != null) {
                        dateNotes.add(o.dateNote());
                    }
                    versions.putAll(o.versions());
                    rows = Math.max(rows, o.rows());                        // the files of one load share its summary: its rows, once
                    rejected = Math.max(rejected, o.rejected());
                } else if (Ledger.FAILED.equals(o.outcome())) {
                    failed++;
                    reason = o.reason();
                } else if (reason == null) {
                    reason = o.reason();
                }
            }
            t.put("state", loaded > 0 ? "committed" : failed > 0 ? "failed" : "returned").put("finishedAt", clock.instant().toString())
                    .put("loadedFiles", loaded).put("failedFiles", failed).put("rows", rows).put("rejected", rejected).put("reason", reason);
            t.set("versions", JSON.valueToTree(versions));
            t.set("dateNotes", JSON.valueToTree(dateNotes));
            save();
        }

        private void save() {
            Path part = file.resolveSibling(FILE + ".part");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(part, doc.toPrettyString());
                Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                LOG.debug("data inbox: the run's plan could not be written: {}", e.toString());   // facts for the screen only: the load goes on
            }
        }
    }
}
