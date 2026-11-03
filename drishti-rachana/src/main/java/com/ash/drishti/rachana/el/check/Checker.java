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
package com.ash.drishti.rachana.el.check;

import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Functions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rachana's checked mode (BI_FOUNDATION_2911E.md 2.1 and 2.2; the type checker ported from DEL's {@code DelChecker}, which now calls
 * this one): checks a parsed expression against the scope it is written in ({@link Scope}), the {@link Signatures} and (when known)
 * the fields, measures and parameters the caller may use. Types (no implicit casts, booleans only for conditions), aggregates where
 * they belong ({@code DRS-9127} "an aggregate belongs in a measure", "wrap it: sum(...)"), names that exist ({@code DRS-9152}), masked
 * fields in filters ({@code DRS-9161}), what is not yet built ({@code DRS-9129}), a chained comparison, a document path in a measure,
 * display functions in tables and models, and the filter-context functions nesting at most three deep. Collects every problem (it does
 * not stop at the first) and the type of the result. It is not a second language: the same text means the same thing checked or not;
 * checked mode only refuses more. One checker checks one expression; not thread-safe, cheap to make.
 */
public final class Checker {

    /** The outcome: the result's type, the problems, and the functions, fields and measures the expression uses. */
    public record Checked(ElType type, List<Problem> problems, Set<String> functions, Set<String> fields, Set<String> measures) {
        public boolean ok() {
            return problems.stream().noneMatch(Problem::error);
        }

        public Optional<Problem> firstError() {
            return problems.stream().filter(Problem::error).findFirst();
        }
    }

    private static final Set<String> COMPARISONS = Set.of("==", "!=", "<", "<=", ">", ">=");

    private final Signatures catalog;
    private final Scope scope;
    private final Fields fields;
    private final Map<Expr, int[]> spans;
    private final String text;
    private final Spelling spelling;
    private final List<Problem> problems = new ArrayList<>();
    private final Set<String> functions = new LinkedHashSet<>();
    private final Set<String> usedFields = new LinkedHashSet<>();
    private final Set<String> usedMeasures = new LinkedHashSet<>();
    private int aggregateDepth;
    private int contextDepth;
    private boolean sawAggregate;

    private Checker(Signatures catalog, Scope scope, Fields fields, Map<Expr, int[]> spans, String text, Spelling spelling) {
        this.catalog = catalog;
        this.scope = scope;
        this.fields = fields == null ? Fields.NONE : fields;
        this.spans = spans == null ? Map.of() : spans;
        this.text = text == null ? "" : text;
        this.spelling = spelling == null ? Spelling.RACHANA : spelling;
    }

    /**
     * Checks a tree written in a scope ({@code null}: the language and the catalogue only, whatever the scope).
     *
     * @param tree the parsed expression
     * @param spans where each node was written (empty: every problem at 0)
     * @param text the expression's text (to tell {@code 1.0} from {@code 1})
     * @param fields what it may name; {@link Fields#NONE} checks the language only
     * @param spelling the words of the fixes
     */
    public static Checked check(Expr tree, Map<Expr, int[]> spans, String text, Scope scope, Fields fields, Spelling spelling) {
        return check(Signatures.get(), tree, spans, text, scope, fields, spelling);
    }

    public static Checked check(Signatures catalog, Expr tree, Map<Expr, int[]> spans, String text, Scope scope, Fields fields,
            Spelling spelling) {
        Checker c = new Checker(catalog, scope, fields, spans, text, spelling);
        ElType t = c.visit(tree);
        c.top(tree, t);
        return new Checked(t, List.copyOf(c.problems), Set.copyOf(c.functions), Set.copyOf(c.usedFields), Set.copyOf(c.usedMeasures));
    }

    private void top(Expr tree, ElType t) {
        if (scope == null) {
            return;
        }
        switch (scope) {
            case RULE -> {
                if (!t.fits(ElType.BOOLEAN)) {
                    err(Problem.LANGUAGE, "a row rule is a condition (" + spelling.booleans() + " for each row); this is a " + t.word(),
                            "compare it: for example trades.desk in user.desks", tree);
                }
            }
            case AGGREGATE, CONTEXT -> {
                if (!sawAggregate && problems.stream().noneMatch(Problem::error)) {
                    err(Problem.LANGUAGE, "a measure aggregates the rows of each group; this expression does not",
                            "wrap the column in an aggregate: sum(...), count(...), avg(...), or use measure('name')", tree);
                }
            }
            default -> { }
        }
    }

