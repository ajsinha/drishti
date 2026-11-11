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

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntConsumer;

/**
 * A set of row ordinals (VAR_GUIDE.md section 25): what a scope resolves to on one business date. Union, difference and
 * intersection are exact set operations, so two overlapping portfolios combined count a position once; a node of the hierarchy is
 * one or a few runs of ordinals. Immutable: every operation returns a new set.
 *
 * <p>Two forms, one meaning. A set built bit by bit (a rule, a basket, ids looked up) is a plain bitmap ({@link BitSet}, one bit per
 * row of the date: 2 million rows, 250 KB). A set made of runs (a node: the union of its books' runs; a position; the whole date) is
 * a sorted list of {@code from, to} pairs: eight bytes a run, whatever the size of the date (a node of a million-trade date costs a
 * few bytes, not the 125 KB of a bitmap). Every query and operation answers the same in both forms; {@link #equals},
 * {@link #hashCode}, {@link #words()} and {@link #digest()} depend only on the members, never on the form. A run set is never turned
 * into a kept bitmap: an operation with a bitmap makes a new bitmap, as before; runs with runs stay runs, and runs minus anything
 * stay runs.
 */
public final class OrdinalSet {

    private static final int[] NO_RUNS = new int[0];
    private static final OrdinalSet EMPTY = new OrdinalSet(NO_RUNS, 0);
    private static final int OR = 0;
    private static final int AND = 1;
    private static final int AND_NOT = 2;
    /** Up to this many ordinals a bitmap's run is checked bit by bit, nothing allocated. */
    private static final int SMALL_RANGE = 128;

    /** The bitmap form, or null for the run form. */
    private final BitSet bits;
    /** The run form: sorted, disjoint, never adjacent pairs {@code from, to} (to excluded), or null for the bitmap form. */
    private final int[] runs;
    /** The member count of the run form. */
    private final int count;

    private OrdinalSet(BitSet bits) {
        this.bits = bits;
        this.runs = null;
        this.count = -1;
    }

    private OrdinalSet(int[] runs, int count) {
        this.bits = null;
        this.runs = runs;
        this.count = count;
    }

    public static OrdinalSet empty() {
        return EMPTY;
    }

    /** The run {@code from} (included) to {@code to} (excluded). */
    public static OrdinalSet range(int from, int to) {
        int f = Math.max(0, from);
        if (to <= f) {
            return EMPTY;
        }
        return new OrdinalSet(new int[] {f, to}, to - f);
    }

    /**
     * The union of runs given as pairs {@code from, to} (to excluded), in any order, overlapping or touching: kept as runs (sorted and
     * merged). A node's members are its books' runs.
     */
    public static OrdinalSet ofRuns(int[] pairs) {
        int n = pairs.length / 2;
        long[] packed = new long[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int f = Math.max(0, pairs[2 * i]);
            int t = pairs[2 * i + 1];
            if (t > f) {
                packed[k++] = ((long) f << 32) | (t & 0xffffffffL);
            }
        }
        Arrays.sort(packed, 0, k);
        int[] out = new int[2 * k];
        int m = 0;
        long c = 0;
        for (int i = 0; i < k; i++) {
            int f = (int) (packed[i] >>> 32);
            int t = (int) packed[i];
            if (m > 0 && f <= out[m - 1]) {
                if (t > out[m - 1]) {
                    c += t - out[m - 1];
                    out[m - 1] = t;
                }
            } else {
                out[m++] = f;
                out[m++] = t;
                c += t - f;
            }
        }
        return m == 0 ? EMPTY : new OrdinalSet(m == out.length ? out : Arrays.copyOf(out, m), (int) c);
    }

    public static OrdinalSet of(int... ordinals) {
        BitSet b = new BitSet();
        for (int o : ordinals) {
            if (o >= 0) {
                b.set(o);
            }
        }
        return new OrdinalSet(b);
    }

    /** A set built bit by bit (a rule evaluated over every row, ids looked up one by one). */
    public static final class Builder {
        private final BitSet b = new BitSet();

        public Builder add(int ordinal) {
            if (ordinal >= 0) {
                b.set(ordinal);
            }
            return this;
        }

        public Builder addRange(int from, int to) {
            if (to > from) {
                b.set(from, to);
            }
            return this;
        }

        public OrdinalSet build() {
            return new OrdinalSet((BitSet) b.clone());
        }
    }

    /** True when this set is held as runs (a node, a position, the whole date), not as a bitmap. */
    public boolean isRuns() {
        return runs != null;
    }

    /** The heap bytes the set holds (its runs, or its bitmap's words), for the weighing of what a date keeps. */
    public long heapBytes() {
        return runs != null ? 40L + 4L * runs.length : 56L + 8L * ((bits.length() + 63) / 64);
    }

