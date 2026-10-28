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
package com.ash.drishti.server.var;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.TempFiles;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * A window of the PV topic turned into one intraday batch (VAR_GUIDE.md section 8.4, Kafka): the window's latest record per trade (a
 * record is one JSONL line of a message; a message carries one or more),
 * checked, written to the lake as ONE batch of the {@code scenario-pv} kind (and of {@code pnl-vector} for a trade sent as a P&amp;L
 * vector), {@code RUN_KIND: INTRADAY} and the window's {@code BATCH_ID}, by Drishti's own lake writer (the {@code lake build} path), then
 * announced as a data-load of the kind, so the intraday layer applies it exactly as it applies a file. A restart rebuilds from the lake,
 * never from Kafka.
 *
 * <p>The guards: a record of another business date is ignored (counted); one computed before the morning run, or not later than the
 * update the date already holds for the trade (a redelivery, a restart replaying the topic), is ignored; one of another scenario set
 * ({@code DRS-5203}), with PVs that are not the set's length of finite numbers (509 in the demo) ({@code DRS-5201}, {@code DRS-5206}: never filled with zero), or
 * a line that is not a JSON object of the contract ({@code DRS-5201}) is rejected and listed with its partition, offset and line number,
 * in the admin status and in the date's {@code intraday.rejections}; the other lines of its message are applied. A cancel is a cancel
 * line ({@code TRADE_STATUS: CANCELLED}, its vector null), as in the files; a tombstone carries no record and is ignored.
 */
final class VarPvStream {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LISTED = 20;
    private final VarService svc;
    private final Path lake;
    private final BiFunction<String, String, List<String>> columns;
    private final Announcer announcer;
    private final Map<String, AtomicLong> counts = new LinkedHashMap<>();
    private final List<String> rejections = new ArrayList<>();
    private final List<Map<String, Object>> windows = new ArrayList<>();
    private int seq;

    /** Announces a batch landed (the data-load signal: LoadService in the server, a direct call in a test). */
    interface Announcer {
        void landed(String kind, LocalDate date, String batchId, long rows);
    }

    /**
     * @param lake the risk lake's root (its tables {@code <lake>/risk/<kind>})
     * @param columns the promoted columns of a kind's table (the pack's layout: kind, "columns" or "vectors")
     */
    VarPvStream(VarService svc, Path lake, BiFunction<String, String, List<String>> columns, Announcer announcer) {
        this.svc = svc;
        this.lake = lake;
        this.columns = columns;
        this.announcer = announcer;
        for (String k : List.of("received", "applied", "batches", "otherDate", "beforeMorning", "notNewer", "rejected", "unknownCancel",
                "tombstonesIgnored")) {
            counts.put(k, new AtomicLong());
        }
    }

    private void count(String k, long n) {
        counts.get(k).addAndGet(n);
    }

    private synchronized void reject(String code, String trade, String why) {
        count("rejected", 1);
        rejections.add(code + " " + trade + ": " + why);
        while (rejections.size() > LISTED) {
            rejections.remove(0);
        }
    }

