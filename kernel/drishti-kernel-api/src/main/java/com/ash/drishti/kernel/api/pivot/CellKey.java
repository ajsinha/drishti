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
package com.ash.drishti.kernel.api.pivot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A cell's coordinates (HOW_IT_FITS.md section A26.2): dimension id to value; a dimension absent is "all" (a total along it). It names a cell
 * without listing its members, so a billion-row table and a set of positions are addressed the same way. Ordered by dimension id, so two
 * keys of the same coordinates are equal however they were made, and keys sort in one fixed order (the merge order).
 *
 * @param coords dimension id to value, sorted
 */
public record CellKey(SortedMap<String, String> coords) implements Comparable<CellKey> {

    /** The value of the Others member of a level (top N): its own members, never total minus the top N. */
    public static final String OTHERS = "\u0000Others";
    /** A missing value of a dimension, as a coordinate. */
    public static final String BLANK = "(blank)";

    public static final CellKey GRAND = new CellKey(new TreeMap<>());

    public CellKey {
        coords = Collections.unmodifiableSortedMap(new TreeMap<>(coords));
    }

    public static CellKey of(Map<String, String> coords) {
        return new CellKey(new TreeMap<>(coords));
    }

    /** The key with one more coordinate. */
    public CellKey with(String dim, String value) {
        TreeMap<String, String> m = new TreeMap<>(coords);
        m.put(dim, value);
        return new CellKey(m);
    }

    /** The canonical text: {@code DESK=RATES|month=2026-10} ({@code *} for the grand total). */
    public String canonical() {
        if (coords.isEmpty()) {
            return "*";
        }
        StringBuilder b = new StringBuilder();
        coords.forEach((k, v) -> b.append(b.isEmpty() ? "" : "|").append(k).append('=').append(v));
        return b.toString();
    }

    /** A short stable id ({@code c:} and 12 hex characters of the canonical text's SHA-256). */
    public String id() {
        return "c:" + hash(canonical()).substring(0, 12);
    }

    public static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int compareTo(CellKey o) {
        return canonical().compareTo(o.canonical());
    }

    @Override
    public String toString() {
        return canonical();
    }
}
