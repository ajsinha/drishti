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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A row predicate as a site writes it (docs/guides/DATA_LAKE_GUIDE.md, "Several source systems: shared or separate tables"; docs/architecture/
 * ACCESS_MODEL.md section 10, row rules): a connector's {@code where:} and a role's row rule. It is Rachana's expression language written the
 * way a filter reads: {@code SOURCE_SYSTEM = 'ENDUR'}, {@code ASSET_CLASS = 'RATES' and DESK in $user.desks}, {@code NOTIONAL >= 1000000},
 * {@code BOOK in ('B1', 'B2')}, {@code MATURITY is not null}. {@code =} and {@code <>} are Rachana's {@code ==} and {@code !=}; {@code and},
 * {@code or}, {@code not} its {@code &&}, {@code ||}, {@code !}; {@code in (a, b)} its {@code in [a, b]}; {@code $user.<attribute>} the
 * person's attribute (a list for {@code in}, its one value otherwise; an attribute the person lacks matches nothing). Text is quoted.
 *
 * <p>The top-level conjuncts that compare a field with a constant ({@code =}, {@code <>}, {@code <}, {@code <=}, {@code >}, {@code >=},
 * {@code in}) are also given as {@link ScanRequest.Filter}s, so a lake read prunes partitions and files by them; the whole predicate is still
 * tested on every row the store returns. Immutable and thread-safe.
 */
public final class RowPredicate {

    /** Every row. */
    public static final RowPredicate ALL = new RowPredicate("", "", null, Set.of(), List.of(), Map.of());

    private static final ElCompiler EL = new ElCompiler(2_000);

    private final String text;
    private final String el;
    private final Expr expr;
    private final Set<String> fields;
    private final List<ScanRequest.Filter> pushdown;
    private final Map<String, Object> equalities;

    private RowPredicate(String text, String el, Expr expr, Set<String> fields, List<ScanRequest.Filter> pushdown, Map<String, Object> equalities) {
        this.text = text;
        this.el = el;
        this.expr = expr;
        this.fields = fields;
        this.pushdown = pushdown;
        this.equalities = equalities;
    }

    /** A predicate without person attributes (a connector's {@code where}). */
    public static RowPredicate parse(String text) {
        return parse(text, null);
    }

    /**
     * Parses {@code text}; {@code user} the person's attributes ({@code $user.desks}), or null where none may be named.
     *
     * @throws IllegalArgumentException with words, when the text is not a predicate
     */
    public static RowPredicate parse(String text, Map<String, List<Object>> user) {
        if (text == null || text.isBlank()) {
            return ALL;
        }
        String el = toEl(text.strip(), user);
        Expr e;
        try {
            e = EL.compile(el);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("'" + text.strip() + "' is not a row predicate: " + ex.getMessage()
                    + " (write it as SOURCE_SYSTEM = 'ENDUR' and DESK in $user.desks; quote text)", ex);
        }
        Set<String> fields = new LinkedHashSet<>();
        fieldsOf(e, fields);
        List<ScanRequest.Filter> push = new ArrayList<>();
        Map<String, Object> eq = new java.util.LinkedHashMap<>();
        conjuncts(e, push, eq);
        return new RowPredicate(text.strip(), el, e, Set.copyOf(fields), List.copyOf(push), java.util.Collections.unmodifiableMap(eq));
    }

    /** True when every row passes. */
    public boolean all() {
        return expr == null;
    }

    /** The text as written. */
    public String text() {
        return text;
    }

    /** The same predicate in Rachana's expression form ({@code SOURCE_SYSTEM == 'ENDUR'}), person attributes resolved: a search's condition. */
    public String el() {
        return el;
    }

    /** The fields it reads (top-level names and dotted paths). */
    public Set<String> fields() {
        return fields;
    }

    /** Its top-level comparisons of a field with constants, for a lake to prune by. */
    public List<ScanRequest.Filter> pushdown() {
        return pushdown;
    }

