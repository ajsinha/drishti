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
package com.ash.drishti.server.liveset;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.Subscription;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A set paged from the store (docs/guides/LIVE_RESULTS_GUIDE.md section 7, "Scale"): the snapshot stays in the store, only the live side is
 * kept; every page and total equals the merged order worked out the long way (store records shadowed, removed or added by the live side),
 * for the key order and a chosen sort, ascending and descending, with a filter; pages fetch only their own rows. At a million records (a
 * synthetic narrow kind; only with 15 GB of memory free) the first page comes within the budget.
 */
class StorePagesTest {

    /** A narrow kind: POS id, BOOK, QTY, in a columnar store; counts what it is asked. */
    static final class Narrow implements LiveSets.Sources {
        final String[] ids;
        final String[] books;
        final double[] qty;
        final Map<String, Integer> at = new java.util.HashMap<>();
        final List<Consumer<EntityDocument>> listeners = new CopyOnWriteArrayList<>();
        final AtomicInteger fetched = new AtomicInteger();
        final AtomicInteger projections = new AtomicInteger();

        Narrow(int n) {
            ids = new String[n];
            books = new String[n];
            qty = new double[n];
            for (int i = 0; i < n; i++) {
                ids[i] = String.format("P%07d", (i * 7919L) % n);   // not in key order in the store
                books[i] = "B" + (i % 13);
                qty[i] = (i * 31L) % 1000;
                at.put(ids[i], i);
            }
        }

        DataNode doc(int i) {
            return DataNode.of(new LinkedHashMap<>(Map.of("POS_ID", ids[i], "BOOK", books[i], "QTY", qty[i])));
        }

        void tick(String id, String book, double q) {
            EntityDocument d = new EntityDocument(EntityRef.of("position", id),
                    DataNode.of(new LinkedHashMap<>(Map.of("POS_ID", id, "BOOK", book, "QTY", q))), new Provenance("test", 1, Instant.now(), true));
            listeners.forEach(l -> l.accept(d));
        }

        void delete(String id) {
            EntityDocument d = new EntityDocument(EntityRef.of("position", id), DataNode.of(Map.of()), new Provenance("test", 1, Instant.now(), true), true);
            listeners.forEach(l -> l.accept(d));
        }

        @Override
        public boolean snapshot(String kind, String query, Consumer<EntityDocument> sink) {
            throw new AssertionError("a paged set never reads the store whole");
        }

        @Override
        public Subscription subscribe(String kind, Consumer<EntityDocument> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public Predicate<DataNode> condition(String query) {
            return d -> true;
        }

        @Override
        public Optional<LiveSetSpec> defaultFor(String kind) {
            return LiveSetConfiguration.defaultSpec(kind, List.of("$.BOOK", "$.QTY"), java.util.Set.of(), "POS");
        }

        @Override
        public Optional<StorePages.Store> store(String kind) {
            return Optional.of(new StorePages.Store() {
                @Override
                public String version() {
                    return "v1";
                }

                @Override
                public boolean project(List<String> fields, StorePages.Projection sink) {
                    projections.incrementAndGet();
                    for (int i = 0; i < ids.length; i++) {
                        Object[] v = new Object[fields.size()];
                        for (int j = 0; j < v.length; j++) {
                            v[j] = switch (fields.get(j)) {
                                case "BOOK" -> books[i];
                                case "QTY" -> qty[i];
                                case "POS_ID" -> ids[i];
                                default -> null;
                            };
                        }
                        sink.row(ids[i], v);
                    }
                    return true;
                }

                @Override
                public Map<String, DataNode> fetch(Collection<String> want) {
                    Map<String, DataNode> out = new LinkedHashMap<>();
                    for (String id : want) {
                        fetched.incrementAndGet();
                        Integer i = at.get(id);
                        if (i != null) {
                            out.put(id, doc(i));
                        }
                    }
                    return out;
                }
            });
        }
    }

    /** The merged set worked out the long way: the store, with the live side's changes and removals, filtered and sorted. */
    static List<String> expected(Narrow n, Map<String, Map<String, Object>> live, java.util.Set<String> removed, String sort, boolean desc,
            String filter) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (int i = 0; i < n.ids.length; i++) {
            all.put(n.ids[i], Map.of("BOOK", n.books[i], "QTY", n.qty[i]));
        }
        removed.forEach(all::remove);
        all.putAll(live);
        List<Map.Entry<String, Map<String, Object>>> e = new ArrayList<>(all.entrySet());
        if (filter != null) {
            e.removeIf(x -> !(x.getValue().get("BOOK") + "\n" + x.getValue().get("QTY")).toLowerCase().contains(filter));
        }
        Comparator<Map.Entry<String, Map<String, Object>>> c = Comparator.comparing(Map.Entry::getKey);
        if (sort != null) {
            Comparator<Map.Entry<String, Map<String, Object>>> v = (a, b) -> LiveSet.compareValues(a.getValue().get(sort), b.getValue().get(sort));
            c = (desc ? v.reversed() : v).thenComparing(Map.Entry::getKey);
        }
        e.sort(c);
        return e.stream().map(Map.Entry::getKey).toList();
    }

