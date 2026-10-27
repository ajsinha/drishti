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
package com.ash.drishti.benchmarks;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.engine.history.HistoryAnswer;
import com.ash.drishti.engine.history.HistoryProperties;
import com.ash.drishti.engine.history.HistoryService;
import com.ash.drishti.engine.history.HistorySpec;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * History queries' benchmark (HISTORY_QUERIES.md section 19.4): {@code trades} synthetic trades (at most 10,000: the local limit) over
 * {@code days} business days in a Delta table written with Drishti's own writer under {@code target/} (never {@code data/}), deleted at the
 * end. For {@code sample} random trades it measures, at the history service: the 30-day history cold (first read of each trade's
 * documents), warm (documents cached), the cached answer, and the default per-day loop (the connector's {@code days()} hidden) against the
 * Delta fast path. It exits 1 when the warm median passes 150 ms or the cold p95 passes 400 ms.
 *
 * <pre>java -Xmx4g -cp drishti-benchmarks/target/classes:$(cat cp.txt) com.ash.drishti.benchmarks.HistoryBench [trades] [days] [sample]</pre>
 */
public final class HistoryBench {

    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final String[] STATUS = {"NEW", "VALIDATED", "VALIDATED", "VALIDATED", "AMENDED"};
    private static final String[] PRODUCTS = {"ComSwap", "IRS", "FXFwd", "Bond", "CDS"};

    private HistoryBench() {}

    public static void main(String[] args) throws Exception {
        int trades = Math.min(10_000, args.length > 0 ? Integer.parseInt(args[0]) : 10_000);
        int days = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int sample = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        Path root = Path.of("target", "history-bench-" + System.nanoTime()).toAbsolutePath();
        int code;
        try {
            code = run(root, trades, days, sample);
        } finally {
            delete(root);
        }
        System.exit(code);
    }

    static int run(Path root, int trades, int days, int sample) throws Exception {
        BusinessCalendar cal = BusinessCalendar.of("USNY");
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = END; dates.size() < days; d = cal.previous(d)) {
            dates.add(0, d);
        }
        long t0 = System.nanoTime();
        write(root, trades, dates);
        System.out.printf("wrote %,d trades x %d business days (%s .. %s) in %.1f s%n", trades, days, dates.get(0), END, secs(t0));

        Random rnd = new Random(7);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < trades; i++) {
            ids.add(id(i));
        }
        Collections.shuffle(ids, rnd);
        List<String> picked = ids.subList(0, Math.min(sample, ids.size() - 1));
        String warmUp = ids.get(picked.size());                         // read first by each bench: the snapshot and the id maps, not measured

        Map<String, Long> out = new LinkedHashMap<>();
        // the JIT's warm-up, both ways, on trades not measured (then each bench below starts with its caches purged)
        List<String> jit = ids.subList(ids.size() - Math.min(50, ids.size() - picked.size() - 1), ids.size());
        for (boolean hide : new boolean[] {false, true}) {
            try (Bench b = new Bench(root, hide, 0)) {
                b.each(jit);
            }
        }
        // the fast path: Delta lists each trade's dates itself
        try (Bench fast = new Bench(root, false, 0)) {
            fast.read(warmUp);
            List<Long> cold = fast.each(picked);
            List<Long> warm = fast.each(picked);
            out.put("cold.median", median(cold));
            out.put("cold.p95", p95(cold));
            out.put("warm.median", median(warm));
            out.put("warm.p95", p95(warm));
        }
        try (Bench cached = new Bench(root, false, 64)) {
            cached.each(picked);
            List<Long> hit = cached.each(picked);
            out.put("cached.median", median(hit));
            out.put("cached.p95", p95(hit));
        }
        // the default per-day loop over every business date of the window (days() hidden), cold
        try (Bench slow = new Bench(root, true, 0)) {
            slow.read(warmUp);
            List<Long> cold = slow.each(picked);
            out.put("default-loop.cold.median", median(cold));
            out.put("default-loop.cold.p95", p95(cold));
        }
        try (Bench again = new Bench(root, false, 0)) {                // the fast path once more, after the default loop: the order's effect
            again.read(warmUp);
            out.put("cold.again.median", median(again.each(picked)));
        }
        out.forEach((k, v) -> System.out.printf("%-26s %8.2f ms%n", k, v / 1e6));
        System.out.printf("fast path vs default loop, cold median: %.1fx%n", (double) out.get("default-loop.cold.median") / out.get("cold.again.median"));
        boolean ok = out.get("warm.median") <= 150_000_000L && out.get("cold.p95") <= 400_000_000L;
        System.out.println(ok ? "PASS" : "FAIL: warm median above 150 ms or cold p95 above 400 ms");
        return ok ? 0 : 1;
    }

    /** A Delta connector over the table and the history service over it; {@code hideDays} reads every date (the default loop). */
    private static final class Bench implements AutoCloseable {
        final DeltaSourcePlugin delta = new DeltaSourcePlugin();
        final HistoryService service;

        Bench(Path root, boolean hideDays, int cacheMb) throws Exception {
            Path caches = root.resolve("caches-" + System.nanoTime());
            delta.start(DatedSourceContract.context(Map.of("root", root.resolve("lake").toString(), "domain", "bench", "source-name", "bench-lake",
                    "warm-dates", "0", "id-map-disk.dir", caches.resolve("idmaps").toString(), "doc-disk-cache.dir", caches.resolve("docs").toString())));
            delta.purgeCaches();                                        // the document caches may be shared by connectors: each bench starts cold
            SourcePlugin source = new Started(delta, hideDays);      // started here: the registry must not start it again
            var props = new SourcesProperties(Map.of("trade", "bench-lake"), null, Map.of(), Duration.ofSeconds(10), null, Map.of());
            SourceRouter router = new SourceRouter(new SourceRegistry(List.of(source), props, new JsonCodec()), props,
                    Executors.newVirtualThreadPerTaskExecutor());
            BusinessDates dates = new BusinessDates(new BusinessDateProperties("USNY", "UTC", null),
                    Clock.fixed(END.atTime(20, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
            HistoryProperties hp = new HistoryProperties(null, null, null, null, null, null, null, null, cacheMb);
            service = new HistoryService(router, dates, hp, new SearchProperties(null, null, Map.of("trade", List.of("TRADE_ID", "TRADE_STATUS"))),
                    Formats.defaults(), new ElCompiler(), HistorySpec::defaults, k -> "TRD");
        }

        long read(String id) {
            long t = System.nanoTime();
            HistoryAnswer a = service.read(new HistoryService.Query("trade", id, 30, null, null, null, Set.of(), false, null, false, null),
                    HistoryService.Viewer.NONE);
            long took = System.nanoTime() - t;
            if (a.rows().isEmpty()) {
                throw new IllegalStateException("no rows for " + id);
            }
            return took;
        }

        List<Long> each(List<String> ids) {
            List<Long> out = new ArrayList<>();
            for (String id : ids) {
                out.add(read(id));
            }
            return out;
        }

        @Override
        public void close() {
            delta.close();
        }
    }

    /** The started connector, optionally with its fast path hidden (the router then falls back to the per-day loop over every date). */
    private record Started(SourcePlugin d, boolean hideDays) implements SourcePlugin {
        public Optional<java.util.NavigableSet<LocalDate>> days(EntityRef ref, LocalDate from, LocalDate to) {
            return hideDays ? Optional.empty() : d.days(ref, from, to);
        }

        public PluginManifest manifest() {
            return d.manifest();
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
            return d.fetch(ref);
        }

        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
            return d.fetch(ref, asOf);
        }

        public Optional<String> dataVersion(String kind) {
            return d.dataVersion(kind);
        }

        public Optional<LocalDate> latestDate(String kind) {
            return d.latestDate(kind);
        }
    }

    // ---- the table ----

    private static String id(int i) {
        return String.valueOf(1_000_000 + i);
    }

    private static void write(Path root, int trades, List<LocalDate> dates) throws IOException {
        Path staged = Files.createDirectories(root.resolve("staged"));
        ObjectMapper json = new ObjectMapper();
        Random rnd = new Random(11);
        double[] qty = new double[trades];
        double[] price = new double[trades];
        int[] version = new int[trades];
        String[] status = new String[trades];
        for (int i = 0; i < trades; i++) {
            qty[i] = 1_000 * (1 + rnd.nextInt(500));
            price[i] = 50 + rnd.nextDouble() * 2_000;
            version[i] = 1;
            status[i] = "NEW";
        }
        List<LakeManifest.Part> parts = new ArrayList<>();
        for (LocalDate d : dates) {
            Path file = staged.resolve(d + ".jsonl");
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (int i = 0; i < trades; i++) {
                    if (rnd.nextDouble() < 0.06) {                       // about 6% of the book amended each day
                        qty[i] = Math.max(1_000, qty[i] + 1_000 * (rnd.nextInt(21) - 10));
                        version[i]++;
                        status[i] = STATUS[rnd.nextInt(STATUS.length)];
                    }
                    price[i] = Math.round(price[i] * (1 + rnd.nextGaussian() * 0.004) * 10_000) / 10_000.0;
                    Map<String, Object> doc = trade(i, d, qty[i], price[i], version[i], status[i]);
                    w.write(json.writeValueAsString(Map.of("id", id(i), "doc", doc,                 // one {id, doc, columns} a line
                            "columns", Map.of("QUANTITY", qty[i], "TRADE_STATUS", status[i]))));
                    w.write('\n');
                }
            }
            parts.add(new LakeManifest.Part(file, trades, Map.of("business_date", d.toString())));
        }
        Path table = root.resolve("lake").resolve("bench").resolve("trade");
        LakeManifest m = new LakeManifest(table.toString(), List.of("business_date"),
                List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"), new LakeManifest.Column("business_date", "date"),
                        new LakeManifest.Column("QUANTITY", "double"), new LakeManifest.Column("TRADE_STATUS", "string")),
                List.of("QUANTITY", "TRADE_STATUS"), "overwrite-dates", 50_000, List.of("id", "business_date", "QUANTITY", "TRADE_STATUS"), parts);
        new LakeWriter().write(m, n -> { });
        delete(staged);
    }

    /** A synthetic Endur-style trade: UPPER_SNAKE fields, two legs, a few events. Never real data. */
    private static Map<String, Object> trade(int i, LocalDate d, double qty, double price, int version, String status) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("SOURCE_SYSTEM", "ENDUR");
        t.put("SOURCE_UPDATE_TIME", d + "T18:00:00Z");
        t.put("TRADE_ID", id(i));
        t.put("TRADE_DATE", "2026-0" + (1 + i % 8) + "-1" + (i % 9));
        t.put("TRADE_STATUS", status);
        t.put("TRADE_VERSION", String.valueOf(version));
        t.put("QUANTITY", qty);
        t.put("PRICE", price);
        t.put("BUY_SELL_CODE", i % 2 == 0 ? "B" : "S");
        t.put("PRODUCT_TYPE", PRODUCTS[i % PRODUCTS.length]);
        t.put("COUNTERPARTY", "CP-SYN-" + (i % 97));
        t.put("PORTFOLIO", "BOOK-SYN-" + (i % 13));
        t.put("TRADER_ID", "T" + (i % 41));
        t.put("CURRENCY", i % 3 == 0 ? "EUR" : "USD");
        t.put("MATURITY_DATE", (2027 + i % 9) + "-06-30");
        for (int k = 0; k < 20; k++) {
            t.put("ATTR_" + k, "VALUE-" + ((i + k) % 17));
        }
        t.put("LEGS", List.of(Map.of("LEG", "FIXED", "NOTIONAL", qty, "RATE", 0.031), Map.of("LEG", "FLOAT", "NOTIONAL", qty, "INDEX", "SOFR")));
        List<Map<String, Object>> events = new ArrayList<>();
        for (int e = 0; e < 1 + version % 4; e++) {
            events.add(Map.of("EVENT", e == 0 ? "BOOKED" : "AMENDED", "VERSION", e + 1));
        }
        t.put("EVENTS", events);
        return t;
    }

    // ---- small helpers ----

    private static long median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static long p95(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.ceil(s.size() * 0.95) - 1));
    }

    private static double secs(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }

    private static void delete(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }
}
