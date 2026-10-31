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
package com.ash.drishti.server.bi.expr;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * The expression converter over a whole item (docs/architecture/BI_DESIGN.md section 3.3.7, {@code drishti.py bi migrate --expressions}): finds every
 * expression of a dataset, model, report or query YAML (a scalar under {@code expr:} or {@code where:}, a measure or {@code define:} written as a plain text),
 * converts each with {@link Expressions#convert} (W1 parser, DEL v2 printer, re-parse, same compiler tree asserted) and writes the new text into the
 * original characters, so comments, key order and layout survive; then sets {@code expressions: 2}. An expression that cannot be converted is listed as a
 * problem and left as it was. Pure and thread-safe.
 */
public final class ExpressionMigrator {

    /** One expression the converter changed (or could not): where ({@code measures.MTM.expr}), the text before and after, and the problem when it failed. */
    public record Change(String path, String from, String to, String problem) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("from", from);
            m.put("to", to);
            if (problem != null) {
                m.put("problem", problem);
            }
            return m;
        }
    }

    /** The converted YAML, the expressions it holds, those that changed, and those that could not be converted. */
    public record Result(String yaml, int expressions, List<Change> changes, List<Change> problems, boolean rewritten) {
        /** True when the text changed: an expression converted, or {@code expressions: 2} set. */
        public boolean changed() {
            return rewritten;
        }
    }

    private record Edit(int start, int end, String text) {}

    private ExpressionMigrator() {}

    public static Result migrate(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Node root;
        try {
            root = new Yaml(options).compose(new StringReader(yaml));
        } catch (RuntimeException e) {
            throw new DelException("DRS-9100", "the YAML is not valid: " + e.getMessage(), null, 0, 0);
        }
        List<Edit> edits = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<Change> problems = new ArrayList<>();
        int[] count = {0};
        if (root != null) {
            walk(root, "", null, yaml, edits, changes, problems, count);
        }
        StringBuilder out = new StringBuilder(yaml);
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit e : edits) {
            out.replace(e.start(), e.end(), e.text());
        }
        String text = out.toString();
        if (count[0] > 0 && root instanceof MappingNode m) {
            text = version(text, m);
        }
        return new Result(text, count[0], List.copyOf(changes), List.copyOf(problems), !text.equals(yaml));
    }

    private static void walk(Node n, String path, String key, String src, List<Edit> edits, List<Change> changes, List<Change> problems, int[] count) {
        switch (n) {
            case MappingNode m -> {
                for (NodeTuple t : m.getValue()) {
                    if (!(t.getKeyNode() instanceof ScalarNode k)) {
                        continue;
                    }
                    String child = path.isEmpty() ? k.getValue() : path + "." + k.getValue();
                    Node v = t.getValueNode();
                    boolean expression = v instanceof ScalarNode && (isExpressionKey(k.getValue()) || "measures".equals(key) || "define".equals(key));
                    if (expression) {
                        convert((ScalarNode) v, child, src, edits, changes, problems, count);
                    } else {
                        walk(v, child, k.getValue(), src, edits, changes, problems, count);
                    }
                }
            }
            case SequenceNode s -> {
                for (int i = 0; i < s.getValue().size(); i++) {
                    walk(s.getValue().get(i), path + "[" + i + "]", null, src, edits, changes, problems, count);
                }
            }
            default -> { }
        }
    }

    private static boolean isExpressionKey(String k) {
        return "expr".equals(k) || "where".equals(k);
    }

    private static void convert(ScalarNode v, String path, String src, List<Edit> edits, List<Change> changes, List<Change> problems, int[] count) {
        String text = v.getValue();
        if (text == null || text.isBlank()) {
            return;
        }
        count[0]++;
        Expressions.Converted c;
        try {
            c = Expressions.convert(text);
        } catch (DelException e) {
            problems.add(new Change(path, text, null, e.code() + " " + e.getMessage()));
            return;
        }
        if (!c.changed()) {
            return;
        }
        int start = v.getStartMark().getIndex();
        int end = v.getEndMark().getIndex();
        String original = src.substring(start, end);
        String replacement = v.getScalarStyle() == DumperOptions.ScalarStyle.SINGLE_QUOTED ? "'" + c.to().replace("'", "''") + "'" : doubleQuoted(c.to());
        if (original.endsWith("\n")) {
            replacement += "\n";                                                // a block scalar ends with its line break
        }
        edits.add(new Edit(start, end, replacement));
        changes.add(new Change(path, text, c.to(), null));
    }

    static String doubleQuoted(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    /** {@code expressions: 2} after {@code rachana:} (or at the top), or an existing {@code expressions: 1} made 2. */
    private static String version(String text, MappingNode root) {
        for (NodeTuple t : root.getValue()) {
            if (t.getKeyNode() instanceof ScalarNode k && "expressions".equals(k.getValue()) && t.getValueNode() instanceof ScalarNode v) {
                return "2".equals(v.getValue()) ? text : text.substring(0, v.getStartMark().getIndex()) + "2" + text.substring(v.getEndMark().getIndex());
            }
        }
        for (NodeTuple t : root.getValue()) {
            if (t.getKeyNode() instanceof ScalarNode k && "rachana".equals(k.getValue())) {
                int eol = text.indexOf('\n', t.getValueNode().getEndMark().getIndex());
                int at = eol < 0 ? text.length() : eol + 1;
                return text.substring(0, at) + (eol < 0 ? "\n" : "") + "expressions: 2                  # DEL v2, the Python-like expression language (docs/guides/BI_LANGUAGE_REFERENCE.md)\n"
                        + text.substring(at);
            }
        }
        int first = root.getValue().isEmpty() ? 0 : root.getValue().get(0).getKeyNode().getStartMark().getIndex();
        return text.substring(0, first) + "expressions: 2\n" + text.substring(first);
    }
}
