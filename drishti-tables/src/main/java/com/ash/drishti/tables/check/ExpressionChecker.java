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
package com.ash.drishti.tables.check;

import com.ash.drishti.tables.Problem;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks one expression of a table or model in its scope (BI_FOUNDATION_2911E.md section 2.2). The layer's own checker
 * ({@link LayerExpressionChecker}) decides what the layer owns: scope rules, names, display functions, paths in measures. <b>Rachana's
 * checked mode</b> (part A of 2.9.11.e: types, {@code in}, {@code ^}, lists, named arguments, the filter-context functions) plugs in
 * through this interface: {@link #andThen} chains it after the layer's, so a definition gets both sets of problems, and the layer's
 * {@code DRS-9612} ("decided by checked mode") disappears once checked mode is chained.
 */
public interface ExpressionChecker {

    /**
     * The problems of {@code text} in {@code scope}, each naming {@code path} (the key it was written under).
     *
     * @param text the expression
     * @param scope where it is written
     * @param env what is in scope
     * @param path the key, for the problems ({@code columns.MTM_M.expr})
     */
    List<Problem> check(String text, Scope scope, Env env, String path);

    /** This checker, then {@code next}: both problem lists, this one's first. */
    default ExpressionChecker andThen(ExpressionChecker next) {
        return (text, scope, env, path) -> {
            List<Problem> out = new java.util.ArrayList<>(check(text, scope, env, path));
            out.addAll(next.check(text, scope, env, path));
            return out;
        };
    }

    /** The scopes of BI_FOUNDATION_2911E.md section 2.2. */
    enum Scope {
        /** A table's column {@code expr}, its {@code filter}: the source ({@code $}, {@code @}, {@code ^}) and {@code param.x}; no aggregates. */
        ROW,
        /** A table's {@code rules[].where}, a model's {@code security.rows}: as row, plus {@code user.x}; never removable. */
        RULE,
        /** A model's measure: {@code alias.COLUMN} inside aggregates, {@code measure('name')}, {@code param.x}; no document paths. */
        AGGREGATE,
        /** The model's own row rules: rule scope over the alias's columns ({@code trades.DESK in user.desks}). */
        MODEL_RULE
    }

    /**
     * What an expression may name.
     *
     * @param columns the names in scope: a table's own column names (row scope: they are refused as bare names, since an expression reads
     *     the source), or a model's {@code alias.COLUMN} (aggregate scope)
     * @param aliases a model's aliases ({@code count(trades)}), empty for a table
     * @param measures a model's measure names
     * @param types column to type where known ({@code trades.MTM} to {@code decimal}); checked mode reads them
     * @param masked the masked columns ({@code alias.COLUMN} or a table's column names)
     * @param grainDepth how many lists deep the table's grain goes (0: no {@code @} or {@code ^})
     * @param params a model's parameters ({@code param.x})
     * @param paths a table's column paths to their column names ({@code $.MTM} to {@code MTM}), for the types and masks of the source
     */
    record Env(Set<String> columns, Set<String> aliases, Set<String> measures, Map<String, String> types, Set<String> masked, int grainDepth,
            Set<String> params, Map<String, String> paths) {

        public static final Env EMPTY = new Env(Set.of(), Set.of(), Set.of(), Map.of(), Set.of(), 0);

        public Env {
            columns = columns == null ? Set.of() : Set.copyOf(columns);
            aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
            measures = measures == null ? Set.of() : Set.copyOf(measures);
            types = types == null ? Map.of() : Map.copyOf(types);
            masked = masked == null ? Set.of() : Set.copyOf(masked);
            params = params == null ? Set.of() : Set.copyOf(params);
            paths = paths == null ? Map.of() : Map.copyOf(paths);
        }

        /** Without parameters or source paths. */
        public Env(Set<String> columns, Set<String> aliases, Set<String> measures, Map<String, String> types, Set<String> masked, int grainDepth) {
            this(columns, aliases, measures, types, masked, grainDepth, Set.of(), Map.of());
        }
    }
}
