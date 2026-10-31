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

import java.util.BitSet;
import java.util.Locale;

/**
 * An id to its index, compactly (2.9.7, RESOURCES_AND_SCALE.md section 5.1 (b), D2): the indexes sorted by their id, and a binary search.
 * It replaces a {@code HashMap<String, Integer>} per trade and position (an entry object, a table slot and a boxed {@code Integer}: about
 * 56 bytes an id) with 4 bytes an id; a lookup is about 20 string compares instead of one hash probe (well under a microsecond warm).
 *
 * <p>Two kinds: exact (an id as written; the same id twice: the later index, as the map's {@code put} did) and upper-case (the id equal
 * ignoring case: {@code toUpperCase(Locale.ROOT)}; two ids equal ignoring case answer -1, as the map's merge did). Skipped indexes (a
 * cancelled trade) are in neither.
 */
final class IdIndex {

    private final String[] ids;
    private final int[] order;
    private final boolean upper;
    /** For the upper-case index: the positions in {@link #order} whose upper-case id is shared (ambiguous: -1). */
    private final BitSet ambiguous;

    private IdIndex(String[] ids, int[] order, boolean upper, BitSet ambiguous) {
        this.ids = ids;
        this.order = order;
        this.upper = upper;
        this.ambiguous = ambiguous;
    }

    /** The exact index of {@code ids} ({@code skip}: indexes left out, or null). */
    static IdIndex exact(String[] ids, BitSet skip) {
        int[] order = kept(ids.length, skip);
        sort(order, ids);
        // the same id twice: keep the later index only (a map's put)
        int n = 0;
        for (int i = 0; i < order.length; i++) {
            if (n > 0 && ids[order[n - 1]].equals(ids[order[i]])) {
                order[n - 1] = Math.max(order[n - 1], order[i]);
            } else {
                order[n++] = order[i];
            }
        }
        return new IdIndex(ids, n == order.length ? order : java.util.Arrays.copyOf(order, n), false, null);
    }

    /** The upper-case index of {@code ids}: two ids equal ignoring case answer -1. */
    static IdIndex upper(String[] ids, BitSet skip) {
        int[] order = kept(ids.length, skip);
        String[] up = new String[ids.length];                           // transient: the keys while sorting
        for (int i : order) {
            up[i] = ids[i].toUpperCase(Locale.ROOT);
        }
        sort(order, up);
        BitSet amb = new BitSet();
        int n = 0;
        for (int i = 0; i < order.length; i++) {
            if (n > 0 && up[order[n - 1]].equals(up[order[i]])) {
                amb.set(n - 1);
            } else {
                order[n++] = order[i];
            }
        }
        return new IdIndex(ids, n == order.length ? order : java.util.Arrays.copyOf(order, n), true, amb);
    }

    private static int[] kept(int n, BitSet skip) {
        int count = skip == null ? n : n - skip.cardinality();
        int[] out = new int[count];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (skip == null || !skip.get(i)) {
                out[k++] = i;
            }
        }
        return out;
    }

    /** The index of {@code id}, or -1. */
    int get(String id) {
        String key = upper ? id.toUpperCase(Locale.ROOT) : id;
        int lo = 0;
        int hi = order.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            String at = ids[order[mid]];
            int c = (upper ? at.toUpperCase(Locale.ROOT) : at).compareTo(key);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return upper && ambiguous.get(mid) ? -1 : order[mid];
            }
        }
        return -1;
    }

    /** The heap this index holds (its order; the ids are the book's). */
    long bytes() {
        return 16L + 4L * order.length + (ambiguous == null ? 0 : ambiguous.size() / 8);
    }

    // ------------------------------------------------------------------------------------------------------------- an int sort by key

    /** Sorts {@code a} (indexes into {@code keys}) by their keys, stably (a merge sort on primitives: no boxing). */
    static void sort(int[] a, String[] keys) {
        if (a.length < 2) {
            return;
        }
        int[] tmp = new int[a.length];
        mergeSort(a, tmp, 0, a.length, keys);
    }

    private static void mergeSort(int[] a, int[] tmp, int from, int to, String[] keys) {
        if (to - from <= 24) {
            for (int i = from + 1; i < to; i++) {                       // insertion sort for short runs
                int x = a[i];
                int j = i - 1;
                while (j >= from && keys[a[j]].compareTo(keys[x]) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = x;
            }
            return;
        }
        int mid = (from + to) >>> 1;
        mergeSort(a, tmp, from, mid, keys);
        mergeSort(a, tmp, mid, to, keys);
        if (keys[a[mid - 1]].compareTo(keys[a[mid]]) <= 0) {
            return;                                                     // already in order
        }
        System.arraycopy(a, from, tmp, from, to - from);
        int i = from;
        int j = mid;
        for (int k = from; k < to; k++) {
            if (j >= to || i < mid && keys[tmp[i]].compareTo(keys[tmp[j]]) <= 0) {
                a[k] = tmp[i++];
            } else {
                a[k] = tmp[j++];
            }
        }
    }
}
