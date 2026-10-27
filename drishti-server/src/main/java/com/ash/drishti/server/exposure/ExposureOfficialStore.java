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
package com.ash.drishti.server.exposure;

import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the exposure engine keeps, in the content store (so it follows the cluster and every version is kept):
 *
 * <ul>
 *   <li>{@code exposure/official/<date>.json}: the date's official record (EXPOSURE_GUIDE.md section 20): the result version, the inputs' versions
 *       and the run, and per netting set, counterparty, group and hierarchy node its figures and profile. A restated date writes a new
 *       version (the store keeps the old one).</li>
 *   <li>{@code exposure/official/<date>-trades-v<n>.jsonl}: every trade's EE contribution per primary date (the audit of who drove the
 *       exposure; retention {@code trade-contributions-years}), JSON Lines since 2.9.4: a header line {@code {"row": "header", businessDate,
 *       runId, tenors}}, then one {@code {"row": "trade", trade, nettingSet, ee}} a trade. Records written before 2.9.4 are
 *       {@code -trades-v<n>.json}, one document, and are read as they are (the store is append-only: they are never rewritten).</li>
 *   <li>{@code exposure/captures/<owner>/<CAP-id>.json}: a person's capture of a scope's exposure (never the trade paths), immutable.</li>
 * </ul>
 */
final class ExposureOfficialStore {

    static final String OWNER = "drishti";
    private static final Pattern INDEX = Pattern.compile("exposure/official/(\\d{4}-\\d{2}-\\d{2})\\.json");
    private final ContentDocs docs;
    private final Map<LocalDate, Map<String, Object>> indexes = new ConcurrentHashMap<>();
    private volatile NavigableSet<LocalDate> dates;

    ExposureOfficialStore(ContentStore store) {
        this.docs = new ContentDocs(store);
    }

    ContentDocs docs() {
        return docs;
    }

    static String indexKey(LocalDate d) {
        return "exposure/official/" + d + ".json";
    }

    static String tradesKey(LocalDate d, long version) {
        return "exposure/official/" + d + "-trades-v" + version + ".jsonl";
    }

    NavigableSet<LocalDate> dates() {
        NavigableSet<LocalDate> d = dates;
        if (d == null) {
            TreeSet<LocalDate> out = new TreeSet<>();
            for (String key : docs.store().list("exposure/official/")) {
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

    void putIndex(LocalDate date, Map<String, Object> index) {
        docs.putLatest(indexKey(date), index);
        indexes.put(date, index);
        dates = null;
    }

    void putTrades(LocalDate date, long version, Map<String, Object> trades) {
        docs.store().putLatest(tradesKey(date, version), tradeLines(trades));
    }

    /** The trade contributions of a key: the JSON Lines of 2.9.4 on, or the one JSON document written before. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> trades(String key) {
        if (!key.endsWith(".jsonl")) {
            return docs.value(key, Map.class).map(x -> (Map<String, Object>) x);
        }
        return docs.store().get(key).map(item -> fromTradeLines(item.text()));
    }

    /** {@code {businessDate, runId, tenors, trades: {id: {nettingSet, ee}}}} as JSON Lines: a header, then a trade a line. */
    @SuppressWarnings("unchecked")
    static byte[] tradeLines(Map<String, Object> trades) {
        StringBuilder b = new StringBuilder();
        try {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("row", "header");
            trades.forEach((k, v) -> {
                if (!"trades".equals(k)) {
                    head.put(k, v);
                }
            });
            b.append(ContentDocs.JSON.writeValueAsString(head)).append('\n');
            Map<String, Object> rows = trades.get("trades") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            for (Map.Entry<String, Object> e : rows.entrySet()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("row", "trade");
                line.put("trade", e.getKey());
                if (e.getValue() instanceof Map<?, ?> v) {
                    v.forEach((k, x) -> line.put(String.valueOf(k), x));
                }
                b.append(ContentDocs.JSON.writeValueAsString(line)).append('\n');
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the trade contributions could not be written: " + e.getOriginalMessage(), e);
        }
        return b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The document {@link #tradeLines} came from. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fromTradeLines(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> rows = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> m = ContentDocs.decode(line.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.class);
            Object kind = m.remove("row");
            if ("header".equals(kind)) {
                out.putAll(m);
            } else {
                rows.put(String.valueOf(m.remove("trade")), m);
            }
        }
        out.put("trades", rows);
        return out;
    }

    /** Removes what is beyond its retention (statistics, trade contributions), counted back from the newest official date. */
    int prune(ExposureProperties.Retention r) {
        NavigableSet<LocalDate> ds = dates();
        if (ds.isEmpty()) {
            return 0;
        }
        LocalDate newest = ds.last();
        int removed = 0;
        for (String key : docs.store().list("exposure/official/")) {
            Matcher m = Pattern.compile("exposure/official/(\\d{4}-\\d{2}-\\d{2})(-trades-v\\d+)?\\.jsonl?").matcher(key);
            if (!m.matches()) {
                continue;
            }
            LocalDate d = LocalDate.parse(m.group(1));
            boolean trades = m.group(2) != null;
            boolean keep = trades ? ExposureRetention.keepsTradeContributions(r, d, newest) : ExposureRetention.keepsStatistics(r, d, newest);
            if (!keep && docs.store().get(key).map(item -> docs.store().delete(key, item.version())).orElse(false)) {
                removed++;
                indexes.remove(d);
            }
        }
        if (removed > 0) {
            dates = null;
        }
        return removed;
    }

    // ------------------------------------------------------------------------------------------------------------------- captures

    private static String owner(String user) {
        return ContentDocs.name(user);
    }

    /** Writes a capture once; its id is {@code XCAP-<yyyymmdd>-<n>}. */
    String capture(Principal p, Map<String, Object> capture, String businessDate) {
        long n = ContentDocs.next(docs.store(), "exposure/captures-seq");
        String id = "XCAP-" + businessDate.replace("-", "") + "-" + n;
        capture.put("id", id);
        if (!docs.create("exposure/captures/" + owner(p.user()) + "/" + id + ".json", capture)) {
            throw new IllegalStateException("a capture " + id + " exists already");
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> capture(Principal p, String id) {
        for (String key : docs.store().list("exposure/captures/")) {
            if (key.endsWith("/" + id + ".json")) {
                return docs.value(key, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m));
            }
        }
        return Optional.empty();
    }

    private static boolean readable(Principal p, Map<String, Object> m) {
        if (p.user().equals(m.get("owner"))) {
            return true;
        }
        if (m.get("sharedWith") instanceof List<?> l) {
            for (Object o : l) {
                String who = String.valueOf(o);
                if (who.equals(p.user()) || p.roles() != null && p.roles().contains(who.replaceFirst("^role:", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> captures(Principal p, String canonical) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : docs.store().list("exposure/captures/")) {
            docs.value(key, Map.class).map(m -> (Map<String, Object>) m).filter(m -> readable(p, m))
                    .filter(m -> canonical == null || canonical.equals(m.get("canonical"))).ifPresent(m -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        for (String f : List.of("id", "scope", "canonical", "label", "businessDate", "capturedAt", "owner", "note", "confidence", "epe",
                                "peakPfe")) {
                            s.put(f, m.get(f));
                        }
                        out.add(s);
                    });
        }
        out.sort((a, b) -> String.valueOf(b.get("capturedAt")).compareTo(String.valueOf(a.get("capturedAt"))));
        return out;
    }
}