    // ---- problems ----------------------------------------------------------------------------------------------------

    private int[] span(Expr at) {
        int[] s = spans.get(at);
        return s == null ? new int[] {0, text.length()} : s;
    }

    private ElType err(String code, String message, String fix, Expr at) {
        int[] s = span(at);
        problems.add(new Problem(code, Problem.ERROR, message, fix, s[0], s[1]));
        return ElType.ANY;
    }

    private void warn(String code, String message, String fix, Expr at) {
        int[] s = span(at);
        problems.add(new Problem(code, Problem.WARNING, message, fix, s[0], s[1]));
    }

    private String written(Expr e) {
        int[] s = spans.get(e);
        return s == null || s[1] > text.length() ? "" : text.substring(s[0], s[1]);
    }

    // ---- visiting ----------------------------------------------------------------------------------------------------

    ElType visit(Expr n) {
        return switch (n) {
            case Expr.Lit l -> literal(l);
            case Expr.Absent a -> ElType.ANY;
            case Expr.ListLit l -> err(Problem.LANGUAGE, "a list is only the right side of in (x in ['A', 'B']) or the value of "
                    + spelling.named("by") + "[...]", null, l);
            case Expr.Field f -> path(f);
            case Expr.At a -> path(a);
            case Expr.Root r -> err(Problem.LANGUAGE, "$ is the whole source row: name a field of it ($.TRADE_ID)", null, r);
            case Expr.Row r -> err(Problem.LANGUAGE, "@ is the whole element: name a field of it (@.AMOUNT)", null, r);
            case Expr.Parent r -> err(Problem.LANGUAGE, "^ is the whole parent element: name a field of it (^.LEG_ID)", null, r);
            case Expr.RowOrRoot r -> ElType.ANY;
            case Expr.Each e -> lists(e);
            case Expr.Pluck e -> lists(e);
            case Expr.Filter e -> lists(e);
            case Expr.Ctl c -> err(Problem.LANGUAGE, "ctl." + c.name() + " is a Sutra's control: a table or a model holds data, not a screen's "
                    + "choices", "use param." + c.name() + " (a parameter of the model)", c);
            case Expr.Event e -> err(Problem.LANGUAGE, "@" + e.panel() + " is a screen's event: a table or a model holds data", null, e);
            case Expr.Src s -> err(Problem.LANGUAGE, "$src is a panel's source: a table or a model holds data", null, s);
            case Expr.Index i -> err(Problem.LANGUAGE, "#index is a row's place on a screen: a table or a model holds data", null, i);
            case Expr.Let l -> err(Problem.LATER, "let(...) is not yet in a table or a model", null, l);
            case Expr.Local l -> ElType.ANY;
            case Expr.Not x -> {
                ElType t = visit(x.e());
                if (!t.fits(ElType.BOOLEAN)) {
                    err(Problem.LANGUAGE, "'!' takes a condition (" + spelling.booleans() + "); this is a " + t.word(), "compare it, for example !(x > 0)", x);
                }
                yield ElType.BOOLEAN;
            }
            case Expr.Neg x -> {
                ElType t = visit(x.e());
                yield t.fits(ElType.NUMBER) ? t : err(Problem.LANGUAGE, "'-' needs a number; this is a " + t.word(), null, x);
            }
            case Expr.And x -> logic("&&", x, x.l(), x.r());
            case Expr.Or x -> logic("||", x, x.l(), x.r());
            case Expr.Ternary x -> {
                ElType test = visit(x.cond());
                if (!test.fits(ElType.BOOLEAN)) {
                    err(Problem.LANGUAGE, "the condition of '" + spelling.conditional() + "' must be " + spelling.booleans() + "; it is a "
                            + test.word(), "compare it, for example x > 0", x.cond());
                }
                ElType a = visit(x.then());
                ElType b = visit(x.otherwise());
                if (!a.comparable(b)) {
                    err(Problem.LANGUAGE, "the two values of '" + spelling.conditional() + "' have different types (" + a.word() + " and "
                            + b.word() + ")", "make both numbers, or both texts", x);
                }
                yield a == ElType.NONE ? b : a;
            }
            case Expr.Binary b -> COMPARISONS.contains(b.op()) ? compare(b) : arithmetic(b);
            case Expr.In i -> in(i);
            case Expr.Call c -> call(c, c.name(), c.args(), c.named());
            case Expr.Form f -> call(f, f.name(), f.args(), Map.of());
            default -> err(Problem.LANGUAGE, "this cannot be written in a table or a model", null, n);
        };
    }

