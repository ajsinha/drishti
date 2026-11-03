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
package com.ash.drishti.tables.migrate;

import com.ash.drishti.tables.check.ExpressionChecker;
import com.ash.drishti.tables.expr.Node;
import com.ash.drishti.tables.expr.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One expression through the whole migration: the DEL translator (pluggable), then the layer's rewrites on Rachana's tree (columns to paths
 * in a table, date parts in a model, the null rule everywhere), printed once. Each expression's before and after, and every note, go into
 * the migration's report ({@link TablesMigrator.Change}).
 */
final class Translate {

    /** The result: the Rachana text, the notes, or the refusal. */
    record Out(String text, List<String> notes, String refusal) {}

    private final DelTranslator translator;

    Translate(DelTranslator translator) {
        this.translator = translator;
    }

    /**
     * An expression of a table (row or rule scope).
     *
     * @param columnPaths each column of the table to its path
     * @param neverMissing the paths that are never missing (the keys')
     */
    Out table(String del, ExpressionChecker.Scope scope, Map<String, String> columnPaths, Set<String> neverMissing) {
        DelTranslator.Result r = translator.translate(del, scope);
        if (r.refused()) {
            return new Out(null, r.notes(), r.refusal());
        }
        List<String> notes = new ArrayList<>(r.notes());
        try {
            Node tree = Reader.read(r.rachana());
            tree = Rewrites.columnsToPaths(tree, columnPaths, notes);
            tree = Rewrites.nullRule(tree, Rewrites.missingUnless(neverMissing), notes);
            return new Out(tree.print(), notes, null);
        } catch (Reader.ReadException e) {
            return new Out(null, notes, "the translation does not read as Rachana: " + e.getMessage());
        }
    }

    /**
     * An expression of a model (aggregate or model-rule scope).
     *
     * @param columns the model's {@code alias.COLUMN}
     * @param neverMissing the {@code alias.COLUMN} that are never missing (keys, built-ins)
     */
    Out model(String del, ExpressionChecker.Scope scope, Set<String> columns, Set<String> neverMissing) {
        DelTranslator.Result r = translator.translate(del, scope);
        if (r.refused()) {
            return new Out(null, r.notes(), r.refusal());
        }
        List<String> notes = new ArrayList<>(r.notes());
        try {
            Node tree = Reader.read(r.rachana());
            tree = Rewrites.dateParts(tree, columns, notes);
            tree = Rewrites.nullRule(tree, Rewrites.missingUnless(neverMissing), notes);
            return new Out(tree.print(), notes, null);
        } catch (Reader.ReadException e) {
            return new Out(null, notes, "the translation does not read as Rachana: " + e.getMessage());
        }
    }
}
