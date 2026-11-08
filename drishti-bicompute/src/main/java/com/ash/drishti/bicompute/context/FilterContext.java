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
package com.ash.drishti.bicompute.context;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The filter-context algebra (BI_DESIGN.md 3.3.5, carried over word for word by docs/architecture/BI_DESIGN.md 2.3), owned by the engine: a
 * measure is evaluated per group of an answer in four layers (1 security, 2 query, 3 group, 4 measure), and every function that needs
 * its aggregate in another context ({@code calc} with {@code ignore}, {@code keep} or {@code via}, {@code share_of}, {@code parent_of},
 * {@code avg_by}, {@code avg_over}, {@code as_of}, {@code latest}, {@code earliest}) becomes a <em>context sub-plan</em>: the same
 * question asked again, as the same reader, with layers 2 and 3 edited, grouped at the coarser grain and joined back to the answer on
 * the fields both keep. This class decides the edit; the compiler that meets the function asks it and writes the sub-plan.
 *
 * <p>The rules, unchanged: (1) security is never in scope, so {@code ignore = ALL} means everything the reader may see (a context
 * edits only layers 2 and 3; rules and masks apply to it as to the answer); (2) closed and compositional, nesting at most
 * {@value #MAX_DEPTH} deep; (3) no context transition, iteration is explicit ({@code avg_by}); (4) every sub-plan keeps grain safety
 * and the fan-out refusal (it is planned by the same compiler); (5) the incremental class of a standing query is derived per function;
 * (6) calculation-group items are templates expanded at bind time, parameters bind as {@code param.name}; (7) {@code as_of}'s date may
 * not depend on a row ({@code DRS-9166}) and a live table has no other dates ({@code DRS-9157}). A masked field is never a group, in
 * the answer or in a context.
 */
public final class FilterContext {

    /** How deep {@code calc}, {@code share_of}, {@code parent_of} and {@code as_of} nest, through the measures they name too (rule 2). */
    public static final int MAX_DEPTH = 3;
    /** The value column of every context sub-plan. */
    public static final String VALUE = "__v";
    /** The measure a context sub-plan computes (the edited aggregate). */
    public static final String MEASURE = "__ctx";

    private FilterContext() {}

    /** What a context sub-plan is for. */
    public enum Kind {
        /** the aggregate at a coarser grain or with other filters ({@code calc}, {@code share_of}, {@code parent_of}) or along another relationship */
        EDIT,
        /** the aggregate per value of more fields, then their average ({@code avg_by}, {@code avg_over}) */
        AVERAGE,
        /** the aggregate at another business date ({@code as_of}) */
        DATED,
        /** the latest or earliest date of each group ({@code latest}, {@code earliest}) */
        EDGE
    }

    /**
     * A field of the answer (layer 3).
     *
     * @param text as the answer names it ({@code trades.DESK}, {@code trades.TRADE_DATE.month})
     * @param column the column it reads, {@code table.column} (a date part counts as its column)
     * @param masked true when it is masked for the reader (never a group)
     */
    public record Field(String text, String column, boolean masked) {}

    /**
     * A filter of the question (layer 2).
     *
     * @param column the column it filters, {@code table.column}
     * @param onMeasure true for a filter on a measure, which filters the answer's groups and never a context
     */
    public record Filter(String column, boolean onMeasure) {}

    /**
     * What a function edits.
     *
     * @param kind the sub-plan's purpose
     * @param all {@code ignore = ALL}: every filter and group dropped
     * @param ignore the columns whose filters and groups are dropped
     * @param keep when not null, the only columns whose filters and groups stay
     * @param via a relationship to follow instead of the active one for the same pair, or null
     * @param by the extra fields an {@link Kind#AVERAGE} groups by
     * @param date the business date of a {@link Kind#DATED} sub-plan
     */
    public record Edit(Kind kind, boolean all, Set<String> ignore, Set<String> keep, String via, List<String> by, LocalDate date) {
        public Edit {
            ignore = ignore == null ? Set.of() : Set.copyOf(ignore);
            keep = keep == null ? null : Set.copyOf(keep);
            by = by == null ? List.of() : List.copyOf(by);
        }

        /** True when the edit changes layers 2 and 3 (else {@code calc} is only its {@code where}, a filter on the aggregate). */
        public boolean edits() {
            return all || !ignore.isEmpty() || keep != null || via != null;
        }
    }

    /**
     * The edited question.
     *
     * @param groups the fields the sub-plan groups by, in the answer's order, then an {@link Kind#AVERAGE}'s extra fields
     * @param on the answer's fields the sub-plan is joined on (its groups before the extra ones)
     * @param filters the indexes of the question's filters the sub-plan keeps
     */
    public record Question(List<String> groups, List<String> on, List<Integer> filters) {}

    /** Refuses a context nested deeper than {@value #MAX_DEPTH} (rule 2), whatever the measures it went through. */
    public static void checkDepth(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("filter-context functions nest more than " + MAX_DEPTH + " deep (through the measures they name)");
        }
    }

    /**
     * The question a context asks: the answer's fields and filters with the edit's dropped. Security (layer 1) is not here: the
     * sub-plan is planned for the same reader, so it can never see more (rule 1).
     */
    public static Question plan(Edit edit, List<Field> fields, List<Filter> filters) {
        List<String> groups = new ArrayList<>();
        List<String> on = new ArrayList<>();
        for (Field f : fields) {
            if (f.masked()) {
                continue;                                                      // a masked field is never a group, in the answer or in a context
            }
            if (!dropped(edit, f.column())) {
                groups.add(f.text());
                on.add(f.text());
            }
        }
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++) {
            Filter f = filters.get(i);
            if (f.onMeasure()) {
                continue;                                                      // a filter on a measure filters the answer's groups, never a context
            }
            if (!dropped(edit, f.column())) {
                kept.add(i);
            }
        }
        if (edit.kind() == Kind.AVERAGE) {
            for (String b : edit.by()) {
                if (!groups.contains(b)) {
                    groups.add(b);
                }
            }
        }
        return new Question(List.copyOf(groups), List.copyOf(on), List.copyOf(kept));
    }

    /** True when the edit drops the filters and the group of a column (only an {@link Kind#EDIT} drops any). */
    static boolean dropped(Edit edit, String column) {
        return edit.kind() == Kind.EDIT && (edit.all() || edit.ignore().contains(column) || edit.keep() != null && !edit.keep().contains(column));
    }

    /** A context's identity within one answer: two functions that ask the same question share one sub-plan. */
    public static String key(Edit edit, String measure, Question q, List<?> keptFilters) {
        return edit.kind() + "|" + measure + "|" + q.groups() + "|" + keptFilters + "|" + edit.via() + "|" + edit.date();
    }

    /** The alias of the {@code n}-th context of an answer at a depth. */
    public static String alias(int depth, int n) {
        return "c" + depth + "_" + n;
    }

    /**
     * An {@link Kind#AVERAGE} sub-plan: the measure per value of the extra fields ({@code inner}), averaged per group of the answer
     * ({@code on}, already quoted). No context transition: the iteration is the one the function names (rule 3).
     */
    public static String average(String inner, List<String> on, String quotedMeasure) {
        return "SELECT " + (on.isEmpty() ? "" : String.join(", ", on) + ", ") + "avg(" + quotedMeasure + ") AS " + quotedMeasure + " FROM ("
                + inner + ") AS \"a\"" + (on.isEmpty() ? "" : " GROUP BY " + String.join(", ", on));
    }

    /**
     * The join of a sub-plan to the answer: on each field both keep, nulls equal; with none (everything ignored), every row of the
     * answer meets the sub-plan's one row. Each answer row meets at most one row of a context (the context is unique on those fields),
     * so no row is repeated and the answer's own aggregates stay what they were.
     */
    public static String join(String sql, String alias, List<String> answerSql, List<String> contextColumns) {
        List<String> conds = new ArrayList<>();
        for (int i = 0; i < answerSql.size(); i++) {
            conds.add(answerSql.get(i) + " IS NOT DISTINCT FROM " + alias + "." + contextColumns.get(i));
        }
        return " LEFT JOIN (" + sql + ") AS " + alias + " ON " + (conds.isEmpty() ? "TRUE" : String.join(" AND ", conds));
    }

    /** How the answer reads a context's value: constant within a group, so {@code max} of it; an {@link Kind#EDGE}'s per row. */
    public static String read(Kind kind, String column) {
        return kind == Kind.EDGE ? column : "max(" + column + ")";
    }

    /**
     * {@code parent_of(agg, hierarchy = h)}: the index of the deepest level of the hierarchy the answer groups by (the parent is that
     * level ignored), or -1 when no level is in the answer or only the top one (the top has no parent: the figure is null).
     *
     * @param present for each level, whether an unmasked field of the answer is it
     */
    public static int parentLevel(List<Boolean> present) {
        int deepest = -1;
        for (int i = 0; i < present.size(); i++) {
            if (present.get(i)) {
                deepest = i;
            }
        }
        return deepest <= 0 ? -1 : deepest;
    }
}