    /** The constant a top-level conjunct {@code field = constant} fixes, if any ({@code SOURCE_SYSTEM = 'ENDUR'}: ENDUR). */
    public Optional<Object> fixes(String field) {
        return Optional.ofNullable(equalities.get(field));
    }

    /** Whether the row passes (a row the predicate cannot be worked out on does not). */
    public boolean test(DataNode row) {
        if (expr == null) {
            return true;
        }
        if (row == null) {
            return false;
        }
        try {
            return Values.truthy(expr.eval(EvalContext.of(row, null)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return text;
    }

    // -- the filter form to Rachana's expression -------------------------------------------------------------------------------

    private static final Set<String> WORDS = Set.of("and", "or", "not", "in", "is", "null", "true", "false");

    /** Rewrites the filter form ({@code =}, {@code and}, {@code in (..)}, {@code is null}, {@code $user.x}) into Rachana's expression. */
    static String toEl(String s, Map<String, List<Object>> user) {
        List<String> out = new ArrayList<>();
        java.util.ArrayDeque<Boolean> parens = new java.util.ArrayDeque<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                int j = i + 1;
                StringBuilder lit = new StringBuilder();
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                    }
                    lit.append(s.charAt(j));
                    j++;
                }
                if (j >= n) {
                    throw new IllegalArgumentException("'" + s + "' is not a row predicate: a quote is not closed");
                }
                out.add(quote(lit.toString()));
                i = j + 1;
            } else if (c == '$' && s.startsWith("$user.", i)) {
                int j = i + 6;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '-')) {
                    j++;
                }
                String attr = s.substring(i + 6, j);
                if (user == null) {
                    throw new IllegalArgumentException("'" + s + "' names $user." + attr + ": a connector's where reads the rows only; person "
                            + "attributes belong in a role's row rule");
                }
                List<Object> values = user.getOrDefault(attr, List.of());
                boolean afterIn = !out.isEmpty() && (out.get(out.size() - 1).equals("in"));
                out.add(!afterIn && values.size() == 1 ? literal(values.get(0)) : list(values, afterIn));
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) {
                    j++;
                }
                String w = s.substring(i, j);
                String lw = w.toLowerCase(Locale.ROOT);
                if (WORDS.contains(lw)) {
                    switch (lw) {
                        case "and" -> out.add("&&");
                        case "or" -> out.add("||");
                        case "not" -> out.add(nextWord(s, j).equals("in") ? "not" : "!");
                        case "is" -> {
                            boolean neg = nextWord(s, j).equals("not");
                            out.add(neg ? "!=" : "==");
                            if (neg) {
                                j = s.toLowerCase(Locale.ROOT).indexOf("not", j) + 3;
                            }
                        }
                        default -> out.add(lw);
                    }
                } else {
                    out.add(w);
                }
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_'
                        || (s.charAt(j) == 'e' || s.charAt(j) == 'E') && j + 1 < n && (Character.isDigit(s.charAt(j + 1)) || s.charAt(j + 1) == '-'))) {
                    j += (s.charAt(j) == 'e' || s.charAt(j) == 'E') && s.charAt(j + 1) == '-' ? 2 : 1;
                }
                out.add(s.substring(i, j).replace("_", ""));
                i = j;
            } else if (c == '(') {
                boolean list = !out.isEmpty() && out.get(out.size() - 1).equals("in");
                parens.push(list);
                out.add(list ? "[" : "(");
                i++;
            } else if (c == ')') {
                if (parens.isEmpty()) {
                    throw new IllegalArgumentException("'" + s + "' is not a row predicate: a ')' closes nothing");
                }
                out.add(parens.pop() ? "]" : ")");
                i++;
            } else if (c == '=' ) {
                out.add("==");
                i += i + 1 < n && s.charAt(i + 1) == '=' ? 2 : 1;
            } else if (c == '<' && i + 1 < n && s.charAt(i + 1) == '>') {
                out.add("!=");
                i += 2;
            } else if ((c == '<' || c == '>' || c == '!') && i + 1 < n && s.charAt(i + 1) == '=') {
                out.add(c + "=");
                i += 2;
            } else if (c == '&' || c == '|') {
                int j = i;
                while (j < n && s.charAt(j) == c) {
                    j++;
                }
                out.add(String.valueOf(c).repeat(2));
                i = j;
            } else {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return String.join(" ", out);
    }

    private static String nextWord(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < s.length() && Character.isLetter(s.charAt(j))) {
            j++;
        }
        return s.substring(i, j).toLowerCase(Locale.ROOT);
    }

    private static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String literal(Object v) {
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return v == null ? "null" : quote(String.valueOf(v));
    }

    private static String list(List<Object> values, boolean bare) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            b.append(i == 0 ? "" : ", ").append(literal(values.get(i)));
        }
        return b.append(']').toString();
    }

    // -- what a lake may prune by --------------------------------------------------------------------------------------------

    private static void conjuncts(Expr e, List<ScanRequest.Filter> push, Map<String, Object> eq) {
        if (e instanceof Expr.And and) {
            conjuncts(and.l(), push, eq);
            conjuncts(and.r(), push, eq);
            return;
        }
        if (e instanceof Expr.Binary b) {
            ScanRequest.Op op = switch (b.op()) {
                case "==" -> ScanRequest.Op.EQ;
                case "!=" -> ScanRequest.Op.NE;
                case "<" -> ScanRequest.Op.LT;
                case "<=" -> ScanRequest.Op.LE;
                case ">" -> ScanRequest.Op.GT;
                case ">=" -> ScanRequest.Op.GE;
                default -> null;
            };
            String f = field(b.l());
            Object v = constant(b.r());
            if (op != null && f != null && v != NONE && v != null) {
                push.add(new ScanRequest.Filter(f, op, List.of(v)));
                if (op == ScanRequest.Op.EQ) {
                    eq.put(f, v);
                }
            }
            return;
        }
        if (e instanceof Expr.In in && !in.negated() && in.list() instanceof Expr.ListLit l) {
            String f = field(in.value());
            List<Object> vs = new ArrayList<>();
            for (Expr x : l.items()) {
                Object v = constant(x);
                if (v == NONE || v == null) {
                    return;
                }
                vs.add(v);
            }
            if (f != null && !vs.isEmpty()) {
                push.add(new ScanRequest.Filter(f, ScanRequest.Op.IN, vs));
            }
        }
    }

    private static final Object NONE = new Object();

    /** Every field the expression reads: its field nodes, walked through the tree's parts. */
    private static void fieldsOf(Object e, Set<String> out) {
        if (e instanceof Expr.Field f) {
            String name = field(f);
            if (name != null) {
                out.add(name);
                return;
            }
        }
        if (e instanceof List<?> l) {
            l.forEach(x -> fieldsOf(x, out));
            return;
        }
        if (e instanceof Record r) {
            for (java.lang.reflect.RecordComponent c : r.getClass().getRecordComponents()) {
                if (Expr.class.isAssignableFrom(c.getType()) || List.class.isAssignableFrom(c.getType())) {
                    try {
                        fieldsOf(c.getAccessor().invoke(r), out);
                    } catch (ReflectiveOperationException ignored) {
                        // a part that cannot be read names no field
                    }
                }
            }
        }
    }

    private static String field(Expr e) {
        if (e instanceof Expr.Field f) {
            if (f.rootPath() != null) {
                return f.rootPath().startsWith("$.") ? f.rootPath().substring(2) : f.rootPath();
            }
            if (f.target() instanceof Expr.RowOrRoot || f.target() instanceof Expr.Root) {
                return f.name();
            }
        }
        return null;
    }

    private static Object constant(Expr e) {
        if (e instanceof Expr.Lit l) {
            Object v = l.value();
            return v instanceof DataNode n ? n.unwrap() : v;
        }
        if (e instanceof Expr.Neg neg && neg.e() instanceof Expr.Lit l && l.value() instanceof Number num) {
            return -num.doubleValue();
        }
        return NONE;
    }
}
