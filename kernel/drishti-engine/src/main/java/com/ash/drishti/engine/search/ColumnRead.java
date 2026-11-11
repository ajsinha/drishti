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
package com.ash.drishti.engine.search;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRouter;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Whole columns of a kind on one business date: every entity's value of a few promoted fields, as the source that keeps
 * them as columns holds them (a Delta table laid out by its pack). What Calc's {@code drishti.columns()} reads, for
 * aggregates over a day without reading a document. Fields the caller's role may not see come back masked, exactly as
 * in a search ({@link StructuredSearch#masks}). Stateless and thread-safe.
 */
public final class ColumnRead {

    /**
     * @param kind the kind read
     * @param businessDate the date the values are for (null for undated data)
     * @param paths the fields, as asked (no {@code $.})
     * @param ids the entities' ids, row by row
     * @param values per field, one value per row (a number, a text, or null)
     * @param total entities the source holds for the date
     * @param truncated true when only the first {@code limit} rows are returned
     * @param masked the fields the caller's role sees masked
     * @param incomplete null when the source read the whole day; else why some entities are missing
     */
    public record Columns(String kind, LocalDate businessDate, List<String> paths, List<String> ids, Map<String, List<Object>> values,
            int total, boolean truncated, List<String> masked, String incomplete) {}

    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final int MAX_PATHS = 32;

    private final SourceRouter router;
    private final StructuredSearch search;
    private final Duration budget;

    public ColumnRead(SourceRouter router, StructuredSearch search, Duration budget) {
        this.router = router;
        this.search = search;
        this.budget = budget;
    }

    /** The kind a mnemonic or kind names ({@code TRD} or {@code trade}). */
    public String kindOf(String head) {
        return search.kindOf(new SearchQuery(head, null, null, false, 1, List.of()));
    }

    /** The fields the kind's source keeps as columns (empty when none does). */
    public Set<String> available(String kind) {
        return router.columnar(kind);
    }

    /**
     * Reads the columns.
     *
     * @param paths document paths ({@code mtm}, {@code risk.dv01}; a leading {@code $.} is allowed)
     * @param limit at most this many rows are returned
     * @throws DrishtiException {@code DRS-5001} for a path the source does not keep as a column (the message lists those it
     *     does), {@code DRS-1003} when the source does not answer within the budget
     */
    public Columns read(String kind, List<String> paths, AsOf asOf, UnaryOperator<DataNode> redact, int limit) {
        List<String> plain = normalise(paths);
        Set<String> kept = router.columnar(kind);
        List<String> missing = plain.stream().filter(p -> !kept.contains(p)).toList();
        if (kept.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no source of " + kind + " keeps its fields as columns here: use drishti.search()");
        }
        if (!missing.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "not kept as columns for " + kind + ": " + missing + "; these are: "
                    + kept.stream().sorted().toList());
        }
        com.ash.drishti.engine.source.SourceFailures failures = new com.ash.drishti.engine.source.SourceFailures();
        Optional<ColumnSet> got = router.columns(kind, plain, asOf, budget, failures);
        if (got.isEmpty()) {
            String why = failures.asMap().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).reduce((a, b) -> a + "; " + b).orElse(null);
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, why != null ? "the source of " + kind + " could not answer with columns: " + why
                    : "the source of " + kind + " did not answer with columns within " + budget.toSeconds() + " s (or does not hold that date)");
        }
        ColumnSet c = got.get();
        Map<String, Object> masked = StructuredSearch.masks(plain, redact);
        int rows = Math.min(c.size(), Math.max(0, limit));
        int[] order = byId(c.ids());                 // by id, so a limit always keeps the same entities
        List<String> ids = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            ids.add(c.ids()[order[r]]);
        }
        Map<String, List<Object>> values = new LinkedHashMap<>();
        for (String path : plain) {
            List<Object> col = new ArrayList<>(rows);
            boolean hidden = masked.containsKey(path);
            for (int r = 0; r < rows; r++) {
                col.add(hidden ? masked.get(path) : StructuredSearch.number(c.value(path, order[r])));
            }
            values.put(path, col);
        }
        return new Columns(kind, c.businessDate(), plain, ids, values, c.size(), rows < c.size(),
                plain.stream().filter(masked::containsKey).toList(), c.incomplete());
    }

    /** Row numbers in id order; a table laid out sorted by id (the packs' large tables) is so already, and is not sorted again. */
    static int[] byId(String[] ids) {
        boolean sorted = true;
        for (int i = 1; i < ids.length && sorted; i++) {
            sorted = ids[i - 1].compareTo(ids[i]) <= 0;
        }
        java.util.stream.IntStream rows = java.util.stream.IntStream.range(0, ids.length);
        return sorted ? rows.toArray() : rows.boxed().sorted(java.util.Comparator.comparing(i -> ids[i])).mapToInt(Integer::intValue).toArray();
    }

    private static List<String> normalise(List<String> paths) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : paths == null ? List.<String>of() : paths) {
            String p = raw == null ? "" : raw.trim();
            if (p.startsWith("$.")) {
                p = p.substring(2);
            }
            if (p.isEmpty()) {
                continue;
            }
            if (!PATH.matcher(p).matches()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + raw + "' is not a field path (letters, digits, _ and dots)");
            }
            out.add(p);
        }
        if (out.isEmpty() || out.size() > MAX_PATHS) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "name 1 to " + MAX_PATHS + " fields");
        }
        return List.copyOf(out);
    }
}
