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

import com.ash.drishti.compute.vector.ParallelSums;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A synthetic bank of any size, in memory only (never written anywhere): legal entities, desks, books, positions of a few trades each
 * (longs and shorts), each trade's vector its signed quantity times its instrument's factor path plus its own noise, in whole units.
 * The rows are added in a shuffled order, as a lake returns them. Used by the engine's tests (small) and its scale benchmark (large).
 */
final class SyntheticBank {

    static final int WIDTH = 509;
    final int les;
    final int desksPerLe;
    final int booksPerDesk;
    final int positions;
    final int tradesPerPosition;

    SyntheticBank(int les, int desksPerLe, int booksPerDesk, int positions, int tradesPerPosition) {
        this.les = les;
        this.desksPerLe = desksPerLe;
        this.booksPerDesk = booksPerDesk;
        this.positions = positions;
        this.tradesPerPosition = tradesPerPosition;
    }

    int books() {
        return les * desksPerLe * booksPerDesk;
    }

    static String le(int l) {
        return "LE-" + l;
    }

    static String desk(int l, int d) {
        return "DESK-" + l + "-" + d;
    }

    static String book(int l, int d, int b) {
        return "BOOK-" + l + "-" + d + "-" + b;
    }

    static List<LocalDate> dates() {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 10, 1);
        while (out.size() < WIDTH) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d);
            }
            d = d.plusDays(1);
        }
        return out;
    }

    /** The factor paths: 40 factors of whole-unit moves, a common factor making them correlated. */
    private static double[][] factors(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double[] common = new double[WIDTH];
        for (int j = 0; j < WIDTH; j++) {
            common[j] = r.nextGaussian() * (j % 97 == 0 ? 4 : 1);
        }
        double[][] f = new double[40][WIDTH];
        for (int k = 0; k < f.length; k++) {
            for (int j = 0; j < WIDTH; j++) {
                f[k][j] = 0.6 * common[j] + 0.8 * r.nextGaussian();
            }
        }
        return f;
    }

    /** Adds every trade to a builder in a shuffled order, and the hierarchy's nodes (MGMT: bank, desk, book; LEGAL: bank, entity). */
    VarBook.Builder fill(VarBook.Builder b, long seed) {
        double[][] f = factors(seed);
        int books = books();
        int trades = positions * tradesPerPosition;
        // a pseudo-random permutation of the trades (a lake's order is not the hierarchy's)
        long step = 2_147_483_629L;
        double[] v = new double[WIDTH];
        String[] texts = new String[VarBook.TRADE_TEXTS.size()];
        double[] nums = new double[VarBook.TRADE_NUMBERS.size()];
        for (long i = 0; i < trades; i++) {
            int t = (int) ((i * step) % trades);
            int p = t / tradesPerPosition;
            int bk = p % books;
            int l = bk / (desksPerLe * booksPerDesk);
            int d = (bk / booksPerDesk) % desksPerLe;
            int bb = bk % booksPerDesk;
            double[] fac = f[p % f.length];
            SplittableRandom tr = new SplittableRandom(seed * 31 + t);
            double qty = (tr.nextInt(5) == 0 ? -1 : 1) * (1 + tr.nextInt(9));        // longs and shorts in one position
            for (int j = 0; j < WIDTH; j++) {
                v[j] = Math.rint(qty * 1000 * fac[j] + 50 * tr.nextGaussian());
            }
            texts[0] = "POS-" + p;
            texts[1] = le(l);
            texts[2] = "DIV-" + (d % 2);
            texts[3] = desk(l, d);
            texts[4] = book(l, d, bb);
            texts[5] = texts[4];
            texts[6] = p % 3 == 0 ? "Rates" : p % 3 == 1 ? "FX" : "Credit";
            texts[7] = "PT-" + (p % 7);
            texts[8] = p % 4 == 0 ? "EUR" : "USD";
            texts[9] = "CP-" + (t % 50);
            texts[10] = qty > 0 ? "LONG" : "SHORT";
            texts[11] = "INS-" + (p % 1000);
            nums[0] = qty;
            nums[1] = Double.NaN;
            b.trade("T-" + t, v, WIDTH, texts, nums);
        }
        b.node("MGMT~BANK", "MGMT", "bank", "Bank", null, allBooks(), Double.NaN);
        b.node("LEGAL~BANK", "LEGAL", "bank", "Bank", null, allBooks(), Double.NaN);
        for (int l = 0; l < les; l++) {
            List<String> leBooks = new ArrayList<>();
            for (int d = 0; d < desksPerLe; d++) {
                List<String> dBooks = new ArrayList<>();
                for (int bb = 0; bb < booksPerDesk; bb++) {
                    dBooks.add(book(l, d, bb));
                    b.node("MGMT~" + book(l, d, bb), "MGMT", "book", book(l, d, bb), "MGMT~" + desk(l, d), List.of(book(l, d, bb)), Double.NaN);
                }
                b.node("MGMT~" + desk(l, d), "MGMT", "desk", desk(l, d), "MGMT~BANK", dBooks, 1e9);
                leBooks.addAll(dBooks);
            }
            b.node("LEGAL~" + le(l), "LEGAL", "legal-entity", le(l), "LEGAL~BANK", leBooks, Double.NaN);
        }
        return b;
    }

    List<String> allBooks() {
        List<String> out = new ArrayList<>();
        for (int l = 0; l < les; l++) {
            for (int d = 0; d < desksPerLe; d++) {
                for (int bb = 0; bb < booksPerDesk; bb++) {
                    out.add(book(l, d, bb));
                }
            }
        }
        return out;
    }

    VarBook build(long seed, ParallelSums sums, long budget) {
        VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", dates(), 65_536, budget);
        return fill(b, seed).build(sums);
    }
}
