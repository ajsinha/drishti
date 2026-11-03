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
package com.ash.drishti.bicompute.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Parity (ONE_GRAMMAR.md part 2): every shipped Sutra draws the same figures from the pivot engine's aggregates as from Rachana's own
 * list aggregates before they were removed. Every call of {@code sum}, {@code min}, {@code max} and {@code group} in every Sutra and
 * fragment of {@code config/packs/<pack>/sutras} is evaluated on every test case of the pack ({@code config/packs/<pack>/tests/<sutra>/
 * cases.jsonl}), at the document's root and, for a call that reads {@code @}, on every element of every list in the document (the rows
 * a panel binds); the figure from the engine is compared with the figure from {@link LegacyListAggregates} (the old code, unchanged)
 * on the same evaluated arguments. Equal means equal, or, for a total of fractional numbers, equal to 12 significant digits: the
 * engine's sum is compensated (Neumaier), so it may differ from the old plain sum in the last binary digit, never in a figure shown.
 */
class SutraAggregateParityTest {

    static final Path PACKS = Path.of("..", "config", "packs");
    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, List.of("../examples/packs/finance/config/formats.yaml"));
    static final Pattern CALL = Pattern.compile("(?<![A-Za-z0-9_.$@])(sum|min|max|group)\\(");
    static final int MAX_ROWS_PER_DOCUMENT = 5_000;
    static final double RELATIVE = 1e-12;

    record Call(String file, String name, String text, List<String> args) {}

    @Test
    void everyShippedSutraDrawsTheSameFiguresFromTheEngine() throws Exception {
        List<String> mismatches = new ArrayList<>();
        List<String> lastDigit = new ArrayList<>();
        int calls = 0;
        int comparisons = 0;
        int figures = 0;
        List<String> skipped = new ArrayList<>();
        try (Stream<Path> s = Files.list(PACKS)) {
            for (Path pack : s.filter(p -> Files.isDirectory(p.resolve("sutras"))).sorted().toList()) {
                List<Call> found = calls(pack.resolve("sutras"), skipped);
                List<DataNode> docs = documents(pack.resolve("tests"));
                calls += found.size();
                for (Call call : found) {
                    Expr whole = EL.compile(call.text());
                    List<Expr> args = call.args().stream().map(EL::compile).toList();
                    boolean rows = call.text().contains("@");
                    for (DataNode doc : docs) {
                        for (EvalContext ctx : contexts(doc, rows)) {
                            Object after = evaluate(whole, ctx);
                            Object before = legacy(call.name(), args, ctx);
                            comparisons++;
                            if (isFigure(after)) {
                                figures++;
                            }
                            int same = same(before, after);
                            if (same < 0) {
                                if (mismatches.size() < 30) {
                                    mismatches.add(pack.getFileName() + " " + call.file() + ": " + call.text() + " before " + show(before)
                                            + " after " + show(after));
                                }
                            } else if (same == 0 && lastDigit.size() < 30) {
                                lastDigit.add(call.text() + ": " + show(before) + " → " + show(after));
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("Sutra parity: %d aggregate calls, %d comparisons, %d with a figure, %d equal only to 12 digits, skipped %s%n",
                calls, comparisons, figures, lastDigit.size(), skipped);
        lastDigit.forEach(l -> System.out.println("  last binary digit: " + l));
        assertThat(mismatches).as("figures that differ between Rachana's old list aggregates and the engine's").isEmpty();
        assertThat(calls).as("the shipped Sutras' aggregate calls").isGreaterThan(30);
        assertThat(figures).as("comparisons with a figure (not empty, not 0)").isGreaterThan(50);
    }

    /** Every aggregate call written in the folder's YAML: its text, its arguments' texts. */
    static List<Call> calls(Path sutras, List<String> skipped) throws Exception {
        List<Call> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(sutras)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                String text = Files.readString(f);
                Matcher m = CALL.matcher(text);
                while (m.find()) {
                    int end = close(text, m.end() - 1);
                    if (end < 0) {
                        skipped.add(f.getFileName() + "@" + m.start());
                        continue;
                    }
                    String call = text.substring(m.start(), end + 1);
                    try {
                        EL.compile(call);
                    } catch (ElException e) {
                        skipped.add(f.getFileName() + ": " + call);
                        continue;
                    }
                    out.add(new Call(f.getFileName().toString(), m.group(1), call, split(text.substring(m.end(), end))));
                }
            }
        }
        return out;
    }

    /** The index of the parenthesis closing the one at {@code open}; -1 when the expression ends first (a line or a YAML quote). */
    static int close(String t, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || (c == '"' && !quoted)) {
                return -1;
            }
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '[')) {
                depth++;
            } else if (!quoted && (c == ')' || c == ']')) {
                depth--;
                if (depth == 0) {
                    return c == ')' ? i : -1;
                }
            }
        }
        return -1;
    }

    /** The arguments' texts: split at the commas outside parentheses, brackets and quotes. */
    static List<String> split(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int from = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '[')) {
                depth++;
            } else if (!quoted && (c == ')' || c == ']')) {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                out.add(args.substring(from, i).strip());
                from = i + 1;
            }
        }
        out.add(args.substring(from).strip());
        return out;
    }

    /** Every test case's document of the pack. */
    static List<DataNode> documents(Path tests) throws Exception {
        List<DataNode> out = new ArrayList<>();
        if (!Files.isDirectory(tests)) {
            return out;
        }
        JsonCodec json = new JsonCodec();
        try (Stream<Path> s = Files.walk(tests)) {
            for (Path f : s.filter(p -> p.getFileName().toString().equals("cases.jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (!line.isBlank()) {
                        DataNode doc = json.read(line).get("document");
                        if (!doc.isMissing()) {
                            out.add(doc);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The document's root, and, for a call that reads {@code @}, each element of each list in it as the row. */
    static List<EvalContext> contexts(DataNode doc, boolean rows) {
        EvalContext root = EvalContext.of(doc, F);
        List<EvalContext> out = new ArrayList<>();
        out.add(root);
        if (rows) {
            List<DataNode> elements = new ArrayList<>();
            elements(doc, elements);
            for (int i = 0; i < elements.size(); i++) {
                out.add(root.withRow(elements.get(i), i));
            }
        }
        return out;
    }

    private static void elements(DataNode n, List<DataNode> sink) {
        if (sink.size() >= MAX_ROWS_PER_DOCUMENT) {
            return;
        }
        if (n instanceof DataNode.Arr a) {
            for (DataNode e : a.elements()) {
                if (e instanceof DataNode.Obj) {
                    sink.add(e);
                }
                elements(e, sink);
            }
        } else if (n instanceof DataNode.Obj o) {
            o.fields().values().forEach(v -> elements(v, sink));
        }
    }

    static Object evaluate(Expr e, EvalContext ctx) {
        try {
            return Values.simplify(e.eval(ctx));
        } catch (ElException ex) {
            return new Failed(ex.getMessage());
        }
    }

    /** What the old code gave: the same arguments evaluated the same way, then the old function (a masked argument masks the call). */
    static Object legacy(String name, List<Expr> args, EvalContext ctx) {
        List<Object> values = new ArrayList<>(args.size());
        for (Expr a : args) {
            Object v;
            try {
                v = a.eval(ctx);
            } catch (ElException ex) {
                return new Failed(ex.getMessage());
            }
            if (Values.masked(v)) {
                return Values.mask();
            }
            values.add(v);
        }
        Object out = switch (name) {
            case "sum" -> LegacyListAggregates.sum(values);
            case "min" -> LegacyListAggregates.lenient(values, true);
            case "max" -> LegacyListAggregates.lenient(values, false);
            default -> LegacyListAggregates.group(values);
        };
        return Values.simplify(out);
    }

    /** A problem raised while evaluating (compared by its being a problem, both sides). */
    record Failed(String message) {}

    static boolean isFigure(Object v) {
        Object s = Values.simplify(v);
        return s instanceof Number n ? n.doubleValue() != 0 : s instanceof DataNode.Arr a && a.size() > 0;
    }

    /** 1 equal, 0 equal to 12 significant digits (a compensated sum), -1 different. */
    static int same(Object before, Object after) {
        Object b = Values.simplify(before);
        Object a = Values.simplify(after);
        if (b instanceof Failed && a instanceof Failed) {
            return 1;
        }
        if (b == null || a == null) {
            return b == a ? 1 : -1;
        }
        if (Values.masked(b) || Values.masked(a)) {
            return Values.masked(b) && Values.masked(a) ? 1 : -1;
        }
        if (b instanceof Number x && a instanceof Number y) {
            double p = x.doubleValue();
            double q = y.doubleValue();
            if (p == q) {
                return 1;
            }
            return Math.abs(p - q) <= RELATIVE * Math.max(Math.abs(p), Math.abs(q)) ? 0 : -1;
        }
        if (b instanceof DataNode.Arr x && a instanceof DataNode.Arr y) {
            if (x.size() != y.size()) {
                return -1;
            }
            int worst = 1;
            for (int i = 0; i < x.size(); i++) {
                for (String field : List.of("label", "value", "count")) {
                    worst = Math.min(worst, same(x.get(i).get(field), y.get(i).get(field)));
                }
            }
            return worst;
        }
        return Values.text(b).equals(Values.text(a)) ? 1 : -1;
    }

    static String show(Object v) {
        Object s = Values.simplify(v);
        if (s instanceof DataNode.Arr a) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(a.size(), 5); i++) {
                m.put(a.get(i).get("label").asText(), Values.simplify(a.get(i).get("value")));
            }
            return m + (a.size() > 5 ? " …" : "");
        }
        return String.valueOf(s);
    }

}