    private ElType literal(Expr.Lit l) {
        Object v = l.value();
        if (v == null) {
            return ElType.NONE;
        }
        if (v instanceof Boolean) {
            return ElType.BOOLEAN;
        }
        if (v instanceof String) {
            return ElType.STRING;
        }
        if (v instanceof Double) {
            return ElType.DECIMAL;
        }
        String w = written(l);
        return w.contains(".") || w.contains("e") || w.contains("E") ? ElType.DECIMAL : ElType.INTEGER;
    }

    private ElType logic(String op, Expr at, Expr l, Expr r) {
        for (Expr v : List.of(l, r)) {
            ElType t = visit(v);
            if (!t.fits(ElType.BOOLEAN)) {
                err(Problem.LANGUAGE, "'" + op + "' takes conditions (" + spelling.booleans() + "); this side is a " + t.word(),
                        "compare it, for example " + spelling.conditionExample(), v);
            }
        }
        return ElType.BOOLEAN;
    }

    private ElType lists(Expr e) {
        return err(Problem.LANGUAGE, "a list of the source (…[*], …[?…]) is a grain of a table, not a value of a checked expression",
                "make the list the table's grain, then name its fields with @", e);
    }

    // ---- names -------------------------------------------------------------------------------------------------------

    /** The parts a field chain names ({@code [trades, MTM]}, {@code [$, LEGS, 0, NOTIONAL]}); null when it is not a plain name. */
    static List<String> parts(Expr e) {
        List<String> out = new ArrayList<>();
        Expr at = e;
        while (true) {
            switch (at) {
                case Expr.Field f -> {
                    out.add(0, f.name());
                    at = f.target();
                }
                case Expr.At a when a.index() instanceof Expr.Lit lit && lit.value() != null -> {
                    out.add(0, String.valueOf(lit.value()));
                    at = a.target();
                }
                case Expr.RowOrRoot r -> {
                    return out;
                }
                case Expr.Root r -> {
                    out.add(0, "$");
                    return out;
                }
                case Expr.Row r -> {
                    out.add(0, "@");
                    return out;
                }
                case Expr.Parent p -> {
                    out.add(0, "^".repeat(p.up()));
                    return out;
                }
                default -> {
                    return null;
                }
            }
        }
    }

    private ElType path(Expr e) {
        List<String> parts = parts(e);
        if (parts == null) {
            if (e instanceof Expr.Field f && !(f.target() instanceof Expr.Field)) {
                visit(f.target());
            }
            return lists(e);
        }
        if (parts.size() == 1 && "ALL".equals(parts.get(0))) {
            return err(Problem.LANGUAGE, "ALL is only the value of " + spelling.named("ignore") + " and " + spelling.named("over")
                    + " (calc, share_of)", null, e);
        }
        return field(parts, e, false);
    }

