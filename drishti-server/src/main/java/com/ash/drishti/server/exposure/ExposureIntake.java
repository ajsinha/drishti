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
package com.ash.drishti.server.exposure;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureGrid;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * One date's exposure-path rows on their way into the {@link ExposureBook} (EXPOSURE_GUIDE.md section 21), row by row as the connector streams
 * them: a row of another run is left out (counted: several runs may share a date, the newest complete one answers); a vector of another
 * length, with NaN or infinity, or a grid index outside the run's grid is rejected ({@code DRS-5444}: counted and listed, never padded);
 * a failed row ({@code STATUS: failed}, no vector) keeps its trade in its set and counts; every other row is added into its netting set's
 * value matrix. Membership wins: a row whose {@code NETTING_SET} is not the set the netting-set records list the trade in goes to the
 * listed set and is counted. When the stream ends: the sets with a member trade missing a row before its maturity are {@code incomplete},
 * the trades whose mean at the first grid date departs from their MtM today are listed (the t0 check), and every netting set the
 * reference data knows that has no paths at all is counted.
 */
final class ExposureIntake {

    private static final int LISTED = 20;
    private final ExposureBook.Builder book;
    private final ExposureGrid grid;
    private final LocalDate date;
    private final String runId;
    private final String batchId;
    private final Map<String, String> memberOf;
    private final double t0Tolerance;
    private final List<String> rejections = new ArrayList<>();
    private final TreeSet<String> batches = new TreeSet<>();
    private final Map<String, String> setOf = new HashMap<>();
    private final Map<String, java.util.BitSet> indices = new HashMap<>();
    private final Map<String, String> maturity = new HashMap<>();
    private final Map<String, Double> notional = new HashMap<>();
    private final Map<String, Double> firstMean = new HashMap<>();
    private int rows;
    private int rejected;
    private int otherRuns;
    private int failed;
    private int proxied;
    private int moved;

    ExposureIntake(ExposureBook.Builder book, ExposureGrid grid, LocalDate date, String runId, String batchId, Map<String, String> memberOf,
            double t0Tolerance) {
        this.book = book;
        this.grid = grid;
        this.date = date;
        this.runId = runId;
        this.batchId = batchId;
        this.memberOf = memberOf;
        this.t0Tolerance = t0Tolerance;
    }

    private static String col(String[] row, List<String> columns, String name) {
        int i = columns.indexOf(name);
        String v = i < 0 || i >= row.length ? null : row[i];
        return v == null || v.isEmpty() ? null : v;
    }

    /** One row of the stream: {@code row[0]} the trade id (null: the lake key {@code id}), then the columns asked. */
    void row(String id, double[] values, int length, String[] row, List<String> columns) {
        String trade = row[0] == null ? id : row[0];
        String run = col(row, columns, "RUN_ID");
        if (run != null && !run.equals(runId)) {
            otherRuns++;
            return;
        }
        String batch = col(row, columns, "BATCH_ID");
        if (batch != null) {
            batches.add(batch);
        }
        rows++;
        String set = col(row, columns, "NETTING_SET");
        String listed = memberOf.get(trade);
        if (listed != null && !listed.equals(set)) {
            moved++;
            set = listed;
        }
        String[] cols = {col(row, columns, "COUNTERPARTY"), col(row, columns, "LEGAL_ENTITY"), col(row, columns, "DESK"), col(row, columns, "TT_BOOK"),
            col(row, columns, "ASSET_CLASS"), col(row, columns, "PRODUCT"), col(row, columns, "SOURCE_SYSTEM")};
        String mat = col(row, columns, "MATURITY_DATE");
        double n = number(col(row, columns, "NOTIONAL"));
        String status = col(row, columns, "STATUS");
        if ("proxied".equalsIgnoreCase(status)) {
            proxied++;
        }
        if (length < 0 || "failed".equalsIgnoreCase(status)) {
            failed++;
            book.failed(trade, set, cols, mat, n);
            return;
        }
        int k = (int) number(col(row, columns, "GRID_INDEX"));
        String why = check(values, length, k, col(row, columns, "GRID_DATE"));
        if (why != null) {
            rejected++;
            if (rejections.size() < LISTED) {
                rejections.add(ErrorCode.EXPOSURE_ROW_REJECTED.code() + " " + trade + " #" + k + ": " + why);
            }
            return;
        }
        book.row(trade, set, cols, mat, n, k, values, length);
        setOf.put(trade, set == null || set.isBlank() ? "TRD~" + trade : set);
        indices.computeIfAbsent(trade, x -> new java.util.BitSet()).set(k);
        if (mat != null) {
            maturity.put(trade, mat);
        }
        notional.put(trade, Double.isNaN(n) ? 0 : n);
        if (k == 0) {
            double s = 0;
            for (int j = 0; j < length; j++) {
                s += values[j];
            }
            firstMean.put(trade, s / length);
        }
    }

