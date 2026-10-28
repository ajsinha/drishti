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
package com.ash.drishti.lakeload;

import com.ash.drishti.lakeload.Inputs.InputFile;
import com.ash.drishti.lakeload.Inputs.Split;
import com.ash.drishti.lakeload.TableTarget.WrittenFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a load did, in the words of {@code drishti.py lake build}'s summary (docs/guides/TOOLS.md section 4.6), so the console, the run log
 * and scripts read either the same way: documents, accepted, rejected (by reason, the first ones with where they were: {@code file:line}),
 * rows per date and partition, ids seen twice, the commit per date, the columns; and beside them the loader's own: the throughput of each
 * stage. The <b>load manifest</b> ({@link #manifest}) lists each written file with its rows and its first and last sort key, for a reader
 * that wants only some legal entities and desks (the cluster's VaR shards).
 */
final class LoadReport {

    static final ObjectMapper JSON = new ObjectMapper();

    private final LoadConfig cfg;
    private final RowPlan plan;
    final String run = OffsetDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) + "-" + ProcessHandle.current().pid();
    final String startedAt = now();
    String status = "failed";
    long documents;
    long accepted;
    long rejected;
    long bytesTotal;
    double readSeconds;
    double writeSeconds;
    long spillEstimate;
    long peakHeapBytes;
    long heapPoolPeaksBytes;
    long peakLiveBytes;
    final Map<String, Long> rejectsByReason = new LinkedHashMap<>();
    final List<ObjectNode> firstRejects = new ArrayList<>();
    final Map<String, Long> rowsByDate = new TreeMap<>();
    final List<ObjectNode> partitions = new ArrayList<>();
    final Map<String, Long> duplicates = new TreeMap<>();
    final List<ObjectNode> files = new ArrayList<>();
    final Map<String, ObjectNode> commits = new TreeMap<>();
    final Map<String, List<WrittenFile>> written = new TreeMap<>();
    Map<String, String> columnTypes = Map.of();
    List<String> leftOut = List.of();
    String sampleId;
    String sampleDate;
    String rejectsFile;
    private Map<String, Meter> meters = Map.of();
    private AtomicLong liveDocs;
    private AtomicLong liveRejected;
    private Meter liveRead;

    LoadReport(LoadConfig cfg, RowPlan plan) {
        this.cfg = cfg;
        this.plan = plan;
    }

    static String now() {
        return OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    void inputs(List<InputFile> in) {
        for (InputFile f : in) {
            files.add(JSON.createObjectNode().put("file", f.path()).put("bytes", f.size()).put("compression", f.compression().name().toLowerCase()));
            bytesTotal += f.size();
        }
    }

    void live(AtomicLong docs, AtomicLong rej, Meter read) {
        liveDocs = docs;
        liveRejected = rej;
        liveRead = read;
    }

    long bytesRead() {
        return liveRead == null ? 0 : liveRead.bytesIn.sum();
    }

    long documentsSoFar() {
        return liveDocs == null ? documents : liveDocs.get();
    }

    long rejectedSoFar() {
        return liveRejected == null ? rejected : liveRejected.get();
    }

    /** After the read: the workers' counts together, rejects named by file and line, ids seen twice counted. */
    void collect(List<ParseWorker> workers, List<Split> splits, ParseWorker.LineCounts lines) throws IOException {
        List<ParseWorker.Reject> first = new ArrayList<>();
        Map<String, Map<List<String>, Long>> parts = new TreeMap<>();
        Map<Integer, List<long[]>> hashes = new TreeMap<>();
        ParseWorker.Where sampleAt = null;
        for (ParseWorker w : workers) {
            documents += w.documents;
            rejected += w.rejected;
            w.rejectsByReason.forEach((k, v) -> rejectsByReason.merge(k, v, Long::sum));
            first.addAll(w.firstRejects);
            for (Map.Entry<Integer, ParseWorker.DateRows> e : w.dates.entrySet()) {
                String day = LocalDate.ofEpochDay(e.getKey()).toString();
                ParseWorker.DateRows d = e.getValue();
                rowsByDate.merge(day, d.rows, Long::sum);
                accepted += d.rows;
                d.partitions.forEach((k, v) -> parts.computeIfAbsent(day, x -> new LinkedHashMap<>()).merge(k, v, Long::sum));
                hashes.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(Arrays.copyOf(d.hashes, d.nHashes));
                d.hashes = null;
            }
            if (w.sampleAt != null && (sampleAt == null || w.sampleAt.compareTo(sampleAt) < 0)) {
                sampleAt = w.sampleAt;
                sampleId = w.sampleId;
                sampleDate = LocalDate.ofEpochDay(w.sampleDay).toString();
            }
        }
        Map<String, Long> sortedReasons = new LinkedHashMap<>();
        rejectsByReason.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> sortedReasons.put(e.getKey(), e.getValue()));
        rejectsByReason.clear();
        rejectsByReason.putAll(sortedReasons);
        first.sort((a, b) -> a.where().compareTo(b.where()));
        for (ParseWorker.Reject r : first.subList(0, Math.min(cfg.rejects().keep(), first.size()))) {
            firstRejects.add(JSON.createObjectNode().put("where", where(r.where(), splits, lines)).put("reason", r.reason()));
        }
        List<String> names = plan.partition.stream().map(RowPlan::column).toList();
        parts.forEach((day, m) -> m.forEach((values, n) -> {
            ObjectNode p = JSON.createObjectNode().put("date", day);
            ObjectNode v = p.putObject("values");
            for (int i = 0; i < names.size(); i++) {
                v.put(names.get(i), values.get(i));
            }
            p.put("rows", n);
            partitions.add(p);
        }));
        hashes.forEach((day, lists) -> {
            int n = lists.stream().mapToInt(a -> a.length).sum();
            long[] all = new long[n];
            int at = 0;
            for (long[] a : lists) {
                System.arraycopy(a, 0, all, at, a.length);
                at += a.length;
            }
            Arrays.sort(all);
            long dups = 0;
            for (int i = 1; i < all.length; i++) {
                if (all[i] == all[i - 1]) {
                    dups++;
                }
            }
            if (dups > 0) {
                duplicates.put(LocalDate.ofEpochDay(day).toString(), dups);
            }
        });
        writeRejects(workers, splits, lines);
    }

    /** {@code file:line} (the line counted in its file, as lake build counts it). */
    private static String where(ParseWorker.Where w, List<Split> splits, ParseWorker.LineCounts lines) {
        Split s = splits.get(w.split());
        long before = 0;
        for (int i = w.split() - s.index(); i < w.split(); i++) {
            before += lines.total(i);
        }
        return s.file().name() + ":" + (before + lines.before(w.split(), w.seq()) + w.line());
    }

    /** Every reject, with where, why and its line as read, to {@code rejects.file} (JSON Lines). */
    private void writeRejects(List<ParseWorker> workers, List<Split> splits, ParseWorker.LineCounts lines) throws IOException {
        if (cfg.rejects().file() == null || rejected == 0) {
            return;
        }
        Path out = Path.of(cfg.rejects().file());
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (ParseWorker pw : workers) {
                if (!Files.exists(pw.rejectsFile)) {
                    continue;
                }
                try (InputStream raw = com.ash.drishti.api.ChannelIo.newInputStream(pw.rejectsFile); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {
                    while (true) {
                        int split;
                        try {
                            split = in.readInt();
                        } catch (EOFException end) {
                            break;
                        }
                        ParseWorker.Where where = new ParseWorker.Where(split, in.readInt(), in.readInt());
                        byte[] reason = in.readNBytes(in.readInt());
                        byte[] line = in.readNBytes(in.readInt());
                        ObjectNode n = JSON.createObjectNode().put("where", where(where, splits, lines))
                                .put("reason", new String(reason, StandardCharsets.UTF_8)).put("line", new String(line, StandardCharsets.UTF_8));
                        w.write(JSON.writeValueAsString(n));
                        w.newLine();
                    }
                }
            }
        }
        rejectsFile = out.toAbsolutePath().toString();
    }

    void columns(Map<String, String> types, List<String> left) {
        columnTypes = types;
        leftOut = left;
    }

    synchronized void committed(LocalDate date, long version, List<WrittenFile> f) {
        long rows = f.stream().mapToLong(WrittenFile::rows).sum();
        long bytes = f.stream().mapToLong(x -> Math.max(0, x.bytes())).sum();
        commits.put(date.toString(), JSON.createObjectNode().put("version", version).put("files", f.size()).put("rows", rows).put("bytes", bytes));
        written.put(date.toString(), f);
    }

    void meters(Map<String, Meter> m) {
        meters = m;
    }

    int exitCode() {
        return switch (status) {
            case "ok", "dry-run" -> rejected > 0 ? 2 : 0;
            case "partial" -> 2;
            case "locked" -> 3;
            default -> 1;
        };
    }

    /** The summary: lake build's keys, and {@code throughput} per stage. */
    ObjectNode summary(String error, double totalSeconds) {
        ObjectNode s = JSON.createObjectNode();
        String[] dt = cfg.domainAndTable();
        s.put("run", run).put("startedAt", startedAt).put("finishedAt", now()).put("status", status).put("exitCode", exitCode())
                .put("dryRun", cfg.report().dryRun()).put("mode", "overwrite-dates").put("format", cfg.target().format())
                .put("engine", "java-loader").put("engineWhy", "the parallel lake loader (drishti-lakeload)").put("table", cfg.tablePath())
                .put("domain", dt[0]).put("kind", cfg.target().kind() != null ? cfg.target().kind() : dt[1]).put("key", plan.key);
        s.put("date", plan.asOf != null ? null : plan.date).put("asOf", plan.asOf == null ? null : plan.asOf.toString());
        ArrayNode promote = s.putArray("promote");
        plan.promoted.forEach(p -> promote.add(p.path()));
        ArrayNode pb = s.putArray("partitionBy");
        plan.partition.forEach(pb::add);
        ArrayNode sb = s.putArray("sortBy");
        plan.sortNames.forEach(sb::add);
        s.set("rows", JSON.valueToTree(rowsByDate));
        s.set("partitions", JSON.valueToTree(partitions));
        s.put("documents", documents).put("accepted", accepted).put("rejected", rejected);
        ObjectNode rj = s.putObject("rejects");
        rj.set("byReason", JSON.valueToTree(rejectsByReason));
        rj.set("first", JSON.valueToTree(firstRejects));
        if (rejectsFile != null) {
            rj.put("file", rejectsFile);
        }
        s.set("duplicates", JSON.valueToTree(duplicates));
        s.set("files", JSON.valueToTree(files));
        s.put("sampleId", sampleId).put("sampleDate", sampleDate);
        ObjectNode timings = s.putObject("timings");
        timings.put("readSeconds", Meter.round(readSeconds)).put("writeSeconds", Meter.round(writeSeconds)).put("totalSeconds", Meter.round(totalSeconds));
        if (!commits.isEmpty()) {
            ObjectNode c = s.putObject("commit");
            c.put("commits", commits.size());
            c.set("dates", JSON.valueToTree(commits));
            c.put("files", commits.values().stream().mapToLong(n -> n.path("files").asLong()).sum());
        }
        ObjectNode cols = s.putObject("columns");
        ArrayNode pc = cols.putArray("partition");
        plan.partition.forEach(p -> pc.add(RowPlan.column(p)));
        cols.set("types", JSON.valueToTree(columnTypes));
        if (!leftOut.isEmpty()) {
            cols.set("leftOut", JSON.valueToTree(leftOut));
        }
        ObjectNode tp = s.putObject("throughput");
        meters.forEach((name, m) -> tp.set(name, m.json(JSON.createObjectNode())));
        if (totalSeconds > 0 && accepted > 0) {
            tp.put("rowsPerSecond", Math.round(accepted / totalSeconds)).put("mbPerSecond", Meter.round(bytesTotal / 1048576.0 / totalSeconds));
        }
        tp.put("cores", Runtime.getRuntime().availableProcessors());
        LoadConfig.Memory mem = cfg.memory();
        ObjectNode memory = s.putObject("memory");
        memory.put("maxHeapMb", Runtime.getRuntime().maxMemory() / 1048576).put("budgetMb", mem.budget() / 1048576)
                .put("queueMb", mem.queueBytes() / 1048576).put("sortBufferMb", mem.sortBytes() / 1048576).put("writersMb", mem.writeBytes() / 1048576)
                .put("peakHeapUsedMb", peakHeapBytes / 1048576).put("peakHeapAfterGcMb", peakLiveBytes / 1048576).put("heapPoolPeaksMb", heapPoolPeaksBytes / 1048576)
                .put("spillEstimateMb", spillEstimate / 1048576);
        Meter sp = meters.get("sortSpill");
        memory.put("spilledMb", sp == null ? 0 : sp.bytesOut.sum() / 1048576);
        if (error != null) {
            s.put("error", error);
        }
        return s;
    }

    /**
     * The load manifest: per date, per file in key order, its path, rows, bytes, partition values and the first and last values of the
     * sort key (by name: the partition columns, the sort-by columns, id). Written beside the summary.
     */
    ObjectNode manifest() {
        ObjectNode m = JSON.createObjectNode();
        m.put("table", cfg.tablePath()).put("format", cfg.target().format()).put("run", run);
        List<String> names = new ArrayList<>();
        plan.partition.forEach(p -> names.add(RowPlan.column(p)));
        names.addAll(plan.sortNames);
        m.set("sortBy", JSON.valueToTree(plan.sortNames));
        List<String> group = names.subList(0, plan.groupComponents);
        m.set("groupBy", JSON.valueToTree(group));
        ObjectNode dates = m.putObject("dates");
        written.forEach((day, list) -> {
            ObjectNode d = dates.putObject(day);
            ObjectNode c = commits.get(day);
            d.put("version", c.path("version").asLong());
            ArrayNode fs = d.putArray("files");
            for (WrittenFile f : list) {
                ObjectNode n = fs.addObject();
                n.put("path", f.path()).put("rows", f.rows()).put("bytes", f.bytes());
                if (f.partition().length > 0) {
                    n.set("partition", JSON.valueToTree(f.partition()));
                }
                n.set("first", named(names, f.first()));
                n.set("last", named(names, f.last()));
            }
        });
        return m;
    }

    private static ObjectNode named(List<String> names, List<Object> values) {
        ObjectNode n = JSON.createObjectNode();
        for (int i = 0; i < names.size() && i < values.size(); i++) {
            Object v = values.get(i);
            if (v == null) {
                n.putNull(names.get(i));
            } else if (v instanceof Double d) {
                n.put(names.get(i), d);
            } else {
                n.put(names.get(i), v.toString());
            }
        }
        return n;
    }

    /** The run log's line, as lake build writes it ({@code <lake>/_drishti-lake/runs.log}). */
    String logLine(ObjectNode s) {
        StringBuilder b = new StringBuilder();
        b.append(now()).append(" lake-build ").append(s.path("status").asText()).append(" exit=").append(s.path("exitCode").asInt())
                .append(" run=").append(run);
        for (String k : List.of("domain", "kind", "engine", "mode")) {
            if (!s.path(k).asText("").isEmpty()) {
                b.append(' ').append(k).append('=').append(s.path(k).asText());
            }
        }
        b.append(" rows=").append(accepted).append(" rejected=").append(rejected).append(" dates=").append(rowsByDate.size())
                .append(" partitions=").append(partitions.size()).append(" seconds=").append(s.path("timings").path("totalSeconds").asDouble());
        if (s.has("error")) {
            try {
                b.append(" error=").append(JSON.writeValueAsString(s.path("error").asText()));
            } catch (IOException e) {
                b.append(" error=?");
            }
        }
        return b.toString();
    }
}