    /** A field: {@code user.x} only in row rules, {@code param.x} where the model has it, a column only inside an aggregate in aggregate scope. */
    private ElType field(List<String> parts, Expr f, boolean bare) {
        String head = parts.get(0);
        String text = String.join(".", parts);
        if ("user".equals(head)) {
            if (parts.size() != 2) {
                return err(Problem.LANGUAGE, "a user attribute is user.<name>, for example user.desks", null, f);
            }
            if (scope != null && scope != Scope.RULE) {
                return err(Problem.LANGUAGE, "user." + parts.get(1) + " is for row rules; " + (scope == Scope.ROW ? "a column's value" : "a measure")
                        + " does not depend on the reader", "put the condition in the table's rules", f);
            }
            return ElType.ANY;
        }
        if ("param".equals(head)) {
            if (parts.size() != 2) {
                return err(Problem.LANGUAGE, "a parameter is param.<name>, for example param.shock", null, f);
            }
            if (scope == Scope.RULE) {
                return err(Problem.LANGUAGE, "param." + parts.get(1) + " cannot be used in a row rule: security never depends on a what-if value", null, f);
            }
            Optional<ElType> t = fields.param(parts.get(1));
            if (t.isEmpty()) {
                List<String> names = fields.params();
                return err(Problem.NAME, "no parameter '" + parts.get(1) + "' in this model" + (names.isEmpty()
                        ? " (it declares none: add it under parameters:)" : "; its parameters: " + names), null, f);
            }
            usedFields.add(text);
            return t.get();
        }
        boolean source = head.equals("$") || head.equals("@") || head.startsWith("^");
        if (source && (scope == Scope.AGGREGATE || scope == Scope.CONTEXT)) {
            return err(Problem.LANGUAGE, "a path of the source (" + text + ") in a measure is refused: a figure is defined once",
                    "add it as a column of the table, then aggregate that column", f);
        }
        if (source && parts.size() == 1) {
            return err(Problem.LANGUAGE, head + " alone is the whole row: name a field of it", null, f);
        }
        if ((scope == Scope.AGGREGATE || scope == Scope.CONTEXT) && aggregateDepth == 0 && !bare) {
            return err(Problem.LANGUAGE, "the column " + text + " is outside an aggregate: a measure has one value per group, not per row",
                    "wrap it: sum(" + text + ")", f);
        }
        Optional<Fields.Field> found = fields.field(parts);
        if (found.isEmpty()) {
            if (fields.alias(text)) {
                return err(Problem.LANGUAGE, "'" + text + "' is a table, not a column", "count its rows with count(" + text
                        + "), or name a column: " + text + ".<column>", f);
            }
            return err(Problem.NAME, "no field '" + text + "'" + suggest(text), null, f);
        }
        usedFields.add(found.get().path());
        return found.get().type();
    }

    private String suggest(String name) {
        List<String> names = fields.names();
        if (names.isEmpty()) {
            return "";
        }
        String low = name.toLowerCase(java.util.Locale.ROOT);
        String best = names.stream().min(Comparator.comparingInt(x -> distance(x.toLowerCase(java.util.Locale.ROOT), low))).orElse(null);
        return best != null && distance(best.toLowerCase(java.util.Locale.ROOT), low) <= Math.max(2, name.length() / 3)
                ? "; did you mean " + best + "?" : "";
    }

    /** The edit distance of two names (for "did you mean"). */
    public static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---- operators ---------------------------------------------------------------------------------------------------

    private ElType arithmetic(Expr.Binary b) {
        ElType l = visit(b.l());
        ElType r = visit(b.r());
        String op = b.op();
        if ("-".equals(op) && l.temporal() && r.temporal()) {
            return ElType.INTEGER;                                              // date - date: days
        }
        if (l.temporal() || r.temporal()) {
            return err(Problem.LANGUAGE, "dates cannot be used with '" + op + "'", "days between two dates: days_between(a, b) or b - a", b);
        }
        if (l == ElType.STRING || r == ElType.STRING) {
            return err(Problem.LANGUAGE, "text cannot be used with '" + op + "' (checked mode has no implicit casts and no text arithmetic)", null, b);
        }
        if (!l.fits(ElType.NUMBER) || !r.fits(ElType.NUMBER)) {
            return err(Problem.LANGUAGE, "'" + op + "' needs numbers; it has a " + l.word() + " and a " + r.word(), null, b);
        }
        return "/".equals(op) ? ElType.DECIMAL : ElType.arithmetic(l, r);
    }

    private ElType compare(Expr.Binary c) {
        if (c.l() instanceof Expr.Binary inner && COMPARISONS.contains(inner.op())) {
            String a = written(inner);
            String rest = written(c.r());
            String middle = written(inner.r());
            return err(Problem.LANGUAGE, "a chained comparison compares a condition with a value",
                    a.isEmpty() ? "write each comparison and join them with &&" : "write " + a + " && " + middle + " " + c.op() + " " + rest, c);
        }
        masked(c.l(), c.op());
        masked(c.r(), c.op());
        ElType lt = visit(c.l());
        ElType rt = visit(c.r());
        if (!comparable(lt, rt, c.l(), c.r())) {
            err(Problem.LANGUAGE, "cannot compare a " + lt.word() + " with a " + rt.word() + " (checked mode has no implicit casts)",
                    lt.temporal() || rt.temporal() ? "write the date as date('YYYY-MM-DD')" : null, c);
        }
        return ElType.BOOLEAN;
    }

