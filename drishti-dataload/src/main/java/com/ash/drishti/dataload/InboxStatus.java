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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The board the Admin screen draws (DATALOAD_INBOX.md section 10, Admin → Data loads → Inbox, mock-up A), built from the folders, the
 * lock, the run's plan ({@code inprocess/<run>/load-plan.json}), the ledger, the rules and the cycles this server ran: the one status source
 * ({@link DataInbox#status()} returns it). Every count, size, duration, total and time a person reads is written here, in words and
 * numbers, so the browser computes nothing: the waiting files with their state and the rule that will route them, the run loading table
 * by table, the last cycles with their files, tables, dates, rows, rejects and time, the failures with their reasons and reports, today's
 * duplicates, the rules in effect and the settings.
 */
final class InboxStatus {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY_HM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DataLoadSettings settings;
    private final Path root;
    private final String node;
    private final Clock clock;
    private final ZoneId zone;
    private final Routes.Packs packs;

    InboxStatus(DataLoadSettings settings, String node, Clock clock, ZoneId zone, Routes.Packs packs) {
        this.settings = settings;
        this.root = Path.of(settings.dir());
        this.node = node;
        this.clock = clock;
        this.zone = zone;
        this.packs = packs;
    }

    /**
     * Adds the board's keys to {@code out} (the keys {@code drishti.py dataload status} reads stay as they are).
     *
     * @param on whether the schedule runs on this server
     * @param next the next cycle's time, or null
     * @param current when this server's own cycle started, or null
     * @param cycles the cycles this server ran, newest first
     */
    void addTo(Map<String, Object> out, boolean on, Instant next, Instant current, List<Cycle.Report> cycles) {
        Instant now = clock.instant();
        out.put("zone", zone.getId());
        out.put("nextCycleText", next == null ? (on ? "-" : "the inbox is off") : time(next) + " (in " + duration(Duration.between(now, next)) + ")");
        out.put("ownerText", settings.owner() ? node + " (this node)" : "not this node (" + node + "): cycles run on the owner");
        JsonNode holder = new Claims(root, clock).holder();
        out.put("lockText", lockText(holder, now));
        Routes routes = null;
        try {
            routes = Routes.read(Path.of(settings.rulesFile()), settings.lake(), packs);
        } catch (IOException | IllegalArgumentException e) {
            out.put("rulesError", "the routing rules cannot be read: " + e.getMessage());
        }
        out.put("rules", rules(routes));
        Map<String, Object> running = running(holder, current, now);
        if (running != null) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (out.get("running") instanceof Map<?, ?> before) {
                before.forEach((k, v) -> merged.put(String.valueOf(k), v));          // this server's own: startedAt, trigger
            }
            running.forEach((k, v) -> {
                if (v != null || !merged.containsKey(k)) {
                    merged.put(k, v);
                }
            });
            out.put("running", merged);
        }
        out.put("board", board(routes, next, now));
        List<Map<String, Object>> rows = cycleRows(cycles, running, now);
        out.put("lastCycles", rows);
        out.put("settings", settings());
        out.put("folders", folders(cycles));
    }

    /** Each waiting file with its state code, label, detail, size, the rule that will route it and when it is taken; and ready.all. */
    private Map<String, Object> board(Routes routes, Instant next, Instant now) {
        Map<String, Object> b = new LinkedHashMap<>();
        List<Map<String, Object>> waiting = new ArrayList<>();
        Path inbox = root.resolve("inbox");
        try {
            Inbox.Found f = new Inbox(inbox).look(now, Duration.ofSeconds(settings.stableSeconds()), settings.maxFiles(), settings.maxBytes());
            String nextText = next == null ? "when the inbox is turned on" : "at the next cycle, " + time(next);
            for (Inbox.Ready r : f.ready()) {
                boolean byAll = r.marker() == null;
                Map<String, Object> w = file(r.name(), r.size(), r.modified(), now, byAll ? "ready-all" : "ready", byAll ? "ready via ready.all" : "ready",
                        "stable " + duration(Duration.between(r.modified(), now)) + (byAll ? " · no marker of its own" : " · marker " + markerText(r.marker())),
                        "taken " + nextText);
                w.put("rule", preview(routes, r.name(), r.marker()));
                waiting.add(w);
            }
            for (Inbox.Waiting x : f.waiting()) {
                String mn = Inbox.markerOf(x.name(), names(inbox), new String[1]);
                Path marker = mn == null ? inbox.resolve(x.name() + Inbox.READY) : inbox.resolve(mn);
                boolean marked = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
                Map<String, Object> w = switch (x.code()) {
                    case Inbox.NOT_STABLE -> file(x.name(), x.size(), x.modified(), now, "not-stable", "not yet stable",
                            "changed " + duration(Duration.between(x.modified(), now)) + " ago · " + (marked ? "marker present" : "ready via ready.all"),
                            "taken once unchanged for " + settings.stableSeconds() + " s (stable-seconds)");
                    case Inbox.NEXT_CYCLE -> file(x.name(), x.size(), x.modified(), now, "next-cycle", "ready, next run",
                            "this run took its most files or bytes (max-files, max-gb)", "taken at the cycle after");
                    default -> file(x.name(), x.size(), x.modified(), now, "no-marker", "no marker", "since " + time(x.modified()),
                            x.why().startsWith("no ") ? "never taken without " + x.name() + Inbox.READY + " or ready.all" : x.why());
                };
                w.put("rule", preview(routes, x.name(), marked ? marker : null));
                waiting.add(w);
            }
            for (Inbox.Refused r : f.refused()) {
                String name = r.file().getFileName().toString();
                Map<String, Object> w = file(name, -1, null, now, "refused", "refused", r.reason(), "goes to failed/ at the next cycle, with the reason");
                waiting.add(w);
            }
            if (f.readyAll() != null) {
                Instant since = Files.getLastModifiedTime(f.readyAll(), LinkOption.NOFOLLOW_LINKS).toInstant();
                Map<String, Object> ra = new LinkedHashMap<>();
                ra.put("since", since.toString());
                ra.put("sinceText", "since " + time(since));
                ra.put("text", "every data file here is ready at the next cycle, with or without its own marker, once stable; then ready.all moves "
                        + "to that run's outbox (applies once)");
                b.put("readyAll", ra);
            }
        } catch (IOException e) {
            b.put("error", "the inbox cannot be read: " + e.getMessage());
        }
        b.put("waiting", waiting);
        b.put("waitingCount", waiting.size());
        b.put("waitingText", waiting.isEmpty() ? "Nothing waiting: inbox/ is empty." : null);
        failures(b);
        duplicates(b);
        return b;
    }

    private Map<String, Object> file(String name, long size, Instant modified, Instant now, String code, String label, String detail, String next) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("file", name);
        w.put("size", size < 0 ? null : size);
        w.put("sizeText", size < 0 ? "" : bytes(size));
        w.put("modified", modified == null ? null : modified.toString());
        w.put("code", code);
        w.put("label", label);
        w.put("detail", detail);
        w.put("next", next);
        return w;
    }

    /** The names in the inbox folder (to pair a file with its marker, {@link Inbox#markerOf}). */
    private static Set<String> names(Path inbox) {
        Set<String> out = new java.util.HashSet<>();
        try (var s = Files.list(inbox)) {
            s.forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException e) {
            // no folder: no names
        }
        return out;
    }

    private static String markerText(Path marker) {
        try {
            return Files.size(marker) == 0 ? "empty" : "with keys";
        } catch (IOException e) {
            return "present";
        }
    }

    private static Map<String, Object> preview(Routes routes, String name, Path marker) {
        if (routes == null) {
            return Map.of("error", "the routing rules cannot be read", "text", "the routing rules cannot be read");
        }
        ReadyNote note = ReadyNote.EMPTY;
        if (marker != null) {
            try {
                note = ReadyNote.read(marker);
            } catch (IOException | RuntimeException e) {
                return Map.of("error", "its .ready file cannot be read: " + e.getMessage(), "text", "it would go to failed/: its .ready file cannot be read");
            }
        }
        return routes.preview(name, note);
    }

    /** The run that loads now: from the lock and its folder's plan (any node on the shared folder), else this server's own start. */
    private Map<String, Object> running(JsonNode holder, Instant current, Instant now) {
        String run = holder == null ? null : holder.path("run").asText(null);
        Path dir = run == null || run.isBlank() ? null : new Claims(root, clock).run(run);
        if ((dir == null || !Files.isDirectory(dir)) && current == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Instant started = current;
        if (holder != null) {
            try {
                started = Instant.parse(holder.path("since").asText());
            } catch (RuntimeException e) {
                // the lock's time unreadable: this server's own start, when it has one
            }
        }
        m.put("run", run);
        m.put("node", holder == null ? node : holder.path("node").asText(node));
        m.put("startedAt", started == null ? null : started.toString());
        m.put("elapsedText", started == null ? "" : duration(Duration.between(started, now)));
        m.put("folder", run == null ? "inprocess/" : "inprocess/" + run + "/");
        JsonNode plan = null;
        if (dir != null) {
            try {
                plan = JSON.readTree(dir.resolve(Cycle.Plan.FILE).toFile());
            } catch (IOException e) {
                plan = null;                                                   // not written yet: the run is claiming its files
            }
        }
        List<Map<String, Object>> files = new ArrayList<>();
        long total = 0;
        if (plan != null) {
            for (JsonNode f : plan.path("files")) {
                long size = f.path("size").asLong();
                total += size;
                files.add(Map.of("file", f.path("file").asText(), "size", size, "sizeText", bytes(size)));
            }
        }
        m.put("files", files);
        m.put("fileCount", files.size());
        m.put("bytes", total);
        m.put("sizeText", bytes(total));
        Instant stops = started == null ? null : started.plus(settings.loadTimeout());
        m.put("timeoutText", "load-timeout " + settings.loadTimeout().toString().substring(2).toLowerCase(Locale.ROOT)
                + (stops == null ? "" : " (a load stops at " + time(stops) + ")"));
        m.put("heap", settings.loaderHeap());
        m.put("nice", settings.nice());
        List<Map<String, Object>> tables = new ArrayList<>();
        int count = plan == null ? 0 : plan.path("tables").size();
        int at = 0;
        for (JsonNode t : plan == null ? JSON.createArrayNode() : plan.path("tables")) {
            tables.add(table(t, count, now));
            if (!"waiting".equals(t.path("state").asText())) {
                at = t.path("n").asInt();
            }
        }
        m.put("tables", tables);
        m.put("tableCount", count);
        m.put("tableText", count == 0 ? "claiming its files" : "table " + Math.max(1, at) + " of " + count);
        m.put("summaryText", (started == null ? "" : "started " + time(started, HMS) + " · running " + m.get("elapsedText") + " · ") + files.size()
                + (files.size() == 1 ? " file, " : " files, ") + bytes(total) + " · " + m.get("timeoutText"));
        m.put("note", "The pack signals go out when the run ends (connectors refresh, checks, alerts, notices).");
        return m;
    }

    private Map<String, Object> table(JsonNode t, int count, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        String state = t.path("state").asText("waiting");
        m.put("n", t.path("n").asInt());
        m.put("state", state);
        m.put("pack", t.path("pack").asText());
        m.put("kind", t.path("kind").asText());
        m.put("position", state + " " + t.path("n").asInt() + " of " + count);
        m.put("name", t.path("pack").asText() + " / " + t.path("kind").asText());
        m.put("label", m.get("position") + " · " + m.get("name"));
        Instant s = instant(t.path("startedAt"));
        Instant e = instant(t.path("finishedAt"));
        m.put("timeText", s == null ? "" : duration(Duration.between(s, e == null ? now : e)));
        List<Map<String, Object>> files = new ArrayList<>();
        for (JsonNode f : t.path("files")) {
            files.add(Map.of("file", f.path("file").asText(), "sizeText", bytes(f.path("size").asLong())));
        }
        m.put("files", files);
        StringBuilder d = new StringBuilder();
        t.path("versions").fields().forEachRemaining(v -> d.append(d.isEmpty() ? "" : ", ").append(v.getKey()).append(" → v").append(v.getValue().asLong()));
        String detail = switch (state) {
            case "committed" -> d + " · " + count(t.path("rows").asLong()) + " rows · " + count(t.path("rejected").asLong()) + " rejects"
                    + notes(t.path("dateNotes"));
            case "loading" -> "the loader is writing";
            case "waiting" -> "loads after the table before it";
            default -> t.path("reason").asText("");
        };
        m.put("detail", detail);
        return m;
    }

    /** The loader's notes on rows loaded under the date of their file's name, after " · " (none: nothing). */
    private static String notes(JsonNode notes) {
        StringBuilder b = new StringBuilder();
        notes.forEach(n -> b.append(" · ").append(n.asText()));
        return b.toString();
    }

    /** The last cycles of today (this server's), each in one line of words and numbers; the run loading now first. */
    private List<Map<String, Object>> cycleRows(List<Cycle.Report> cycles, Map<String, Object> running, Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (running != null && running.get("startedAt") != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("time", time(Instant.parse((String) running.get("startedAt"))));
            r.put("run", running.get("run"));
            r.put("outcome", "loading");
            r.put("label", "loading");
            r.put("text", "see Loading · " + running.get("elapsedText"));
            out.add(r);
        }
        int skipped = 0;
        for (Cycle.Report c : cycles) {
            if (c.started() == null || !LocalDate.ofInstant(c.started(), zone).equals(today)) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("time", time(c.started()));
            r.put("run", "-".equals(c.run()) ? null : c.run());
            r.put("trigger", c.trigger());
            String took = c.finished() == null ? "" : duration(Duration.between(c.started(), c.finished()));
            Map<String, Integer> by = new TreeMap<>();
            Set<String> tables = new LinkedHashSet<>();
            Set<String> dates = new java.util.TreeSet<>();
            List<String> versions = new ArrayList<>();
            long rows = 0;
            long rejects = 0;
            Set<String> counted = new java.util.HashSet<>();
            List<String> dateNotes = new ArrayList<>();
            for (Cycle.FileOutcome f : c.files()) {
                by.merge(f.outcome(), 1, Integer::sum);
                if (f.dateNote() != null) {
                    dateNotes.add(f.dateNote());
                }
                if (Ledger.LOADED.equals(f.outcome())) {
                    String t = f.pack() + "/" + f.kind();
                    tables.add(t);
                    dates.addAll(f.versions().keySet());
                    String key = t + f.versions();
                    if (counted.add(key)) {                                     // the files of one load share its summary: counted once
                        rows += f.rows();
                        rejects += f.rejected();
                        versions.add(t + " " + String.join(", ", f.versions().values().stream().map(v -> "v" + v).toList()));
                    }
                }
            }
            String outcome;
            String text;
            if ("skipped".equals(c.state())) {
                skipped++;
                outcome = "skipped";
                text = c.why();
            } else if ("failed".equals(c.state())) {
                outcome = "failed";
                text = c.why();
            } else if (c.files().isEmpty()) {
                outcome = "nothing";
                text = null;
            } else {
                int n = c.files().size();
                int failed = by.getOrDefault(Ledger.FAILED, 0);
                int loaded = by.getOrDefault(Ledger.LOADED, 0);
                outcome = failed > 0 ? "failed-files" : loaded > 0 ? "loaded" : by.containsKey(Ledger.DUPLICATE) ? "duplicate" : "returned";
                List<String> parts = new ArrayList<>();
                String split = by.size() > 1 ? ": " + String.join(", ", by.entrySet().stream().map(x -> x.getValue() + " " + x.getKey()).toList()) : "";
                parts.add(n + (n == 1 ? " file" : " files") + split);
                if (!tables.isEmpty()) {
                    parts.add(tables.size() == 1 ? tables.iterator().next() : tables.size() + " tables");
                    parts.add(String.join(", ", dates));
                    parts.add(count(rows) + " rows");
                    parts.add(count(rejects) + " rejects");
                } else if ("duplicate".equals(outcome)) {
                    parts.add("already loaded: moved to the outbox, not loaded again");
                }
                parts.addAll(dateNotes);
                parts.add(took);
                text = String.join(" · ", parts);
                r.put("versionsText", String.join(" · ", versions));
            }
            r.put("outcome", outcome);
            r.put("label", switch (outcome) {
                case "nothing" -> "nothing ready";
                case "failed-files" -> by.get(Ledger.FAILED) + " failed";
                default -> outcome;
            });
            r.put("text", text);
            r.put("tookText", took);
            r.put("notices", c.notices());
            out.add(r);
        }
        if (!out.isEmpty()) {
            out.get(0).put("todayCount", out.size());
            out.get(0).put("skippedToday", skipped);
        }
        return out;
    }

    /** The failed files (the newest 50) with their reason, run, time, pack and kind, report and rejects; how many there are. */
    private void failures(Map<String, Object> b) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Outbox ob = new Outbox(root, clock, zone);
            List<Path> failed = ob.failedCopies();
            for (Path p : failed.subList(Math.max(0, failed.size() - 50), failed.size()).reversed()) {
                Map<String, Object> m = new LinkedHashMap<>(ob.describeFailed(p));
                Path rep = p.resolveSibling(p.getFileName() + ".failed.json");
                m.put("report", rel(rep));
                try {
                    JsonNode r = JSON.readTree(rep.toFile());
                    Instant at = instant(r.path("at"));
                    m.put("atText", at == null ? "" : day(at));
                    JsonNode route = r.path("load").path("route");
                    m.put("pack", r.path("route").path("pack").asText(route.path("pack").asText(null)));
                    m.put("kind", r.path("route").path("kind").asText(route.path("kind").asText(null)));
                    long docs = r.path("load").path("documents").asLong(0);
                    long rej = r.path("load").path("rejected").asLong(0);
                    m.put("rejectsText", docs > 0 && rej > 0 ? count(rej) + " of " + count(docs) + " rows rejected" : null);
                    Path rejects = p.getParent().resolve("loads");
                    m.put("rejects", Files.isDirectory(rejects) ? rel(rejects) + "/" : null);
                } catch (IOException e) {
                    m.put("atText", "");
                }
                m.put("retryText", "Retry puts the file and its marker back in the inbox; the next cycle or Load now loads it. Fix the file or the rules first.");
                out.add(m);
            }
            b.put("failedTotal", failed.size());
        } catch (IOException e) {
            b.put("failedTotal", 0);
        }
        b.put("failures", out);
        b.put("failedText", out.isEmpty() ? "No failed files in the last " + settings.keepOutboxDays() + " days (keep-outbox-days)."
                : out.size() + (out.size() == 1 ? ", needs a person" : ", need a person"));
    }

    /** Today's duplicates, from the ledger: the file, the run, and the run that loaded it before. */
    private void duplicates(Map<String, Object> b) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        try {
            for (JsonNode n : Ledger.tail(root.resolve("ledger"), 500)) {
                Instant at = instant(n.path("at"));
                if (at == null || !LocalDate.ofInstant(at, zone).equals(today) || !Ledger.DUPLICATE.equals(n.path("outcome").asText())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", n.path("file").asText());
                m.put("run", n.path("run").asText());
                m.put("at", at.toString());
                m.put("alreadyRun", n.path("alreadyRun").asText(null));
                m.put("text", "run " + n.path("run").asText() + " · moved to the outbox, not loaded again");
                m.put("reason", n.path("reason").asText(""));
                out.add(m);
            }
        } catch (IOException e) {
            // no ledger yet: no duplicates
        }
        b.put("duplicates", out);
        b.put("duplicatesCount", out.size());
    }

    private Map<String, Object> rules(Routes routes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", routes == null ? settings.rulesFile() : routes.source());
        m.put("rules", routes == null ? List.of() : routes.describe());
        m.put("unmatched", routes == null ? "fail" : routes.unmatched());
        m.put("unmatchedText", "anything else: unmatched: fail, to failed/ with the reason, never guessed");
        m.put("note", "Read-only, from " + Path.of(settings.rulesFile()).getFileName() + " (read again every cycle). A file's .ready may name its own "
                + "pack, kind, table and date over these.");
        return m;
    }

    private Map<String, Object> settings() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("interval", settings.interval().toString());
        cfg.put("stableSeconds", settings.stableSeconds());
        cfg.put("maxFiles", settings.maxFiles());
        cfg.put("maxGb", settings.maxGb());
        cfg.put("loaderHeap", settings.loaderHeap());
        cfg.put("loadTimeout", settings.loadTimeout().toString());
        cfg.put("rejectMaxPercent", settings.rejectMaxPercent());
        cfg.put("reloadDuplicates", settings.reloadDuplicates());
        cfg.put("keepOutboxDays", settings.keepOutboxDays());
        cfg.put("lake", settings.lake());
        cfg.put("rulesFile", settings.rulesFile());
        cfg.put("text", "interval " + iso(settings.interval()) + " · stable-seconds " + settings.stableSeconds() + " · loader-heap " + settings.loaderHeap()
                + " · load-timeout " + iso(settings.loadTimeout()) + " · reject-max-percent " + trim(settings.rejectMaxPercent()) + " · max-files "
                + (settings.maxFiles() <= 0 ? "off" : settings.maxFiles()) + " · max-gb " + (settings.maxGb() <= 0 ? "off" : trim(settings.maxGb()))
                + " · reload-duplicates " + settings.reloadDuplicates() + " · keep-outbox-days " + settings.keepOutboxDays());
        return cfg;
    }

    private Map<String, Object> folders(List<Cycle.Report> cycles) {
        Map<String, Object> m = new LinkedHashMap<>();
        String today = LocalDate.ofInstant(clock.instant(), zone).toString();
        String last = cycles.stream().filter(c -> "done".equals(c.state()) && !c.files().isEmpty()).map(Cycle.Report::run).findFirst().orElse(null);
        m.put("outbox", "outbox/" + today + "/" + (last == null ? "" : last + "/"));
        m.put("failed", "failed/" + today + "/");
        m.put("ledger", "ledger/ledger-" + DateTimeFormatter.ofPattern("yyyy-MM").withZone(java.time.ZoneOffset.UTC).format(clock.instant()) + ".jsonl");
        return m;
    }

    private String lockText(JsonNode holder, Instant now) {
        if (holder == null) {
            return "free";
        }
        Instant beat = instant(holder.path("heartbeat"));
        String who = holder.path("node").asText("?");
        return "held by " + who + (who.equals(node) ? " (this node)" : "") + (holder.path("run").asText("").isEmpty() ? "" : ", run "
                + holder.path("run").asText()) + (beat == null ? "" : ", heartbeat " + duration(Duration.between(beat, now)) + " ago");
    }

    private String rel(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private String time(Instant t) {
        return time(t, HM);
    }

    private String time(Instant t, DateTimeFormatter f) {
        return f.format(t.atZone(zone));
    }

    private String day(Instant t) {
        return DAY_HM.format(t.atZone(zone));
    }

    private static Instant instant(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        try {
            return Instant.parse(n.asText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code 4 s}, {@code 1 min 12 s}, {@code 23 min}, {@code 2 h 5 min}: never negative. */
    static String duration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min" + (s % 60 == 0 ? "" : " " + (s % 60) + " s");
        }
        long min = (s % 3600) / 60;
        return (s / 3600) + " h" + (min == 0 ? "" : " " + min + " min");
    }

    /** {@code 412 MB}, {@code 1.8 GB}, {@code 22 KB}, {@code 300 B} (1024-based, as the command line shows). */
    static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        double v = n;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (v >= 100 || u == 0 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v)) + " " + units[u];
    }

    /** {@code 1,284,551}. */
    static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String iso(Duration d) {
        return d.toString().substring(2).toLowerCase(Locale.ROOT);
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