    /** The members as a new bitmap (either form). */
    private BitSet toBits() {
        if (bits != null) {
            return (BitSet) bits.clone();
        }
        BitSet b = new BitSet(runs.length == 0 ? 0 : runs[runs.length - 1]);
        for (int i = 0; i < runs.length; i += 2) {
            b.set(runs[i], runs[i + 1]);
        }
        return b;
    }

    public OrdinalSet or(OrdinalSet o) {
        if (runs != null && o.runs != null) {
            return combine(runs, o.runs, OR);
        }
        BitSet b = bits != null ? (BitSet) bits.clone() : (BitSet) o.bits.clone();
        int[] r = bits != null ? o.runs : runs;
        if (r == null) {
            b.or(o.bits);
        } else {
            for (int i = 0; i < r.length; i += 2) {
                b.set(r[i], r[i + 1]);
            }
        }
        return new OrdinalSet(b);
    }

    public OrdinalSet andNot(OrdinalSet o) {
        if (runs != null && o.runs != null) {
            return combine(runs, o.runs, AND_NOT);
        }
        if (runs != null) {                                   // runs minus a bitmap: runs again (the same set when nothing is taken)
            return intersects(o) ? runsMinusBits(o.bits) : this;
        }
        BitSet b = (BitSet) bits.clone();
        if (o.runs != null) {
            for (int i = 0; i < o.runs.length; i += 2) {
                b.clear(o.runs[i], o.runs[i + 1]);
            }
        } else {
            b.andNot(o.bits);
        }
        return new OrdinalSet(b);
    }

    public OrdinalSet and(OrdinalSet o) {
        if (runs != null && o.runs != null) {
            return combine(runs, o.runs, AND);
        }
        BitSet b = bits != null ? (BitSet) bits.clone() : (BitSet) o.bits.clone();
        int[] r = bits != null ? o.runs : runs;
        if (r == null) {
            b.and(o.bits);
        } else {                                              // a bitmap within runs: clear the gaps between the runs
            int at = 0;
            for (int i = 0; i < r.length; i += 2) {
                if (r[i] > at) {
                    b.clear(at, r[i]);
                }
                at = r[i + 1];
            }
            int len = b.length();
            if (len > at) {
                b.clear(at, len);
            }
        }
        return new OrdinalSet(b);
    }

    private OrdinalSet runsMinusBits(BitSet minus) {
        int[] out = new int[runs.length + 16];
        int m = 0;
        long c = 0;
        for (int i = 0; i < runs.length; i += 2) {
            int f = runs[i];
            int t = runs[i + 1];
            while (f < t) {
                int g = minus.nextSetBit(f);
                int end = g < 0 || g >= t ? t : g;
                if (end > f) {
                    if (m + 2 > out.length) {
                        out = Arrays.copyOf(out, out.length * 2);
                    }
                    out[m++] = f;
                    out[m++] = end;
                    c += end - f;
                }
                if (end >= t) {
                    break;
                }
                f = Math.min(t, minus.nextClearBit(end));
            }
        }
        return m == 0 ? EMPTY : new OrdinalSet(Arrays.copyOf(out, m), (int) c);
    }

    /** Two run lists combined by one sweep over their edges: the result's runs. */
    private static OrdinalSet combine(int[] a, int[] b, int op) {
        int[] out = new int[a.length + b.length];
        int m = 0;
        long c = 0;
        int i = 0;
        int j = 0;
        int pos = 0;
        while (i < a.length || j < b.length) {
            int ea = i < a.length ? a[i] : Integer.MAX_VALUE;
            int eb = j < b.length ? b[j] : Integer.MAX_VALUE;
            int e = Math.min(ea, eb);
            boolean before = in(op, (i & 1) == 1, (j & 1) == 1);
            if (ea == e) {
                i++;
            }
            if (eb == e) {
                j++;
            }
            boolean after = in(op, (i & 1) == 1, (j & 1) == 1);
            if (!before && after) {
                pos = e;
            } else if (before && !after) {
                out[m++] = pos;
                out[m++] = e;
                c += e - pos;
            }
        }
        return m == 0 ? EMPTY : new OrdinalSet(Arrays.copyOf(out, m), (int) c);
    }

    private static boolean in(int op, boolean inA, boolean inB) {
        return op == OR ? inA || inB : op == AND ? inA && inB : inA && !inB;
    }

    public int size() {
        return runs != null ? count : bits.cardinality();
    }

    public boolean isEmpty() {
        return runs != null ? runs.length == 0 : bits.isEmpty();
    }