    /** A text written in ISO date form compared with a date is read as that date (the one allowed reading of a text as a date). */
    private static boolean comparable(ElType a, ElType b, Expr left, Expr right) {
        if (a.comparable(b)) {
            return true;
        }
        return a.temporal() && right instanceof Expr.Lit s && s.value() instanceof String t && isoDate(t)
                || b.temporal() && left instanceof Expr.Lit s2 && s2.value() instanceof String t2 && isoDate(t2);
    }

    /** True for a text in ISO date or timestamp form. */
    public static boolean isoDate(String s) {
        return s.matches("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?");
    }

    private void masked(Expr side, String op) {
        List<String> parts = parts(side);
        if (parts != null && !parts.isEmpty() && !"user".equals(parts.get(0)) && !"param".equals(parts.get(0))) {
            fields.field(parts).filter(Fields.Field::masked).ifPresent(x -> err(Problem.MASKED, "'" + String.join(".", parts)
                    + "' is masked for you: a filter on it (" + op + ") is refused", "filter on a field that is not masked", side));
        }
    }

    private ElType in(Expr.In i) {
        String op = i.negated() ? "not in" : "in";
        masked(i.value(), op);
        ElType lt = visit(i.value());
        Expr right = i.list();
        if (right instanceof Expr.ListLit l) {
            for (Expr item : l.items()) {
                ElType it = visit(item);
                if (it != ElType.NONE && !comparable(lt, it, null, item)) {
                    err(Problem.LANGUAGE, "the list has a " + it.word() + " where the value is a " + lt.word(), null, item);
                }
            }
            return ElType.BOOLEAN;
        }
        List<String> parts = parts(right);
        if (parts != null && parts.size() == 2 && ("user".equals(parts.get(0)) || "param".equals(parts.get(0)))) {
            field(parts, right, false);
            return ElType.BOOLEAN;
        }
        err(Problem.LANGUAGE, "'" + op + "' takes a list: x " + op + " ['A', 'B'], or user.<attribute> in a row rule",
                parts != null ? null : "write the values in [ ]", right);
        return ElType.BOOLEAN;
    }

    // ---- calls -------------------------------------------------------------------------------------------------------

