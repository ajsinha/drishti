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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hierarchy built from the trades' columns (2.9.5, VAR_GUIDE.md section 16): limit letters over desks, {@code LIMIT_LETTER > DESK},
 * beside the book-based MGMT tree. The derived nodes and their ids; a letter holding half of a book counts exactly its rows; every node's
 * aggregate is the sum of its members (a leaf's to the bit); the planned vector of any set equals the naive sum; a position whose trades
 * sit under two letters is split per letter; leaf ids are refused when a value has two parents; the date's hot file keeps the cells
 * (format 3) and answers the same; an intraday change of a trade's letter equals a full reload; the scope word {@code LL} names a letter.
 */
class ColumnHierarchyTest {

    static final int W = SyntheticBank.WIDTH;
    static final HierarchySpec LL = new HierarchySpec("LL", "Limit letters", "columns", List.of("LIMIT_LETTER", "DESK"), null, null, null,
            false, "LL", "var-limit");
    static ParallelSums sums;
    @TempDir
    static Path dir;

    /** A trade: book, desk, letter, position (null: its own), P&amp;L. */
    record T(String id, String book, String desk, String letter, String pos, double[] pnl) {}

    static final Map<String, T> TRADES = new LinkedHashMap<>();

    @BeforeAll
    static void data() {
        sums = new ParallelSums(2, 64);
        SplittableRandom r = new SplittableRandom(5);
        for (int t = 0; t < 120; t++) {
            int b = t % 4;
            String book = "BOOK-" + b;
            String desk = b < 2 ? "DESK-A" : "DESK-B";
            // BOOK-0 is half LL-1, half LL-2 (a letter holds part of a book); BOOK-1 is LL-1; BOOK-2 and BOOK-3 are LL-3
            String letter = b == 0 ? (t % 8 == 0 ? "LL-1" : "LL-2") : b == 1 ? "LL-1" : "LL-3";
            String pos = t == 0 || t == 4 ? "POS-BOOK-0~SEC-X" : null;                   // one position across two letters: split
            TRADES.put("T-" + t, new T("T-" + t, book, desk, letter, pos, vector(r)));
        }
    }

    static double[] vector(SplittableRandom r) {
        double[] v = new double[W];
        for (int j = 0; j < W; j++) {
            v[j] = Math.rint(2e4 * r.nextGaussian()) + 0.29 * r.nextGaussian();
        }
        return v;
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    static String[] texts(VarBook.Builder b, T t) {
        List<String> cols = b.textColumns();
        String[] x = new String[cols.size()];
        x[cols.indexOf("POSITION_ID")] = t.pos() == null ? "POS-" + t.book() + "~" + t.id() : t.pos();
        x[cols.indexOf("LEGAL_ENTITY")] = "LE-1";
        x[cols.indexOf("DESK")] = t.desk();
        x[cols.indexOf("TT_BOOK")] = t.book();
        x[cols.indexOf("CURRENCY")] = "USD";
        if (cols.contains("LIMIT_LETTER")) {
            x[cols.indexOf("LIMIT_LETTER")] = t.letter();
        }
        return x;
    }

    static VarBook build(Map<String, T> trades, Path mapped, List<HierarchySpec> specs) {
        VarBook.Builder b = mapped == null ? new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), 65_536, Long.MAX_VALUE)
                : new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), Long.MAX_VALUE,
                        SyntheticBank.target(mapped, "c-" + System.nanoTime() + ".vhot", "d-" + System.nanoTime()));
        b.columnHierarchies(specs, Map.of("LL~LL-1", 9.0e6, "LL~LL-3/DESK-B", 4.0e6));
        for (T t : trades.values()) {
            b.trade(t.id(), t.pnl(), W, texts(b, t), new double[] {1, 100});
        }
        List<String> books = List.of("BOOK-0", "BOOK-1", "BOOK-2", "BOOK-3");
        b.node("MGMT~BANK", "MGMT", "bank", "Bank", null, books, Double.NaN);
        b.node("MGMT~DESK-A", "MGMT", "desk", "A", "MGMT~BANK", books.subList(0, 2), Double.NaN);
        b.node("MGMT~DESK-B", "MGMT", "desk", "B", "MGMT~BANK", books.subList(2, 4), Double.NaN);
        for (String bk : books) {
            b.node("MGMT~" + bk, "MGMT", "book", bk, "MGMT~DESK-" + (bk.endsWith("0") || bk.endsWith("1") ? "A" : "B"), List.of(bk), Double.NaN);
        }
        return b.build(sums);
    }

    static double[] naive(VarBook b, OrdinalSet set) {
        double[] acc = new double[W];
        set.forEach(o -> {
            double[] v = b.tradeVector(o);
            for (int j = 0; j < W; j++) {
                acc[j] += v[j];
            }
        });
        return acc;
    }

    @Test
    void theNodesComeFromTheDistinctPathsOfTheLevelColumns() {
        VarBook b = build(TRADES, null, List.of(LL));
        assertThat(b.cellColumns()).containsExactly("LIMIT_LETTER", "DESK");
        assertThat(b.hierarchies()).containsExactly("MGMT", "LL");
        assertThat(b.node("LL~BANK").parent()).isNull();
        assertThat(b.node("LL~BANK").level()).isEqualTo("bank");
        assertThat(b.node("LL~BANK").children()).containsExactlyInAnyOrder("LL~LL-1", "LL~LL-2", "LL~LL-3");
        assertThat(b.node("LL~LL-1").children()).containsExactly("LL~LL-1/DESK-A");
        assertThat(b.node("LL~LL-3/DESK-B").level()).isEqualTo("desk");
        assertThat(b.node("LL~LL-1").level()).isEqualTo("limit-letter");
        assertThat(b.node("LL~LL-1").limit()).isEqualTo(9.0e6);
        assertThat(b.node("LL~LL-2").limit()).isNaN();                                // no limit row: no limit (never zero)
        assertThat(b.node("LL~LL-3/DESK-B").limit()).isEqualTo(4.0e6);
        // the root holds every trade, as MGMT's does
        assertThat(b.node("LL~BANK").members()).isEqualTo(b.node("MGMT~BANK").members());
    }

    @Test
    void aLetterHoldingHalfABookCountsExactlyItsRowsAndEveryAggregateIsTheSumOfItsMembers() {
        VarBook b = build(TRADES, null, List.of(LL));
        OrdinalSet ll1 = b.node("LL~LL-1").members();
        long rows = TRADES.values().stream().filter(t -> t.letter().equals("LL-1")).count();
        assertThat(ll1.size()).isEqualTo((int) rows);
        assertThat(b.node("LL~LL-1").members().and(b.node("MGMT~BOOK-0").members()).size())
                .isEqualTo((int) TRADES.values().stream().filter(t -> t.book().equals("BOOK-0") && t.letter().equals("LL-1")).count());
        assertThat(ll1.and(b.node("LL~LL-2").members()).isEmpty()).isTrue();
        for (VarBook.Node n : b.nodes().values()) {
            double[] agg = b.aggregate(n.id());
            double[] want = naive(b, n.members());
            double[] planned = b.vector(n.members(), sums);
            for (int j = 0; j < W; j++) {
                assertThat(agg[j]).as(n.id() + " " + j).isCloseTo(want[j], within(1e-6));
                assertThat(planned[j]).as("planned " + n.id() + " " + j).isCloseTo(want[j], within(1e-6));
            }
            if (n.hierarchy().equals("LL") && n.books().size() == 1) {         // a leaf of one cell: its aggregate is the cell's, to the bit
                assertThat(agg).isEqualTo(sums.sum(b.store(), n.members()));
            }
        }
        // any set: a letter and a book, half overlapping
        OrdinalSet set = ll1.or(b.node("MGMT~BOOK-2").members());
        double[] got = b.vector(set, sums);
        double[] want = naive(b, set);
        for (int j = 0; j < W; j++) {
            assertThat(got[j]).isCloseTo(want[j], within(1e-6));
        }
    }

    @Test
    void aPositionUnderTwoLettersIsSplitPerLetter() {
        VarBook b = build(TRADES, null, List.of(LL));
        assertThat(b.splitPositions()).isEqualTo(1);
        assertThat(b.positionIndex("POS-BOOK-0~SEC-X")).isEqualTo(-1);
        int p1 = b.positionIndex("POS-BOOK-0~SEC-X@LL-1");
        int p2 = b.positionIndex("POS-BOOK-0~SEC-X@LL-2");
        assertThat(p1).isGreaterThanOrEqualTo(0);
        assertThat(p2).isGreaterThanOrEqualTo(0);
        assertThat(b.positionMembers(p1).size() + b.positionMembers(p2).size()).isEqualTo(2);
        assertThat(b.positionMembers(p1).and(b.node("LL~LL-1").members()).size()).isEqualTo(1);
        assertThat(b.trades().text("POSITION_ID", b.tradeOrdinal("T-0"))).isEqualTo("POS-BOOK-0~SEC-X@LL-1");
        // without the letters the position is whole, as before 2.9.5
        VarBook plain = build(TRADES, null, List.of());
        assertThat(plain.cellColumns()).isEmpty();
        assertThat(plain.splitPositions()).isZero();
        assertThat(plain.positionMembers(plain.positionIndex("POS-BOOK-0~SEC-X")).size()).isEqualTo(2);
        assertThat(plain.trades().textColumns()).isEqualTo(VarBook.TRADE_TEXTS);
    }

    @Test
    void leafIdsAreRefusedWhenAValueHasTwoParents() {
        HierarchySpec leaf = new HierarchySpec("LL", "Limit letters", "columns", List.of("LIMIT_LETTER", "DESK"), null, null, null, true, "LL", null);
        // DESK-A sits under LL-1 and LL-2
        assertThatThrownBy(() -> build(TRADES, null, List.of(leaf))).isInstanceOf(IllegalStateException.class).hasMessageContaining("DESK-A")
                .hasMessageContaining("ids: path");
        // a tree where each value has one parent: leaf ids work
        HierarchySpec deskBook = new HierarchySpec("DB", "Desks", "columns", List.of("DESK", "TT_BOOK"), null, null, null, true, "DB", null);
        VarBook b = build(TRADES, null, List.of(deskBook));
        assertThat(b.node("DB~BOOK-0").parent()).isEqualTo("DB~DESK-A");
        assertThat(b.node("DB~BOOK-0").members()).isEqualTo(b.node("MGMT~BOOK-0").members());
        assertThat(b.cellColumns()).containsExactly("DESK");
    }

    @Test
    void theHotFileKeepsTheCellsAndAnswersTheSame() throws Exception {
        VarBook heap = build(TRADES, null, List.of(LL));
        VarBook mapped = build(TRADES, dir, List.of(LL));
        assertThat(VarHotFile.header(mapped.hot().file()).version()).isEqualTo(VarHotFile.VERSION_CELLS);
        VarBook reopened = VarHotFile.open(mapped.hot().file(), 1);
        try {
            assertThat(reopened.cellColumns()).containsExactly("LIMIT_LETTER", "DESK");
            assertThat(reopened.splitPositions()).isEqualTo(1);
            assertThat(reopened.nodes().keySet()).isEqualTo(heap.nodes().keySet());
            for (VarBook.Node n : heap.nodes().values()) {
                assertThat(reopened.node(n.id()).members()).isEqualTo(n.members());
                assertThat(reopened.node(n.id()).books()).isEqualTo(n.books());
                double[] a = reopened.aggregate(n.id());
                double[] e = n.books().isEmpty() ? null : heap.aggregate(n.id());
                for (int j = 0; e != null && j < W; j++) {
                    assertThat(a[j]).isCloseTo(e[j], within(1e-6));
                }
            }
        } finally {
            reopened.close();
            mapped.close();
        }
        VarBook plain = build(TRADES, dir, List.of());
        assertThat(VarHotFile.header(plain.hot().file()).version()).isEqualTo(VarHotFile.VERSION);
        plain.close();
    }

    @Test
    void anIntradayChangeOfLetterEqualsAFullReload() {
        VarBook morning = build(TRADES, null, List.of(LL));
        Map<String, T> after = new LinkedHashMap<>(TRADES);
        T toExisting = TRADES.get("T-12");                                            // BOOK-0, LL-2 -> LL-1 (an existing cell of the book)
        after.put(toExisting.id(), new T(toExisting.id(), toExisting.book(), toExisting.desk(), "LL-1", toExisting.pos(), toExisting.pnl()));
        T amended = TRADES.get("T-8");                                                  // BOOK-0, LL-1: new P&L, same letter
        double[] v = amended.pnl().clone();
        v[3] += 12345.5;
        after.put(amended.id(), new T(amended.id(), amended.book(), amended.desk(), amended.letter(), amended.pos(), v));
        VarBook.Builder layout = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SyntheticBank.dates(), 1, Long.MAX_VALUE)
                .columnHierarchies(List.of(LL), Map.of());
        List<VarIntraday.Update> ups = new ArrayList<>();
        for (String id : List.of(toExisting.id(), amended.id())) {
            T t = after.get(id);
            ups.add(new VarIntraday.Update(id, t.pnl().clone(), 100, texts(layout, t), new double[] {1, 100}, 100, t.pnl().clone()));
        }
        VarBook got = VarIntraday.apply(morning, ups, o -> TRADES.get(morning.tradeId(o)).pnl().clone(), c -> null, List.of("B-1"), "2026-09-30T10:00:00Z");
        VarBook want = build(after, null, List.of(LL));
        for (String node : want.nodes().keySet()) {
            double[] a = got.aggregate(node);
            double[] e = want.aggregate(node);
            for (int j = 0; j < W; j++) {
                assertThat(a[j]).as(node + " " + j).isCloseTo(e[j], within(0.005));
            }
            assertThat(got.node(node).members().size()).as(node).isEqualTo(want.node(node).members().size());
            double[] planned = got.vector(got.node(node).members(), sums);
            for (int j = 0; j < W; j++) {
                assertThat(planned[j]).as("planned " + node + " " + j).isCloseTo(e[j], within(0.005));
            }
        }
        assertThat(toExisting.letter()).isEqualTo("LL-2");
        assertThat(got.node("LL~LL-2").members().size()).isEqualTo(morning.node("LL~LL-2").members().size() - 1);
    }

    @Test
    void theScopeWordNamesALetterAndAPath() {
        VarBook b = build(TRADES, null, List.of(LL));
        ScopeResolver r = new ScopeResolver(b, new ScopeResolver.Definitions() {
            @Override
            public java.util.Optional<String> portfolio(String id) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<String> basket(String name) {
                return java.util.Optional.empty();
            }
        }, Map.of("LL", "LL"));
        ScopeResolver.Resolution one = r.resolve("ll:LL-1");
        assertThat(one.node()).isEqualTo("LL~LL-1");
        assertThat(one.canonical()).isEqualTo("LL:LL-1");
        assertThat(one.set()).isEqualTo(b.node("LL~LL-1").members());
        assertThat(r.resolve("LL:1").node()).isEqualTo("LL~LL-1");                 // the word's own prefix, as DESK:RATES
        assertThat(r.resolve("LL:LL-3/DESK-B").node()).isEqualTo("LL~LL-3/DESK-B");
        assertThat(r.resolve("NODE:LL~LL-3/DESK-B").set()).isEqualTo(b.node("LL~LL-3/DESK-B").members());
        assertThat(r.resolve("LL:LL-1 - BOOK:BOOK-1").set().size()).isEqualTo(
                b.node("LL~LL-1").members().andNot(b.node("MGMT~BOOK-1").members()).size());
        assertThat(r.resolve("LL:LL-9").unknown()).containsExactly("LL:LL-9");
        assertThat(r.resolve("BANK").node()).isEqualTo("MGMT~BANK");
        // a resolver without the word refuses it, saying the words it knows
        assertThatThrownBy(() -> new ScopeResolver(b, null).resolve("LL:LL-1")).hasMessageContaining("not a scope word");
    }
}
