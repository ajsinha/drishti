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
package com.ash.drishti.bicompute.custom;

import com.ash.drishti.bicompute.formula.FormulaFunction;
import com.ash.drishti.bicompute.formula.FormulaLowering;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An SQL aggregate macro (PIVOT_ENGINE.md section 8.2): {@code CREATE MACRO wret(r, w) AS sum(r * w) / sum(w)}. The engine first rewrites
 * the macro into states: a body that is a formula of the built-ins (arithmetic over {@code sum}, {@code count}, {@code avg}, ...)
 * becomes that {@link FormulaFunction}, mergeable, exact and fast. Any other body ({@code regr_slope(y, x)}, {@code mode(x)}) is holistic: the
 * tabular provider computes it in DuckDB for each grouping set from the set's own rows, and outside SQL (the harness, the playground) the
 * engine runs the body over the cell's rows in its own DuckDB.
 *
 * <p>A body may use only its parameters, numbers, quoted texts and the functions and words of {@link #ALLOWED}: no table, no subquery, no
 * file or network function, no second statement. It is SQL, not code; it is reviewed before it is shared.
 */
public final class SqlMacroFunction {

    private static final Pattern MACRO = Pattern.compile("(?is)\\s*create\\s+(?:or\\s+replace\\s+)?(?:temp\\s+|temporary\\s+)?macro\\s+"
            + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s+as\\s+(.+?)\\s*;?\\s*");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The SQL a macro body may use besides its parameters: aggregates, row functions and expression words. */
    public static final Set<String> ALLOWED = Set.of("sum", "fsum", "sumkahan", "count", "avg", "mean", "min", "max", "median", "mode",
            "quantile_cont", "quantile_disc", "quantile", "stddev", "stddev_samp", "stddev_pop", "variance", "var_samp", "var_pop", "corr",
            "covar_pop", "covar_samp", "regr_slope", "regr_intercept", "regr_r2", "regr_count", "regr_avgx", "regr_avgy", "kurtosis",
            "skewness", "entropy", "product", "arg_max", "arg_min", "bool_and", "bool_or", "approx_count_distinct", "approx_quantile",
            "abs", "sqrt", "ln", "log", "log10", "log2", "exp", "power", "pow", "round", "floor", "ceil", "sign", "greatest", "least",
            "coalesce", "nullif", "case", "when", "then", "else", "end", "and", "or", "not", "is", "null", "distinct", "filter", "where",
            "cast", "try_cast", "as", "double", "bigint", "integer", "varchar", "true", "false", "in", "between", "like", "isnan", "isinf");

    private SqlMacroFunction() {}

    /** A macro's parts. */
    public record Macro(String name, List<String> params, String body) {}

    /** The macro's name, parameters and body; refused in words when it is not one macro of allowed SQL. */
    public static Macro parse(String text) {
        Matcher m = MACRO.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw CustomFunctions.invalid("an SQL function is one macro: CREATE MACRO name(a, b) AS <an aggregate expression of a and b>");
        }
        List<String> params = new ArrayList<>();
        for (String p : m.group(2).split(",")) {
            if (!p.isBlank()) {
                String s = p.strip();
                if (!WORD.matcher(s).matches()) {
                    throw CustomFunctions.invalid("a macro's parameter is a name, not '" + s + "'");
                }
                params.add(s);
            }
        }
        String body = m.group(3).strip();
        check(body, params);
        return new Macro(m.group(1), params, body);
    }

    private static void check(String body, List<String> params) {
        if (body.contains(";") || body.contains("--") || body.contains("/*")) {
            throw CustomFunctions.invalid("a macro is one expression: no ';' and no comments");
        }
        String noTexts = body.replaceAll("'(?:[^']|'')*'", "''");
        if (noTexts.contains("\"")) {
            throw CustomFunctions.invalid("a macro names only its parameters: no quoted identifiers");
        }
        Matcher w = WORD.matcher(noTexts);
        while (w.find()) {
            String word = w.group();
            int before = w.start() - 1;
            if (before >= 0 && Character.isDigit(noTexts.charAt(before))) {
                continue;                                  // 1e6
            }
            if (!params.contains(word) && !ALLOWED.contains(word.toLowerCase(Locale.ROOT))) {
                throw CustomFunctions.invalid("'" + word + "' is not allowed in a macro: it may use its parameters " + params
                        + " and SQL's aggregate and row functions (no tables, subqueries, files or settings)");
            }
        }
    }

    /**
     * The macro as a function of {@code inputs} (the parameters' order): a formula when its body is one (mergeable, exact), else holistic,
     * computed by {@code runner} outside SQL.
     */
    public static AggFunction<Object> of(String ref, String text, List<FunctionDef.Input> inputs, Runner runner) {
        Macro m = parse(text);
        if (inputs != null && !inputs.isEmpty() && !inputs.stream().map(FunctionDef.Input::name).toList().equals(m.params())) {
            throw CustomFunctions.invalid("the macro's parameters " + m.params() + " are its inputs, in order; declared: "
                    + inputs.stream().map(FunctionDef.Input::name).toList());
        }
        try {
            return FormulaFunction.of(ref, m.body(), m.params());
        } catch (FormulaLowering.FormulaException notAFormula) {
            boolean anyText = inputs != null && inputs.stream().anyMatch(FunctionDef.Input::text);
            return new Holistic(ref, m, !anyText, runner);
        }
    }

    /** True when the macro's body is a formula of the built-ins (so it is mergeable). */
    public static boolean rewritesToStates(String text) {
        return FormulaLowering.isFormula(parse(text).body());
    }

    /** Runs a macro's body over rows in a DuckDB outside the provider (the harness, the playground, batches fed by the engine). */
    @FunctionalInterface
    public interface Runner {
        /** The body's value over the rows ({@code columns}: one array per parameter, double[] or String[]). */
        Double run(String body, List<String> params, int rows, Object[] columns);
    }

    /** The body with each parameter replaced by its input expression. */
    static String bind(Macro m, List<String> inputs) {
        String out = m.body();
        Matcher w = WORD.matcher(out);
        StringBuilder b = new StringBuilder();
        while (w.find()) {
            int i = m.params().indexOf(w.group());
            w.appendReplacement(b, Matcher.quoteReplacement(i >= 0 ? "(" + inputs.get(i) + ")" : w.group()));
        }
        w.appendTail(b);
        return b.toString();
    }

    /** A holistic macro: the rows of the cell, then the body over them. */
    static final class Holistic implements AggFunction<Object> {
        private final String ref;
        private final Macro macro;
        private final boolean numeric;
        private final Runner runner;

        Holistic(String ref, Macro macro, boolean numeric, Runner runner) {
            this.ref = ref;
            this.macro = macro;
            this.numeric = numeric;
            this.runner = runner;
        }

        /** The rows of a cell: one growable column per parameter. */
        static final class Rows {
            final List<Object[]> parts = new ArrayList<>();
            int n;

            @Override
            public String toString() {
                return "rows n=" + n;
            }
        }

        @Override
        public String name() {
            return ref;
        }

        @Override
        public Kind kind() {
            return Kind.HOLISTIC;
        }

        @Override
        public int inputs() {
            return macro.params().size();
        }

        @Override
        public boolean numeric() {
            return numeric;
        }

        @Override
        public Object start() {
            return new Rows();
        }

        @Override
        public Object add(Object state, Batch batch) {
            Rows r = (Rows) state;
            Object[] cols = new Object[batch.columns().length];
            for (int i = 0; i < cols.length; i++) {
                cols[i] = numeric ? java.util.Arrays.copyOf(batch.num(i), batch.rows()) : java.util.Arrays.copyOf(batch.text(i), batch.rows());
            }
            r.parts.add(cols);
            r.n += batch.rows();
            return r;
        }

        @Override
        public Object merge(Object a, Object b) {
            if (a instanceof com.ash.drishti.bicompute.custom.SqlMacroFunction.Figure || b instanceof Figure) {
                throw new IllegalStateException(ref + " is holistic: a figure computed by DuckDB is never merged");
            }
            Rows out = new Rows();
            out.parts.addAll(((Rows) a).parts);
            out.parts.addAll(((Rows) b).parts);
            out.n = ((Rows) a).n + ((Rows) b).n;
            return out;
        }

        @Override
        public Double finish(Object state) {
            if (state instanceof Figure f) {
                return f.value();
            }
            Rows r = (Rows) state;
            if (r.n == 0) {
                return null;
            }
            if (runner == null) {
                throw new IllegalStateException(ref + ": no DuckDB to run the macro outside a query");
            }
            Object[] cols = new Object[macro.params().size()];
            for (int c = 0; c < cols.length; c++) {
                if (numeric) {
                    double[] all = new double[r.n];
                    int k = 0;
                    for (Object[] p : r.parts) {
                        double[] x = (double[]) p[c];
                        System.arraycopy(x, 0, all, k, x.length);
                        k += x.length;
                    }
                    cols[c] = all;
                } else {
                    String[] all = new String[r.n];
                    int k = 0;
                    for (Object[] p : r.parts) {
                        String[] x = (String[]) p[c];
                        System.arraycopy(x, 0, all, k, x.length);
                        k += x.length;
                    }
                    cols[c] = all;
                }
            }
            return runner.run(macro.body(), macro.params(), r.n, cols);
        }

        @Override
        public byte[] encode(Object state) {
            if (state instanceof Figure f) {
                return ByteBuffer.allocate(10).put((byte) 9).put((byte) (f.value() == null ? 0 : 1)).putDouble(f.value() == null ? 0 : f.value())
                        .array();
            }
            Rows r = (Rows) state;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(0);
            out.writeBytes(ByteBuffer.allocate(8).putInt(r.n).putInt(macro.params().size()).array());
            for (int c = 0; c < macro.params().size(); c++) {
                for (Object[] p : r.parts) {
                    if (numeric) {
                        for (double d : (double[]) p[c]) {
                            out.writeBytes(ByteBuffer.allocate(8).putDouble(d).array());
                        }
                    } else {
                        for (String s : (String[]) p[c]) {
                            byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                            out.writeBytes(ByteBuffer.allocate(4).putInt(s == null ? -1 : b.length).array());
                            out.writeBytes(b);
                        }
                    }
                }
            }
            return out.toByteArray();
        }

        @Override
        public Object decode(byte[] bytes) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            if (bb.get() == 9) {
                boolean has = bb.get() == 1;
                double v = bb.getDouble();
                return new Figure(has ? v : null);
            }
            int n = bb.getInt();
            int c = bb.getInt();
            Object[] cols = new Object[c];
            for (int i = 0; i < c; i++) {
                if (numeric) {
                    double[] d = new double[n];
                    for (int r = 0; r < n; r++) {
                        d[r] = bb.getDouble();
                    }
                    cols[i] = d;
                } else {
                    String[] s = new String[n];
                    for (int r = 0; r < n; r++) {
                        int len = bb.getInt();
                        if (len >= 0) {
                            byte[] b = new byte[len];
                            bb.get(b);
                            s[r] = new String(b, StandardCharsets.UTF_8);
                        }
                    }
                    cols[i] = s;
                }
            }
            Rows r = new Rows();
            if (n > 0) {
                r.parts.add(cols);
            }
            r.n = n;
            return r;
        }

        @Override
        public long bytes(Object state) {
            return state instanceof Rows r ? 64 + 16L * r.n * Math.max(1, macro.params().size()) : 32;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of(bind(macro, in));
        }

        @Override
        public Object fromSql(Object[] parts) {
            Object v = parts[0];
            return new Figure(v instanceof Number x && !Double.isNaN(x.doubleValue()) ? x.doubleValue() : null);
        }

        @Override
        public boolean sqlStateMergeable() {
            return false;
        }
    }

    /** A holistic macro's figure for one grouping set, computed by DuckDB from the set's own rows; never merged. */
    record Figure(Double value) {}
}
