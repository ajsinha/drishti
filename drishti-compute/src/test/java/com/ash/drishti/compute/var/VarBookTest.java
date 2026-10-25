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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * One business date in memory: rows arrive shuffled and end in hierarchy order (a book and a position are each one run); node vectors
 * equal the sums of their positions; the planner's vector equals the brute-force sum for random scopes; the scope algebra never counts
 * an overlap twice; trade, position and desk VaR are consistent (a position is the sum of its trades, a desk of its positions); and
 * components add up to the VaR of every node.
 */
class VarBookTest {

    static ParallelSums sums;
    static VarBook book;
    static final SyntheticBank BANK = new SyntheticBank(2, 3, 4, 600, 3);       // 24 books, 600 positions, 1,800 trades

    @BeforeAll
    static void load() {
        sums = new ParallelSums(4, 64);
        book = BANK.build(42, sums, Long.MAX_VALUE);
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    private static double[] brute(OrdinalSet set) {
        double[] acc = new double[book.width()];
        set.forEach(o -> book.store().addInto(acc, o));
        return acc;
    }

    @Test
    void rowsEndInHierarchyOrderWithContiguousBooksAndPositions() {
        assertThat(book.tradeCount()).isEqualTo(1800);
        assertThat(book.positionCount()).isEqualTo(600);
        String prev = "";
        for (int o = 0; o < book.tradeCount(); o++) {
            Columns c = book.trades();
            String key = c.text("LEGAL_ENTITY", o) + "|" + c.text("DESK", o) + "|" + c.text("TT_BOOK", o) + "|" + c.text("POSITION_ID", o);
            assertThat(key.compareTo(prev)).isGreaterThanOrEqualTo(0);
            prev = key;
        }
        for (int p = 0; p < book.positionCount(); p++) {
            for (int o = book.positionFrom(p); o < book.positionTo(p); o++) {
                assertThat(book.trades().text("POSITION_ID", o)).isEqualTo(book.positionId(p));
                assertThat(book.positionOf(o)).isEqualTo(p);
            }
        }
    }

    @Test
    void nodeVectorsEqualTheSumOfTheirPositions() {
        for (VarBook.Node n : book.nodes().values()) {
            double[] fromPositions = new double[book.width()];
            n.members().forEach(o -> { });
            List<Integer> seen = new ArrayList<>();
            n.members().forEachRun((f, t) -> {
                for (int o = f; o < t; o++) {
                    int p = book.positionOf(o);
                    if (seen.isEmpty() || seen.get(seen.size() - 1) != p) {
                        seen.add(p);
                        VarMath.addInto(fromPositions, book.positionVector(p));
                    }
                }
            });
            assertThat(book.aggregate(n.id())).as(n.id()).containsExactly(fromPositions);
        }
        assertThat(book.aggregate("MGMT~BANK")).containsExactly(book.aggregate("LEGAL~BANK"));
    }

    @Test
    void thePlannersVectorEqualsTheBruteForceSumForRandomScopes() {
        Random r = new Random(7);
        ScopeResolver res = new ScopeResolver(book, defs());
        List<String> scopes = List.of("BANK", "DESK:DESK-0-1", "DESK:DESK-0-1 - TRD:T-3,T-4", "LE:LE-1 + DESK:DESK-0-0",
                "BOOK:BOOK-1-2-3 + POS:POS-5,POS-6", "WHERE:CURRENCY == 'EUR' and DIRECTION == 'SHORT'", "DESK:DESK-1-0 & WHERE:PRODUCT_GROUP == 'FX'",
                "PORT:PF-1 + BASKET:me/x", "BANK - DESK:DESK-1-2");
        for (String s : scopes) {
            OrdinalSet set = res.resolve(s).set();
            assertThat(book.vector(set, sums)).as(s).containsExactly(brute(set));
        }
        for (int k = 0; k < 20; k++) {
            OrdinalSet.Builder b = new OrdinalSet.Builder();
            int n = r.nextInt(1800);
            for (int i = 0; i < n; i++) {
                b.add(r.nextInt(1800));
            }
            if (r.nextBoolean()) {
                b.addRange(r.nextInt(900), 900 + r.nextInt(900));
            }
            OrdinalSet set = b.build();
            assertThat(book.vector(set, sums)).containsExactly(brute(set));
        }
    }

    @Test
    void overlappingScopesNeverCountTwice() {
        ScopeResolver res = new ScopeResolver(book, defs());
        OrdinalSet desk = res.resolve("DESK:DESK-0-0").set();
        OrdinalSet both = res.resolve("DESK:DESK-0-0 + BOOK:BOOK-0-0-0 + POS:POS-0").set();     // the book and the position are in the desk
        assertThat(both).isEqualTo(desk);
        assertThat(book.vector(both, sums)).containsExactly(book.aggregate("MGMT~DESK-0-0"));
        ScopeResolver.Resolution u = res.resolve("TRD:T-4, NOPE + POS:POS-1");
        assertThat(u.unknown()).containsExactly("NOPE");
        assertThat(u.set().size()).isEqualTo(3);                                           // T-4 is in POS-1: counted once
    }

    @Test
    void tradePositionAndDeskVarAreConsistent() {
        VarCalc calc = VarCalc.defaults();
        int p = 0;
        double[] fromTrades = new double[book.width()];
        for (int o = book.positionFrom(p); o < book.positionTo(p); o++) {
            VarMath.addInto(fromTrades, book.tradeVector(o));
        }
        assertThat(book.positionVector(p)).containsExactly(fromTrades);
        // a position whose trades are long and short nets: its VaR is less than the sum of its trades' VaRs
        for (int q = 0; q < book.positionCount(); q++) {
            boolean lng = false;
            boolean shrt = false;
            for (int o = book.positionFrom(q); o < book.positionTo(q); o++) {
                lng |= book.trades().number("QUANTITY", o) > 0;
                shrt |= book.trades().number("QUANTITY", o) < 0;
            }
            if (lng && shrt) {
                double sumTrades = 0;
                for (int o = book.positionFrom(q); o < book.positionTo(q); o++) {
                    sumTrades += calc.measures(book.tradeVector(o)).var();
                }
                assertThat(calc.measures(book.positionVector(q)).var()).isLessThan(sumTrades);
                break;
            }
        }
        // a desk's components over its positions add up to the desk's VaR; over its trades too
        double[] desk = book.aggregate("MGMT~DESK-0-2");
        VarCalc.Measures m = calc.measures(desk);
        List<VarCalc.Part> byPosition = new ArrayList<>();
        List<VarCalc.Part> byTrade = new ArrayList<>();
        book.node("MGMT~DESK-0-2").members().forEachRun((f, t) -> {
            for (int o = f; o < t; o++) {
                byTrade.add(new VarCalc.Part(book.tradeId(o), "", "trade", book.tradeVector(o), Double.NaN));
                if (o == f || book.positionOf(o) != book.positionOf(o - 1)) {
                    int q = book.positionOf(o);
                    byPosition.add(new VarCalc.Part(book.positionId(q), "", "position", book.positionVector(q), Double.NaN));
                }
            }
        });
        double sp = calc.contributions(desk, m, byPosition).stream().mapToDouble(VarCalc.Contribution::componentVar).sum();
        double st = calc.contributions(desk, m, byTrade).stream().mapToDouble(VarCalc.Contribution::componentVar).sum();
        assertThat(sp).isCloseTo(m.var(), within(1e-6));
        assertThat(st).isCloseTo(m.var(), within(1e-6));
        double ses = calc.contributions(desk, m, byPosition).stream().mapToDouble(VarCalc.Contribution::componentEs).sum();
        assertThat(ses).isCloseTo(m.es(), within(1e-6));
    }

    static ScopeResolver.Definitions defs() {
        Map<String, String> ports = Map.of("PF-1", "WHERE:CURRENCY == 'USD' and PRODUCT_GROUP == 'Rates'");
        return new ScopeResolver.Definitions() {
            @Override
            public Optional<String> portfolio(String id) {
                return Optional.ofNullable(ports.get(id));
            }

            @Override
            public Optional<String> basket(String name) {
                return name.equals("me/x") ? Optional.of("POS:POS-7,POS-8 + TRD:T-100") : Optional.empty();
            }
        };
    }
}
