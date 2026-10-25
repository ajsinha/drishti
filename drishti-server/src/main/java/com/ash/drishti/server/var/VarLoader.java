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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnBatch;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.engine.source.SourceRouter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads one business date of the VaR engine's data through the connectors the kinds are routed to (the market-risk pack's
 * {@code risk-store} in the demo; the bank's lake in production) and builds the {@link VarBook}: the trades' vectors streamed from the
 * vector column straight into the store (no document is parsed for them), the positions, the hierarchies with their limits, the
 * scenario set, the nodes' official history and their stored vectors, and the portfolios' definitions. Reads as the server; what a
 * viewer may see is applied per request.
 */
final class VarLoader {

    private final SourceRouter router;
    private final VarProperties props;
    private final ParallelSums sums;

    VarLoader(SourceRouter router, VarProperties props, ParallelSums sums) {
        this.router = router;
        this.props = props;
        this.sums = sums;
    }

    /** The newest business date that has vectors, or null. */
    LocalDate latest() {
        return router.latestDate(props.kinds().vector()).orElse(null);
    }

    VarDate load(LocalDate date) {
        long t0 = System.nanoTime();
        VarProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        Map<String, DataNode> sets = documents(k.scenarioSet(), asOf);
        DataNode set = sets.values().stream().findFirst().orElse(null);
        if (set == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no scenario set for " + date + " (kind " + k.scenarioSet() + ")");
        }
        List<LocalDate> scenarioDates = new ArrayList<>();
        DataNode sd = set.get("SCENARIO_DATES");
        for (int i = 0; i < sd.size(); i++) {
            scenarioDates.add(LocalDate.parse(sd.get(i).asText()));
        }
        String setId = text(set, "SCENARIO_SET_ID");
        VarBook.Builder b = new VarBook.Builder(date, setId, scenarioDates, props.rowsPerChunk(), props.memoryBudgetMb() * 1024 * 1024);
        List<String> texts = new ArrayList<>(List.of("TRADE_ID"));
        texts.addAll(VarBook.TRADE_TEXTS);
        texts.addAll(VarBook.TRADE_NUMBERS);
        int nt = VarBook.TRADE_TEXTS.size();
        String[] tt = new String[nt];
        double[] tn = new double[VarBook.TRADE_NUMBERS.size()];
        boolean read;
        try {
            read = router.vectors(k.vector(), "PNL", texts, asOf, (id, values, len, row) -> {
                System.arraycopy(row, 1, tt, 0, nt);
                for (int i = 0; i < tn.length; i++) {
                    tn[i] = number(row[1 + nt + i]);
                }
                b.trade(row[0] == null ? id : row[0], values, len, tt, tn);
            });
        } catch (com.ash.drishti.compute.vector.VectorStore.BudgetExceeded e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the P&L vectors of " + date + " could not be read: " + e.getMessage());
        }
        if (!read) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "no connector keeps " + k.vector() + ".PNL as a vector column "
                    + "(the pack's layout." + k.vector() + ".vectors)");
        }
        long tVectors = System.nanoTime();
        for (DataNode p : documents(k.position(), asOf).values()) {
            String[] pt = new String[VarBook.POSITION_TEXTS.size()];
            for (int i = 0; i < pt.length; i++) {
                String f = VarBook.POSITION_TEXTS.get(i);
                pt[i] = f.equals("TAGS") ? tags(p.get("TAGS")) : text(p, f);
            }
            double[] pn = new double[VarBook.POSITION_NUMBERS.size()];
            for (int i = 0; i < pn.length; i++) {
                pn[i] = number(text(p, VarBook.POSITION_NUMBERS.get(i)));
            }
            b.position(text(p, "POSITION_ID"), pt, pn);
        }
        for (DataNode n : documents(k.hierarchy(), asOf).values()) {
            List<String> books = new ArrayList<>();
            DataNode bs = n.get("BOOKS");
            for (int i = 0; i < bs.size(); i++) {
                books.add(bs.get(i).asText());
            }
            b.node(text(n, "NODE_ID"), text(n, "HIERARCHY"), text(n, "LEVEL"), text(n, "NAME"), text(n, "PARENT_ID"), books,
                    number(text(n, "LIMIT_VAR")));
        }
        history(b, date);
        try {
            router.vectors(k.node(), "PNL", List.of("NODE_ID"), asOf, (id, values, len, row) -> {
                if (len > 0 && row[0] != null) {
                    b.officialVector(row[0], java.util.Arrays.copyOf(values, len));
                }
            });
        } catch (Exception e) {
            // the nodes' stored vectors are a cross-check, not needed for a figure
        }
        Map<String, VarDate.Portfolio> portfolios = new LinkedHashMap<>();
        documents(k.portfolio(), asOf).forEach((id, p) -> portfolios.put(id, VarDate.Portfolio.of(id, p)));
        VarBook book = b.build(sums);
        String version = router.dataVersion(k.vector(), asOf).orElse("");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new VarDate(date, book, portfolios, version, java.time.Instant.now(), ms, (tVectors - t0) / 1_000_000);
    }

    /** The nodes' official VaR, ES and hypothetical P&amp;L over the history window ending on the date (var-node, read as columns). */
    private void history(VarBook.Builder b, LocalDate date) {
        LocalDate from = date.minusDays((long) Math.ceil(props.historyDays() * 7.0 / 5.0) + 14);
        Map<String, TreeMap<LocalDate, double[]>> rows = new HashMap<>();
        List<String> paths = List.of("NODE_ID", "VAR_99", "ES_975", "HYPO_PNL", "LIMIT");
        try {
            var scan = router.scan(new ScanRequest(props.kinds().node(), paths, from, date));
            if (scan.isEmpty()) {
                return;
            }
            try (var s = scan.get()) {
                ColumnBatch cb;
                while ((cb = s.next()) != null) {
                    for (int r = 0; r < cb.rows(); r++) {
                        Object node = cb.value(0, r);
                        if (node == null) {
                            continue;
                        }
                        double[] v = {num(cb.value(1, r)), num(cb.value(2, r)), num(cb.value(3, r)), num(cb.value(4, r))};
                        rows.computeIfAbsent(node.toString(), x -> new TreeMap<>()).put(cb.businessDate(), v);
                    }
                }
            }
        } catch (Exception e) {
            return;                                                 // no history: the screens say so
        }
        rows.forEach((node, byDate) -> {
            List<LocalDate> ds = new ArrayList<>(byDate.keySet());
            int start = Math.max(0, ds.size() - props.historyDays());
            int n = ds.size() - start;
            LocalDate[] dates = new LocalDate[n];
            double[] var = new double[n];
            double[] es = new double[n];
            double[] hypo = new double[n];
            double[] limit = new double[n];
            for (int i = 0; i < n; i++) {
                LocalDate d = ds.get(start + i);
                double[] v = byDate.get(d);
                dates[i] = d;
                var[i] = v[0];
                es[i] = v[1];
                hypo[i] = v[2];
                limit[i] = v[3];
            }
            b.history(node, new VarBook.NodeHistory(dates, var, es, hypo, limit));
        });
    }

    private Map<String, DataNode> documents(String kind, AsOf asOf) {
        Map<String, DataNode> out = new LinkedHashMap<>();
        boolean whole = router.documents(kind, asOf, (EntityDocument d) -> out.put(d.ref().id(), d.data()));
        if (!whole) {
            var listing = router.listAcross(kind, 100_000, java.time.Duration.ofSeconds(30), asOf, new com.ash.drishti.engine.source.SourceFailures());
            var refs = listing.hits().stream().map(h -> h.ref()).toList();
            router.fetchAll(refs, java.time.Duration.ofSeconds(30), asOf).forEach((r, d) -> out.put(r.id(), d.data()));
        }
        return out;
    }

    static String text(DataNode n, String field) {
        DataNode v = n.get(field);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    private static String tags(DataNode list) {
        if (list == null || list.isMissing() || list.isNull()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.get(i).asText());
        }
        return String.join("|", out);
    }

    static double number(String s) {
        if (s == null || s.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : o == null ? Double.NaN : number(o.toString());
    }
}
