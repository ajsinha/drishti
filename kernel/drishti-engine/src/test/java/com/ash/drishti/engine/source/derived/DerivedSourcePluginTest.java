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
package com.ash.drishti.engine.source.derived;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityReader;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceContext;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DerivedSourcePluginTest {

    /** Three trades in two books (one without a book), read as of whatever date is asked. */
    static class Trades implements EntityReader {
        final AtomicInteger reads = new AtomicInteger();
        final List<AsOf> asked = new ArrayList<>();

        @Override
        public List<EntityRef> list(String kind, AsOf asOf, int limit) {
            return kind.equals("trade") ? List.of(EntityRef.of("trade", "T-1"), EntityRef.of("trade", "T-2"), EntityRef.of("trade", "T-3"),
                    EntityRef.of("trade", "T-4")) : List.of();
        }

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, AsOf asOf) {
            reads.incrementAndGet();
            asked.add(asOf);
            double bump = asOf.live() ? 0 : -10;
            Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
            put(out, "T-1", Map.of("book", "RATES-1", "mtm", 100 + bump, "currency", "USD", "status", "Live"), asOf);
            put(out, "T-2", Map.of("book", "RATES-1", "mtm", -40, "currency", "EUR", "status", "Live"), asOf);
            put(out, "T-3", Map.of("book", "FX-1", "mtm", 7, "currency", "USD", "status", "Matured"), asOf);
            put(out, "T-4", Map.of("mtm", 1, "status", "Live"), asOf);
            return out;
        }

        private static void put(Map<EntityRef, EntityDocument> out, String id, Map<String, Object> doc, AsOf asOf) {
            EntityRef ref = EntityRef.of("trade", id);
            out.put(ref, new EntityDocument(ref, DataNode.of(doc), new Provenance("lake", 1, Instant.now(), false, asOf.live() ? null : asOf.businessDate())));
        }
    }

    record Ctx(Map<String, String> settings, EntityReader reader) implements SourceContext {
        @Override
        public DataNode parseJson(InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return null;
        }
    }

    static Map<String, String> settings() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("book-pnl.from", "trade");
        s.put("book-pnl.group-by", "$.book");
        s.put("book-pnl.id-field", "book");
        s.put("book-pnl.members", "tradeIds");
        s.put("book-pnl.fields.tradeCount", "count");
        s.put("book-pnl.fields.mtm", "sum $.mtm");
        s.put("book-pnl.fields.avgMtm", "avg $.mtm");
        s.put("book-pnl.fields.worst", "min $.mtm");
        s.put("book-pnl.fields.best", "max $.mtm");
        s.put("book-pnl.fields.currencies", "distinct $.currency");
        s.put("book-pnl.rows.trade", "$.ccy");
        s.put("book-pnl.rows.mtm", "$.mtm");
        s.put("book-pnl.rows.ccy", "$.currency");
        s.put("live-books.from", "trade");
        s.put("live-books.group-by", "$.book");
        s.put("live-books.where", "$.status == 'Live'");
        s.put("live-books.fields.n", "count");
        return s;
    }

    private static DerivedSourcePlugin started(Map<String, String> s, EntityReader r) {
        DerivedSourcePlugin p = new DerivedSourcePlugin();
        p.start(new Ctx(s, r));
        return p;
    }

    @Test
    void groupsMembersAndAggregatesEachGroup() throws Exception {
        Trades trades = new Trades();
        DerivedSourcePlugin p = started(settings(), trades);
        assertThat(p.manifest().kinds()).containsExactlyInAnyOrder("book-pnl", "live-books");
        DataNode book = p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data();
        assertThat(book.get("book").asText()).isEqualTo("RATES-1");
        assertThat(book.get("tradeCount").asDouble()).isEqualTo(2);
        assertThat(book.get("mtm").asDouble()).isEqualTo(60);
        assertThat(book.get("avgMtm").asDouble()).isEqualTo(30);
        assertThat(book.get("worst").asDouble()).isEqualTo(-40);
        assertThat(book.get("best").asDouble()).isEqualTo(100);
        assertThat(book.get("currencies").unwrap()).isEqualTo(List.of("EUR", "USD"));
        assertThat(book.get("tradeIds").unwrap()).isEqualTo(List.of("T-1", "T-2"));
        assertThat(book.get("derivedFrom").asText()).isEqualTo("trade");
        assertThat(book.get("id").asText()).isEqualTo("RATES-1");
        assertThat(book.get("rows").size()).isEqualTo(2);
        assertThat(book.get("rows").get(1).get("ccy").asText()).isEqualTo("EUR");          // in member id order: T-1, T-2
        assertThat(book.get("rows").get(1).get("trade").isNull()).isTrue();               // a missing value is null, not an error
        assertThat(p.fetch(EntityRef.of("book-pnl", "NOPE"))).isEmpty();                       // a trade without a book is in no group
        assertThat(p.fetch(EntityRef.of("live-books", "FX-1"))).isEmpty();                      // where: only live trades
        assertThat(p.fetch(EntityRef.of("live-books", "RATES-1")).orElseThrow().data().get("members").unwrap()).isEqualTo(List.of("T-1", "T-2"));
        assertThat(p.search("book-pnl", "rat", 10)).extracting(h -> h.ref().id()).containsExactly("RATES-1");
        assertThat(p.search("book-pnl", "", 10)).hasSize(2);
        assertThat(trades.reads.get()).isEqualTo(1);              // one read of the trades for both derived kinds, then cached
    }

    @Test
    void aPickedDateIsComputedFromThatDaysMembers() throws Exception {
        Trades trades = new Trades();
        DerivedSourcePlugin p = started(settings(), trades);
        LocalDate day = LocalDate.of(2026, 9, 28);
        EntityDocument then = p.fetch(EntityRef.of("book-pnl", "RATES-1"), AsOf.of(day)).orElseThrow();
        assertThat(then.data().get("mtm").asDouble()).isEqualTo(50);
        assertThat(then.provenance().businessDate()).isEqualTo(day);
        assertThat(p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(60);
        assertThat(trades.asked).extracting(AsOf::live).containsExactly(false, true);
    }

    @Test
    void mistakesInTheDeclarationFailTheStart() {
        Trades r = new Trades();
        assertThatThrownBy(() -> started(Map.of(), r)).isInstanceOf(PluginNotConfigured.class);
        assertThatThrownBy(() -> started(Map.of("x.from", "trade"), r)).hasMessageContaining("group-by is required");
        assertThatThrownBy(() -> started(Map.of("x.from", "x", "x.group-by", "$.a"), r)).hasMessageContaining("cannot be built from itself");
        assertThatThrownBy(() -> started(Map.of("x.from", "trade", "x.group-by", "$.a", "x.fields.y", "mode $.m"), r))
                .hasMessageContaining("must start with count, sum, avg, median");
        assertThatThrownBy(() -> started(Map.of("x.from", "trade", "x.group-by", "$.a", "x.fields.y", "sum"), r))
                .hasMessageContaining("needs an expression");
        assertThatThrownBy(() -> started(Map.of("x.from", "trade", "x.group-by", "$.a +"), r)).hasMessageContaining("is not an expression");
    }

    /**
     * The trades of {@link Trades}, read in one pass ({@link EntityReader#documents}) with a data version the test moves (a
     * load); reading entity by entity is counted, and must not happen.
     */
    static final class Stream extends Trades {
        final AtomicInteger passes = new AtomicInteger();
        final AtomicInteger versionAsks = new AtomicInteger();
        volatile String version = "v1";
        volatile double extra = 0;

        @Override
        public boolean documents(String kind, AsOf asOf, java.util.function.Consumer<EntityDocument> sink) {
            passes.incrementAndGet();
            Map<EntityRef, EntityDocument> all = new LinkedHashMap<>();
            Map<String, Object> t1 = new LinkedHashMap<>(Map.of("book", "RATES-1", "mtm", 100 + extra, "currency", "USD", "status", "Live"));
            all.put(EntityRef.of("trade", "T-1"), new EntityDocument(EntityRef.of("trade", "T-1"), DataNode.of(t1), new Provenance("lake", 1, Instant.now(), false, null)));
            all.put(EntityRef.of("trade", "T-2"), new EntityDocument(EntityRef.of("trade", "T-2"),
                    DataNode.of(Map.of("book", "RATES-1", "mtm", -40, "currency", "EUR", "status", "Live")), new Provenance("lake", 1, Instant.now(), false, null)));
            all.values().forEach(sink);
            return true;
        }

        @Override
        public java.util.Optional<String> dataVersion(String kind, AsOf asOf) {
            versionAsks.incrementAndGet();
            return java.util.Optional.of(version);
        }
    }

    @Test
    void aSourceThatReadsAWholeDayIsReadInOnePassNotEntityByEntity() throws Exception {
        Stream trades = new Stream();
        DerivedSourcePlugin p = started(settings(), trades);
        DataNode book = p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data();
        assertThat(book.get("mtm").asDouble()).isEqualTo(60);
        assertThat(book.get("tradeIds").unwrap()).isEqualTo(List.of("T-1", "T-2"));
        assertThat(trades.passes.get()).isEqualTo(1);             // one pass for both derived kinds
        assertThat(trades.reads.get()).isZero();                  // no read entity by entity
    }

    @Test
    void aCutBuildSaysSoInItsHealthAndKindsWithTheSameColumnsShareOneCopy() throws Exception {
        Stream trades = new Stream();
        Map<String, String> s = new LinkedHashMap<>();
        for (String k : List.of("by-book", "by-ccy")) {                // the same fields and row columns, grouped differently
            s.put(k + ".from", "trade");
            s.put(k + ".group-by", k.equals("by-book") ? "$.book" : "$.currency");
            s.put(k + ".fields.mtm", "sum $.mtm");
            s.put(k + ".rows.mtm", "$.mtm");
        }
        s.put("max-stream", "1");
        DerivedSourcePlugin p = started(s, trades);
        assertThat(p.fetch(EntityRef.of("by-book", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(100);   // T-1 only
        assertThat(p.health()).startsWith("DEGRADED: partial:").contains("first 1 of 2 trade documents").contains("max-stream");
        s.put("max-stream", "10");
        DerivedSourcePlugin whole = started(s, trades);
        assertThat(whole.fetch(EntityRef.of("by-ccy", "USD")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(100);
        assertThat(whole.fetch(EntityRef.of("by-ccy", "EUR")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(-40);
        assertThat(whole.health()).isEqualTo("UP");
    }

    @Test
    void pagesKeptOnDiskAreTheSamePagesAndTheirFolderGoesWhenTheyAreRetired(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path root = tmp.resolve("derived").resolve("pages");
        java.nio.file.Files.createDirectories(root.resolve("left-over-from-a-crash"));
        Map<String, String> s = settings();
        s.put("store", "disk");
        s.put("store-dir", root.toString());
        s.put("retire-after", "0s");
        Stream disk = new Stream();
        DerivedSourcePlugin onDisk = started(s, disk);
        assertThat(root.resolve("left-over-from-a-crash")).doesNotExist();                  // the folder is the pages' own: emptied at start
        DerivedSourcePlugin inHeap = started(settings(), new Stream());
        for (String kind : List.of("book-pnl", "live-books")) {
            assertThat(onDisk.search(kind, "", 10)).isEqualTo(inHeap.search(kind, "", 10));
            for (var hit : inHeap.search(kind, "", 10)) {
                assertThat(onDisk.fetch(hit.ref()).orElseThrow().data().unwrap()).as(kind + " " + hit.ref().id())
                        .isEqualTo(inHeap.fetch(hit.ref()).orElseThrow().data().unwrap());
            }
        }
        assertThat(onDisk.fetch(EntityRef.of("book-pnl", "NOPE"))).isEmpty();
        java.nio.file.Path first;
        try (var dirs = java.nio.file.Files.list(root)) {
            List<java.nio.file.Path> all = dirs.toList();
            assertThat(all).hasSize(1);                                                     // one build, one folder, under store-dir only
            first = all.get(0);
        }
        onDisk.purgeCaches();                                                               // retired (its folder goes) and built again at once
        for (int i = 0; i < 300 && java.nio.file.Files.exists(first); i++) {
            Thread.sleep(10);
        }
        assertThat(first).doesNotExist();
        assertThat(onDisk.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(60);   // built again
        try (var dirs = java.nio.file.Files.list(root)) {
            assertThat(dirs.toList()).hasSize(1).doesNotContain(first);                     // the new build's folder only
        }
    }

    /** Trades read slowly (a full pass over a large book), counting how many passes run at once. */
    static final class SlowTrades extends Trades {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, AsOf asOf) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
                return super.read(refs, asOf);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                running.decrementAndGet();
            }
        }
    }

    @Test
    void aBurstOfPurgesNeverRunsTwoFullReadsAtOnce() throws Exception {
        // QA3A-01: sixty data-load signals each purged the pages while readers kept reading; each purge dropped the running build and the
        // next reader started another full read beside it
        SlowTrades slow = new SlowTrades();
        DerivedSourcePlugin p = started(settings(), slow);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            readers.add(Thread.ofVirtual().start(() -> {
                while (!stop.get()) {
                    p.fetch(EntityRef.of("book-pnl", "RATES-1"), AsOf.LATEST);
                }
            }));
        }
        Thread.sleep(50);
        for (int i = 0; i < 60; i++) {
            p.purgeCaches();
            Thread.sleep(10);
        }
        Thread.sleep(400);
        stop.set(true);
        for (Thread t : readers) {
            t.join(5_000);
        }
        assertThat(slow.peak.get()).as("full reads at once").isEqualTo(1);
        assertThat(p.builds()).as("builds for 60 purges in 0.6 s, each read taking 0.15 s").isLessThanOrEqualTo(8);
        assertThat(p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(60);
    }

    @Test
    void sixtyPurgesInARowRebuildThePagesOnDiskOnce(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        Map<String, String> s = settings();
        s.put("store", "disk");
        s.put("store-dir", root.toString());
        s.put("retire-after", "0s");
        s.put("rebuild-delay", "300ms");
        SlowTrades slow = new SlowTrades();
        DerivedSourcePlugin p = started(s, slow);
        p.fetch(EntityRef.of("book-pnl", "RATES-1"));
        long before = p.builds();
        for (int i = 0; i < 60; i++) {
            p.purgeCaches();                                    // sixty signals in quick succession
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (p.builds() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(800);
        assertThat(p.builds() - before).as("rebuilds after sixty purges").isBetween(1L, 2L);
        assertThat(slow.peak.get()).isEqualTo(1);
        assertThat(p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(60);
    }

    @Test
    void todayInAFieldIsTheComputationsDateInTheHeapAndOnDisk(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        for (String store : List.of("heap", "disk")) {
            Map<String, String> s = settings();
            s.put("book-pnl.fields.asOf", "first today()");
            s.put("book-pnl.fields.live", "sum $.mtm > 0 && days(today(), today()) == 0 ? 1 : 0");
            s.put("store", store);
            s.put("store-dir", tmp.resolve(store).toString());
            DerivedSourcePlugin p = started(s, new Stream());
            DataNode book = p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data();
            assertThat(book.get("asOf").asText()).as(store).isEqualTo(LocalDate.now(java.time.ZoneId.of("America/New_York")).toString());
            assertThat(book.get("live").asDouble()).as(store).isEqualTo(1);                 // T-1 (mtm 100) counts, T-2 (-40) not
            DataNode then = p.fetch(EntityRef.of("book-pnl", "RATES-1"), AsOf.of(LocalDate.of(2026, 9, 28))).orElseThrow().data();
            assertThat(then.get("asOf").asText()).as(store).isEqualTo("2026-09-28");      // a picked date: today() is that date
        }
    }

    @Test
    void pagesOnDiskAreServedAgainAfterARestartWithoutReadingTheSource(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        Map<String, String> s = settings();
        s.put("store", "disk");
        s.put("store-dir", tmp.resolve("pages").toString());
        Stream first = new Stream();
        DerivedSourcePlugin running = started(s, first);
        DataNode before = running.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data();
        running.close();                                                               // the server stops: the folder stays
        Stream again = new Stream();                                                   // a restart: a new plugin, the same folder
        DerivedSourcePlugin restarted = started(s, again);
        assertThat(restarted.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().unwrap()).isEqualTo(before.unwrap());
        assertThat(again.passes.get()).isZero();                                      // served from disk: no pass over the source
        assertThat(restarted.search("book-pnl", "", 10)).hasSize(1);
    }

    @Test
    void aNewDataVersionRebuildsAndAnUnchangedOneNeverDoes() throws Exception {
        Stream trades = new Stream();
        Map<String, String> s = settings();
        s.put("version-check", "0s");                             // ask the version on every read
        s.put("refresh", "0s");                                   // the timer would rebuild at once: the version must win over it
        DerivedSourcePlugin p = started(s, trades);
        assertThat(p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(60);
        for (int i = 0; i < 5; i++) {
            Thread.sleep(2);
            assertThat(p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(60);
        }
        assertThat(trades.passes.get()).isEqualTo(1);             // the version did not move: nothing was read again
        assertThat(trades.versionAsks.get()).isGreaterThan(1);

        trades.extra = 5;                                         // a load: new data, a new version
        trades.version = "v2";
        Thread.sleep(2);
        p.fetch(EntityRef.of("book-pnl", "RATES-1"));            // answers the last computation and rebuilds behind
        for (int i = 0; i < 200 && trades.passes.get() < 2; i++) {
            Thread.sleep(10);
        }
        assertThat(trades.passes.get()).isEqualTo(2);
        for (int i = 0; i < 200; i++) {
            if (p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble() == 65) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(p.fetch(EntityRef.of("book-pnl", "RATES-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(65);
    }

    /** 2,500 trades served as columns only: reading a document would fail the test. */
    static final class Columns implements EntityReader {
        int documentReads;

        @Override
        public List<EntityRef> list(String kind, AsOf asOf, int limit) {
            documentReads++;
            return List.of();
        }

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, AsOf asOf) {
            documentReads++;
            return Map.of();
        }

        @Override
        public java.util.Optional<com.ash.drishti.api.ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            int n = 2500;
            String[] ids = new String[n];
            String[] books = new String[n];
            double[] mtm = new double[n];
            String[] ccy = new String[n];
            for (int i = 0; i < n; i++) {
                ids[i] = String.format("MX-%08d", 30_000_000 + i);
                books[i] = i % 2 == 0 ? "RATES-1" : "FX-1";
                mtm[i] = i % 10 == 0 ? Double.NaN : 1;                // every tenth trade has no MTM
                ccy[i] = i % 3 == 0 ? "EUR" : "USD";
            }
            Map<String, double[]> nums = new LinkedHashMap<>();
            Map<String, String[]> texts = new LinkedHashMap<>();
            for (String p : paths) {
                switch (p) {
                    case "book" -> texts.put(p, books);
                    case "mtm" -> nums.put(p, mtm);
                    case "currency" -> texts.put(p, ccy);
                    case "status" -> texts.put(p, new String[n]);
                    default -> { }
                }
            }
            return java.util.Optional.of(new com.ash.drishti.api.ColumnSet(ids, nums, texts, LocalDate.of(2026, 9, 30)));
        }
    }

    @Test
    void aBookOfAnySizeIsAggregatedFromColumnsAndListsAtMostItsLimit() throws Exception {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("book-pnl.from", "trade");
        s.put("book-pnl.group-by", "$.book");
        s.put("book-pnl.max-members", "100");
        s.put("book-pnl.fields.n", "count");
        s.put("book-pnl.fields.mtm", "sum $.mtm");
        s.put("book-pnl.fields.currencies", "distinct $.currency");
        s.put("book-pnl.rows.mtm", "$.mtm");
        Columns cols = new Columns();
        DerivedSourcePlugin p = started(s, cols);
        DataNode book = p.fetch(EntityRef.of("book-pnl", "RATES-1"), AsOf.of(LocalDate.of(2026, 9, 30))).orElseThrow().data();
        assertThat(book.get("n").asDouble()).isEqualTo(1250);
        assertThat(book.get("memberCount").asDouble()).isEqualTo(1250);
        assertThat(book.get("mtm").asDouble()).isEqualTo(1000);                // 1250 trades, 250 without an MTM
        assertThat(book.get("currencies").unwrap()).isEqualTo(List.of("EUR", "USD"));
        assertThat(book.get("members").size()).isEqualTo(100);
        assertThat(book.get("rows").size()).isEqualTo(100);
        assertThat(cols.documentReads).isZero();
    }
}
