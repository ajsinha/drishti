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
package com.ash.drishti.banking.compute.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.vector.ParallelSums;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The (Blank) nodes of a hierarchy (VAR_GUIDE.md section 15.14): a row whose level column is empty, or whose source does not keep the
 * column at all, is placed under a (Blank) node at that level, under its real parent, the deeper levels beneath it; nothing is dropped and
 * every parent is the sum of its children.
 */
class BlankNodeTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static final int W = 6;
    static ParallelSums sums;

    @BeforeAll
    static void pool() {
        sums = new ParallelSums(2, 64);
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    static final HierarchySpec MGMT = HierarchySpec.of("MGMT", Map.of("title", "Management", "levels", "DIVISION > DESK > TT_BOOK",
            "level-names", "division > desk > book", "ids", "leaf", "limits", "var-limit"));

    /** id, division, desk, book (null: none) and its P&amp;L (a multiple of a fixed path). */
    record T(String id, String div, String desk, String book, double f) {}

    static final List<T> TRADES = List.of(
            new T("T1", "DIV-A", "DESK-1", "B1", 1),
            new T("T2", "DIV-A", null, "B2", 2),           // no desk: DIV-A › (Blank) › B2
            new T("T3", "DIV-B", "", "B3", -3),            // an empty text is the same
            new T("T4", null, null, "B4", 5),              // two levels empty: one (Blank) per level
            new T("T5", "DIV-A", "DESK-1", null, 7));      // no book

    static VarBook build(List<HierarchySpec> specs, Map<String, Double> limits) {
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < W; i++) {
            days.add(DAY.minusDays(W - i));
        }
        VarBook.Builder b = new VarBook.Builder(DAY, "S", days, 64, Long.MAX_VALUE);
        b.columnHierarchies(specs, limits);
        List<String> cols = b.textColumns();
        for (T t : TRADES) {
            String[] texts = new String[cols.size()];
            texts[cols.indexOf("DIVISION")] = t.div();
            texts[cols.indexOf("DESK")] = t.desk();
            texts[cols.indexOf("TT_BOOK")] = t.book();
            texts[cols.indexOf("LEGAL_ENTITY")] = "LE-1";
            double[] v = new double[W];
            for (int j = 0; j < W; j++) {
                v[j] = t.f() * (j - 2.5);
            }
            b.trade(t.id(), v, W, texts, new double[] {1, Double.NaN});
        }
        return b.build(sums);
    }

    @Test
    void anEmptyLevelIsABlankNodeUnderItsRealParentAndTheDeeperLevelsContinue() {
        VarBook b = build(List.of(MGMT), Map.of("MGMT~DIV-A/~", 12.0));
        VarBook.Node blankDesk = b.node("MGMT~DIV-A/~");
        assertThat(blankDesk.parent()).isEqualTo("MGMT~DIV-A");
        assertThat(blankDesk.level()).isEqualTo("desk");
        assertThat(blankDesk.name()).isEqualTo("(Blank)");
        assertThat(blankDesk.limit()).as("a limit row names it").isEqualTo(12.0);
        assertThat(b.node("MGMT~B2").parent()).isEqualTo("MGMT~DIV-A/~");
        // two (Blank) desks under two divisions never collide (path ids, even with ids: leaf)
        assertThat(b.node("MGMT~DIV-B/~").parent()).isEqualTo("MGMT~DIV-B");
        assertThat(b.node("MGMT~DIV-B/~").limit()).as("no limit row: none").isNaN();
        assertThat(b.node("MGMT~B3").parent()).isEqualTo("MGMT~DIV-B/~");
        // several empty levels: one (Blank) per level
        assertThat(b.node("MGMT~~").parent()).isEqualTo("MGMT~BANK");
        assertThat(b.node("MGMT~~").level()).isEqualTo("division");
        assertThat(b.node("MGMT~~/~").parent()).isEqualTo("MGMT~~");
        assertThat(b.node("MGMT~B4").parent()).isEqualTo("MGMT~~/~");
        // no book: a (Blank) book under its desk
        assertThat(b.node("MGMT~DIV-A/DESK-1/~").parent()).isEqualTo("MGMT~DESK-1");
        assertThat(b.node("MGMT~DIV-A/DESK-1/~").level()).isEqualTo("book");
        assertThat(b.node("MGMT~DIV-A/DESK-1/~").members().size()).isEqualTo(1);
    }

    @Test
    void totalsStayComplete() {
        VarBook b = build(List.of(MGMT), Map.of());
        assertThat(b.node("MGMT~BANK").members()).isEqualTo(b.all());
        assertThat(b.all().size()).isEqualTo(TRADES.size());
        for (VarBook.Node n : b.nodes().values()) {
            if (n.children().isEmpty()) {
                continue;
            }
            double[] sum = new double[W];
            int members = 0;
            for (String c : n.children()) {
                double[] v = b.aggregate(c);
                for (int j = 0; j < W; j++) {
                    sum[j] += v[j];
                }
                members += b.node(c).members().size();
            }
            assertThat(b.aggregate(n.id())).as("parent = sum of children: " + n.id()).isEqualTo(sum);
            assertThat(n.members().size()).as("every row once: " + n.id()).isEqualTo(members);
        }
        // the bank's vector is every trade's
        double[] all = new double[W];
        TRADES.forEach(t -> {
            for (int j = 0; j < W; j++) {
                all[j] += t.f() * (j - 2.5);
            }
        });
        double[] bank = b.aggregate("MGMT~BANK");
        for (int j = 0; j < W; j++) {
            assertThat(bank[j]).isEqualTo(all[j]);
        }
    }

    @Test
    void aColumnTheSourceDoesNotKeepPutsEveryRowUnderBlank() {
        HierarchySpec reg = HierarchySpec.of("REG", Map.of("levels", "REGION > DESK", "word", "REG", "empty-label", "(no region)"));
        VarBook b = build(List.of(MGMT, reg), Map.of());
        assertThat(b.node("REG~~").name()).isEqualTo("(no region)");
        assertThat(b.node("REG~~").members()).isEqualTo(b.all());
        assertThat(b.node("REG~~").children()).containsExactlyInAnyOrder("REG~~/DESK-1", "REG~~/~");
        assertThat(b.node("REG~~/~").members().size()).isEqualTo(3);
        // the label: the server's default when the hierarchy sets none
        HierarchySpec mgmt = MGMT.withDefaultEmptyLabel("(unassigned)");
        assertThat(build(List.of(mgmt), Map.of()).node("MGMT~DIV-A/~").name()).isEqualTo("(unassigned)");
    }
}
