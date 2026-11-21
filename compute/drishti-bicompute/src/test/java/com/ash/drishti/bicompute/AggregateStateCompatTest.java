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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A cluster mid-upgrade (SPLIT_PLAN 8.3h): a cell state written by the engine's own aggregate classes before the move (recorded in
 * {@code aggregate-states.txt}, written by those classes over one fixed batch) decodes with Core's kernel to the same figure, and
 * Core's kernel writes the very same bytes for the same rows. Every built-in name of the catalogue, with its parameters.
 * The record is written again only with {@code -Ddrishti.aggstate.write=true}, and only ever from the pre-move classes.
 */
class AggregateStateCompatTest {

    static final Path RECORD = Path.of("src/test/resources/aggregate-states.txt");

    /** Name, fields (inputs), numbers, texts. */
    record Call(String name, int inputs, List<Double> numbers, List<String> texts) {}

    static final List<Call> CALLS = List.of(
            new Call("sum", 1, List.of(), List.of()), new Call("count", 1, List.of(), List.of()), new Call("avg", 1, List.of(), List.of()),
            new Call("min", 1, List.of(), List.of()), new Call("max", 1, List.of(), List.of()), new Call("wavg", 2, List.of(), List.of()),
            new Call("var_samp", 1, List.of(), List.of()), new Call("var_pop", 1, List.of(), List.of()),
            new Call("stddev_samp", 1, List.of(), List.of()), new Call("stddev_pop", 1, List.of(), List.of()),
            new Call("median", 1, List.of(), List.of()), new Call("percentile", 1, List.of(0.9), List.of()),
            new Call("count_distinct", 1, List.of(), List.of()), new Call("nmiss", 1, List.of(), List.of()),
            new Call("range", 1, List.of(), List.of()), new Call("stderr", 1, List.of(), List.of()), new Call("cv", 1, List.of(), List.of()),
            new Call("skewness", 1, List.of(), List.of()), new Call("kurtosis", 1, List.of(), List.of()),
            new Call("geomean", 1, List.of(), List.of()), new Call("harmean", 1, List.of(), List.of()), new Call("corr", 2, List.of(), List.of()),
            new Call("covar", 2, List.of(), List.of()), new Call("slope", 2, List.of(), List.of()), new Call("intercept", 2, List.of(), List.of()),
            new Call("rsq", 2, List.of(), List.of()), new Call("mode", 1, List.of(), List.of()), new Call("iqr", 1, List.of(), List.of()),
            new Call("trimmean", 1, List.of(0.2), List.of()), new Call("kth_largest", 1, List.of(2.0), List.of()),
            new Call("kth_smallest", 1, List.of(2.0), List.of()), new Call("string_agg", 1, List.of(1.0), List.of(", ")),
            new Call("skewness_pop", 1, List.of(), List.of()), new Call("devsq", 1, List.of(), List.of()),
            new Call("covar_pop", 2, List.of(), List.of()), new Call("steyx", 2, List.of(), List.of()),
            new Call("sumproduct", 2, List.of(), List.of()), new Call("sumsq", 1, List.of(), List.of()), new Call("product", 1, List.of(), List.of()),
            new Call("avedev", 1, List.of(), List.of()), new Call("percentile_exc", 1, List.of(0.25), List.of()),
            new Call("rank_avg", 1, List.of(4.0), List.of()), new Call("percent_rank_of", 1, List.of(4.0), List.of()),
            new Call("mode_multi", 1, List.of(), List.of()));

    static final double[] X = {3.5, 1.25, Double.NaN, 4.0, 2.0, 4.0, 7.75, 0.5, 3.0, 4.0};
    static final double[] Y = {1.0, 2.5, 3.0, Double.NaN, 5.0, 4.5, 8.0, 0.25, 2.0, 6.0};

    static Batch batch(AggFunction<Object> f, int inputs) {
        Object[] cols = new Object[inputs];
        for (int c = 0; c < inputs; c++) {
            double[] d = c == 0 ? X : Y;
            if (f.numeric()) {
                cols[c] = d.clone();
            } else {
                String[] s = new String[d.length];
                for (int r = 0; r < d.length; r++) {
                    s[r] = Double.isNaN(d[r]) ? null : Double.toString(d[r]);
                }
                cols[c] = s;
            }
        }
        return new Batch(X.length, cols);
    }

    static AggFunction<Object> fn(Call c) {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < c.inputs(); i++) {
            fields.add("F" + i);
        }
        return Functions.builtIn(c.name(), fields, c.numbers(), c.texts()).orElseThrow();
    }

    /** The state over the batch split in two and merged, as a cluster merges two partitions. */
    static Object state(AggFunction<Object> f, int inputs) {
        Batch b = batch(f, inputs);
        Object a = f.add(f.start(), slice(b, 0, 4));
        Object z = f.add(f.start(), slice(b, 4, b.rows()));
        return f.merge(a, z);
    }

    static Batch slice(Batch b, int from, int to) {
        Object[] cols = new Object[b.columns().length];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = b.columns()[i] instanceof double[] d ? java.util.Arrays.copyOfRange(d, from, to)
                    : java.util.Arrays.copyOfRange((String[]) b.columns()[i], from, to);
        }
        return new Batch(to - from, cols);
    }

    @Test
    void aStateWrittenBeforeTheMoveReadsTheSameWithCoresKernel() throws Exception {
        if (Boolean.getBoolean("drishti.aggstate.write")) {
            StringBuilder out = new StringBuilder("# name|state bytes (hex)|result, written by the engine's pre-move aggregate classes\n");
            for (Call c : CALLS) {
                AggFunction<Object> f = fn(c);
                Object s = state(f, c.inputs());
                out.append(c.name()).append('|').append(HexFormat.of().formatHex(f.encode(s))).append('|').append(f.result(s)).append('\n');
            }
            Files.writeString(RECORD, out.toString(), StandardCharsets.UTF_8);
        }
        List<String> lines = Files.readAllLines(RECORD, StandardCharsets.UTF_8).stream().filter(l -> !l.startsWith("#")).toList();
        assertThat(lines).hasSize(CALLS.size());
        for (int i = 0; i < CALLS.size(); i++) {
            Call c = CALLS.get(i);
            String[] rec = lines.get(i).split("\\|", 3);
            assertThat(rec[0]).isEqualTo(c.name());
            AggFunction<Object> f = fn(c);
            byte[] old = HexFormat.of().parseHex(rec[1]);
            assertThat(String.valueOf(f.result(f.decode(old)))).as(c.name() + ": the recorded state decoded").isEqualTo(rec[2]);
            Object s = state(f, c.inputs());
            assertThat(HexFormat.of().formatHex(f.encode(s))).as(c.name() + ": the same rows, the same bytes").isEqualTo(rec[1]);
        }
    }
}
