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
    static final class Trades implements EntityReader {
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
        assertThatThrownBy(() -> started(Map.of("x.from", "trade", "x.group-by", "$.a", "x.fields.y", "median $.m"), r))
                .hasMessageContaining("must start with count, sum");
        assertThatThrownBy(() -> started(Map.of("x.from", "trade", "x.group-by", "$.a", "x.fields.y", "sum"), r))
                .hasMessageContaining("needs an expression");
        assertThatThrownBy(() -> started(Map.of("x.from", "trade", "x.group-by", "$.a +"), r)).hasMessageContaining("is not an expression");
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
