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
 * Where the official end-of-day captures live (VAR_GUIDE.md section 19), in the content store beside the people's captures, so they follow
 * the cluster and every version is kept:
 *
 * <ul>
 *   <li>{@code var/official/<date>.json}: the date's index, the record the history reads: the result version, the inputs' versions and
 *       batch ids, and per node of every hierarchy its figures (VaR, ES, hypothetical P&amp;L, limit, counts, backtest) and its capture id.
 *       A restated date writes a new version of it (the store keeps the old one); the captures it names are never changed.</li>
 *   <li>{@code var/official/<date>-membership-v<n>.json}: the date's positions and their trades and each node's books as the rules
 *       derived them, so every official figure can be recomputed from its inputs.</li>
 *   <li>{@code var/captures/official/<CAP-id>.json}: one capture per node, {@code official: true}, owner {@code drishti}.</li>
 *   <li>{@code var/official/<CCY>/<date>.json} and {@code var/official/<CCY>/<date>-membership-v<n>.json}: the same in another reporting
 *       currency of {@code drishti.var.official.currencies} (2.9.4); USD keeps the paths above.</li>
 * </ul>
 *
 * The indexes are read once and kept in memory (they change only when a date is captured here).
 */
final class VarOfficialStore {

    /** A node's official figures over time, oldest first. */
    record NodeHistory(LocalDate[] dates, double[] var, double[] es, double[] hypo, double[] limit) {}

    static final String OWNER = "drishti";
    static final String FOLDER = "var/captures/official/";
    private final ContentDocs docs;
    private final Map<String, Map<String, Object>> indexes = new ConcurrentHashMap<>();
    private final Map<String, NavigableSet<LocalDate>> dates = new ConcurrentHashMap<>();

    VarOfficialStore(ContentStore store) {
        this.docs = new ContentDocs(store);
    }

    ContentDocs docs() {
        return docs;
    }

    /** The folder of a currency's records: {@code var/official/} for USD, {@code var/official/<CCY>/} for another. */
    private static String folder(String currency) {
        return currency == null || VarDate.USD.equals(currency) ? "var/official/" : "var/official/" + currency + "/";
    }

    static String indexKey(LocalDate d) {
        return indexKey(d, VarDate.USD);
    }

    static String indexKey(LocalDate d, String currency) {
        return folder(currency) + d + ".json";
    }

    static String membershipKey(LocalDate d, long version) {
        return membershipKey(d, version, VarDate.USD);
    }

    static String membershipKey(LocalDate d, long version, String currency) {
        return folder(currency) + d + "-membership-v" + version + ".json";
    }

    /** The dates that have an official record, oldest first. */
    NavigableSet<LocalDate> dates() {
        return dates(VarDate.USD);
    }

    /** The dates that have an official record in a currency, oldest first. */
    NavigableSet<LocalDate> dates(String currency) {
        String c = currency == null ? VarDate.USD : currency;
        return dates.computeIfAbsent(c, x -> {
            TreeSet<LocalDate> out = new TreeSet<>();
            Pattern p = Pattern.compile(Pattern.quote(folder(c)) + "(\\d{4}-\\d{2}-\\d{2})\\.json");
            for (String key : docs.store().list(folder(c))) {
                Matcher m = p.matcher(key);
                if (m.matches()) {
                    out.add(LocalDate.parse(m.group(1)));
                }
            }
            return java.util.Collections.unmodifiableNavigableSet(out);
        });
    }

    /** A date's official record (the latest result version), if any. */
    Optional<Map<String, Object>> index(LocalDate date) {
        return index(date, VarDate.USD);
    }

    /** A date's official record in a currency (the latest result version), if any. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> index(LocalDate date, String currency) {
        String key = indexKey(date, currency);
        Map<String, Object> m = indexes.get(key);
        if (m == null) {
            m = docs.value(key, Map.class).map(x -> (Map<String, Object>) x).orElse(null);
            if (m == null) {
                return Optional.empty();
            }
            indexes.put(key, m);
        }
        return Optional.of(m);
    }

    /** Writes a date's record (after its captures and membership): the history reads it from now on. */
    void putIndex(LocalDate date, Map<String, Object> index) {
        putIndex(date, index, VarDate.USD);
    }

    void putIndex(LocalDate date, Map<String, Object> index, String currency) {
        String key = indexKey(date, currency);
        docs.putLatest(key, index);
        indexes.put(key, index);
        dates.remove(currency == null ? VarDate.USD : currency);
    }

    void putMembership(LocalDate date, long version, Map<String, Object> membership, String currency) {
        docs.putLatest(membershipKey(date, version, currency), membership);
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
    NodeHistory history(String node, LocalDate upTo, int days) {
        return history(node, upTo, days, VarDate.USD);
    }

    /** The same in a reporting currency: its own official records (none when the currency is not captured officially). */
    NodeHistory history(String node, LocalDate upTo, int days, String currency) {
        List<LocalDate> ds = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        for (LocalDate d : dates(currency).headSet(upTo, true).descendingSet()) {
            if (ds.size() >= days) {
                break;
            }
            Object n = index(d, currency).map(m -> m.get("nodes")).orElse(null);
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
