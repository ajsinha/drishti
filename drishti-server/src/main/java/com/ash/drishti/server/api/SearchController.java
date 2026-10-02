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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Structured search (W17) and pick lists: {@code GET /api/v1/search?q=TRD where mtm > 1m order by mtm desc limit 20},
 * {@code q=TRD MX-200000}, {@code q=TRD productType=Revolver} (see {@link SearchQuery#pick}). The caller must
 * be allowed to open the kind; the condition is evaluated on each document as the caller may see it (redacted), so
 * hidden fields cannot be probed. Follows the business date and "known at" of the request like every read.
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final StructuredSearch search;
    private final Entitlements entitlements;
    private final Mnemonics mnemonics;
    private final com.ash.drishti.engine.search.SearchPivot pivot;

    public SearchController(StructuredSearch search, Entitlements entitlements, Mnemonics mnemonics,
            com.ash.drishti.engine.search.SearchPivot pivot) {
        this.search = search;
        this.entitlements = entitlements;
        this.mnemonics = mnemonics;
        this.pivot = pivot;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam String q, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        SearchQuery query = SearchQuery.pick(q);           // TRD where …, TRD MX-200000, TRD productType=Revolver, TRD
        String kind = search.kindOf(query);
        entitlements.requireOpen(principal, kind);
        StructuredSearch.Result r = search.run(query, asOf, data -> entitlements.redact(principal, data));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("query", q);
        out.put("kind", r.kind());
        out.put("mnemonic", mnemonics.codeFor(r.kind()));
        out.put("condition", r.condition());
        out.put("orderBy", r.orderBy());
        out.put("descending", query.descending());
        out.put("limit", query.limit());
        out.put("columns", r.columns());
        out.put("labels", r.columns().stream().collect(java.util.stream.Collectors.toMap(c -> c, c -> HistoryController.label(c.substring(2)),
                (a, b) -> a, java.util.LinkedHashMap::new)));
        out.put("rows", r.rows());
        out.put("scanned", r.scanned());
        out.put("matched", r.matched());
        out.put("partial", r.partial());
        // the sources that failed or did not answer in time, and why: a partial answer says what it is missing
        out.put("failed", r.failed().entrySet().stream().map(e -> Map.of("source", e.getKey(), "reason", e.getValue())).toList());
        out.put("elapsedMs", r.elapsedMs());
        out.put("pivot", offered(r.kind()));               // the Pivot tab, when the kind's pack opts in (null otherwise)
        return out;
    }

    /** What the Pivot tab of this kind's results offers; null when its pack does not opt in (or says what cannot be read). */
    private Map<String, Object> offered(String kind) {
        try {
            return pivot.describe(kind).orElse(null);
        } catch (IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger(SearchController.class).warn("no Pivot tab for {}: {}", kind, e.getMessage());
            return null;
        }
    }

    /**
     * The same search as CSV, for spreadsheets ({@code GET /api/v1/search/csv?q=TRD productType=Revolver}): a header row
     * (kind, id, title, then the columns' labels) and one row per entity, values unformatted.
     */
    @GetMapping(path = "/csv", produces = "text/csv")
    public String csv(@RequestParam String q, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Map<String, Object> r = search(q, asOf, principal);
        @SuppressWarnings("unchecked")
        java.util.List<String> columns = (java.util.List<String>) r.get("columns");
        @SuppressWarnings("unchecked")
        Map<String, String> labels = (Map<String, String>) r.get("labels");
        StringBuilder out = new StringBuilder("kind,id,title");
        columns.forEach(c -> out.append(',').append(cell(labels.getOrDefault(c, c))));
        out.append("\r\n");
        @SuppressWarnings("unchecked")
        java.util.List<StructuredSearch.Row> rows = (java.util.List<StructuredSearch.Row>) r.get("rows");
        for (StructuredSearch.Row row : rows) {
            out.append(cell(row.ref().kind())).append(',').append(cell(row.ref().id())).append(',').append(cell(row.title()));
            columns.forEach(c -> out.append(',').append(cell(row.values().get(c))));
            out.append("\r\n");
        }
        return out.toString();
    }

    /** RFC 4180 quoting; a value that a spreadsheet would run as a formula is prefixed so it stays text. */
    private static String cell(Object v) {
        if (v == null) {
            return "";
        }
        // numbers in full (199000000, not 1.99E8), so every spreadsheet reads them as numbers
        String s = (v instanceof Double || v instanceof Float) && Double.isFinite(((Number) v).doubleValue())
                ? java.math.BigDecimal.valueOf(((Number) v).doubleValue()).stripTrailingZeros().toPlainString() : String.valueOf(v);
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    /**
     * A search on two business dates, side by side ({@code GET /api/v1/search/compare?q=TRD MX-20000001&from=2026-09-25&to=2026-09-30}):
     * the entities of the later date, each column with its value on both dates and, for numbers, the change. An entity
     * only one date holds is marked {@code added} or {@code removed}.
     */
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String q, @RequestParam String from, @RequestParam(required = false) String to,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        AsOf later = to == null || to.isBlank() ? asOf : AsOf.of(java.time.LocalDate.parse(to));
        AsOf earlier = AsOf.of(java.time.LocalDate.parse(from));
        Map<String, Object> b = search(q, later, principal);
        Map<String, Object> a = search(q, earlier, principal);
        @SuppressWarnings("unchecked")
        java.util.List<String> columns = (java.util.List<String>) b.get("columns");
        @SuppressWarnings("unchecked")
        java.util.List<StructuredSearch.Row> before = (java.util.List<StructuredSearch.Row>) a.get("rows");
        @SuppressWarnings("unchecked")
        java.util.List<StructuredSearch.Row> after = (java.util.List<StructuredSearch.Row>) b.get("rows");
        Map<com.ash.drishti.api.EntityRef, StructuredSearch.Row> old = new java.util.LinkedHashMap<>();
        before.forEach(r -> old.put(r.ref(), r));
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (StructuredSearch.Row r : after) {
            StructuredSearch.Row was = old.remove(r.ref());
            rows.add(compared(r, was, columns, was == null ? "added" : null));
        }
        old.values().forEach(r -> rows.add(compared(null, r, columns, "removed")));
        Map<String, Object> out = new java.util.LinkedHashMap<>(b);
        out.put("from", earlier.businessDate());
        out.put("to", later.businessDate());
        out.put("rows", rows);
        // either date incomplete makes the comparison incomplete (an entity a failing source holds would look added or removed)
        out.put("partial", Boolean.TRUE.equals(a.get("partial")) || Boolean.TRUE.equals(b.get("partial")));
        java.util.List<Object> failed = new java.util.ArrayList<>((java.util.List<?>) b.get("failed"));
        ((java.util.List<?>) a.get("failed")).stream().filter(f -> !failed.contains(f)).forEach(failed::add);
        out.put("failed", failed);
        return out;
    }

    private static Map<String, Object> compared(StructuredSearch.Row now, StructuredSearch.Row was, java.util.List<String> columns, String status) {
        StructuredSearch.Row any = now != null ? now : was;
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("ref", Map.of("kind", any.ref().kind(), "id", any.ref().id()));
        row.put("title", any.title());
        row.put("status", status);
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String c : columns) {
            Object x = was == null ? null : was.values().get(c);
            Object y = now == null ? null : now.values().get(c);
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("from", x);
            v.put("to", y);
            if (x instanceof Number nx && y instanceof Number ny) {
                v.put("delta", ny.doubleValue() - nx.doubleValue());
            }
            values.put(c, v);
        }
        row.put("values", values);
        return row;
    }
}
