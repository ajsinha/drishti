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
package com.ash.drishti.bicompute.formula;

import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A formula of built-ins as one function (PIVOT_ENGINE.md section 8.2, the first route offered): {@code sum(PNL) / sum(abs(PNL))}. Its
 * state is the tuple of its aggregates' states, merged part by part with each built-in's own exact merge; {@code finish} finishes every part
 * and evaluates the arithmetic. Its kind is its parts' kind (a ratio of sums is mergeable, so its totals are made from states, never from the
 * figures shown; a formula with a median is holistic). In SQL it is its parts' state expressions over the row expressions, so the tabular
 * provider computes it in the same {@code GROUPING SETS} statement as any built-in.
 */
public final class FormulaFunction implements AggFunction<Object> {

    /** One aggregate of the formula: the built-in, its row inputs and its condition. */
    record Part(String text, AggFunction<Object> fn, List<RowExpr> args, RowExpr where, boolean countRows) {}

    private final String name;
    private final String text;
    private final FormulaAst tree;
    private final FormulaAst top;
    private final List<String> fields;
    private final List<Part> parts;
    private final Kind kind;
    private final boolean plainSum;

    private FormulaFunction(String name, String text, FormulaAst tree) {
        this.name = name;
        this.text = text;
        this.tree = tree;
        List<String> fs = new ArrayList<>();
        FormulaAst.walk(tree, n -> {
            if (n instanceof FormulaAst.Field f && !fs.contains(f.name())) {
                fs.add(f.name());
            }
        });
        this.fields = List.copyOf(fs);
        Map<FormulaAst.Agg, Integer> index = new LinkedHashMap<>();
        List<Part> ps = new ArrayList<>();
        FormulaAst.walk(tree, n -> {
            if (n instanceof FormulaAst.Agg a && !index.containsKey(a)) {
                index.put(a, ps.size());
                ps.add(part(a));
            }
        });
        this.parts = List.copyOf(ps);
        this.top = substitute(tree, index);
        Kind k = Kind.MERGEABLE;
        for (Part p : parts) {
            if (p.fn().kind() == Kind.HOLISTIC) {
                k = Kind.HOLISTIC;
            } else if (p.fn().kind() == Kind.SKETCH && k == Kind.MERGEABLE) {
                k = Kind.SKETCH;
            }
        }
        this.kind = k;
        this.plainSum = additive(tree);
    }

    /** The formula as a function; refused in words ({@link FormulaParser.FormulaException}) when it is not a measure formula. */
    public static FormulaFunction of(String text) {
        return of(null, text);
    }

    /** The formula as a function named {@code name} (a custom function's name; null: the formula's own text). */
    public static FormulaFunction of(String name, String text) {
        FormulaAst t = FormulaParser.parse(text);
        String canon = text.strip().replaceAll("\\s+", " ");
        return new FormulaFunction(name == null ? canon : name, canon, t);
    }

    private Part part(FormulaAst.Agg a) {
        String fn = a.name();
        boolean countRows = fn.equals("count") && a.args().isEmpty();
        List<String> placeholder = new ArrayList<>();
        a.args().forEach(x -> placeholder.add("x"));
        AggFunction<Object> f;
        if (countRows) {
            f = Functions.builtIn("count", a.where() == null ? List.of() : List.of("x"), List.of()).orElseThrow();
        } else {
            try {
                f = Functions.builtIn(fn, placeholder, a.numbers()).orElseThrow(() -> new FormulaParser.FormulaException("no aggregate " + fn, -1));
            } catch (IllegalArgumentException e) {
                throw new FormulaParser.FormulaException(fn + ": " + e.getMessage(), -1);
            }
        }
        List<RowExpr> args = a.args().stream().map(x -> new RowExpr(x, fields)).toList();
        RowExpr where = a.where() == null ? null : new RowExpr(a.where(), fields);
        return new Part(fn + "(" + a.args().size() + ")", f, args, where, countRows);
    }

    /** The tree with each aggregate replaced by a field naming its part's figure, so the top is evaluated like a row expression. */
    private static FormulaAst substitute(FormulaAst n, Map<FormulaAst.Agg, Integer> index) {
        return switch (n) {
            case FormulaAst.Agg a -> new FormulaAst.Field("\u0001p" + index.get(a));
            case FormulaAst.Unary u -> new FormulaAst.Unary(u.op(), substitute(u.operand(), index));
            case FormulaAst.Binary b -> new FormulaAst.Binary(b.op(), substitute(b.left(), index), substitute(b.right(), index));
            case FormulaAst.BoolOp b -> new FormulaAst.BoolOp(b.op(), b.values().stream().map(v -> substitute(v, index)).toList());
            case FormulaAst.Compare c -> new FormulaAst.Compare(substitute(c.first(), index), c.ops(), c.rest().stream()
                    .map(v -> substitute(v, index)).toList());
            case FormulaAst.IfExp i -> new FormulaAst.IfExp(substitute(i.body(), index), substitute(i.test(), index), substitute(i.orElse(), index));
            case FormulaAst.Scalar s -> new FormulaAst.Scalar(s.name(), s.args().stream().map(v -> substitute(v, index)).toList());
            case FormulaAst.ListLit l -> new FormulaAst.ListLit(l.items().stream().map(v -> substitute(v, index)).toList());
            default -> n;
        };
    }