    private ElType call(Expr at, String written, List<Expr> args, Map<String, Expr> named) {
        String name = written;
        functions.add(name);
        if (!Functions.oneName(name).equals(name)) {
            return err(Problem.LANGUAGE, "'" + name + "' is the old spelling", "write " + spelling.name(Functions.oneName(name)) + "(...)", at);
        }
        if ("measure".equals(name)) {
            return measure(at, args, named);
        }
        Optional<Signatures.Signature> found = catalog.function(name);
        if (found.isEmpty()) {
            if (Functions.arity().containsKey(name)) {
                return err(Problem.LATER, name + "() is not yet in a table or a model (the rest of tier 1 comes in the next release)", null, at);
            }
            String near = catalog.names().stream().min(Comparator.comparingInt(x -> distance(x, name))).orElse("");
            return err(Problem.LANGUAGE, "unknown function '" + spelling.name(name) + "'" + (distance(near, name) <= 2
                    ? "; did you mean " + spelling.name(near) + "?" : ""), "see Help → Functions", at);
        }
        Signatures.Signature f = found.get();
        String shown = spelling.name(name);
        if (!f.built()) {
            return err(Problem.LATER, shown + "() arrives in " + f.status() + " (" + f.family() + ")", null, at);
        }
        if (scope != null && !f.allowedIn(scope)) {
            if (f.aggregate() || f.takesAggregate()) {
                return err(Problem.LANGUAGE, "an aggregate belongs in a measure: " + shown + "() cannot be used " + where(), "make it a measure of the model", at);
            }
            return err(Problem.LANGUAGE, shown + "() cannot be used " + where(), null, at);
        }
        if (f.aggregate() && !f.takesAggregate() && aggregateDepth > 0) {
            return err(Problem.LANGUAGE, "an aggregate inside an aggregate (" + shown + "() within another)",
                    "aggregate the column once; for two levels use avg_by", at);
        }
        List<List<Expr>> bound = bind(f, at, args, named);
        if (bound == null) {
            sawAggregate |= f.aggregate() || f.takesAggregate();
            return ElType.ANY;
        }
        boolean rows = f.aggregate() && !f.takesAggregate();                   // sum(x) reads rows; ratio(sum(x), sum(y)) combines aggregates
        if (rows) {
            sawAggregate = true;
            aggregateDepth++;
        }
        boolean context = Signatures.CONTEXT_FAMILIES.contains(f.family()) && f.takesAggregate();
        if (context && ++contextDepth > Signatures.MAX_CONTEXT_DEPTH) {
            contextDepth--;
            if (rows) {
                aggregateDepth--;
            }
            return err(Problem.LANGUAGE, "filter-context functions (calc, share_of, parent_of, as_of) nest at most " + Signatures.MAX_CONTEXT_DEPTH
                    + " deep", "make the inner part a measure", at);
        }
        ElType first = ElType.ANY;
        try {
            for (int i = 0; i < f.params().size(); i++) {
                Signatures.Param p = f.params().get(i);
                boolean rowCondition = context && "where".equals(p.name());       // calc(agg, where = cond): cond is read per row
                if (rowCondition) {
                    aggregateDepth++;
                }
                try {
                    for (Expr a : bound.get(i)) {
                        ElType t = argument(f, shown, p, a);
                        if (i == 0) {
                            first = t;
                        }
                    }
                } finally {
                    if (rowCondition) {
                        aggregateDepth--;
                    }
                }
            }
        } finally {
            if (rows) {
                aggregateDepth--;
            }
            if (context) {
                contextDepth--;
            }
        }
        if (f.takesAggregate()) {
            sawAggregate = true;
        }
        if ("switch".equals(name) && args.size() >= 3) {
            return visitQuietly(args.get(2));
        }
        return "same".equals(f.returns()) ? first : ElType.ofReturn(f.returns());
    }

    /** The type of an argument already checked (the problems it has are already recorded). */
    private ElType visitQuietly(Expr e) {
        int before = problems.size();
        ElType t = visit(e);
        while (problems.size() > before) {
            problems.remove(problems.size() - 1);
        }
        return t;
    }

    private String where() {
        return switch (scope) {
            case ROW -> "in a row expression (a column or a filter)";
            case RULE -> "in a row rule";
            default -> "here";
        };
    }

    private ElType measure(Expr at, List<Expr> args, Map<String, Expr> named) {
        if (args.isEmpty() || !named.isEmpty() || !(args.get(0) instanceof Expr.Lit l && l.value() instanceof String s)) {
            return err(Problem.LANGUAGE, "measure() takes the measure's name or id as text: measure('MTM')", null, at);
        }
        if (scope != null && scope != Scope.AGGREGATE && scope != Scope.CONTEXT) {
            return err(Problem.LANGUAGE, "measure('" + s + "') belongs in a measure, not " + where(), null, at);
        }
        if (aggregateDepth > 0) {
            return err(Problem.LANGUAGE, "a measure inside an aggregate (measure('" + s + "') within sum(...))",
                    "aggregate columns; combine measures outside aggregates", at);
        }
        sawAggregate = true;
        if (args.size() > 1) {
            usedMeasures.add(s);                                               // an engine's measure for a scope (VaR, ES ...): its engine checks it
            return ElType.NUMBER;
        }
        Optional<String> m = fields.measure(s);
        if (m.isEmpty()) {
            return err(Problem.NAME, "no measure '" + s + "'" + suggest(s), null, at);
        }
        usedMeasures.add(m.get());
        return ElType.NUMBER;
    }