    static List<String> ids(LiveSet.Page p) {
        return p.rows().stream().map(r -> String.valueOf(r.get("id"))).toList();
    }

    @Test
    void pagesAndTotalsEqualTheMergedOrderWithTheLiveSide() throws Exception {
        Narrow n = new Narrow(2_000);
        try (LiveSets sets = new LiveSets(n, Clock.systemUTC(), 20, 100)) {
            LiveSet s = sets.open(sets.forKind("position"), null);
            assertThat(s.paged()).isTrue();
            assertThat(s.page(0, 50, null, false, null, null).total()).isEqualTo(2_000);
            Map<String, Map<String, Object>> live = new LinkedHashMap<>();
            java.util.Set<String> removed = new java.util.HashSet<>();
            n.tick("P0000005", "B99", 5_000);                       // a store record changed: shadowed, sorts elsewhere now
            live.put("P0000005", Map.of("BOOK", "B99", "QTY", 5_000.0));
            n.tick("P9999999", "B1", 1.5);                          // a new record
            live.put("P9999999", Map.of("BOOK", "B1", "QTY", 1.5));
            n.tick("A0000000", "B2", 999.0);                        // a new record sorting first by key
            live.put("A0000000", Map.of("BOOK", "B2", "QTY", 999.0));
            n.delete("P0000010");                                    // a store record deleted
            removed.add("P0000010");
            n.delete("P0001500");
            removed.add("P0001500");
            for (String sort : new String[] {null, "QTY", "BOOK"}) {
                for (boolean desc : new boolean[] {false, true}) {
                    for (String filter : new String[] {null, "b1"}) {
                        List<String> want = expected(n, live, removed, sort, desc, filter);
                        for (int offset : new int[] {0, 1, 2, 3, 37, 500, want.size() - 3, want.size() + 5}) {
                            LiveSet.Page p = s.page(Math.max(0, offset), 25, sort, desc, filter, null);
                            int from = Math.min(Math.max(0, offset), want.size());
                            assertThat(ids(p)).as("sort %s desc %s filter %s offset %d", sort, desc, filter, offset)
                                    .isEqualTo(want.subList(from, Math.min(want.size(), from + 25)));
                            assertThat(p.total()).isEqualTo(want.size());
                        }
                    }
                }
            }
            assertThat(s.total(null)).isEqualTo(2_000 - 2 + 2);
            assertThat(s.row("P0000010", null)).isEmpty();                   // removed by the live side
            assertThat(s.row("P0000005", null).orElseThrow().get("doc")).isEqualTo(Map.of("POS_ID", "P0000005", "BOOK", "B99", "QTY", 5_000.0));
            assertThat(s.row("P0000006", null)).isPresent();                 // from the store
            assertThat(s.size()).isEqualTo(3);                               // only the live side is held
        }
    }

    @Test
    void aMillionRecordsPageWithinTheBudget() throws Exception {
        Assumptions.assumeTrue(availableGb() >= 15, "a million-record run needs 15 GB of memory free");
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= 2L << 30, "a million-record run needs a 2 GB heap");
        int count = Integer.getInteger("drishti.test.live-rows", 1_000_000);
        Narrow n = new Narrow(count);
        try (LiveSets sets = new LiveSets(n, Clock.systemUTC(), 20, 100)) {
            LiveSet s = sets.open(sets.forKind("position"), null);
            long t0 = System.nanoTime();
            LiveSet.Page first = s.page(0, 100, null, false, null, null);
            long firstMs = (System.nanoTime() - t0) / 1_000_000;
            long t1 = System.nanoTime();
            LiveSet.Page deep = s.page(count / 2, 100, null, false, null, null);
            long nextMs = (System.nanoTime() - t1) / 1_000_000;
            long t2 = System.nanoTime();
            LiveSet.Page sorted = s.page(0, 100, "QTY", true, null, null);
            long sortMs = (System.nanoTime() - t2) / 1_000_000;
            n.tick("P0000001", "B1", 10_000);
            long t3 = System.nanoTime();
            LiveSet.Page after = s.page(0, 100, "QTY", true, null, null);
            long liveMs = (System.nanoTime() - t3) / 1_000_000;
            System.out.printf("live sets at %,d records: first page %d ms, a page half-way %d ms, first sorted page %d ms, a page after a"
                    + " live change %d ms; %d fetched%n", count, firstMs, nextMs, sortMs, liveMs, n.fetched.get());
            assertThat(first.total()).isEqualTo(count);
            assertThat(first.rows()).hasSize(100);
            assertThat(deep.rows()).hasSize(100);
            assertThat(sorted.rows().get(0).get("doc")).isInstanceOf(Map.class);
            assertThat(ids(after).get(0)).isEqualTo("P0000001");
            assertThat(after.total()).isEqualTo(count);
            assertThat(n.fetched.get()).isLessThanOrEqualTo(400);              // the pages' rows only, never the store whole
            assertThat(firstMs).isLessThan(10_000);
            assertThat(nextMs).isLessThan(1_000);
            assertThat(liveMs).isLessThan(1_000);
        }
    }

    static long availableGb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / (1024 * 1024);
                }
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }
}