    /** What the stream did, for the admin status. */
    synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        counts.forEach((k, v) -> m.put(k, v.get()));
        m.put("rejections", List.copyOf(rejections));
        m.put("lastWindows", List.copyOf(windows));
        return m;
    }

    /** A tombstone (a null value) carries no record: ignored, counted. */
    void tombstone() {
        count("tombstonesIgnored", 1);
    }

    /** One window's latest records (and its unreadable lines), applied as one intraday batch; returns what it did. */
    synchronized Map<String, Object> apply(List<VarPvWindow.Msg> msgs, String why) {
        Map<String, Object> out = new LinkedHashMap<>();
        count("received", msgs.size());
        LocalDate day = svc.loader().latest();
        if (day == null || msgs.isEmpty()) {
            out.put("status", "nothing to apply");
            return out;
        }
        VarDate usd = svc.date(day);
        VarBook book = usd.book();
        String set = book.scenarioSet();
        int setLength = usd.intake().setLength() > 0 ? usd.intake().setLength() : book.width();   // a row carries the whole set
        VarIntradayRows held = usd.intraday();
        String morningAt = held != null ? held.morningAt() : null;
        String batch = "K-" + day + "-" + Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14) + "-" + String.format("%03d", ++seq);
        Map<String, List<ObjectNode>> rows = new LinkedHashMap<>();
        List<String> dateRejections = new ArrayList<>();
        for (VarPvWindow.Msg m : msgs) {
            if (m.bad() != null) {                                      // a line that could not be read: rejected and listed, never zero
                String code = m.bad().substring(0, 8);
                reject(code, m.where(), m.bad().substring(9));
                dateRejections.add(code + " " + m.where() + ": " + m.bad().substring(9));
                continue;
            }
            JsonNode v;
            try {
                v = JSON.readTree(m.value());
            } catch (IOException e) {
                v = null;
            }
            if (!(v instanceof ObjectNode row)) {
                reject(ErrorCode.VAR_ROW_REJECTED.code(), m.where(), "not a JSON object of the contract");
                continue;
            }
            // what the record is (VAR_GUIDE.md section 15.13): its own fields, against its payload, its id and its system's pin
            String payload = row.has("SCENARIO_PV") || row.has("BASE_PV") ? VarInputs.PV : row.has("PNL") ? VarInputs.PNL : null;
            String[] said = new VarInputs(svc.props()::input).check(payload, text(row, "VALUE_TYPE"), text(row, "GRAIN"), text(row, "TRADE_ID"),
                    text(row, "POSITION_ID"), text(row, "SOURCE_SYSTEM"), text(row, "CURRENCY"));
            if (said == null && payload == null) {
                said = new String[] {ErrorCode.VAR_RECORD_INVALID.code(), "no SCENARIO_PV, BASE_PV or PNL: neither scenario PVs nor a P&L vector"};
            }
            if (said != null) {
                reject(said[0], m.where(), said[1]);
                dateRejections.add(said[0] + " " + m.where() + ": " + said[1]);
                continue;
            }
            String trade = m.key();
            String date = row.path("BUSINESS_DATE").asText("");
            if (!date.startsWith(day.toString())) {
                count("otherDate", 1);                                  // another business date: not today's layer
                continue;
            }
            String s = row.path("SCENARIO_SET_ID").asText(set);
            if (!s.equals(set)) {
                reject(ErrorCode.VAR_INCONSISTENT_DATE.code(), trade + " (" + m.where() + ")", "priced over the scenario set " + s
                        + "; the date's is " + set);
                dateRejections.add(ErrorCode.VAR_INCONSISTENT_DATE.code() + " " + trade + " (" + m.where() + "): the scenario set " + s + ", not "
                        + set);
                continue;
            }
            // routed by VALUE_TYPE: scenario PVs to scenario-pv, a P&L vector to pnl-vector (a cancel line carries its vector as null)
            String kind = text(row, "VALUE_TYPE").equalsIgnoreCase(VarInputs.PV) ? "scenario-pv" : "pnl-vector";
            boolean live = !"CANCELLED".equalsIgnoreCase(row.path("TRADE_STATUS").asText("")) && svc.loader().rules().live(row.path("TRADE_STATUS")
                    .asText(null));
            if (!live && book.tradeOrdinal(trade) < 0 && (held == null || held.get(trade) == null || held.get(trade).cancel())) {
                count("unknownCancel", 1);                              // a cancel of a trade the date does not hold
                continue;
            }
            if (live) {
                String bad = badVector(row.get(kind.equals("scenario-pv") ? "SCENARIO_PV" : "PNL"), setLength);
                if (bad == null && kind.equals("scenario-pv") && !(row.path("BASE_PV").isNumber() && Double.isFinite(row.path("BASE_PV").asDouble()))) {
                    bad = ErrorCode.VAR_ROW_REJECTED.code() + " no BASE_PV";
                }
                if (bad != null) {
                    String code = bad.substring(0, 8);
                    reject(code, trade + " (" + m.where() + ")", bad.substring(9) + "; not applied (never filled with zero)");
                    dateRejections.add(code + " " + trade + " (" + m.where() + "): " + bad.substring(9));
                    continue;
                }
            }
            String at = row.hasNonNull("COMPUTED_AT") ? row.get("COMPUTED_AT").asText() : m.computedAt();
            if (morningAt != null && VarIntradayRows.compare(at, morningAt) < 0) {
                count("beforeMorning", 1);
                continue;
            }
            VarIntradayRows.Row h = held == null ? null : held.get(trade);
            if (h != null && VarIntradayRows.compare(at, h.computedAt()) <= 0) {
                count("notNewer", 1);                                   // a redelivery, or the topic replayed after a restart
                continue;
            }
            row.put("COMPUTED_AT", at);
            row.put("RUN_KIND", VarIntake.INTRADAY);
            row.put("BATCH_ID", batch);
            row.put("BUSINESS_DATE", day.toString());
            // the record's id: SPV-<trade>-<set>-<batch>, or (2.9.5) PNL-<trade>-<set>-<batch> for a P&L vector (PV- before)
            row.put(kind.equals("scenario-pv") ? "SCENARIO_PV_ID" : "VECTOR_ID", (kind.equals("scenario-pv") ? "SPV-" : VarLoader.PNL_ID) + trade + "-"
                    + set + "-" + batch);
            if (kind.equals("scenario-pv")) {
                row.put("VALUATION_DATE", row.path("VALUATION_DATE").asText(day.toString()));
                row.put("BASE_VALUATION_DATE", row.path("BASE_VALUATION_DATE").asText(day.toString()));
            }
            rows.computeIfAbsent(kind, k -> new ArrayList<>()).add(row);
        }
        if (!dateRejections.isEmpty()) {
            svc.intradayRejected(day, dateRejections);
        }
        long n = rows.values().stream().mapToLong(List::size).sum();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("batchId", batch);
        w.put("businessDate", day.toString());
        w.put("why", why);
        w.put("messages", msgs.size());
        w.put("rows", n);
        w.put("at", Instant.now().toString());
        if (n > 0) {
            String written = null;
            for (Map.Entry<String, List<ObjectNode>> e : rows.entrySet()) {
                try {
                    write(e.getKey(), day, e.getValue());
                    written = e.getKey();
                } catch (IOException | RuntimeException ex) {
                    w.put("error", e.getKey() + ": " + ex.getMessage());
                    reject(ErrorCode.SOURCE_FAILED.code(), batch, "the window could not be written to the lake: " + ex.getMessage());
                }
            }
            if (written != null) {
                // one signal for the window: the intraday layer reads both kinds' new batches at once (a second signal of the same batch
                // would read as a full run)
                announcer.landed(written, day, batch, n);
                count("applied", n);
                count("batches", 1);
            }
        }
        windows.add(w);
        while (windows.size() > 10) {
            windows.remove(0);
        }
        out.putAll(w);
        out.put("status", n > 0 ? "applied" : "nothing new");
        return out;
    }

    private static String text(JsonNode row, String field) {
        JsonNode x = row.get(field);
        return x == null || x.isNull() ? null : x.asText();
    }

    /** Why a vector is not the set's PVs: its length, a null (a failed scenario) or a value not finite; null when it is fine. */
    static String badVector(JsonNode v, int width) {
        if (v == null || !v.isArray()) {
            return ErrorCode.VAR_ROW_REJECTED.code() + " no vector of PVs";
        }
        if (v.size() != width) {
            return ErrorCode.VAR_ROW_REJECTED.code() + " " + v.size() + " values; the scenario set has " + width;
        }
        for (int j = 0; j < width; j++) {
            JsonNode x = v.get(j);
            if (x == null || x.isNull() || !x.isNumber() || !Double.isFinite(x.asDouble())) {
                return ErrorCode.VAR_SCENARIO_FAILED.code() + " scenario " + j + " is " + (x == null || x.isNull() ? "null (failed to price)" : x);
            }
        }
        return null;
    }

    /** Appends one kind's rows to its table as one commit (the same writer and layout as {@code lake build}). */
    private void write(String kind, LocalDate day, List<ObjectNode> rows) throws IOException {
        String vector = kind.equals("scenario-pv") ? "SCENARIO_PV" : "PNL";
        String key = kind.equals("scenario-pv") ? "SCENARIO_PV_ID" : "VECTOR_ID";
        List<String> promoted = columns.apply(kind, "columns");
        Path staged = TempFiles.createFile("var-pv-", ".rows");
        try {
            Map<String, String> types = new LinkedHashMap<>();
            try (BufferedWriter out = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
                for (ObjectNode r : rows) {
                    Map<String, Object> cols = new LinkedHashMap<>();
                    for (String c : promoted) {
                        JsonNode x = r.get(c);
                        cols.put(c, x == null || x.isNull() ? null : x.isNumber() ? x.asDouble() : x.isContainerNode() ? JSON.writeValueAsString(x)
                                : x.asText());                         // an array (TRADE_IDS) as JSON text, as lake build keeps it
                        if (x != null && !x.isNull()) {
                            types.merge(c, x.isNumber() ? "double" : "string", (a, b) -> a.equals(b) ? a : "string");
                        }
                    }
                    cols.put(vector, r.get(vector) == null || r.get(vector).isNull() ? null : JSON.convertValue(r.get(vector), double[].class));
                    ObjectNode doc = r.deepCopy();
                    doc.set(vector, JSON.createObjectNode().put("$vector", vector));
                    out.write(JSON.writeValueAsString(List.of(r.get(key).asText(), JSON.writeValueAsString(doc), cols)));
                    out.newLine();
                }
            }
            List<LakeManifest.Column> cs = new ArrayList<>(List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"),
                    new LakeManifest.Column("business_date", "date")));
            for (String c : promoted) {
                cs.add(new LakeManifest.Column(c, types.getOrDefault(c, "string")));
            }
            cs.add(new LakeManifest.Column(vector, "vector"));
            LakeManifest m = new LakeManifest(lake.resolve("risk").resolve(kind).toString(), List.of("business_date"), cs, promoted, "append", 10_000,
                    List.of("id", "business_date"), List.of(new LakeManifest.Part(staged, rows.size(), Map.of("business_date", day.toString()))), Map.of());
            new LakeWriter().write(m, x -> { });
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