    /** The index (even) of the run that holds {@code o}, or -1. */
    private int runOf(int o) {
        int r = firstRunEndingAfter(o);
        return r < runs.length && runs[r] <= o ? r : -1;
    }

    /** The index (even) of the first run that ends after {@code o} ({@code runs.length} when none). */
    private int firstRunEndingAfter(int o) {
        int lo = 0;
        int hi = runs.length / 2;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (runs[2 * mid + 1] <= o) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return 2 * lo;
    }

    public boolean contains(int ordinal) {
        if (ordinal < 0) {
            return false;
        }
        return runs != null ? runOf(ordinal) >= 0 : bits.get(ordinal);
    }

    /** True when every ordinal of {@code o} is in this set. */
    public boolean containsAll(OrdinalSet o) {
        if (o.runs != null) {
            for (int i = 0; i < o.runs.length; i += 2) {
                if (!coversRange(o.runs[i], o.runs[i + 1])) {
                    return false;
                }
            }
            return true;
        }
        if (runs != null) {
            boolean[] all = {true};
            o.forEachRun((f, t) -> all[0] &= coversRange(f, t));
            return all[0];
        }
        BitSet b = (BitSet) o.bits.clone();
        b.andNot(bits);
        return b.isEmpty();
    }

    /** True when the two sets share an ordinal. */
    public boolean intersects(OrdinalSet o) {
        if (runs != null && o.runs != null) {
            int i = 0;
            int j = 0;
            while (i < runs.length && j < o.runs.length) {
                if (runs[i + 1] <= o.runs[j]) {
                    i += 2;
                } else if (o.runs[j + 1] <= runs[i]) {
                    j += 2;
                } else {
                    return true;
                }
            }
            return false;
        }
        if (runs == null && o.runs == null) {
            return bits.intersects(o.bits);
        }
        int[] r = runs != null ? runs : o.runs;
        BitSet b = runs != null ? o.bits : bits;
        for (int i = 0; i < r.length; i += 2) {
            int g = b.nextSetBit(r[i]);
            if (g < 0) {
                return false;
            }
            if (g < r[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** The ordinals between {@code from} (included) and {@code to} (excluded) in the set. */
    public int countIn(int from, int to) {
        if (to <= from) {
            return 0;
        }
        if (runs == null) {
            return bits.get(Math.max(0, from), Math.max(0, to)).cardinality();
        }
        long c = 0;
        for (int i = firstRunEndingAfter(from); i < runs.length && runs[i] < to; i += 2) {
            c += Math.min(to, runs[i + 1]) - Math.max(from, runs[i]);
        }
        return (int) c;
    }

    /**
     * True when every ordinal of the run is in the set: a binary search over runs; over a bitmap, the run's own bits only (never
     * {@code nextClearBit(from)}, which inside a long run of the set would scan to that run's end, not to {@code to}).
     */
    public boolean coversRange(int from, int to) {
        if (to <= from) {
            return true;
        }
        if (from < 0) {
            return false;
        }
        if (runs != null) {
            int r = runOf(from);
            return r >= 0 && runs[r + 1] >= to;
        }
        if (to - from <= SMALL_RANGE) {
            for (int o = from; o < to; o++) {
                if (!bits.get(o)) {
                    return false;
                }
            }
            return true;
        }
        return bits.get(from, to).cardinality() == to - from;
    }

    public void forEach(IntConsumer f) {
        if (runs != null) {
            for (int i = 0; i < runs.length; i += 2) {
                for (int o = runs[i], t = runs[i + 1]; o < t; o++) {
                    f.accept(o);
                }
            }
            return;
        }
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            f.accept(i);
        }
    }

    /** The runs of consecutive ordinals, each as {@code from, to} (to excluded), in order. */
    public void forEachRun(RunConsumer f) {
        if (runs != null) {
            for (int i = 0; i < runs.length; i += 2) {
                f.run(runs[i], runs[i + 1]);
            }
            return;
        }
        int i = bits.nextSetBit(0);
        while (i >= 0) {
            int end = bits.nextClearBit(i);
            f.run(i, end);
            i = bits.nextSetBit(end);
        }
    }

    /** The ordinals of the set in {@code [from, to)}, as runs. */
    public void forEachRunIn(int from, int to, RunConsumer f) {
        if (to <= from) {
            return;
        }
        if (runs != null) {
            for (int i = firstRunEndingAfter(from); i < runs.length && runs[i] < to; i += 2) {
                f.run(Math.max(from, runs[i]), Math.min(to, runs[i + 1]));
            }
            return;
        }
        int i = bits.nextSetBit(Math.max(0, from));
        while (i >= 0 && i < to) {
            int end = Math.min(to, bits.nextClearBit(i));
            f.run(i, end);
            i = end >= to ? -1 : bits.nextSetBit(end);
        }
    }

    public int first() {
        if (runs != null) {
            return runs.length == 0 ? -1 : runs[0];
        }
        return bits.nextSetBit(0);
    }

    /**
     * The members in {@code [from, to)}, renumbered from {@code from} (member {@code from + i} becomes {@code i}): one shard's part of a
     * set, in the shard's own ordinals (HOW_IT_FITS.md#d13-var-sharding-how-it-is-built).
     */
    public OrdinalSet slice(int from, int to) {
        if (to <= from) {
            return EMPTY;
        }
        if (runs == null) {
            return new OrdinalSet(bits.get(from, to));
        }
        int[] out = new int[runs.length];
        int[] m = {0};
        long[] c = {0};
        forEachRunIn(from, to, (a, b) -> {
            out[m[0]++] = a - from;
            out[m[0]++] = b - from;
            c[0] += b - a;
        });
        return m[0] == 0 ? EMPTY : new OrdinalSet(Arrays.copyOf(out, m[0]), (int) c[0]);
    }

    /** The members moved up by {@code offset} (a shard's ordinals placed back in the date's). */
    public OrdinalSet shifted(int offset) {
        if (offset == 0 || isEmpty()) {
            return this;
        }
        if (runs != null) {
            int[] out = runs.clone();
            for (int i = 0; i < out.length; i++) {
                out[i] += offset;
            }
            return new OrdinalSet(out, count);
        }
        BitSet b = new BitSet(bits.length() + offset);
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            b.set(i + offset);
        }
        return new OrdinalSet(b);
    }

    /** The members as the words of a bitmap (64 per word, little-endian bit order), to send to another server. */
    public long[] words() {
        if (runs == null) {
            return bits.toLongArray();
        }
        long[] w = new long[runs.length == 0 ? 0 : ((runs[runs.length - 1] - 1) >>> 6) + 1];
        forEachWord((i, v) -> w[i] = v);
        return w;
    }

    /** A set from the words of {@link #words()}. */
    public static OrdinalSet ofWords(long[] words) {
        return new OrdinalSet(BitSet.valueOf(words));
    }

    @FunctionalInterface
    private interface WordSink {
        void word(int index, long value);
    }

    /** Every word of the bitmap of the members, from word 0 to the last that is not zero (zeros between included), in order. */
    private void forEachWord(WordSink s) {
        if (runs == null) {
            long[] w = bits.toLongArray();
            for (int i = 0; i < w.length; i++) {
                s.word(i, w[i]);
            }
            return;
        }
        if (runs.length == 0) {
            return;
        }
        int w = 0;
        long val = 0;
        for (int i = 0; i < runs.length; i += 2) {
            int f = runs[i];
            int last = runs[i + 1] - 1;
            int fw = f >>> 6;
            int lw = last >>> 6;
            while (w < fw) {
                s.word(w++, val);
                val = 0;
            }
            if (fw == lw) {
                val |= (-1L << (f & 63)) & (-1L >>> (63 - (last & 63)));
            } else {
                val |= -1L << (f & 63);
                s.word(w++, val);
                while (w < lw) {
                    s.word(w++, -1L);
                }
                val = -1L >>> (63 - (last & 63));
            }
        }
        s.word(w, val);
    }

    /** A stable digest of the members (the membership hash a result's stamp carries), the same in either form. */
    public String digest() {
        long[] h = {1125899906842597L};
        forEachWord((i, w) -> h[0] = 31 * h[0] + w);
        return Long.toHexString(h[0]) + ":" + size();
    }

    @FunctionalInterface
    public interface RunConsumer {
        void run(int from, int to);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrdinalSet s)) {
            return false;
        }
        if (runs != null && s.runs != null) {
            return Arrays.equals(runs, s.runs);
        }
        if (runs == null && s.runs == null) {
            return s.bits.equals(bits);
        }
        if (size() != s.size()) {
            return false;
        }
        int[] r = runs != null ? runs : s.runs;
        BitSet b = runs != null ? s.bits : bits;
        int i = 0;
        int at = b.nextSetBit(0);
        while (at >= 0) {
            int end = b.nextClearBit(at);
            if (i >= r.length || r[i] != at || r[i + 1] != end) {
                return false;
            }
            i += 2;
            at = b.nextSetBit(end);
        }
        return i == r.length;
    }

    /** The hash {@link BitSet#hashCode()} gives the members, in either form. */
    @Override
    public int hashCode() {
        if (runs == null) {
            return bits.hashCode();
        }
        long[] h = {1234};
        forEachWord((i, w) -> h[0] ^= w * (i + 1));
        return (int) ((h[0] >> 32) ^ h[0]);
    }
}
