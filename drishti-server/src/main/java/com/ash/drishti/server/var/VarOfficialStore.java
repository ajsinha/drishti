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
package com.ash.drishti.server.var;

import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the official end-of-day captures live (VAR.md section 9.6), in the content store beside the people's captures, so they follow
 * the cluster and every version is kept:
 *
 * <ul>
 *   <li>{@code var/official/<date>.json}: the date's index, the record the history reads: the result version, the inputs' versions and
 *       batch ids, and per node of every hierarchy its figures (VaR, ES, hypothetical P&amp;L, limit, counts, backtest) and its capture id.
 *       A restated date writes a new version of it (the store keeps the old one); the captures it names are never changed.</li>
 *   <li>{@code var/official/<date>-membership-v<n>.json}: the date's positions and their trades and each node's books as the rules
 *       derived them, so every official figure can be recomputed from its inputs.</li>
 *   <li>{@code var/captures/official/<CAP-id>.json}: one capture per node, {@code official: true}, owner {@code drishti}.</li>
 * </ul>
 *
 * The indexes are read once and kept in memory (they change only when a date is captured here).
 */
final class VarOfficialStore {

    /** A node's official figures over time, oldest first. */
    record NodeHistory(LocalDate[] dates, double[] var, double[] es, double[] hypo, double[] limit) {}

    static final String OWNER = "drishti";
    static final String FOLDER = "var/captures/official/";
    private static final Pattern INDEX = Pattern.compile("var/official/(\\d{4}-\\d{2}-\\d{2})\\.json");
    private final ContentDocs docs;
    private final Map<LocalDate, Map<String, Object>> indexes = new ConcurrentHashMap<>();
    private volatile NavigableSet<LocalDate> dates;

    VarOfficialStore(ContentStore store) {
        this.docs = new ContentDocs(store);
    }

    ContentDocs docs() {
        return docs;
    }

    static String indexKey(LocalDate d) {
        return "var/official/" + d + ".json";
    }

    static String membershipKey(LocalDate d, long version) {
        return "var/official/" + d + "-membership-v" + version + ".json";
    }

    /** The dates that have an official record, oldest first. */
    NavigableSet<LocalDate> dates() {
        NavigableSet<LocalDate> d = dates;
        if (d == null) {
            TreeSet<LocalDate> out = new TreeSet<>();
            for (String key : docs.store().list("var/official/")) {
                Matcher m = INDEX.matcher(key);
                if (m.matches()) {
                    out.add(LocalDate.parse(m.group(1)));
                }
            }
            d = java.util.Collections.unmodifiableNavigableSet(out);
            dates = d;
        }
        return d;
    }

    /** A date's official record (the latest result version), if any. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> index(LocalDate date) {
        Map<String, Object> m = indexes.get(date);
        if (m == null) {
            m = docs.value(indexKey(date), Map.class).map(x -> (Map<String, Object>) x).orElse(null);
            if (m == null) {
                return Optional.empty();
            }
            indexes.put(date, m);
        }
        return Optional.of(m);
    }

    /** Writes a date's record (after its captures and membership): the history reads it from now on. */
    void putIndex(LocalDate date, Map<String, Object> index) {
        docs.putLatest(indexKey(date), index);
        indexes.put(date, index);
        dates = null;
    }

    void putMembership(LocalDate date, long version, Map<String, Object> membership) {
        docs.putLatest(membershipKey(date, version), membership);
    }

    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> membership(String key) {
        return docs.value(key, Map.class).map(x -> (Map<String, Object>) x);
    }

    /** Writes one official capture once (an existing id is refused by the store). */
    boolean putCapture(String id, Map<String, Object> capture) {
        return docs.create(FOLDER + id + ".json", capture);
    }

    /**
     * A node's official figures on the dates up to {@code upTo}, at most {@code days} of them, oldest first; empty arrays when none.
     */
    @SuppressWarnings("unchecked")
    NodeHistory history(String node, LocalDate upTo, int days) {
        List<LocalDate> ds = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        for (LocalDate d : dates().headSet(upTo, true).descendingSet()) {
            if (ds.size() >= days) {
                break;
            }
            Object n = index(d).map(m -> m.get("nodes")).orElse(null);
            if (n instanceof Map<?, ?> nodes && nodes.get(node) instanceof Map<?, ?> f) {
                ds.add(0, d);
                rows.add(0, new double[] {num(f.get("var")), num(f.get("es")), num(f.get("hypoPnl")), num(f.get("limit"))});
            }
        }
        int n = ds.size();
        NodeHistory h = new NodeHistory(ds.toArray(new LocalDate[0]), new double[n], new double[n], new double[n], new double[n]);
        for (int i = 0; i < n; i++) {
            h.var()[i] = rows.get(i)[0];
            h.es()[i] = rows.get(i)[1];
            h.hypo()[i] = rows.get(i)[2];
            h.limit()[i] = rows.get(i)[3];
        }
        return h;
    }

    /** The capture ids of a node over its official dates, newest first (the captures list of a node scope). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> captures(String node) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d : dates().descendingSet()) {
            index(d).ifPresent(m -> {
                if (m.get("nodes") instanceof Map<?, ?> nodes && nodes.get(node) instanceof Map<?, ?> f) {
                    Map<String, Object> s = new java.util.LinkedHashMap<>();
                    s.put("id", f.get("captureId"));
                    s.put("scope", "NODE:" + node);
                    s.put("canonical", "NODE:" + node);
                    s.put("label", f.get("name"));
                    s.put("businessDate", d.toString());
                    s.put("capturedAt", m.get("computedAt"));
                    s.put("owner", OWNER);
                    s.put("note", "official end of day, result version " + m.get("resultVersion"));
                    s.put("var", f.get("var"));
                    s.put("es", f.get("es"));
                    s.put("official", true);
                    out.add(s);
                }
            });
        }
        return out;
    }

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }
}
