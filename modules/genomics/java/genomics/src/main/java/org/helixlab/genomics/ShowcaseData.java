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
package org.helixlab.genomics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The showcase's tables (docs/architecture/GENOMICS_SHOWCASE.md section 6): the fourteen kinds of the packs {@code genomics} and
 * {@code protein}, read through the kernel's bindings as rows (never a connection), kept as one immutable snapshot and replaced whole. The
 * first call reads them; a read that found a kind empty is tried again after a minute (a lake still loading), and the answers cached by
 * their query are dropped with the snapshot they came from. Nothing is computed when the rows are read: the boards compute per call.
 */
public final class ShowcaseData {

    /** Reads every row of a kind to the sink; false when the kind is not bound here. */
    @FunctionalInterface
    public interface Source {
        boolean rows(String kind, Consumer<Map<String, Object>> sink);
    }

    /** The kinds, by pack: the names the data packs declare (the design's section 6). */
    public static final List<String> GENOMICS_KINDS = List.of("gene", "sample", "variant", "variant_call", "association", "cnv_segment", "pathway_gene");
    public static final List<String> PROTEIN_KINDS = List.of("protein", "residue", "residue_contact", "dms_score", "design", "design_mutation", "design_round");

    /** One read of every kind: the rows of each, in the order read. */
    public record Tables(Map<String, List<Map<String, Object>>> rows, long version, long readAt) {

        static final Tables EMPTY = new Tables(Map.of(), 0, 0);

        /** The rows of a kind (empty when it had none). */
        public List<Map<String, Object>> of(String kind) {
            return rows.getOrDefault(kind, List.of());
        }

        /** The kinds of a list that had no rows. */
        public List<String> missing(List<String> kinds) {
            return kinds.stream().filter(k -> of(k).isEmpty()).toList();
        }
    }

    private static final long RETRY_MS = 60_000;
    private static final int CACHE_MAX = 400;

    private volatile Source source;
    private volatile Tables tables = Tables.EMPTY;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    private long version;

    /** Where the rows come from: the kernel's bindings once the module has started, a fixture in a test. */
    public void attach(Source s) {
        this.source = s;
        this.tables = Tables.EMPTY;
        cache.clear();
    }

    /** Whether a source is attached. */
    public boolean attached() {
        return source != null;
    }

    /** The tables now: read on first use, read again when a kind was empty a minute ago, or when asked (refresh). */
    public Tables now(boolean refresh) {
        Tables t = tables;
        boolean stale = t.version() == 0 || refresh
                || (!t.missing(GENOMICS_KINDS).isEmpty() || !t.missing(PROTEIN_KINDS).isEmpty()) && System.currentTimeMillis() - t.readAt() > RETRY_MS;
        if (stale && source != null) {
            synchronized (this) {
                if (tables == t) {
                    tables = read(source);
                    cache.clear();
                }
                t = tables;
            }
        }
        return t;
    }

    /** The tables now (read on first use). */
    public Tables now() {
        return now(false);
    }

    /** An answer by its query, made once per snapshot (the cache is dropped with the snapshot; at most a few hundred answers kept). */
    @SuppressWarnings("unchecked")
    public <T> T cached(String query, Supplier<T> make) {
        long v = now().version();
        String key = v + "|" + query;
        Object hit = cache.get(key);
        if (hit != null) {
            return (T) hit;
        }
        T made = make.get();
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        if (made != null) {
            cache.put(key, made);
        }
        return made;
    }

    /** Forgets everything (the module stopping). */
    public void clear() {
        tables = Tables.EMPTY;
        cache.clear();
    }

    private synchronized Tables read(Source s) {
        Map<String, List<Map<String, Object>>> all = new LinkedHashMap<>();
        List<String> kinds = new ArrayList<>(GENOMICS_KINDS);
        kinds.addAll(PROTEIN_KINDS);
        for (String k : kinds) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try {
                s.rows(k, rows::add);
            } catch (RuntimeException e) {
                rows.clear();                    // a kind that does not read is a kind with no rows: the answers say which is missing
            }
            all.put(k, Collections.unmodifiableList(rows));
        }
        return new Tables(Collections.unmodifiableMap(all), ++version, System.currentTimeMillis());
    }

    // ---- reading a row's values: the lake gives numbers as Long, Double or text ----------------------------------------------------

    /** A value as text ("" for none). */
    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** A value as a number, or NaN. */
    public static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A value as a whole number, or the fallback. */
    public static int integer(Object v, int fallback) {
        double d = num(v);
        return Double.isNaN(d) ? fallback : (int) Math.round(d);
    }

    /** A value as yes or no: true, "true", "yes", "y" or a number above 0. */
    public static boolean yes(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = str(v).trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("y") || (!Double.isNaN(num(v)) && num(v) > 0);
    }

    /** A number rounded to the decimals given (for answers people read). */
    public static double round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
