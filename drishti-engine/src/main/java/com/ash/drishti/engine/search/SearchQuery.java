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
package com.ash.drishti.engine.search;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A structured search as typed on the command line:
 * {@code TRD where mtm > 1m and counterparty.name contains 'Meridian' order by mtm desc limit 50}.
 * The head is a mnemonic or a kind. The condition is friendly Rachana-EL: {@code and}, {@code or}, {@code not},
 * {@code =} for equality, {@code x contains 'y'} and {@code x startswith 'y'}, amounts with {@code k}, {@code m} or
 * {@code bn}, and bare field paths, which are read from the document ({@code mtm} is {@code $.mtm}). Anything
 * Rachana-EL accepts is accepted too. Immutable.
 *
 * @param head the mnemonic or kind as typed
 * @param condition the condition as Rachana-EL, or null for every entity
 * @param orderBy the sort expression as Rachana-EL, or null
 * @param descending sort order
 * @param limit at most this many results
 * @param fields the document paths the query reads, in order of appearance (the result columns)
 */
public record SearchQuery(String head, String condition, String orderBy, boolean descending, int limit, List<String> fields) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private static final Pattern LIMIT = Pattern.compile("(?i)\\s+limit\\s+(\\d+)\\s*$");
    private static final Pattern ORDER = Pattern.compile("(?i)\\s+order\\s+by\\s+(.+?)(?:\\s+(asc|desc))?\\s*$");
    private static final Pattern WHERE = Pattern.compile("(?i)^\\s*(\\S+)(?:\\s+where\\s+(.+))?$", Pattern.DOTALL);
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null");
    private static final Set<String> FUNCTIONS = Set.of("link", "size", "sum", "fmt", "coalesce", "first", "last", "abs", "min", "max",
            "upper", "lower", "contains", "startsWith");

    /** True when the text is a structured search rather than an entity to open. */
    public static boolean looksLikeSearch(String text) {
        return text != null && text.matches("(?is)^\\s*\\S+\\s+(where|order\\s+by|limit)\\s+.+");
    }

    public static SearchQuery parse(String text) {
        if (text == null || text.isBlank()) {
            throw bad("an empty search");
        }
        String rest = text.trim();
        int limit = DEFAULT_LIMIT;
        Matcher m = LIMIT.matcher(rest);
        if (m.find() && outsideQuotes(rest, m.start())) {
            limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(m.group(1))));
            rest = rest.substring(0, m.start());
        }
        String order = null;
        boolean desc = false;
        m = ORDER.matcher(rest);
        if (m.find() && outsideQuotes(rest, m.start())) {
            order = m.group(1).trim();
            desc = "desc".equalsIgnoreCase(m.group(2));
            rest = rest.substring(0, m.start());
        }
        m = WHERE.matcher(rest);
        if (!m.matches()) {
            throw bad("cannot read '" + text + "': expected <mnemonic or kind> where <condition>");
        }
        Set<String> fields = new LinkedHashSet<>();
        String condition = m.group(2) == null ? null : translate(m.group(2).trim(), fields);
        String orderBy = order == null ? null : translate(order, fields);
        return new SearchQuery(m.group(1), condition, orderBy, desc, limit, List.copyOf(fields));
    }

    /** Friendly syntax to Rachana-EL; collects the document paths read. */
    static String translate(String friendly, Set<String> fields) {
        List<String> out = new ArrayList<>();
        int i = 0;
        String s = friendly;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {                     // a string: kept, always single-quoted
                int j = i + 1;
                StringBuilder str = new StringBuilder();
                while (j < s.length() && s.charAt(j) != c) {
                    char x = s.charAt(j++);
                    if (x == '\'') {
                        str.append("\\'");
                    } else {
                        str.append(x);
                    }
                }
                if (j >= s.length()) {
                    throw bad("a string is not closed: " + s.substring(i));
                }
                out.add("'" + str + "'");
                i = j + 1;
                continue;
            }
            if (Character.isDigit(c)) {                      // a number, maybe with an amount suffix
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) {
                    j++;
                }
                int k = j;
                while (k < s.length() && Character.isLetter(s.charAt(k))) {
                    k++;
                }
                double v = Double.parseDouble(s.substring(i, j).replace("_", ""));
                String suffix = s.substring(j, k).toLowerCase(Locale.ROOT);
                v *= switch (suffix) {
                    case "" -> 1;
                    case "k" -> 1e3;
                    case "m", "mm", "mn" -> 1e6;
                    case "b", "bn" -> 1e9;
                    case "tn", "t" -> 1e12;
                    default -> throw bad("unknown amount suffix '" + suffix + "' in " + s.substring(i, k) + " (use k, m or bn)");
                };
                out.add(v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v));
                i = k;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$' || c == '@') {  // a word: keyword, function or field path
                int j = i;
                int depth = 0;
                while (j < s.length()) {
                    char x = s.charAt(j);
                    if (x == '[') {
                        depth++;
                    } else if (x == ']') {
                        depth--;
                    } else if (depth == 0 && !(Character.isLetterOrDigit(x) || x == '_' || x == '.' || x == '$' || x == '@')) {
                        break;
                    }
                    j++;
                }
                String word = s.substring(i, j);
                String lower = word.toLowerCase(Locale.ROOT);
                int next = j;
                while (next < s.length() && Character.isWhitespace(s.charAt(next))) {
                    next++;
                }
                boolean call = next < s.length() && s.charAt(next) == '(';
                switch (lower) {
                    case "and" -> out.add("&&");
                    case "or" -> out.add("||");
                    case "not" -> out.add("!");
                    case "contains", "startswith" -> {
                        if (call) {
                            out.add(lower.equals("contains") ? "contains" : "startsWith");
                        } else {
                            infix(out, lower.equals("contains") ? "contains" : "startsWith");
                        }
                    }
                    default -> {
                        if (KEYWORDS.contains(lower)) {
                            out.add(lower);
                        } else if (call || FUNCTIONS.contains(word)) {
                            out.add(word);
                        } else if (word.startsWith("$") || word.startsWith("@")) {
                            out.add(word);
                            if (word.startsWith("$.")) {
                                fields.add(word);
                            }
                        } else {
                            out.add("$." + word);
                            fields.add("$." + word);
                        }
                    }
                }
                i = j;
                continue;
            }
            // operators and punctuation
            String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("&&") || two.equals("||")) {
                out.add(two);
                i += 2;
            } else if (two.equals("<>")) {
                out.add("!=");
                i += 2;
            } else if (c == '=') {
                out.add("==");
                i++;
            } else {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return join(out);
    }

    /** Rewrites the previous operand and the next one into {@code fn(a, b)}: marks it; resolved in join. */
    private static void infix(List<String> out, String fn) {
        out.add("\u0000" + fn);
    }

    private static String join(List<String> tokens) {
        List<String> t = new ArrayList<>(tokens);
        for (int k = 0; k < t.size(); k++) {
            if (t.get(k).startsWith("\u0000")) {
                if (k == 0 || k + 1 >= t.size() || t.get(k - 1).equals(")") || t.get(k + 1).equals("(")) {
                    throw bad(t.get(k).substring(1) + " needs a field on the left and a value on the right: name contains 'text'");
                }
                String fn = t.get(k).substring(1) + "(" + t.get(k - 1) + ", " + t.get(k + 1) + ")";
                t.set(k - 1, fn);
                t.remove(k + 1);
                t.remove(k);
                k--;
            }
        }
        return String.join(" ", t);                          // Rachana-EL ignores whitespace
    }

    private static boolean outsideQuotes(String s, int at) {
        int singles = 0;
        int doubles = 0;
        for (int i = 0; i < at; i++) {
            if (s.charAt(i) == '\'') {
                singles++;
            } else if (s.charAt(i) == '"') {
                doubles++;
            }
        }
        return singles % 2 == 0 && doubles % 2 == 0;
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_SEARCH, message);
    }
}
