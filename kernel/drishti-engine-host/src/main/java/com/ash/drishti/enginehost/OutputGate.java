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
package com.ash.drishti.enginehost;

import com.ash.engine.Answer;
import com.ash.engine.ColumnSchema;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Table;
import com.ash.engine.TableSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The host's enforcement on outputs (engine specification 8.6 rule 6; EN-47): every column that {@code passes} a masked input column is
 * masked for the caller, and for a column that carries a scope column through {@code passes} every returned row is checked against the
 * caller's row rule on that input; a row outside it fails the request ({@code ENG-4005}) and is never served. Inputs are already read as the
 * caller (per-viewer), so this holds by construction there; it is the second side for a shared engine and a guard against an engine that
 * leaks.
 */
final class OutputGate {

    private OutputGate() {
    }

    /** {@code answer} as the caller may see it. */
    static Answer apply(Descriptor d, Answer answer, ViewerAccess viewer, InputBinder.Binding binding) throws EngineException {
        if (viewer == null || answer.tables().isEmpty()) {
            return answer;
        }
        List<Table> out = new ArrayList<>(answer.tables().size());
        for (Table t : answer.tables()) {
            Descriptor.Output o = d.output(t.schema().name());
            if (o == null) {
                out.add(t);
                continue;
            }
            Set<String> mask = new java.util.HashSet<>();
            for (Descriptor.Column c : o.columns()) {
                String passes = c.passes();
                if (passes == null || passes.indexOf('.') < 0) {
                    continue;
                }
                String input = passes.substring(0, passes.indexOf('.'));
                String column = passes.substring(passes.indexOf('.') + 1);
                InputBinder.Bound b = binding.inputs().get(input);
                String path = b == null ? column : b.column(column);
                if (viewer.masked(path) || viewer.masked(column)) {
                    mask.add(c.name());
                }
                Descriptor.Input in = d.inputs().get(input);
                if (in != null && in.scopeColumns().contains(column) && b != null) {
                    for (String kind : b.kinds()) {
                        RowRule rule = viewer.rows(kind);
                        if (!rule.all() && Set.of(path).containsAll(rule.fields())) {
                            String[] values = t.schema().column(c.name()).carrier() == ColumnSchema.Carrier.TEXT ? t.texts(c.name()) : null;
                            for (int r = 0; values != null && r < t.rows(); r++) {
                                String v = values[r];
                                if (!rule.test(f -> f.equals(path) ? v : null)) {
                                    throw new EngineException("ENG-4005", Kind.NOT_PERMITTED, d.id() + " answered a row of " + t.schema().name()
                                            + " outside what the caller may read (" + c.name() + " " + v + "); it is not served");
                                }
                            }
                        }
                    }
                }
            }
            out.add(mask.isEmpty() ? t : new Masked(t, mask));
        }
        return new Answer(answer.status(), out, answer.missing(), answer.stamp(), answer.notes());
    }

    /** A table with some columns read as null in every row. */
    private record Masked(Table t, Set<String> masked) implements Table {

        @Override public TableSchema schema() { return t.schema(); }
        @Override public int rows() { return t.rows(); }

        @Override
        public String[] texts(String c) {
            return masked.contains(c) ? new String[t.rows()] : t.texts(c);
        }

        @Override
        public long[] integers(String c) {
            return masked.contains(c) ? new long[t.rows()] : t.integers(c);
        }

        @Override
        public double[] numbers(String c) {
            if (!masked.contains(c)) {
                return t.numbers(c);
            }
            double[] a = new double[t.rows()];
            Arrays.fill(a, Double.NaN);
            return a;
        }

        @Override
        public boolean[] flags(String c) {
            return masked.contains(c) ? new boolean[t.rows()] : t.flags(c);
        }

        @Override
        public double[][] lists(String c) {
            return masked.contains(c) ? new double[t.rows()][] : t.lists(c);
        }

        @Override
        public boolean isNull(String c, int row) {
            return masked.contains(c) || t.isNull(c, row);
        }
    }
}
