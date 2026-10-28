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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The official end-of-day exposure captures (EXPOSURE_GUIDE.md section 20): when a date's run is complete, the server captures every netting set,
 * counterparty and connected group, and every node of the MGMT and LEGAL hierarchies (a node's figures over its trades: the netting
 * sets it covers whole, the sets it cuts recomputed from its trades), at the configured confidences under the default collateral
 * model; every trade's EE contribution per primary date beside it. The records are kept in the content store and published to
 * {@code risk-results/exposure-official} (nodes) and {@code exposure-official-cpty} (sets, counterparties, groups) for other systems. A
 * node's PFE sums the paths of what it faces, never the PFEs of its parts.
 *
 * <p>Idempotent: a date already captured from the same inputs is not captured again; a restated date (an input's version or the run
 * changed) is captured anew with the next result version. One capture at a time (a lock, never {@code synchronized}).
 */
final class ExposureOfficial implements AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExposureOfficial.class);
    static final java.time.Duration START_WAIT = java.time.Duration.ofMinutes(10);
    private static final long START_RETRY_MS = 5_000;
    private final ExposureService svc;
    private final ExposurePublisher publisher;
    private final ExecutorService runner = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drishti-exposure-official");
        t.setDaemon(true);
        return t;
    });
    private final ReentrantLock lock = new ReentrantLock();

    ExposureOfficial(ExposureService svc, ExposurePublisher publisher) {
        this.svc = svc;
        this.publisher = publisher;
    }

    /** Captures a date in the background (the newest at start), trying again every few seconds while the connectors come up. */
    void later(java.util.function.Supplier<LocalDate> date) {
        runner.execute(() -> {
            long until = System.nanoTime() + START_WAIT.toNanos();
            String last = "no business date with exposure paths yet";
            while (System.nanoTime() < until && !Thread.currentThread().isInterrupted()) {
                try {
                    LocalDate d = date.get();
                    if (d != null) {
                        if (svc.official().index(d).isEmpty()) {
                            LOG.info("official exposure capture of {}: {}", d, capture(d));
                        }
                        return;
                    }
                } catch (RuntimeException e) {
                    last = e.getMessage();
                }
                try {
                    Thread.sleep(START_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LOG.warn("the official exposure capture at start did not run: {}", last);
        });
    }

    @Override
    public void close() {
        runner.shutdownNow();
    }

    /** The data-load signal of an input on a date (product owner, 2026-10-25): the date reloads, then is captured in the background. */
    void onLoad(String kind, LocalDate date) {
        ExposureProperties p = svc.props();
        if (!p.enabled() || date == null || !p.kinds().inputs().contains(kind)) {
            return;
        }
        svc.reload(date);
        svc.audit(ExposureOfficialStore.OWNER, "ccr-reload", date.toString(), "the data-load signal of " + kind);
        if (p.official().enabled()) {
            runner.execute(() -> {
                try {
                    capture(date);
                } catch (RuntimeException e) {
                    LOG.warn("the official exposure capture of {} failed: {}", date, e.getMessage());
                }
            });
        }
    }

    Map<String, Object> capture(LocalDate date) {
        lock.lock();
        try {
            return captureNow(date);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> captureNow(LocalDate date) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", date.toString());
        ExposureDate d;
        try {
            d = svc.date(date);
        } catch (DrishtiException e) {
            out.put("status", "skipped");
            out.put("reason", e.getMessage());
            return out;
        }
        if (!d.date().equals(date) || !d.warnings().isEmpty()) {
            out.put("status", "skipped");
            out.put("reason", d.warnings().isEmpty() ? "no exposure paths of " + date : String.join("; ", d.warnings()));
            return out;
        }
        ExposureBook b = d.book();
        if (b.sets().isEmpty()) {
            out.put("status", "skipped");
            out.put("reason", "incomplete: no netting set has paths");
            return out;
        }
        ExposureOfficialStore store = svc.official();
        Map<String, Object> inputs = d.inputs();
        Map<String, Object> prev = store.index(date).orElse(null);
        if (prev != null && inputs.equals(prev.get("inputs"))) {
            out.put("status", "unchanged");
            out.put("resultVersion", prev.get("resultVersion"));
            return out;
        }
        long version = prev == null ? 1 : ((Number) prev.get("resultVersion")).longValue() + 1;
        String computedAt = Instant.now().toString();
        ExposureService.Ask ask = new ExposureService.Ask(svc.props().limitConfidence(), svc.props().collateralModel(), "", false);
        Map<String, Object> scopes = new LinkedHashMap<>();
        List<Map<String, Object>> cptyRows = new ArrayList<>();
        Set<String> cps = new LinkedHashSet<>();
        for (ExposureBook.SetInfo s : b.sets()) {
            cps.add(s.counterparty());
            row(d, "NSET", "NSET:" + s.id(), s.id(), s.counterparty(), "netting-set", s.counterparty(), s.legalEntity(), ask, version, computedAt, inputs,
                    scopes, cptyRows);
        }
        Map<String, String> groupOf = new LinkedHashMap<>();
        d.groups().forEach((g, ms) -> ms.forEach(c -> groupOf.putIfAbsent(c, g)));
        Set<String> groups = new LinkedHashSet<>();
        for (String c : cps) {
            if (c == null) {
                continue;
            }
            row(d, "CPTY", "CPTY:" + c, d.names().getOrDefault(c, c), groupOf.get(c), "counterparty", c, null, ask, version, computedAt, inputs, scopes,
                    cptyRows);
            if (groupOf.get(c) != null) {
                groups.add(groupOf.get(c));
            }
        }
        for (String g : groups) {
            row(d, "GROUP", "GROUP:" + g, g, null, "group", null, null, ask, version, computedAt, inputs, scopes, cptyRows);
        }
        List<Map<String, Object>> nodeRows = new ArrayList<>();
        for (Node n : nodes(d)) {
            if (n.scope() == null) {
                continue;
            }
            try {
                Map<String, Object> f = figures(d, n.scope(), ask);
                scopes.put(n.hierarchy() + ":" + n.id(), f);
                nodeRows.add(published(d, n.hierarchy(), n.id(), n.name(), n.parent(), n.level(), null, null, f, version, computedAt, inputs));
            } catch (DrishtiException e) {
                // a node with no trade of the run: not published
            }
        }
        Map<String, Object> trades = tradeContributions(d, ask);
        store.putTrades(date, version, trades);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("businessDate", date.toString());
        index.put("resultVersion", version);
        index.put("computedAt", computedAt);
        index.put("contractVersion", ExposurePublisher.CONTRACT_VERSION);
        index.put("inputs", inputs);
        index.put("runId", b.runId());
        index.put("confidence", ask.q());
        index.put("collateralModel", ask.collateral());
        index.put("trades", ExposureOfficialStore.tradesKey(date, version));
        index.put("scopes", scopes);
        if (svc.props().official().publish() && publisher.where() != null) {
            try {
                long vc = publisher.publish(ExposurePublisher.CPTY, date, cptyRows);
                long vn = nodeRows.isEmpty() ? -1 : publisher.publish(ExposurePublisher.NODES, date, nodeRows);
                index.put("published", "risk-results/" + ExposurePublisher.CPTY + " (" + publisher.describe(vc) + ")"
                        + (vn < 0 ? "" : ", risk-results/" + ExposurePublisher.NODES + " (" + publisher.describe(vn) + ")"));
            } catch (java.io.IOException | RuntimeException e) {
                index.put("publishError", publisher.format() + " tables under " + publisher.where() + ": " + e.getMessage());
                LOG.warn("the official exposure results of {} could not be published to the {} tables under {}: {}", date, publisher.format(),
                        publisher.where(), e.getMessage());
            }
        }
        store.putIndex(date, index);
        int pruned = store.prune(svc.props().retention());
        svc.audit(ExposureOfficialStore.OWNER, "ccr-official", date.toString(), "resultVersion=" + version + " scopes=" + scopes.size() + " sets="
                + b.sets().size() + " inputs=" + inputs + " pruned=" + pruned);
        out.put("status", version == 1 ? "captured" : "restated");
        out.put("resultVersion", version);
        out.put("scopes", scopes.size());
        out.put("nettingSets", b.sets().size());
        out.put("nodes", nodeRows.size());
        out.put("published", index.containsKey("published"));
        if (index.containsKey("publishError")) {
            out.put("publishError", index.get("publishError"));
        }
        return out;
    }

    private void row(ExposureDate d, String hierarchy, String scope, String name, String parent, String level, String cpty, String le,
            ExposureService.Ask ask, long version, String computedAt, Map<String, Object> inputs, Map<String, Object> scopes,
            List<Map<String, Object>> rows) {
        Map<String, Object> f = figures(d, scope, ask);
        scopes.put(scope, f);
        rows.add(published(d, hierarchy, scope, name, parent, level, cpty, le, f, version, computedAt, inputs));
    }

    /** A scope's official figures (as the server: everything, no interactive limit). */
    private Map<String, Object> figures(ExposureDate d, String scope, ExposureService.Ask ask) {
        ExposureService.Scoped s = svc.scoped(null, scope, d.date(), ask);
        ExposureMath.Stats st = s.result().stats;
        ExposureGrid g = d.book().grid();
        int q95 = st.at(0.95);
        int q99 = st.at(0.99);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ce", Math.round(st.ce));
        f.put("epe", Math.round(st.epe));
        f.put("eepe", Math.round(st.eepe));
        f.put("peakEe", Math.round(st.peakEe));
        f.put("peakPfe95", q95 < 0 ? null : Math.round(st.peakPfe[q95]));
        f.put("peakPfe99", q99 < 0 ? null : Math.round(st.peakPfe[q99]));
        int qa = st.at(ask.q());
        f.put("peakPfeDate", st.peakAt[qa] < 0 ? null : g.date(g.primaryAt(st.peakAt[qa])).toString());
        f.put("eadImm", Math.round(svc.props().alpha() * st.eepe));
        f.put("pfe95At1Y", at(g, st, q95, "1Y", true));
        f.put("pfe95At5Y", at(g, st, q95, "5Y", true));
        f.put("eeAt1Y", at(g, st, q95, "1Y", false));
        f.put("eeAt5Y", at(g, st, q95, "5Y", false));
        List<Map<String, Object>> prof = new ArrayList<>();
        for (int i = 0; i < g.primaryCount(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("TENOR", g.tenor(g.primaryAt(i)));
            r.put("DATE", g.date(g.primaryAt(i)).toString());
            r.put("EE", Math.round(st.ee[i]));
            r.put("ENE", Math.round(st.ene[i]));
            r.put("EEE", Math.round(st.eee[i]));
            r.put("PFE_95", q95 < 0 ? null : Math.round(st.pfe[q95][i]));
            r.put("PFE_99", q99 < 0 ? null : Math.round(st.pfe[q99][i]));
            prof.add(r);
        }
        f.put("profile", prof);
        f.put("nettingSets", s.result().pieces.size());
        f.put("partialSets", s.result().partialSets());
        f.put("trades", s.result().trades.size());
        f.put("flags", s.result().flags);
        return f;
    }

    private static Long at(ExposureGrid g, ExposureMath.Stats st, int q, String tenor, boolean pfe) {
        for (int i = 0; i < g.primaryCount(); i++) {
            if (tenor.equals(g.tenor(g.primaryAt(i)))) {
                return Math.round(pfe ? (q < 0 ? Double.NaN : st.pfe[q][i]) : st.ee[i]);
            }
        }
        return null;
    }

    private Map<String, Object> published(ExposureDate d, String hierarchy, String id, String name, String parent, String level, String cpty,
            String le, Map<String, Object> f, long version, String computedAt, Map<String, Object> inputs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("HIERARCHY", hierarchy);
        r.put("NODE_ID", id);
        r.put("NODE_NAME", name);
        r.put("PARENT_ID", parent);
        r.put("LEVEL", level);
        r.put("COUNTERPARTY", cpty);
        r.put("LEGAL_ENTITY", le);
        r.put("RESULT_VERSION", version);
        r.put("INPUT_VERSIONS", ExposureLoader.json(inputs));
        r.put("COMPUTED_AT", computedAt);
        r.put("CONTRACT_VERSION", ExposurePublisher.CONTRACT_VERSION);
        r.put("RUN_ID", d.book().runId());
        r.put("PATHS", (long) d.book().paths());
        r.put("CALIBRATION", d.book().run().get("CALIBRATION") == null ? null : String.valueOf(d.book().run().get("CALIBRATION")));
        r.put("CE", dbl(f.get("ce")));
        r.put("EPE", dbl(f.get("epe")));
        r.put("EEPE", dbl(f.get("eepe")));
        r.put("PEAK_EE", dbl(f.get("peakEe")));
        r.put("PEAK_PFE_95", dbl(f.get("peakPfe95")));
        r.put("PEAK_PFE_99", dbl(f.get("peakPfe99")));
        r.put("EAD_IMM", dbl(f.get("eadImm")));
        r.put("PEAK_PFE_DATE", f.get("peakPfeDate"));
        r.put("PFE_95_1Y", dbl(f.get("pfe95At1Y")));
        r.put("PFE_95_5Y", dbl(f.get("pfe95At5Y")));
        r.put("EE_1Y", dbl(f.get("eeAt1Y")));
        r.put("EE_5Y", dbl(f.get("eeAt5Y")));
        r.put("PROFILE", ExposureLoader.json(f.get("profile")));
        r.put("NETTING_SETS", ((Number) f.get("nettingSets")).longValue());
        r.put("TRADES", ((Number) f.get("trades")).longValue());
        r.put("FAILED_TRADES", ((Number) d.intake().getOrDefault("failed", 0)).longValue());
        r.put("PARTIAL_SETS", ((Number) f.get("partialSets")).longValue());
        return r;
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    /** Every trade's EE contribution at each primary date, from its netting set's exposure (the audit of who drove the exposure). */
    private Map<String, Object> tradeContributions(ExposureDate d, ExposureService.Ask ask) {
        ExposureBook b = d.book();
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> tenors = new ArrayList<>();
        for (int i = 0; i < b.grid().primaryCount(); i++) {
            tenors.add(b.grid().tenor(b.grid().primaryAt(i)));
        }
        out.put("businessDate", d.date().toString());
        out.put("runId", b.runId());
        out.put("tenors", tenors);
        Map<String, Object> rows = new LinkedHashMap<>();
        for (ExposureBook.SetInfo s : b.sets()) {
            ExposureService.Scoped sc = svc.scoped(null, "NSET:" + s.id(), d.date(), ask);
            for (ExposureCalc.Part pt : ExposureCalc.parts(sc.result(), "trade", svc.paths(d))) {
                List<Long> ee = new ArrayList<>();
                for (int i = 0; i < b.grid().primaryCount(); i++) {
                    double sum = 0;
                    for (int j = 0; j < b.paths(); j++) {
                        sum += pt.x()[i * b.paths() + j];
                    }
                    ee.add(Math.round(sum / b.paths()));
                }
                rows.put(pt.key(), Map.of("nettingSet", s.id(), "ee", ee));
            }
        }
        out.put("trades", rows);
        return out;
    }

    /** A column hierarchy's nodes from the date's trades: the root (the bank), then every distinct path of the level columns' values. */
    static List<Node> columnNodes(ExposureDate d, com.ash.drishti.compute.var.HierarchySpec s) {
        List<Node> out = new ArrayList<>();
        out.add(new Node(s.id(), s.rootNodeId(), s.rootName(), null, com.ash.drishti.compute.var.HierarchySpec.ROOT_LEVEL, "BANK"));
        com.ash.drishti.compute.ccr.ExposureScopes cols = new com.ash.drishti.compute.ccr.ExposureScopes(d.book(), null);
        Set<String> seen = new java.util.HashSet<>();
        Map<String, String> parentOf = new java.util.HashMap<>();
        int k = s.levels().size();
        for (ExposureBook.TradeInfo t : d.book().trades()) {
            List<String> values = new ArrayList<>(k);
            for (String c : s.levels()) {
                Object v = cols.column(t, c);
                values.add(v == null ? "" : String.valueOf(v));
            }
            String parent = s.rootNodeId();
            StringBuilder where = new StringBuilder("WHERE:");
            for (int i = 1; i <= k; i++) {
                String id = s.nodeId(values, i);
                where.append(i == 1 ? "" : " and ").append(s.levels().get(i - 1)).append(" == '").append(values.get(i - 1)).append('\'');
                if (seen.add(id)) {
                    out.add(new Node(s.id(), id, values.get(i - 1), parent, s.levelNames().get(i - 1), where.toString()));
                    parentOf.put(id, parent);
                } else if (s.leafIds() && !parent.equals(parentOf.get(id))) {
                    throw new DrishtiException(com.ash.drishti.common.ErrorCode.VAR_HIERARCHY_INVALID, "the hierarchy " + s.id() + " names its nodes by their own value "
                            + "(ids: leaf), but " + values.get(i - 1) + " sits under both " + parentOf.get(id) + " and " + parent + ": use ids: path");
                }
                parent = id;
            }
        }
        return out;
    }

    /** A hierarchy node and the scope of its trades (its books'). */
    record Node(String hierarchy, String id, String name, String parent, String level, String scope) {}

    /**
     * The hierarchies' nodes VaR builds (2.9.5, {@code Hierarchies}): those of the market-risk pack's risk-hierarchy rows (a node's scope its
     * books, {@code BOOK:a + BOOK:b}), and those of every hierarchy built from columns (a node's scope its path of values,
     * {@code WHERE:LIMIT_LETTER == 'LL-0003' and DESK == 'DESK-RATES'}); with neither, the bank and the legal entities.
     */
    List<Node> nodes(ExposureDate d) {
        List<Node> out = new ArrayList<>();
        com.ash.drishti.server.var.Hierarchies hs = svc.loader().hierarchies();
        Map<String, DataNode> h = new LinkedHashMap<>();
        try {
            svc.loader().documents(svc.props().kinds().hierarchy(), AsOf.of(d.date())).forEach((id, n) -> {
                if (hs.fromRows(String.valueOf(ExposureLoader.text(n, "HIERARCHY")))) {
                    h.put(id, n);
                }
            });
        } catch (RuntimeException e) {
            if (hs.columnSpecs().isEmpty()) {
                throw e;
            }
        }
        Set<String> books = new LinkedHashSet<>();
        d.book().trades().forEach(t -> {
            if (t.book() != null) {
                books.add(t.book());
            }
        });
        for (Map.Entry<String, DataNode> e : h.entrySet()) {
            DataNode n = e.getValue();
            List<String> bs = new ArrayList<>();
            DataNode list = n.get("BOOKS");
            for (int i = 0; i < list.size(); i++) {
                if (books.contains(list.get(i).asText())) {
                    bs.add("BOOK:" + list.get(i).asText());
                }
            }
            String id = e.getKey();
            out.add(new Node(String.valueOf(ExposureLoader.text(n, "HIERARCHY")), id, ExposureLoader.text(n, "NAME"), ExposureLoader.text(n, "PARENT_ID"),
                    ExposureLoader.text(n, "LEVEL"), bs.isEmpty() ? null : String.join(" + ", bs)));
        }
        for (com.ash.drishti.compute.var.HierarchySpec s : hs.columnSpecs()) {
            out.addAll(columnNodes(d, s));
        }
        if (out.isEmpty()) {
            out.add(new Node("MGMT", "MGMT~BANK", "Bank", null, "bank", "BANK"));
            Set<String> les = new LinkedHashSet<>();
            d.book().trades().forEach(t -> {
                if (t.legalEntity() != null) {
                    les.add(t.legalEntity());
                }
            });
            for (String le : les) {
                out.add(new Node("LEGAL", "LEGAL~" + le, le, null, "legal-entity", "LE:" + le));
            }
        }
        return out;
    }
}
