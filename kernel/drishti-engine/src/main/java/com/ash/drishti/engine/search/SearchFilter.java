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

import com.ash.drishti.api.DataNode;
import com.ash.rachana.el.EvalContext;
import com.ash.rachana.el.Expr;
import com.ash.rachana.table.FilterTree;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A search as a query on a live table (LIVE_TABLES LT-5, docs/guides/USER_GUIDE.md): the one compiler from Drishti's search
 * grammar ({@link SearchQuery#pick}: {@code TRD where CURRENCY = eur and QUANTITY >= 100m order by QUANTITY desc limit 25},
 * {@code TRD MX-2*}) to the Data Protocol. The condition (its field names spelt as the kind spells them, text equality ignoring case,
 * amounts expanded) becomes a row condition in Rachana-EL ({@code @.QUANTITY}) and, through Rachana's {@link FilterTree}, the filter
 * tree of PROTOCOL 7; a condition outside the tree's subset ({@code matches}, {@code size}, a link) has no tree and stays a row
 * condition the table evaluates itself ({@link #pushed()} false). An id pattern is a condition on the key column.
 *
 * @param kind the kind the search reads
 * @param head the mnemonic or kind as typed
 * @param condition the row condition in Rachana-EL, or null for every row
 * @param tree the condition as a filter tree, or null (no condition, or one that cannot travel as a tree)
 * @param sort the order: {@code [{col, dir}]}, empty for the key's order
 * @param limit the rows the search shows (its {@code limit})
 * @param paths the result's columns as the search names them ({@code $.CURRENCY}), in order
 * @param columns the top-level columns a table needs to answer it: the result's, the condition's and the order's
 * @param reads the top-level columns the condition reads
 */
public record SearchFilter(String kind, String head, String condition, Map<String, Object> tree, List<Map<String, Object>> sort, int limit,
        List<String> paths, List<String> columns, List<String> reads) {

    /** The key column of a kind's live table: the record's id. */
    public static final String KEY = "id";

    private static final Pattern PLAIN_PATH = Pattern.compile("^\\$\\.[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    public SearchFilter {
        sort = List.copyOf(sort);
        paths = List.copyOf(paths);
        columns = List.copyOf(columns);
        reads = List.copyOf(reads);
    }

    /** Whether the whole condition travels as a filter tree (no condition at all counts). */
    public boolean pushed() {
        return condition == null || tree != null;
    }

    /**
     * Compiles a search command with the search's own reading of it: the kind, the field names as the kind spells them, its compiler's
     * functions; a command it cannot read is refused with the search's words (DRS-4004, DRS-4010).
     */
    public static SearchFilter compile(StructuredSearch search, String command) {
        SearchQuery asked = SearchQuery.pick(command);
        String kind = search.kindOf(asked);
        SearchQuery q = search.named(asked, kind);
        SearchQuery.checkPatterns(q.condition());
        search.compiled(q.condition(), "condition");                       // a condition that cannot be read says so here
        search.compiled(q.orderBy(), "order by");
        List<String> conditions = new ArrayList<>();
        if (q.condition() != null && !q.condition().isBlank()) {
            conditions.add("(" + rowRelative(q.condition()) + ")");
        }
        if (q.idPattern() != null) {
            conditions.add(idCondition(q.idPattern()));
        }
        String condition = conditions.isEmpty() ? null : String.join(" && ", conditions);
        Map<String, Object> tree = null;
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add(KEY);
        List<String> paths = search.columns(q, kind);
        paths.forEach(p -> columns.add(top(p)));
        LinkedHashSet<String> reads = new LinkedHashSet<>();
        if (condition != null) {
            Expr e = search.compiled(condition, "condition");
            tree = FilterTree.of(e, EvalContext.of(DataNode.of(Map.of()), search.formats()));
            List<String> read = FilterTree.columns(e);
            if (read != null) {
                reads.addAll(read);
            } else {
                mapPathsOf(condition).forEach(p -> reads.add(top(p)));
            }
            columns.addAll(reads);
        }
        List<Map<String, Object>> sort = new ArrayList<>();
        if (q.orderBy() != null) {
            String o = q.orderBy().strip();
            if (PLAIN_PATH.matcher(o).matches()) {
                sort.add(Map.of("col", o.substring(2), "dir", q.descending() ? "desc" : "asc"));
                columns.add(top(o));
            }
        }
        if (sort.isEmpty()) {
            sort.add(Map.of("col", KEY, "dir", "asc"));                       // as one node orders its rows: by id
        }
        return new SearchFilter(kind, asked.head(), condition, tree, sort, q.limit(), paths, new ArrayList<>(columns), new ArrayList<>(reads));
    }

    /** The row paths ({@code @.a.b}) a row condition reads, in order. */
    static List<String> mapPathsOf(String condition) {
        List<String> out = new ArrayList<>();
        SearchQuery.mapPaths(condition.replace("@.", "$."), p -> {
            out.add(p);
            return p;
        });
        return out;
    }

    /** A condition with its document paths ({@code $.a.b}) read from the row ({@code @.a.b}), in the canonical function names. */
    static String rowRelative(String condition) {
        String rel = SearchQuery.mapPaths(condition, p -> "@." + p.substring(2));
        return outsideQuotes(rel, "startsWith(", "starts_with(");
    }

    /** {@code TRD MX-2*}: a word without {@code *} names ids that start with it; with {@code *} it is a wildcard on the id (case ignored). */
    static String idCondition(String pattern) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String stars = p.replaceAll("[^*]", "");
        String bare = p.replace("*", "");
        if (stars.isEmpty() || p.endsWith("*") && stars.length() == 1) {
            return "starts_with(@." + KEY + ", " + quote(bare) + ")";
        }
        if (stars.length() == 2 && p.startsWith("*") && p.endsWith("*")) {
            return "contains(lower(@." + KEY + "), " + quote(bare) + ")";
        }
        StringBuilder re = new StringBuilder("^");
        for (String part : p.split("\\*", -1)) {
            re.append(Pattern.quote(part)).append(".*");
        }
        re.setLength(re.length() - 2);
        return "matches(@." + KEY + ", " + quote(re + "$") + ")";
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** A path's top-level column ({@code $.counterparty.name} reads the column {@code counterparty}). */
    static String top(String path) {
        String p = path.startsWith("$.") || path.startsWith("@.") ? path.substring(2) : path;
        int dot = p.indexOf('.');
        int br = p.indexOf('[');
        int end = dot < 0 ? br : br < 0 ? dot : Math.min(dot, br);
        return end < 0 ? p : p.substring(0, end);
    }

    private static String outsideQuotes(String el, String from, String to) {
        StringBuilder out = new StringBuilder(el.length());
        int i = 0;
        while (i < el.length()) {
            char c = el.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < el.length() && el.charAt(j) != c) {
                    j += el.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(el.length(), j + 1);
                out.append(el, i, j);
                i = j;
            } else if (el.startsWith(from, i) && (i == 0 || !Character.isLetterOrDigit(el.charAt(i - 1)))) {
                out.append(to);
                i += from.length();
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
