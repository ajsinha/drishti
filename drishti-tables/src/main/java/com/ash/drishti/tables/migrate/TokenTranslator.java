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
 * The stand-in for part A's translator ({@link DelTranslator}): the mechanical, meaning-preserving part of DEL into Rachana, done token by
 * token and then on Rachana's tree. It maps {@code True}, {@code False}, {@code None}, {@code and}, {@code or} (whose precedence is the same
 * in both languages), {@code x is None} and {@code x is not None}, double-quoted texts, {@code 1_000_000}, chained comparisons
 * ({@code 0 < x <= 10} is {@code 0 < x && x <= 10}) and the renamed functions ({@code startswith}, {@code endswith}, {@code cumulative},
 * the balances {@code first} and {@code last} with two arguments, Q5). It refuses what needs DEL's own parser to keep the meaning:
 * {@code not} before anything but {@code in} (its precedence differs from {@code !}), {@code a if c else b}, {@code **}, {@code //} and
 * comments. Part A's translator replaces it where {@link TablesMigrator} is built; nothing else changes.
 */
public final class TokenTranslator implements DelTranslator {

    private static final Map<String, String> WORDS = Map.of("True", "true", "False", "false", "None", "null", "and", "&&", "or", "||");
    private static final Set<String> REFUSED = Set.of("if", "else", "lambda", "for", "elif");
    private static final Map<String, String> RENAMED = Map.of("startswith", "starts_with", "endswith", "ends_with", "cumulative", "running");

    @Override
    public Result translate(String del, ExpressionChecker.Scope scope) {
        if (del == null || del.isBlank()) {
            return Result.refused("an empty expression");
        }
        String outside = outsideTexts(del);
        if (outside.contains("#")) {
            return Result.refused("a comment (#): part A's translator moves it to a YAML comment above the key");
        }
        if (outside.contains("**") || outside.contains("//")) {
            return Result.refused("** or // (power, floor division): part A's translator writes power(x, y) and floor(x / y)");
        }
        List<Reader.Token> tokens;
        try {
            tokens = Reader.tokens(del);
        } catch (Reader.ReadException e) {
            return Result.refused("does not read: " + e.getMessage());
        }
        List<String> notes = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Reader.Token t = tokens.get(i);
            if (t.kind().equals("end")) {
                break;
            }
            String text = t.text();
            if (t.kind().equals("id")) {
                if (REFUSED.contains(text)) {
                    return Result.refused("'" + text + "': a conditional in DEL's form needs part A's translator (c ? a : b)");
                }
                if (text.equals("not")) {
                    if (i + 1 < tokens.size() && tokens.get(i + 1).text().equals("in")) {
                        sb.append("not ");
                        continue;
                    }
                    return Result.refused("'not': its precedence differs from Rachana's '!', so part A's translator writes it from DEL's tree");
                }
                if (text.equals("is")) {
                    boolean negated = i + 1 < tokens.size() && tokens.get(i + 1).text().equals("not");
                    int none = i + (negated ? 2 : 1);
                    if (none >= tokens.size() || !tokens.get(none).text().equals("None")) {
                        return Result.refused("'is' compares only with None");
                    }
                    sb.append(negated ? "!= null " : "== null ");
                    i = none;
                    notes.add("is " + (negated ? "not " : "") + "None is " + (negated ? "!=" : "==") + " null");
                    continue;
                }
                String mapped = WORDS.get(text);
                if (mapped != null) {
                    boolean nameStart = i == 0 || !tokens.get(i - 1).text().equals(".");
                    if (nameStart) {
                        text = mapped;
                    }
                }
            }
            sb.append(text).append(' ');
        }
        Node tree;
        try {
            tree = Reader.read(sb.toString());
        } catch (Reader.ReadException e) {
            return Result.refused("does not read after the words are mapped: " + e.getMessage());
        }
        Node out = tree.map(n -> rewrite(n, scope, notes));
        return Result.ok(out.print(), notes);
    }

    private static Node rewrite(Node n, ExpressionChecker.Scope scope, List<String> notes) {
        if (n instanceof Node.Binary b && b.comparison() && b.left() instanceof Node.Binary l && l.comparison()) {
            notes.add("a chained comparison written as two joined by &&");
            return new Node.Binary("&&", l, new Node.Binary(b.op(), l.right(), b.right()));
        }
        if (n instanceof Node.Call c) {
            String to = RENAMED.get(c.function());
            if (scope == ExpressionChecker.Scope.AGGREGATE && c.args().size() == 2 && (c.function().equals("last") || c.function().equals("first"))) {
                to = c.function().equals("last") ? "latest" : "earliest";
            }
            if (to != null) {
                notes.add(c.function() + " is " + to);
                return new Node.Call(to, c.args());
            }
        }
        return n;
    }

    /** The text with every quoted part blanked, so a {@code #} or {@code //} inside a text is not read as code. */
    static String outsideTexts(String s) {
        StringBuilder out = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                out.append(' ');
            } else if (c == '\'' || c == '"') {
                quote = c;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
