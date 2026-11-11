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
package com.ash.drishti.compute.vector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The two forms of an {@link OrdinalSet} (runs and bitmap) answer the same: every query, every operation in every pairing of forms,
 * {@code equals}, {@code hashCode}, {@code words} and {@code digest}, on seeded random sets.
 */
class OrdinalSetRunsTest {

    /** A random set of runs, as pairs (unsorted, some overlapping or touching). */
    private static int[] randomPairs(Random r, int span) {
        int n = r.nextInt(12);
        int[] p = new int[2 * n];
        for (int i = 0; i < n; i++) {
            int f = r.nextInt(span);
            int len = r.nextInt(5) == 0 ? r.nextInt(400) : r.nextInt(20);
            p[2 * i] = f;
            p[2 * i + 1] = f + len;
        }
        return p;
    }

    /** The same members as a bitmap set. */
    private static OrdinalSet bitmap(int[] pairs) {
        OrdinalSet.Builder b = new OrdinalSet.Builder();
        for (int i = 0; i < pairs.length; i += 2) {
            b.addRange(pairs[i], pairs[i + 1]);
        }
        return b.build();
    }

    private static List<int[]> runsOf(OrdinalSet s) {
        List<int[]> out = new ArrayList<>();
        s.forEachRun((f, t) -> out.add(new int[] {f, t}));
        return out;
    }

    private static void assertSame2(OrdinalSet a, OrdinalSet b) {
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.digest(), b.digest());
        assertArrayEquals(a.words(), b.words());
        assertEquals(a.size(), b.size());
        assertEquals(a.isEmpty(), b.isEmpty());
        assertEquals(a.first(), b.first());
        List<int[]> ra = runsOf(a);
        List<int[]> rb = runsOf(b);
        assertEquals(ra.size(), rb.size());
        for (int i = 0; i < ra.size(); i++) {
            assertArrayEquals(ra.get(i), rb.get(i));
        }
    }

    @Test
    void bothFormsAnswerTheSame() {
        Random r = new Random(20261101L);
        for (int round = 0; round < 3000; round++) {
            int span = 50 + r.nextInt(3000);
            int[] pa = randomPairs(r, span);
            int[] pb = randomPairs(r, span);
            OrdinalSet ra = OrdinalSet.ofRuns(pa);
            OrdinalSet ba = bitmap(pa);
            OrdinalSet rb = OrdinalSet.ofRuns(pb);
            OrdinalSet bb = bitmap(pb);
            assertTrue(ra.isRuns());
            assertFalse(ba.isRuns());
            assertSame2(ra, ba);
            assertSame2(OrdinalSet.ofWords(ra.words()), ba);
            for (OrdinalSet x : List.of(ra, ba)) {
                for (OrdinalSet y : List.of(rb, bb)) {
                    assertSame2(ba.or(bb), x.or(y));
                    assertSame2(ba.and(bb), x.and(y));
                    assertSame2(ba.andNot(bb), x.andNot(y));
                    assertEquals(ba.intersects(bb), x.intersects(y));
                    assertEquals(ba.containsAll(bb), x.containsAll(y));
                    assertEquals(bb.containsAll(ba.and(bb)), y.containsAll(x.and(y)));
                }
            }
            for (int k = 0; k < 40; k++) {
                int f = r.nextInt(span + 10) - 5;
                int t = f + r.nextInt(500);
                assertEquals(ba.contains(f), ra.contains(f), "contains " + f);
                assertEquals(ba.countIn(Math.max(0, f), t), ra.countIn(Math.max(0, f), t));
                assertEquals(ba.coversRange(Math.max(0, f), t), ra.coversRange(Math.max(0, f), t), "covers " + f + " " + t);
                List<int[]> x = new ArrayList<>();
                List<int[]> y = new ArrayList<>();
                ba.forEachRunIn(Math.max(0, f), t, (a, b) -> x.add(new int[] {a, b}));
                ra.forEachRunIn(Math.max(0, f), t, (a, b) -> y.add(new int[] {a, b}));
                assertEquals(x.size(), y.size());
                for (int i = 0; i < x.size(); i++) {
                    assertArrayEquals(x.get(i), y.get(i));
                }
                int from = Math.max(0, f);
                assertSame2(ba.slice(from, t), ra.slice(from, t));
            }
            int off = r.nextInt(100);
            assertSame2(ba.shifted(off), ra.shifted(off));
            List<Integer> ea = new ArrayList<>();
            List<Integer> eb = new ArrayList<>();
            ra.forEach(ea::add);
            ba.forEach(eb::add);
            assertEquals(eb, ea);
        }
    }

    @Test
    void runsMinusABitmapStayRunsAndTheSameObjectWhenNothingIsTaken() {
        OrdinalSet node = OrdinalSet.ofRuns(new int[] {0, 100, 200, 300});
        OrdinalSet none = OrdinalSet.of(150, 350);
        assertSame(node, node.andNot(none));
        OrdinalSet minus = node.andNot(OrdinalSet.of(5, 250));
        assertTrue(minus.isRuns());
        assertEquals(198, minus.size());
        assertTrue(node.or(OrdinalSet.range(100, 200)).isRuns());
        assertEquals(OrdinalSet.range(0, 300), node.or(OrdinalSet.range(100, 200)));
    }

    @Test
    void aNodeOfAMillionOrdinalsCostsBytesNotABitmap() {
        OrdinalSet node = OrdinalSet.ofRuns(new int[] {999_000, 1_000_000});
        assertTrue(node.heapBytes() < 100, "runs: " + node.heapBytes());
        assertTrue(new OrdinalSet.Builder().addRange(999_000, 1_000_000).build().heapBytes() > 100_000);
        assertTrue(node.coversRange(999_500, 999_600));
        assertFalse(node.coversRange(998_999, 999_100));
    }

    @Test
    void coversRangeOfABitmapDoesNotDependOnTheRunAround() {
        OrdinalSet big = new OrdinalSet.Builder().addRange(0, 2_000_000).build();
        long t0 = System.nanoTime();
        int whole = 0;
        for (int p = 0; p < 2_000_000; p += 2) {
            whole += big.coversRange(p, p + 2) ? 1 : 0;
        }
        assertEquals(1_000_000, whole);
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "a million positions checked without scanning the run");
    }
}