    /** The arguments of each parameter, in parameter order (a variadic parameter has several); null after a problem. */
    private List<List<Expr>> bind(Signatures.Signature f, Expr at, List<Expr> args, Map<String, Expr> named) {
        List<List<Expr>> values = new ArrayList<>();
        f.params().forEach(p -> values.add(new ArrayList<>()));
        String shown = spelling.name(f.name());
        int pi = 0;
        boolean ok = true;
        boolean byPlace = "R".equals(f.runs());                                  // Rachana's own functions take every argument by place
        for (Expr a : args) {
            while (!byPlace && pi < f.params().size() && f.params().get(pi).keywordOnly()) {
                pi++;
            }
            if (pi >= f.params().size()) {
                Signatures.Param kw = f.params().stream().filter(Signatures.Param::keywordOnly).findFirst().orElse(null);
                int n = positional(f);
                err(Problem.LANGUAGE, shown + "() takes at most " + n + " argument" + (n == 1 ? "" : "s") + " before its named ones; the signature is "
                        + f.signature(), kw == null ? null : "name it: " + spelling.named(kw.name()) + "...", a);
                ok = false;
                break;
            }
            if (!(a instanceof Expr.Absent)) {
                values.get(pi).add(a);
            }
            if (!f.params().get(pi).variadic()) {
                pi++;
            }
        }
        for (Map.Entry<String, Expr> k : named.entrySet()) {
            int idx = indexOf(f, k.getKey());
            if (idx < 0) {
                List<String> kws = f.params().stream().filter(Signatures.Param::keywordOnly).map(Signatures.Param::name).toList();
                err(Problem.LANGUAGE, shown + "() has no named argument " + spelling.named(k.getKey()).strip() + (kws.isEmpty() ? ""
                        : "; its named arguments: " + String.join(", ", kws.stream().map(x -> spelling.named(x).strip()).toList())), null, k.getValue());
                ok = false;
            } else if (!values.get(idx).isEmpty()) {
                err(Problem.LANGUAGE, shown + "() gets " + k.getKey() + " twice", null, k.getValue());
                ok = false;
            } else {
                values.get(idx).add(k.getValue());
            }
        }
        for (int i = 0; i < f.params().size(); i++) {
            Signatures.Param p = f.params().get(i);
            if (!p.optional() && values.get(i).isEmpty()) {
                err(Problem.LANGUAGE, shown + "() needs " + (p.keywordOnly() ? spelling.named(p.name()).strip() : p.name()) + ": " + f.signature(), null, at);
                ok = false;
            }
        }
        if ("coalesce".equals(f.name()) && args.size() < 2) {
            err(Problem.LANGUAGE, "coalesce() needs two values or more", null, at);
            ok = false;
        }
        return ok ? values : null;
    }

    private static int positional(Signatures.Signature f) {
        return (int) f.params().stream().filter(p -> !p.keywordOnly()).count();
    }

