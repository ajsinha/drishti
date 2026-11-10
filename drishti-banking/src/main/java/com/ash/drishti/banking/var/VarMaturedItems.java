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
package com.ash.drishti.banking.var;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The matured records of a date kept for the explorer's matured-items switch (VAR_GUIDE.md, "Matured items"): each record the intake left
 * out because it had matured, at the grain it came in ({@code POSITION} or {@code TRADE}), with its texts (the builder's text columns, in
 * {@code columns} order), its numbers and its USD 1-day P&amp;L vector as float32 (4 bytes a scenario, what the mapped tier keeps of every vector;
 * base64 in a mapped date's hot-file header, which brings them back when the date is opened again). Nothing else of the date reads them: the official book, the records, captures and limits never hold a matured record.
 * The switch builds a small book of them on its first use of the date ({@link VarLoader#maturedBook}).
 *
 * <p>Kept up to 1% of the date's records ({@link #MAX_SHARE}: at most about 7% of a mapped date's heap, 1% of a heap-tier date's); beyond it none are kept and {@code dropped} says why (the switch
 * then answers in words, never a partial figure).
 *
 * @param columns the builder's text columns the items' texts follow
 * @param items the matured records, in the order read
 * @param dropped why none are kept, or null
 */
record VarMaturedItems(List<String> columns, List<Item> items, String dropped) {

    /** At most this share of a date's records is kept as matured items (VAR_GUIDE.md, "Matured items": the memory). */
    static final double MAX_SHARE = 0.01;
    /** Up to this many items the share is not asked (a small date may have a few). */
    static final int ALWAYS_KEPT = 100;

    /** One matured record: its id, grain, maturity (or its status), texts, numbers and P&amp;L vector (float32, little endian, base64). */
    record Item(String id, String grain, String maturity, List<String> texts, double[] numbers, byte[] pnl) {

        /** The vector into {@code out} (its length the date's width). */
        void vector(double[] out) {
            ByteBuffer b = ByteBuffer.wrap(pnl).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < out.length; i++) {
                out[i] = b.getFloat();
            }
        }

        /** The heap this item holds, about. */
        long bytes() {
            long b = 112 + id.length() + pnl.length + 8L * numbers.length + 16L * texts.size();
            for (String t : texts) {
                b += t == null ? 0 : 40 + t.length();
            }
            return b;
        }
    }

    boolean empty() {
        return items == null || items.isEmpty();
    }

    /** The heap the items hold, about (the measure of VAR_GUIDE.md, "Matured items"). */
    long bytes() {
        return items == null ? 0 : items.stream().mapToLong(Item::bytes).sum();
    }

    /** The items read during a load. */
    static final class Collector {
        private final List<String> columns;
        private final List<Item> items = new ArrayList<>();

        Collector(List<String> columns) {
            this.columns = List.copyOf(columns);
        }

        void add(String id, String grain, String maturity, String[] texts, double[] numbers, double[] vector, int width) {
            ByteBuffer b = ByteBuffer.allocate(4 * width).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < width; i++) {
                b.putFloat((float) vector[i]);
            }
            items.add(new Item(id, grain, maturity, java.util.Arrays.asList(texts.clone()), numbers.clone(),
                    b.array()));
        }

        /** The items, or null when none were read; none kept, said in words, when they are more than 1% of the date's records. */
        VarMaturedItems build(int records) {
            if (items.isEmpty()) {
                return null;
            }
            if (items.size() > ALWAYS_KEPT && items.size() > MAX_SHARE * (records + items.size())) {
                return new VarMaturedItems(columns, List.of(), items.size() + " matured records are more than 1% of the date's records: "
                        + "not kept, so the switch cannot add them on this date");
            }
            return new VarMaturedItems(columns, List.copyOf(items), null);
        }
    }
}
