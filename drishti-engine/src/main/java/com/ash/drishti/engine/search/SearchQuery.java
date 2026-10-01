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
 * {@code TRD where mtm > 1m and counterparty.name contains 'Meridian' order by mtm desc limit 50}, or as a pick list
 * ({@link #pick}): {@code TRD MX-200000}, {@code TRD productType=Revolver}, {@code TRD MX-2* desk=rates}.
 * The head is a mnemonic or a kind. The condition is friendly Rachana-EL: {@code and}, {@code or}, {@code not},
 * {@code =} for equality, {@code x contains 'y'} and {@code x startswith 'y'}, amounts with {@code k}, {@code m} or
 * {@code bn}, and bare field paths, which are read from the document ({@code mtm} is {@code $.mtm}). A bare word
 * after a comparison is a value ({@code productType = Revolver}). Text comparisons ignore case. Anything Rachana-EL
 * accepts is accepted too. Immutable.
 *
 * @param head the mnemonic or kind as typed
 * @param condition the condition as Rachana-EL, or null for every entity
 * @param orderBy the sort expression as Rachana-EL, or null
 * @param descending sort order
 * @param limit at most this many results
 * @param fields the document paths the query reads, in order of appearance (the result columns)
 * @param idPattern only entities this word names (see {@link #pick}), or null
 */
public record SearchQuery(String head, String condition, String orderBy, boolean descending, int limit, List<String> fields,
        String idPattern) {

    public SearchQuery(String head, String condition, String orderBy, boolean descending, int limit, List<String> fields) {
        this(head, condition, orderBy, descending, limit, fields, null);
    }

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

    private static final Pattern OPERATOR = Pattern.compile("(?i)(!=|<>|<=|>=|==|=|<|>|\\scontains\\s|\\sstartswith\\s)");
    private static final Pattern STARTS_WITH_OPERATOR = Pattern.compile("(?i)^(!=|<>|<=|>=|==|=|<|>|contains\\s|startswith\\s)");
    private static final Pattern SUFFIX = Pattern.compile("(?i)^(order\\s+by|limit)\\s.*");

    /**
     * A command that lists entities to pick from, as a Bloomberg terminal does:
     * <ul>
     *   <li>{@code TRD MX-200000}: trades whose id starts with MX-200000 (ignoring case); {@code TRD *100*}: ids containing 100;</li>
     *   <li>{@code TRD productType=Revolver}, {@code TRD notional > 10m and currency = usd}: trades whose fields match;</li>
     *   <li>{@code TRD MX-2* desk=Rates order by mtm desc}: both, sorted;</li>
     *   <li>{@code TRD where …}: the structured search ({@link #parse});</li>
     *   <li>{@code TRD}: every trade.</li>
     * </ul>
     * A word without {@code *} matches ids that start with it and titles that contain it ({@code CPTY northbridge});
     * with {@code *} it is a wildcard on the id. Case never matters.
     */
    public static SearchQuery pick(String text) {
        if (text == null || text.isBlank()) {
            throw bad("an empty search");
        }
        String t = text.replace("<GO>", "").trim();
        if (looksLikeSearch(t)) {
            return parse(t);
        }
        int sp = indexOfSpace(t, 0);
        if (sp < 0) {
            return parse(t);                                              // TRD <GO>: every trade
        }
        String head = t.substring(0, sp);
        String rest = t.substring(sp).trim();
        int end = indexOfSpace(rest, 0);
        String first = end < 0 ? rest : rest.substring(0, end);
        String after = end < 0 ? "" : rest.substring(end).trim();
        String pattern = null;
        String tail = rest;
        boolean condition = first.equalsIgnoreCase("not") || first.startsWith("(");
        if (!condition && !OPERATOR.matcher(" " + first + " ").find() && !STARTS_WITH_OPERATOR.matcher(after).find()) {
            pattern = first;                                               // the first word names entities: see matches
            tail = after;
        }
        SearchQuery q = tail.isEmpty() ? parse(head) : SUFFIX.matcher(tail).matches() ? parse(head + " " + tail) : parse(head + " where " + tail);
        return new SearchQuery(q.head(), q.condition(), q.orderBy(), q.descending(), q.limit(), q.fields(), pattern);
    }

    /** True when the text lists entities rather than naming one: a field condition or an id pattern with {@code *}. */
    public static boolean looksLikePick(String text) {
        if (text == null) {
            return false;
        }
        String t = text.replace("<GO>", "").trim();
        int sp = indexOfSpace(t, 0);
        if (sp < 0) {
            return false;
        }
        String rest = t.substring(sp).trim();
        return looksLikeSearch(t) || rest.contains("*") || OPERATOR.matcher(" " + rest + " ").find() || indexOfSpace(rest, 0) >= 0;
    }

    /** Whether an entity matches a pick pattern (see {@link #pick}), ignoring case. */
    public static boolean matches(String pattern, String id, String title) {
        if (pattern == null) {
            return true;
        }
        if (!pattern.contains("*")) {
            String p = pattern.toLowerCase(Locale.ROOT);
            return id.toLowerCase(Locale.ROOT).startsWith(p) || (title != null && title.toLowerCase(Locale.ROOT).contains(p));
        }
        StringBuilder re = new StringBuilder("(?is)");
        for (String part : pattern.split("\\*", -1)) {
            re.append(Pattern.quote(part)).append(".*");
        }
        re.setLength(re.length() - 2);
        return id.matches(re.toString());
    }

    private static int indexOfSpace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
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
            if (expectsValue(out) && (Character.isLetter(c) || c == '_')) {   // productType = Revolver: a value, not a field
                int j = i;
                while (j < s.length() && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != ')') {
                    j++;
                }
                String word = s.substring(i, j);
                String lower = word.toLowerCase(Locale.ROOT);
                if (!KEYWORDS.contains(lower) && !lower.equals("not") && !word.startsWith("$") && !word.contains("(")) {
                    out.add("'" + word.replace("'", "\\'") + "'");
                    i = j;
                    continue;
                }
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

    private static final Set<String> COMPARISONS = Set.of("==", "!=", "<", ">", "<=", ">=");

    private static boolean expectsValue(List<String> out) {
        if (out.isEmpty()) {
            return false;
        }
        String last = out.get(out.size() - 1);
        return COMPARISONS.contains(last) || last.startsWith("\u0000");
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
        for (int k = 1; k + 1 < t.size(); k++) {             // text equality ignores case: currency = usd
            String op = t.get(k);
            if ((op.equals("==") || op.equals("!=")) && (t.get(k - 1).startsWith("'") || t.get(k + 1).startsWith("'"))
                    && single(t.get(k - 1)) && single(t.get(k + 1))) {
                t.set(k - 1, "lower(" + t.get(k - 1) + ")");
                t.set(k + 1, "lower(" + t.get(k + 1) + ")");
            }
        }
        for (int k = 0; k + 3 < t.size(); k++) {             // not status = 'Matured' means not (status = 'Matured')
            if (t.get(k).equals("!") && COMPARISONS.contains(t.get(k + 2)) && !t.get(k + 1).equals("(")) {
                t.set(k + 1, "(" + t.get(k + 1));
                t.set(k + 3, t.get(k + 3) + ")");
            }
        }
        return String.join(" ", t);                          // Rachana-EL ignores whitespace
    }

    /** One operand (a field path or a literal), not part of a larger expression. */
    private static boolean single(String token) {
        return token.startsWith("'") || token.startsWith("$.") || token.startsWith("@");
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