    private static int indexOf(Signatures.Signature f, String name) {
        for (int i = 0; i < f.params().size(); i++) {
            if (f.params().get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** One argument against its parameter's type. */
    private ElType argument(Signatures.Signature f, String shown, Signatures.Param p, Expr a) {
        String what = shown + "(): " + p.name();
        switch (p.type()) {
            case "integer-literal", "number-literal" -> {
                Expr lit = a instanceof Expr.Neg u ? u.e() : a;
                if (!(lit instanceof Expr.Lit n && n.value() instanceof Number num)) {
                    return err(Problem.LANGUAGE, what + " is a " + ("integer-literal".equals(p.type()) ? "whole number" : "number")
                            + " written in the expression", null, a);
                }
                boolean integral = literal(n) == ElType.INTEGER;
                if ("integer-literal".equals(p.type()) && !integral) {
                    return err(Problem.LANGUAGE, what + " is a whole number written in the expression", null, a);
                }
                if ("percentile".equals(f.name()) && (num.doubleValue() < 0 || num.doubleValue() > 1 || lit != a)) {
                    return err(Problem.LANGUAGE, what + " is between 0 and 1 (0.5 is the median)", null, a);
                }
                return integral ? ElType.INTEGER : ElType.DECIMAL;
            }
            case "string-literal" -> {
                if (a instanceof Expr.Lit l && l.value() == null && p.optional()) {
                    return ElType.NONE;
                }
                if (!(a instanceof Expr.Lit l && l.value() instanceof String s)) {
                    return err(Problem.LANGUAGE, what + " is a text written in the expression ('...')", null, a);
                }
                if (("date".equals(f.name()) || "timestamp".equals(f.name())) && !isoDate(s)) {
                    return err(Problem.LANGUAGE, f.name() + "('" + s + "') is not an ISO " + f.name(), "date('2026-10-09'), timestamp('2026-10-09T09:30')", a);
                }
                return ElType.STRING;
            }
            case "boolean-literal" -> {
                if (!(a instanceof Expr.Lit l && l.value() instanceof Boolean)) {
                    return err(Problem.LANGUAGE, what + " is " + spelling.booleans(), null, a);
                }
                return ElType.BOOLEAN;
            }
            case "field", "date-field" -> {
                List<String> parts = parts(a);
                if (parts == null || parts.isEmpty() || "user".equals(parts.get(0))) {
                    return err(Problem.LANGUAGE, what + " is a field (alias.COLUMN)", null, a);
                }
                ElType t = field(parts, a, true);
                if ("date-field".equals(p.type()) && !t.fits(ElType.DATE)) {
                    return err(Problem.LANGUAGE, what + " is a date field; " + String.join(".", parts) + " is a " + t.word(), null, a);
                }
                return t;
            }
            case "fields", "fields-or-all" -> {
                List<String> all = parts(a);
                if (all != null && all.equals(List.of("ALL"))) {
                    return "fields-or-all".equals(p.type()) ? ElType.LIST
                            : err(Problem.LANGUAGE, what + " is a list of fields; ALL is only for " + spelling.named("ignore").strip() + " and "
                                    + spelling.named("over").strip(), null, a);
                }
                if (!(a instanceof Expr.ListLit l)) {
                    return err(Problem.LANGUAGE, what + " is a list of fields: " + spelling.named(p.name()) + "[alias.COLUMN, ...]"
                            + ("fields-or-all".equals(p.type()) ? " or ALL" : ""), null, a);
                }
                for (Expr item : l.items()) {
                    List<String> fp = parts(item);
                    if (fp == null || fp.isEmpty() || "user".equals(fp.get(0)) || "param".equals(fp.get(0))) {
                        err(Problem.LANGUAGE, what + " lists fields (alias.COLUMN)", null, item);
                    } else {
                        field(fp, item, true);
                        if ("by".equals(p.name()) && "avg_by".equals(f.name())) {
                            fields.field(fp).filter(Fields.Field::masked).ifPresent(x -> err(Problem.MASKED, "'" + String.join(".", fp)
                                    + "' is masked for you: avg_by cannot group on it", null, item));
                        }
                    }
                }
                return ElType.LIST;
            }
            case "table-or-any" -> {
                List<String> parts = parts(a);
                if (parts != null && parts.size() == 1 && (fields.alias(parts.get(0)) || !fields.known())) {
                    return ElType.TABLE;                                         // count(trades): the table's rows
                }
                return visit(a);
            }
            case "aggregate" -> {
                sawAggregate = false;
                ElType t = visit(a);
                if (!sawAggregate) {
                    err(Problem.LANGUAGE, what + " is an aggregate (sum(...), count(...), measure('...'))", "for example " + shown
                            + "(sum(trades.MTM), ...)", a);
                }
                sawAggregate = true;
                return t;
            }
            default -> {
                if (a instanceof Expr.Lit l && l.value() == null && p.optional()) {
                    return ElType.NONE;
                }
                ElType t = visit(a);
                ElType want = switch (p.type()) {
                    case "number" -> ElType.NUMBER;
                    case "string" -> ElType.STRING;
                    case "boolean" -> ElType.BOOLEAN;
                    case "date" -> ElType.DATE;
                    default -> ElType.ANY;
                };
                if ("date".equals(p.type()) && a instanceof Expr.Lit s && s.value() instanceof String txt && isoDate(txt)) {
                    warn(Problem.LANGUAGE, "a text read as a date", "write date('" + txt + "')", a);
                    return ElType.DATE;
                }
                if (!t.fits(want)) {
                    return err(Problem.LANGUAGE, what + " is a " + want.word() + "; this is a " + t.word() + " (checked mode has no implicit casts)", null, a);
                }
                if ("any".equals(p.type()) && t == ElType.LIST) {
                    return err(Problem.LANGUAGE, what + " is one value, not a list", null, a);
                }
                return t;
            }
        }
    }
}