    private String check(double[] values, int length, int k, String gridDate) {
        if (length != book.paths()) {
            return "the vector has " + length + " values; the run has " + book.paths() + " paths";
        }
        if (k < 0 || k >= grid.size()) {
            return "GRID_INDEX " + k + " is outside the run's grid of " + grid.size() + " dates";
        }
        if (gridDate != null && !gridDate.startsWith(grid.date(k).toString())) {
            return "GRID_DATE " + gridDate + " is not the run's grid date " + grid.date(k);
        }
        for (int j = 0; j < length; j++) {
            if (!Double.isFinite(values[j])) {
                return "the vector holds " + (Double.isNaN(values[j]) ? "a null or NaN" : "an infinite") + " value at path " + j;
            }
        }
        return null;
    }

    private static double number(String s) {
        if (s == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** When the stream has ended: the checks of the whole date, the sets' flags (into the reference), and what the provenance says. */
    Map<String, Object> summary(ExposureLoader.Reference ref) {
        if (batches.size() > 1) {
            throw ExposureLoader.inconsistent(date, "the run's rows come from " + batches.size() + " batches (" + String.join(", ", batches) + ")");
        }
        if (batchId != null && !batches.isEmpty() && !batches.first().equals(batchId)) {
            throw ExposureLoader.inconsistent(date, "the rows' BATCH_ID " + batches.first() + " is not the run's " + batchId);
        }
        List<String> incomplete = new ArrayList<>();
        Map<String, Integer> missingBySet = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : ref.liveMembers.entrySet()) {
            boolean any = e.getValue().stream().anyMatch(setOf::containsKey);
            if (!any) {
                continue;                                               // a set without paths at all: not in this run
            }
            for (String t : e.getValue()) {
                if (!setOf.containsKey(t) || gap(t)) {
                    missingBySet.merge(e.getKey(), 1, Integer::sum);
                    if (incomplete.size() < LISTED) {
                        incomplete.add(t + " (" + e.getKey() + ")");
                    }
                }
            }
        }
        missingBySet.keySet().forEach(s -> ref.flags.computeIfAbsent(s, x -> new ArrayList<>()).add("incomplete"));
        List<String> far = new ArrayList<>();
        int farCount = 0;
        for (Map.Entry<String, Double> e : firstMean.entrySet()) {
            double m0 = ref.mtm0(e.getKey());
            if (Double.isNaN(m0)) {
                continue;
            }
            double tol = Math.max(t0Tolerance * Math.abs(m0), 0.05 * Math.abs(notional.getOrDefault(e.getKey(), 0.0)));
            if (Math.abs(e.getValue() - m0) > tol) {
                farCount++;
                if (far.size() < LISTED) {
                    far.add(e.getKey() + ": paths' mean " + Math.round(e.getValue()) + " at " + grid.date(0) + ", MtM today " + Math.round(m0));
                }
            }
        }
        List<String> withoutPaths = ref.known.keySet().stream().filter(s -> ref.liveMembers.getOrDefault(s, List.of()).stream()
                .noneMatch(setOf::containsKey)).sorted().toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("trades", book.trades());
        m.put("sets", book.sets());
        m.put("rejected", rejected);
        m.put("rejections", rejections);
        m.put("otherRuns", otherRuns);
        m.put("failed", failed);
        m.put("proxied", proxied);
        m.put("membershipCorrected", moved);
        m.put("incompleteTrades", missingBySet.values().stream().mapToInt(Integer::intValue).sum());
        m.put("incomplete", incomplete);
        m.put("t0Departures", farCount);
        m.put("t0", far);
        m.put("setsWithoutPaths", withoutPaths.size());
        m.put("setsWithoutPathsListed", withoutPaths.subList(0, Math.min(LISTED, withoutPaths.size())));
        m.put("batchIds", List.copyOf(batches));
        return m;
    }

    /** A live trade missing a row before its maturity plus 10 business days (14 calendar days, the grid having no calendar). */
    private boolean gap(String trade) {
        java.util.BitSet have = indices.get(trade);
        LocalDate until = null;
        String m = maturity.get(trade);
        if (m != null && m.length() >= 10) {
            try {
                until = LocalDate.parse(m.substring(0, 10)).plusDays(14);
            } catch (java.time.format.DateTimeParseException e) {
                until = null;
            }
        }
        for (int k = 0; k < grid.size(); k++) {
            if (until != null && grid.date(k).isAfter(until)) {
                break;
            }
            if (until == null && k > have.length() - 1) {
                break;
            }
            if (!have.get(k)) {
                return true;
            }
        }
        return false;
    }
}
