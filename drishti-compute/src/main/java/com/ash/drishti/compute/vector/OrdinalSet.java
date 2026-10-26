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

import java.util.BitSet;
import java.util.function.IntConsumer;

/**
 * A set of row ordinals (VAR_GUIDE.md section 25): what a scope resolves to on one business date. Union, difference and
 * intersection are exact set operations, so two overlapping portfolios combined count a position once; a node of the hierarchy is
 * one run of ordinals. One bit per row of the date (2 million rows: 250 KB), immutable: every operation returns a new set.
 *
 * <p>A plain bitmap (java.util.BitSet) rather than a compressed one (Roaring): the design's open question 9 asks whether to add that
 * library; a bitmap of the date's rows is small enough at bank scale and needs no new dependency.
 */
public final class OrdinalSet {

    private static final OrdinalSet EMPTY = new OrdinalSet(new BitSet(0));
    private final BitSet bits;

    private OrdinalSet(BitSet bits) {
        this.bits = bits;
    }

    public static OrdinalSet empty() {
        return EMPTY;
    }

    /** The run {@code from} (included) to {@code to} (excluded). */
    public static OrdinalSet range(int from, int to) {
        BitSet b = new BitSet(Math.max(0, to));
        if (to > from) {
            b.set(from, to);
        }
        return new OrdinalSet(b);
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

    public OrdinalSet or(OrdinalSet o) {
        BitSet b = (BitSet) bits.clone();
        b.or(o.bits);
        return new OrdinalSet(b);
    }

    public OrdinalSet andNot(OrdinalSet o) {
        BitSet b = (BitSet) bits.clone();
        b.andNot(o.bits);
        return new OrdinalSet(b);
    }

    public OrdinalSet and(OrdinalSet o) {
        BitSet b = (BitSet) bits.clone();
        b.and(o.bits);
        return new OrdinalSet(b);
    }

    public int size() {
        return bits.cardinality();
    }

    public boolean isEmpty() {
        return bits.isEmpty();
    }

    public boolean contains(int ordinal) {
        return ordinal >= 0 && bits.get(ordinal);
    }

    /** True when every ordinal of {@code o} is in this set. */
    public boolean containsAll(OrdinalSet o) {
        BitSet b = (BitSet) o.bits.clone();
        b.andNot(bits);
        return b.isEmpty();
    }

    /** True when the two sets share an ordinal. */
    public boolean intersects(OrdinalSet o) {
        return bits.intersects(o.bits);
    }

    /** The ordinals between {@code from} (included) and {@code to} (excluded) in the set. */
    public int countIn(int from, int to) {
        return to <= from ? 0 : bits.get(from, to).cardinality();
    }

    /** True when every ordinal of the run is in the set. */
    public boolean coversRange(int from, int to) {
        return to <= from || bits.nextClearBit(from) >= to;
    }

    public void forEach(IntConsumer f) {
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            f.accept(i);
        }
    }

    /** The runs of consecutive ordinals, each as {@code from, to} (to excluded), in order. */
    public void forEachRun(RunConsumer f) {
        int i = bits.nextSetBit(0);
        while (i >= 0) {
            int end = bits.nextClearBit(i);
            f.run(i, end);
            i = bits.nextSetBit(end);
        }
    }

    /** The ordinals of the set in {@code [from, to)}, as runs. */
    public void forEachRunIn(int from, int to, RunConsumer f) {
        int i = bits.nextSetBit(from);
        while (i >= 0 && i < to) {
            int end = Math.min(to, bits.nextClearBit(i));
            f.run(i, end);
            i = bits.nextSetBit(end);
        }
    }

    public int first() {
        return bits.nextSetBit(0);
    }

    /** A stable digest of the members (the membership hash a result's stamp carries). */
    public String digest() {
        long h = 1125899906842597L;
        for (long w : bits.toLongArray()) {
            h = 31 * h + w;
        }
        return Long.toHexString(h) + ":" + size();
    }

    @FunctionalInterface
    public interface RunConsumer {
        void run(int from, int to);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OrdinalSet s && s.bits.equals(bits);
    }

    @Override
    public int hashCode() {
        return bits.hashCode();
    }
}
