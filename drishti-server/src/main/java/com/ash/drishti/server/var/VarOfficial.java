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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.var.Backtest;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.identity.content.ContentDocs;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The official end-of-day captures (VAR_GUIDE.md section 19): when a date's inputs are complete, every node of every hierarchy (MGMT and
 * LEGAL) is captured by the server as the official record: the node's VaR, ES, components, worst scenarios and vector, its hypothetical
 * P&amp;L and backtest over the official history, the inputs' versions and batch ids, and the membership the position rules derived
 * (recorded once per date). The node history of the explorer reads these records; the same figures are published to
 * {@code risk-results/var-official} for other systems ({@link VarPublisher}).
 *
 * <p>In every reporting currency of {@code drishti.var.official.currencies} (2.9.4): USD always, first, and the only one published; each
 * other currency is captured the same way from the date's book in that currency, kept in the content store under its own records.
 *
 * <p>Idempotent: a date already captured from the same inputs is not captured again; a restated date (an input's version or batch
 * changed) is captured anew with the next result version, the earlier captures kept. One capture runs at a time (a lock, never
 * {@code synchronized}: it reads the lake and writes the content store and the published table).
 */
final class VarOfficial implements AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VarOfficial.class);
    /** The components an official capture keeps (the largest by component VaR); every member is in the date's membership record. */
    private static final int COMPONENTS = 50;
    private final VarService svc;
    private final VarViews views;
    private final VarPublisher publisher;
    /** One capture at a time, off the request and the load threads. */
    private final ExecutorService runner = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drishti-var-official");
        t.setDaemon(true);
        return t;
    });
    private final ReentrantLock lock = new ReentrantLock();

    VarOfficial(VarService svc, VarViews views, VarPublisher publisher) {
        this.svc = svc;
        this.views = views;
        this.publisher = publisher;
    }

    /** How long the capture at start waits for the lake's connectors to come up (they start a moment after the server). */
    static final java.time.Duration START_WAIT = java.time.Duration.ofMinutes(10);
    private static final long START_RETRY_MS = 5_000;

    /**
     * Captures a date in the background (the newest one at start). While no date is readable yet (the connectors are still coming up)
     * or the capture fails, it tries again every few seconds, for {@link #START_WAIT}; then the failure is logged and the next data-load
     * signal captures the date instead.
     */
    void later(java.util.function.Supplier<java.time.LocalDate> date) {
        runner.execute(() -> {
            long until = System.nanoTime() + START_WAIT.toNanos();
            String last = "no business date with P&L vectors yet";
            while (System.nanoTime() < until && !Thread.currentThread().isInterrupted()) {
                try {
                    LocalDate d = date.get();
                    if (d != null) {
                        if (svc.props().official().currencies().stream().anyMatch(c -> svc.official().index(d, c).isEmpty())) {
                            LOG.info("official VaR capture of {}: {}", d, capture(d));
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
            LOG.warn("the official VaR capture at start did not run: {}", last);
        });
    }

    @Override
    public void close() {
        runner.shutdownNow();
    }

    /**
     * The data-load signal of a kind on a date (VAR_GUIDE.md section 23.1 Q5, decided): an input of the engine (vectors, scenario set,
     * hierarchy, portfolios, or the trades positions come from) reloads that date, then the date is captured officially in the
     * background. Other kinds do nothing.
     */
    void onLoad(String kind, LocalDate date) {
        VarProperties p = svc.props();
        if (!p.enabled() || date == null || !(p.kinds().inputs().contains(kind) || "trade".equals(kind))) {
            return;
        }
        if (kind.equals(p.kinds().vector()) || kind.equals(p.kinds().scenarioPv())) {
            // a trades' input on a loaded date: its new intraday batches applied in place, no reload, no official capture (the
            // official records are the morning run's, VAR_GUIDE.md section 8.4); a full run reloads and captures as before
            VarService.IntradayResult r = svc.intraday(date);
            if (!r.status().equals("reload") && !r.status().equals("not loaded")) {
                LOG.info("VaR: the data-load signal of {} on {}: intraday {} ({} updates, batches {})", kind, date, r.status(), r.updates(), r.batches());
                return;
            }
            if (r.status().equals("reload")) {
                svc.audit(VarOfficialStore.OWNER, "var-reload", date.toString(), "the data-load signal of " + kind + ": a full run");
                runCapture(date, p);
                return;
            }
        }
        svc.reload(date);
        svc.audit(VarOfficialStore.OWNER, "var-reload", date.toString(), "the data-load signal of " + kind);
        runCapture(date, p);
    }

    private void runCapture(LocalDate date, VarProperties p) {
        if (p.official().enabled()) {
            runner.execute(() -> {                                     // after the reload: the date is read again from the lake
                try {
                    capture(date);
                } catch (RuntimeException e) {
                    LOG.warn("the official VaR capture of {} failed: {}", date, e.getMessage());
                }
            });
        }
    }

    /** Captures the date officially when its inputs are complete and not yet captured from these inputs; says what it did. */
    Map<String, Object> capture(LocalDate date) {
        lock.lock();
        try {
            return svc.morningOnly(() -> captureNow(date));            // the morning run alone: never the intraday layer
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> captureNow(LocalDate date) {
        Map<String, Object> out = captureIn(date, VarDate.USD);
        List<String> others = svc.props().official().currencies().stream().filter(c -> !c.equals(VarDate.USD)).toList();
        if (!others.isEmpty() && !"skipped".equals(out.get("status"))) {
            Map<String, Object> more = new LinkedHashMap<>();
            for (String c : others) {
                try {
                    more.put(c, captureIn(date, c));
                } catch (RuntimeException e) {
                    more.put(c, Map.of("status", "skipped", "reason", String.valueOf(e.getMessage())));
                    LOG.warn("the official VaR capture of {} in {} failed: {}", date, c, e.getMessage());
                }
            }
            out.put("currencies", more);
        }
        return out;
    }

    /** One currency's official capture of the date (USD: the record of section 19, published; another: kept beside it). */
    private Map<String, Object> captureIn(LocalDate date, String ccy) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", date.toString());
        out.put("currency", ccy);
        VarDate d;
        try {
            d = svc.date(date, ccy);
        } catch (DrishtiException e) {
            out.put("status", "skipped");
            out.put("reason", e.getMessage());
            return out;
        }
        if (!d.date().equals(date) || !d.warnings().isEmpty()) {
            out.put("status", "skipped");
            out.put("reason", d.warnings().isEmpty() ? "no P&L vectors of " + date : String.join("; ", d.warnings()));
            return out;
        }
        VarBook b = d.book();
        if (b.tradeCount() == 0 || b.nodes().isEmpty()) {
            out.put("status", "skipped");
            out.put("reason", "incomplete: " + b.tradeCount() + " trades valued, " + b.nodes().size() + " hierarchy nodes");
            return out;
        }
        VarOfficialStore store = svc.official();
        Map<String, Object> inputs = d.inputs();
        Map<String, Object> prev = store.index(date, ccy).orElse(null);
        if (prev != null && (inputs.equals(prev.get("inputs")) || svc.hasIntraday(date) && sameRun(inputs, prev.get("inputs")))) {
            out.put("status", "unchanged");
            out.put("resultVersion", prev.get("resultVersion"));
            return out;
        }
        long version = prev == null ? 1 : ((Number) prev.get("resultVersion")).longValue() + 1;
        String computedAt = Instant.now().toString();
        String membership = VarOfficialStore.membershipKey(date, version, ccy);
        store.putMembership(date, version, membership(d, version), ccy);
        Map<String, Object> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VarBook.Node n : ordered(b)) {
            Map<String, Object> f = node(d, n, version, membership, computedAt, inputs);
            nodes.put(n.id(), f);
            rows.add(row(d, n, f, version, computedAt, inputs));
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("businessDate", date.toString());
        index.put("currency", ccy);
        index.put("fx", VarViews.fxNote(d));
        index.put("resultVersion", version);
        index.put("computedAt", computedAt);
        index.put("contractVersion", VarPublisher.CONTRACT_VERSION);
        index.put("inputs", inputs);
        index.put("positionRules", d.intake().rules());
        index.put("membership", membership);
        index.put("nodes", nodes);
        if (!VarDate.USD.equals(ccy)) {
            index.put("published", "not published: the published table holds the USD record (the content store keeps " + ccy + ")");
        } else if (svc.props().official().publish() && publisher.table() != null) {
            try {
                long v = publisher.publish(date, rows);
                index.put("published", "risk-results/var-official (Delta version " + v + ")");   // never the server's own folder
            } catch (java.io.IOException | RuntimeException e) {
                index.put("publishError", String.valueOf(e.getMessage()));
                LOG.warn("the official VaR results of {} could not be published to {}: {}", date, publisher.table(), e.getMessage());
            }
        }
        store.putIndex(date, index, ccy);
        svc.audit(VarOfficialStore.OWNER, "var-official", date.toString(), "currency=" + ccy + " resultVersion=" + version + " nodes=" + nodes.size()
                + " trades=" + b.tradeCount() + " inputs=" + inputs);
        out.put("status", version == 1 ? "captured" : "restated");
        out.put("resultVersion", version);
        out.put("nodes", nodes.size());
        out.put("published", VarDate.USD.equals(ccy) && index.containsKey("published"));
        if (index.containsKey("publishError")) {
            out.put("publishError", index.get("publishError"));
        }
        return out;
    }

    /**
     * The same morning run: the inputs alike but for the trades' tables' data versions, which an intraday batch appended to (the batch
     * ids, the scenario set, the hierarchy and the rules the same). An intraday batch never restates an official record.
     */
    private boolean sameRun(Map<String, Object> now, Object before) {
        if (!(before instanceof Map<?, ?> b)) {
            return false;
        }
        Map<Object, Object> x = new LinkedHashMap<>(now);
        Map<Object, Object> y = new LinkedHashMap<>(b);
        for (String k : List.of(svc.props().kinds().vector(), svc.props().kinds().scenarioPv())) {
            x.remove(k);
            y.remove(k);
        }
        return x.equals(y);
    }

    /** Every node, hierarchy by hierarchy, parents before children. */
    private static List<VarBook.Node> ordered(VarBook b) {
        List<VarBook.Node> out = new ArrayList<>();
        b.nodes().values().stream().filter(n -> n.parent() == null).sorted(Comparator.comparing(VarBook.Node::hierarchy))
                .forEach(root -> walk(b, root, out));
        return out;
    }

    private static void walk(VarBook b, VarBook.Node n, List<VarBook.Node> out) {
        out.add(n);
        for (String c : n.children()) {
            VarBook.Node cn = b.node(c);
            if (cn != null) {
                walk(b, cn, out);
            }
        }
    }

    /** One node's official capture written, and its figures for the date's record. */
    private Map<String, Object> node(VarDate d, VarBook.Node n, long version, String membership, String computedAt, Map<String, Object> inputs) {
        LocalDate date = d.date();
        String ccy = d.currency();
        String scope = "NODE:" + n.id();
        VarService.Scoped s = svc.scoped(null, scope, date, ccy);
        Map<String, Object> head = views.headline(null, scope, date, ccy);
        Map<String, Object> contrib = views.contributions(null, scope, date, "child", null, ccy);
        Map<String, Object> path = views.path(null, scope, date, 0, 25, ccy);
        double hypo = s.vector().length == 0 ? 0 : s.vector()[s.vector().length - 1];
        // the backtest over the official history up to the day before, and today
        VarOfficialStore.NodeHistory h = svc.official().history(n.id(), date.minusDays(1), svc.props().historyDays() - 1, ccy);
        int k = h.dates().length;
        double[] var = java.util.Arrays.copyOf(h.var(), k + 1);
        double[] pnl = java.util.Arrays.copyOf(h.hypo(), k + 1);
        var[k] = s.measures().var();
        pnl[k] = hypo;
        Backtest bt = Backtest.of(pnl, var, svc.props().confidence());
        String id = "CAP-" + date.toString().replace("-", "") + "-" + ContentDocs.next(svc.official().docs().store(), "var/captures-seq");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) contrib.getOrDefault("rows", List.of());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("official", true);
        c.put("resultVersion", version);
        c.put("scope", scope);
        c.put("canonical", s.res().canonical());
        c.put("node", n.id());
        c.put("label", n.name());
        c.put("kind", "node");
        c.put("businessDate", date.toString());
        c.put("currency", ccy);
        c.put("capturedAt", computedAt);
        c.put("owner", VarOfficialStore.OWNER);
        c.put("sharedWith", List.of());
        c.put("note", "official end of day");
        for (String f : List.of("var", "es", "k", "esCount", "confidence", "esConfidence", "limit", "sumStandalone", "diversification")) {
            c.put(f, head.get(f));
        }
        c.put("hypoPnl", Math.round(hypo));
        c.put("components", rows.subList(0, Math.min(rows.size(), COMPONENTS)));
        c.put("componentsBy", contrib.get("by"));
        c.put("componentCount", rows.size());
        c.put("worst", path.get("worst"));
        long[] vector = new long[s.vector().length];
        for (int j = 0; j < vector.length; j++) {
            vector[j] = Math.round(s.vector()[j]);
        }
        c.put("vector", vector);
        c.put("scenarioSet", d.book().scenarioSet());
        c.put("membership", membership);
        c.put("memberCount", s.set().size());
        c.put("membershipHash", s.set().digest());
        c.put("dataVersion", d.version());
        c.put("inputs", inputs);
        c.put("provenance", head.get("provenance"));
        if (!svc.official().putCapture(id, c)) {
            throw new IllegalStateException("an official capture " + id + " exists already");
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("captureId", id);
        f.put("currency", ccy);
        f.put("hierarchy", n.hierarchy());
        f.put("level", n.level());
        f.put("name", n.name());
        f.put("parent", n.parent());
        f.put("var", head.get("var"));
        f.put("es", head.get("es"));
        f.put("hypoPnl", Math.round(hypo));
        f.put("limit", head.get("limit"));
        f.put("limitUse", head.get("limitUse"));
        f.put("sumStandalone", head.get("sumStandalone"));
        f.put("diversification", head.get("diversification"));
        f.put("trades", s.set().size());
        f.put("positions", ((Map<?, ?>) head.get("provenance")).get("positions"));
        f.put("exceptions250", bt.exceptions());
        f.put("light", bt.light());
        return f;
    }

    /** The published row of a node (VAR_GUIDE.md section 19). */
    private Map<String, Object> row(VarDate d, VarBook.Node n, Map<String, Object> f, long version, String computedAt, Map<String, Object> inputs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("HIERARCHY", n.hierarchy());
        r.put("NODE_ID", n.id());
        r.put("NODE_NAME", n.name());
        r.put("PARENT_ID", n.parent());
        r.put("LEVEL", n.level());
        r.put("CAPTURE_ID", f.get("captureId"));
        r.put("RESULT_VERSION", version);
        try {
            r.put("INPUT_VERSIONS", ContentDocs.JSON.writeValueAsString(inputs));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            r.put("INPUT_VERSIONS", String.valueOf(inputs));
        }
        r.put("COMPUTED_AT", computedAt);
        r.put("CONTRACT_VERSION", VarPublisher.CONTRACT_VERSION);
        r.put("VAR", f.get("var"));
        r.put("ES", f.get("es"));
        r.put("CONFIDENCE", svc.props().confidence());
        r.put("ES_CONFIDENCE", svc.props().esConfidence());
        r.put("SCENARIO_SET_ID", d.book().scenarioSet());
        r.put("SCENARIO_WINDOW", (long) d.book().width());             // the newest N scenarios used (drishti.var.scenario-window)
        r.put("STANDALONE_SUM", f.get("sumStandalone"));
        r.put("DIVERSIFICATION", f.get("diversification"));
        r.put("HYPO_PNL", f.get("hypoPnl"));
        r.put("LIMIT", f.get("limit"));
        r.put("LIMIT_USE", f.get("limitUse"));
        r.put("TRADE_COUNT", f.get("trades"));
        r.put("POSITION_COUNT", f.get("positions"));
        r.put("EXCEPTIONS_250", f.get("exceptions250"));
        r.put("TRAFFIC_LIGHT", f.get("light"));
        return r;
    }

    /** The date's positions and their trades by book, and each node's books: what the rules derived, kept so it can be derived again. */
    private static Map<String, Object> membership(VarDate d, long version) {
        VarBook b = d.book();
        Map<String, Map<String, List<String>>> books = new LinkedHashMap<>();
        for (int p = 0; p < b.positionCount(); p++) {
            String book = String.valueOf(b.positions().text("TT_BOOK", p));
            List<String> trades = new ArrayList<>();
            for (int o = b.positionFrom(p); o < b.positionTo(p); o++) {
                trades.add(b.tradeId(o));
            }
            books.computeIfAbsent(book, x -> new LinkedHashMap<>()).put(b.positionId(p), trades);
        }
        Map<String, List<String>> nodes = new LinkedHashMap<>();
        b.nodes().forEach((id, n) -> nodes.put(id, n.books()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessDate", d.date().toString());
        m.put("resultVersion", version);
        m.put("positionRules", d.intake().rules());
        m.put("positionRulesDigest", d.intake().rulesDigest());
        m.put("trades", b.tradeCount());
        m.put("positions", b.positionCount());
        m.put("books", books);
        m.put("nodes", nodes);
        return m;
    }
}