    /** True when the formula's total is the sum of its cells' figures: sums and counts, added and subtracted. */
    private static boolean additive(FormulaAst n) {
        return switch (n) {
            case FormulaAst.Agg a -> a.name().equals("sum") || a.name().equals("count");
            case FormulaAst.Binary b -> (b.op().equals("+") || b.op().equals("-")) && additive(b.left()) && additive(b.right());
            case FormulaAst.Unary u -> u.op().equals("-") && additive(u.operand());
            default -> false;
        };
    }

    /** The formula's text, whitespace collapsed (part of the cache key). */
    public String text() {
        return text;
    }

    /** The source fields the formula reads, in order: the plan's inputs. */
    public List<String> fields() {
        return fields;
    }

    /** The aggregates the formula is made of, as {@code name(n)}. */
    public List<String> partNames() {
        return parts.stream().map(Part::text).toList();
    }

    public FormulaAst tree() {
        return tree;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public int inputs() {
        return fields.size();
    }

    @Override
    public boolean numeric() {
        return false;                    // the row expressions cast what they compute on; a text compared stays text
    }

    @Override
    public boolean plainSum() {
        return plainSum;
    }

    @Override
    public Object start() {
        Object[] s = new Object[parts.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = parts.get(i).fn().start();
        }
        return s;
    }

    @Override
    public Object add(Object state, Batch batch) {
        Object[] s = (Object[]) state;
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            boolean[] mask = p.where() == null ? null : p.where().mask(batch);
            Object[] cols;
            if (p.countRows()) {
                if (mask == null) {
                    cols = new Object[0];
                } else {
                    double[] ones = new double[batch.rows()];
                    for (int r = 0; r < ones.length; r++) {
                        ones[r] = mask[r] ? 1 : Double.NaN;
                    }
                    cols = new Object[] {ones};
                }
            } else {
                cols = new Object[p.args().size()];
                for (int j = 0; j < cols.length; j++) {
                    cols[j] = p.fn().numeric() ? p.args().get(j).numbers(batch, mask) : p.args().get(j).texts(batch, mask);
                }
            }
            s[i] = p.fn().add(s[i], new Batch(batch.rows(), cols));
        }
        return s;
    }

    @Override
    public Object merge(Object a, Object b) {
        Object[] x = (Object[]) a;
        Object[] y = (Object[]) b;
        Object[] out = new Object[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = parts.get(i).fn().merge(x[i], y[i]);
        }
        return out;
    }

    @Override
    public Double finish(Object state) {
        Object[] s = (Object[]) state;
        Object[] cols = new Object[parts.size()];
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Double v = parts.get(i).fn().finish(s[i]);
            cols[i] = new double[] {v == null ? Double.NaN : v};
            names.add("\u0001p" + i);
        }
        Object v = new RowExpr(top, names).eval(new Batch(1, cols))[0];
        double d = RowExpr.toNum(v);
        return v == null || Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    @Override
    public String emptyNote() {
        return "no values in the cell, or a division by zero";
    }

    @Override
    public byte[] encode(Object state) {
        Object[] s = (Object[]) state;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.size(); i++) {
            byte[] b = parts.get(i).fn().encode(s[i]);
            out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    @Override
    public Object decode(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        Object[] s = new Object[parts.size()];
        for (int i = 0; i < s.length; i++) {
            byte[] b = new byte[bb.getInt()];
            bb.get(b);
            s[i] = parts.get(i).fn().decode(b);
        }
        return s;
    }

    @Override
    public long bytes(Object state) {
        Object[] s = (Object[]) state;
        long n = 16;
        for (int i = 0; i < parts.size(); i++) {
            n += parts.get(i).fn().bytes(s[i]);
        }
        return n;
    }

    @Override
    public List<String> sqlState(List<String> inputs) {
        List<String> out = new ArrayList<>();
        for (Part p : parts) {
            String cond = p.where() == null ? null : p.where().sqlCondition(inputs);
            List<String> ins = new ArrayList<>();
            if (p.countRows()) {
                if (cond != null) {
                    ins.add("CASE WHEN " + cond + " THEN 1 END");
                }
            } else {
                for (RowExpr a : p.args()) {
                    String e = p.fn().numeric() ? a.sqlNumber(inputs) : a.sqlText(inputs);
                    ins.add(cond == null ? e : "CASE WHEN " + cond + " THEN " + e + " END");
                }
            }
            List<String> st = p.fn().sqlState(ins);
            if (st.isEmpty()) {
                return List.of();
            }
            out.addAll(st);
        }
        return out;
    }

    @Override
    public Object fromSql(Object[] values) {
        Object[] s = new Object[parts.size()];
        int k = 0;
        for (int i = 0; i < parts.size(); i++) {
            AggFunction<Object> f = parts.get(i).fn();
            int n = f.sqlState(placeholders(parts.get(i))).size();
            Object[] mine = new Object[n];
            System.arraycopy(values, k, mine, 0, n);
            k += n;
            s[i] = f.fromSql(mine);
        }
        return s;
    }

    private static List<String> placeholders(Part p) {
        int n = p.countRows() ? (p.where() == null ? 0 : 1) : p.args().size();
        List<String> x = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            x.add("x");
        }
        return x;
    }

    @Override
    public boolean sqlStateMergeable() {
        return kind != Kind.HOLISTIC;
    }
}
