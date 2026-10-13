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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a DEL v2 tree against the scope it is written in, the function catalogue and (when known) the fields and measures the caller may use: function
 * names, arguments and keywords, the wave a function arrives in ({@code DRS-9129}), types (section 3.3.3: no implicit casts, booleans only for conditions,
 * {@code is None} instead of {@code == None}), aggregates where they belong ({@code DRS-9127} "an aggregate belongs in a measure", "wrap it: sum(...)"),
 * names that exist ({@code DRS-9152}) and masked fields in filters ({@code DRS-9161}). Collects every problem (it does not stop at the first) and the type
 * of the result. One checker checks one expression; not thread-safe, cheap to make.
 */
public final class DelChecker {

    public static final String LANGUAGE = DelParser.CODE;
    public static final String LATER = "DRS-9129";
    public static final String NAME = "DRS-9152";
    public static final String MASKED = "DRS-9161";

    /** The outcome: the result's type, the problems, and the functions, fields and measures the expression uses. */
    public record Checked(DelType type, List<DelException.Problem> problems, Set<String> functions, Set<String> fields, Set<String> measures) {
        public boolean ok() {
            return problems.stream().noneMatch(DelException.Problem::error);
        }

        public Optional<DelException.Problem> firstError() {
            return problems.stream().filter(DelException.Problem::error).findFirst();
        }
    }

    private final FunctionCatalog catalog;
    private final Scope scope;
    private final Fields fields;
    private final List<DelException.Problem> problems = new ArrayList<>();
    private final Set<String> functions = new LinkedHashSet<>();
    private final Set<String> usedFields = new LinkedHashSet<>();
    private final Set<String> usedMeasures = new LinkedHashSet<>();
    private int aggregateDepth;
    private boolean sawAggregate;

    private DelChecker(FunctionCatalog catalog, Scope scope, Fields fields) {
        this.catalog = catalog;
        this.scope = scope;
        this.fields = fields == null ? Fields.NONE : fields;
    }

    /** Checks a tree written in a scope ({@code null}: the language and the catalogue only, whatever the scope); {@code fields} may be {@link Fields#NONE}. */
    public static Checked check(DelAst tree, Scope scope, Fields fields) {
        return check(FunctionCatalog.get(), tree, scope, fields);
    }

    public static Checked check(FunctionCatalog catalog, DelAst tree, Scope scope, Fields fields) {
        DelChecker c = new DelChecker(catalog, scope, fields);
        DelType t = c.visit(tree);
        c.top(tree, t);
        return new Checked(t, List.copyOf(c.problems), Set.copyOf(c.functions), Set.copyOf(c.usedFields), Set.copyOf(c.usedMeasures));
    }

    private void top(DelAst tree, DelType t) {
        if (scope == null) {
            return;
        }
        switch (scope) {
            case RULE -> {
                if (!t.fits(DelType.BOOLEAN)) {
                    err(LANGUAGE, "a row rule is a condition (True or False for each row); this is a " + t.word(), "compare it: for example trades.desk in user.desks", tree);
                }
            }
            case AGGREGATE, CONTEXT -> {
                if (!sawAggregate && problems.stream().noneMatch(DelException.Problem::error)) {
                    err(LANGUAGE, "a measure aggregates the rows of each group; this expression does not", "wrap the column in an aggregate: sum(...), count(...), avg(...), or use measure('name')",
                            tree);
                }
            }
            default -> { }
        }
    }

    // ---- problems ----------------------------------------------------------------------------------------------------

    private DelType err(String code, String message, String fix, DelAst at) {
        problems.add(new DelException.Problem(code, DelException.Problem.ERROR, message, fix, at.start(), at.end()));
        return DelType.ANY;
    }

    private void warn(String code, String message, String fix, DelAst at) {
        problems.add(new DelException.Problem(code, DelException.Problem.WARNING, message, fix, at.start(), at.end()));
    }

    // ---- visiting ----------------------------------------------------------------------------------------------------

    DelType visit(DelAst n) {
        return switch (n) {
            case DelAst.Num x -> x.integral() ? DelType.INTEGER : DelType.DECIMAL;
            case DelAst.Str x -> DelType.STRING;
            case DelAst.Bool x -> DelType.BOOLEAN;
            case DelAst.NoneLit x -> DelType.NONE;
            case DelAst.AllLit x -> err(LATER, "ALL is the value of ignore= and over= (filter context, wave B)", null, x);
            case DelAst.ListLit x -> err(LANGUAGE, "a list is only the right side of in (x in ['A', 'B']) or the value of by=[...]", null, x);
            case DelAst.Field f -> field(f, false);
            case DelAst.Call c -> call(c);
            case DelAst.Unary u -> unary(u);
            case DelAst.Binary b -> binary(b);
            case DelAst.BoolOp b -> {
                for (DelAst v : b.values()) {
                    DelType t = visit(v);
                    if (!t.fits(DelType.BOOLEAN)) {
                        err(LANGUAGE, "'" + b.op() + "' takes conditions (True or False); this side is a " + t.word(), "compare it, for example x > 0 or x is not None", v);
                    }
                }
                yield DelType.BOOLEAN;
            }
            case DelAst.Compare c -> compare(c);
            case DelAst.IfExp i -> {
                DelType test = visit(i.test());
                if (!test.fits(DelType.BOOLEAN)) {
                    err(LANGUAGE, "the condition of 'a if c else b' must be True or False; it is a " + test.word(), "compare it, for example x > 0", i.test());
                }
                DelType a = visit(i.body());
                DelType b = visit(i.orElse());
                if (!a.comparable(b)) {
                    err(LANGUAGE, "the two values of 'a if c else b' have different types (" + a.word() + " and " + b.word() + ")", "make both numbers, or both texts", i);
                }
                yield a == DelType.NONE ? b : a;
            }
        };
    }

    /** A field: {@code user.x} only in row rules, {@code param.x} from wave B, a column only inside an aggregate in aggregate scope (unless {@code bare}). */
    private DelType field(DelAst.Field f, boolean bare) {
        List<String> parts = f.parts();
        String head = parts.get(0);
        if ("user".equals(head)) {
            if (parts.size() != 2) {
                return err(LANGUAGE, "a user attribute is user.<name>, for example user.desks", null, f);
            }
            if (scope != null && scope != Scope.RULE) {
                return err(LANGUAGE, "user." + parts.get(1) + " is for row rules; " + (scope == Scope.ROW ? "a column's value" : "a measure") + " does not depend on the reader",
                        "put the condition in the dataset's security.rows", f);
            }
            return DelType.ANY;
        }
        if ("param".equals(head)) {
            return err(LATER, "parameters (param." + (parts.size() > 1 ? parts.get(1) : "x") + ") arrive in wave B", null, f);
        }
        if ((scope == Scope.AGGREGATE || scope == Scope.CONTEXT) && aggregateDepth == 0 && !bare) {
            return err(LANGUAGE, "the column " + f.text() + " is outside an aggregate: a measure has one value per group, not per row", "wrap it: sum(" + f.text() + ")", f);
        }
        Optional<Fields.Field> found = fields.field(parts);
        if (found.isEmpty()) {
            if (fields.alias(f.text())) {
                return err(LANGUAGE, "'" + f.text() + "' is a dataset, not a column", "count its rows with count(" + f.text() + "), or name a column: " + f.text() + ".<column>", f);
            }
            return err(NAME, "no field '" + f.text() + "'" + suggest(f.text()), null, f);
        }
        usedFields.add(found.get().path());
        return found.get().type();
    }

    private String suggest(String name) {
        List<String> names = fields.names();
        if (names.isEmpty()) {
            return "";
        }
        String best = names.stream().min(Comparator.comparingInt(x -> distance(x.toLowerCase(java.util.Locale.ROOT), name.toLowerCase(java.util.Locale.ROOT)))).orElse(null);
        return best != null && distance(best.toLowerCase(java.util.Locale.ROOT), name.toLowerCase(java.util.Locale.ROOT)) <= Math.max(2, name.length() / 3)
                ? "; did you mean " + best + "?" : "";
    }

    static int distance(String a, String b) {
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

    private DelType unary(DelAst.Unary u) {
        DelType t = visit(u.operand());
        if ("not".equals(u.op())) {
            if (!t.fits(DelType.BOOLEAN)) {
                err(LANGUAGE, "'not' takes a condition (True or False); this is a " + t.word(), "compare it, for example not (x > 0)", u);
            }
            return DelType.BOOLEAN;
        }
        if (!t.fits(DelType.NUMBER)) {
            return err(LANGUAGE, "'" + u.op() + "' needs a number; this is a " + t.word(), null, u);
        }
        return t;
    }

    private DelType binary(DelAst.Binary b) {
        DelType l = visit(b.left());
        DelType r = visit(b.right());
        String op = b.op();
        if ("-".equals(op) && l.temporal() && r.temporal()) {
            return DelType.INTEGER;                                             // date - date: days
        }
        if (l.temporal() || r.temporal()) {
            return err(LANGUAGE, "dates cannot be used with '" + op + "'", "days between two dates: days_between(a, b) or b - a", b);
        }
        if (l == DelType.STRING || r == DelType.STRING) {
            return err(LANGUAGE, "text cannot be used with '" + op + "' (DEL has no implicit casts and no text arithmetic)", null, b);
        }
        if (!l.fits(DelType.NUMBER) || !r.fits(DelType.NUMBER)) {
            return err(LANGUAGE, "'" + op + "' needs numbers; it has a " + l.word() + " and a " + r.word(), null, b);
        }
        return switch (op) {
            case "/", "**" -> DelType.DECIMAL;
            case "//" -> l == DelType.ANY || r == DelType.ANY ? DelType.ANY : DelType.INTEGER;
            default -> DelType.arithmetic(l, r);
        };
    }

    private DelType compare(DelAst.Compare c) {
        DelAst left = c.first();
        DelType lt = visit(left);
        for (int i = 0; i < c.ops().size(); i++) {
            String op = c.ops().get(i);
            DelAst right = c.rest().get(i);
            masked(left, op);
            switch (op) {
                case "is", "is not" -> {
                    if (!(right instanceof DelAst.NoneLit)) {
                        err(LANGUAGE, "'" + op + "' is only for None (x is None, x is not None)", "compare values with == or !=", right);
                    }
                }
                case "in", "not in" -> inList(lt, right, op);
                default -> {
                    if (right instanceof DelAst.NoneLit || left instanceof DelAst.NoneLit) {
                        String x = left instanceof DelAst.NoneLit ? "y" : "x";
                        err(LANGUAGE, "a comparison with None is never True in DEL", "write " + x + ("==".equals(op) ? " is None" : " is not None"), right);
                    } else {
                        DelType rt = visit(right);
                        if (!comparable(lt, rt, left, right)) {
                            err(LANGUAGE, "cannot compare a " + lt.word() + " with a " + rt.word() + " (DEL has no implicit casts)",
                                    lt.temporal() || rt.temporal() ? "write the date as date('YYYY-MM-DD')" : null, c);
                        }
                    }
                }
            }
            left = right;
            lt = right instanceof DelAst.ListLit || right instanceof DelAst.NoneLit ? DelType.ANY : lt;
        }
        return DelType.BOOLEAN;
    }

    /** A text written in ISO date form compared with a date is read as that date (the one allowed reading of a text as a date). */
    private static boolean comparable(DelType a, DelType b, DelAst left, DelAst right) {
        if (a.comparable(b)) {
            return true;
        }
        return a.temporal() && right instanceof DelAst.Str s && isoDate(s.value()) || b.temporal() && left instanceof DelAst.Str s2 && isoDate(s2.value());
    }

    static boolean isoDate(String s) {
        return s.matches("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?");
    }

    private void masked(DelAst left, String op) {
        if (left instanceof DelAst.Field f && !"user".equals(f.parts().get(0))) {
            fields.field(f.parts()).filter(Fields.Field::masked).ifPresent(x -> err(MASKED, "'" + f.text() + "' is masked for you: a filter on it (" + op + ") is refused",
                    "filter on a field that is not masked", f));
        }
    }

    private void inList(DelType lt, DelAst right, String op) {
        if (right instanceof DelAst.ListLit l) {
            for (DelAst item : l.items()) {
                DelType it = visit(item);
                if (item instanceof DelAst.NoneLit) {
                    err(LANGUAGE, "None in a list never matches", "test it on its own: x is None", item);
                } else if (!comparable(lt, it, null, item)) {
                    err(LANGUAGE, "the list has a " + it.word() + " where the value is a " + lt.word(), null, item);
                }
            }
            return;
        }
        if (right instanceof DelAst.Field f && "user".equals(f.parts().get(0))) {
            field(f, false);
            return;
        }
        err(LANGUAGE, "'" + op + "' takes a list: x " + op + " ['A', 'B'], or user.<attribute> in a row rule", right instanceof DelAst.Field ? null : "write the values in [ ]", right);
    }

    // ---- calls -------------------------------------------------------------------------------------------------------

    private DelType call(DelAst.Call c) {
        String name = c.name();
        functions.add(name);
        if (name.startsWith("fn.")) {
            return err(LATER, "user functions (" + name + ") arrive in wave B", null, c);
        }
        if ("measure".equals(name)) {
            return measure(c);
        }
        Optional<FunctionCatalog.Fn> found = catalog.function(name);
        if (found.isEmpty()) {
            String del = catalog.fromLegacy(name);
            if (!del.equals(name)) {
                return err(LANGUAGE, "'" + name + "' is the old name", "write " + del + "(...)", c);
            }
            if ("if".equals(name)) {
                return err(LANGUAGE, "if(c, a, b) is the old syntax", "write: a if c else b", c);
            }
            String near = catalog.names().stream().min(Comparator.comparingInt(x -> distance(x, name))).orElse("");
            return err(LANGUAGE, "unknown function '" + name + "'" + (distance(near, name) <= 2 ? "; did you mean " + near + "?" : ""), "see GET /api/v1/bi/expr/functions", c);
        }
        FunctionCatalog.Fn f = found.get();
        if (!f.built()) {
            return err(LATER, name + "() arrives in wave " + f.status() + " (" + f.family() + ")", null, c);
        }
        if (scope != null && !f.allowedIn(scope)) {
            if (f.aggregate() || f.takesAggregate()) {
                return err(LANGUAGE, "an aggregate belongs in a measure: " + name + "() cannot be used " + where(), "make it a measure of the model", c);
            }
            return err(LANGUAGE, name + "() cannot be used " + where(), null, c);
        }
        if (f.aggregate() && !f.takesAggregate() && aggregateDepth > 0) {
            return err(LANGUAGE, "an aggregate inside an aggregate (" + name + "() within another)", "aggregate the column once; for two levels use avg_by (wave B)", c);
        }
        Bound b = bind(f, c);
        if (b == null) {
            sawAggregate |= f.aggregate() || f.takesAggregate();
            return DelType.ANY;
        }
        boolean rows = f.aggregate() && !f.takesAggregate();                   // sum(x) reads rows; ratio(sum(x), sum(y)) combines aggregates
        if (rows) {
            sawAggregate = true;
            aggregateDepth++;
        }
        DelType first = DelType.ANY;
        try {
            for (int i = 0; i < f.params().size(); i++) {
                FunctionCatalog.Param p = f.params().get(i);
                for (DelAst a : b.values().get(i)) {
                    DelType t = argument(f, p, a);
                    if (i == 0) {
                        first = t;
                    }
                }
            }
        } finally {
            if (rows) {
                aggregateDepth--;
            }
        }
        if (f.takesAggregate()) {
            sawAggregate = true;
        }
        return "same".equals(f.returns()) ? first : DelType.ofReturn(f.returns());
    }

    private String where() {
        return switch (scope) {
            case ROW -> "in a row expression (a column or a filter)";
            case RULE -> "in a row rule";
            default -> "here";
        };
    }

    private DelType measure(DelAst.Call c) {
        if (c.args().size() != 1 || !c.keywords().isEmpty() || !(c.args().get(0) instanceof DelAst.Str s)) {
            return err(LANGUAGE, "measure() takes the measure's name or id as text: measure('MTM')", null, c);
        }
        if (scope != null && scope != Scope.AGGREGATE && scope != Scope.CONTEXT) {
            return err(LANGUAGE, "measure('" + s.value() + "') belongs in a measure, not " + where(), null, c);
        }
        if (aggregateDepth > 0) {
            return err(LANGUAGE, "a measure inside an aggregate (measure('" + s.value() + "') within sum(...))", "aggregate columns; combine measures outside aggregates", c);
        }
        sawAggregate = true;
        Optional<String> m = fields.measure(s.value());
        if (m.isEmpty()) {
            return err(NAME, "no measure '" + s.value() + "'" + suggest(s.value()), null, c);
        }
        usedMeasures.add(m.get());
        return DelType.NUMBER;
    }

    /** The arguments of each parameter, in parameter order (a variadic parameter has several). */
    private record Bound(List<List<DelAst>> values) {}

    private Bound bind(FunctionCatalog.Fn f, DelAst.Call c) {
        List<List<DelAst>> values = new ArrayList<>();
        f.params().forEach(p -> values.add(new ArrayList<>()));
        int pi = 0;
        boolean ok = true;
        for (DelAst a : c.args()) {
            while (pi < f.params().size() && f.params().get(pi).keywordOnly()) {
                pi++;
            }
            if (pi >= f.params().size()) {
                FunctionCatalog.Param kw = f.params().stream().filter(FunctionCatalog.Param::keywordOnly).findFirst().orElse(null);
                err(LANGUAGE, f.name() + "() takes at most " + positional(f) + " argument" + (positional(f) == 1 ? "" : "s") + " before its keywords; the signature is " + f.signature(),
                        kw == null ? null : "name it: " + kw.name() + "=...", a);
                ok = false;
                break;
            }
            values.get(pi).add(a);
            if (!f.params().get(pi).variadic()) {
                pi++;
            }
        }
        for (DelAst.Keyword k : c.keywords()) {
            int idx = indexOf(f, k.name());
            if (idx < 0) {
                List<String> kws = f.params().stream().filter(FunctionCatalog.Param::keywordOnly).map(FunctionCatalog.Param::name).toList();
                err(LANGUAGE, f.name() + "() has no keyword " + k.name() + "=" + (kws.isEmpty() ? "" : "; its keywords: " + String.join(", ", kws.stream().map(x -> x + "=").toList())),
                        null, k.value());
                ok = false;
            } else if (!values.get(idx).isEmpty()) {
                err(LANGUAGE, f.name() + "() gets " + k.name() + " twice", null, k.value());
                ok = false;
            } else {
                values.get(idx).add(k.value());
            }
        }
        for (int i = 0; i < f.params().size(); i++) {
            FunctionCatalog.Param p = f.params().get(i);
            if (!p.optional() && values.get(i).isEmpty()) {
                err(LANGUAGE, f.name() + "() needs " + p.name() + (p.keywordOnly() ? "=" : "") + ": " + f.signature(), null, c);
                ok = false;
            }
        }
        if (f.params().stream().anyMatch(FunctionCatalog.Param::variadic) && c.args().size() < 2 && "coalesce".equals(f.name())) {
            err(LANGUAGE, "coalesce() needs two values or more", null, c);
            ok = false;
        }
        return ok ? new Bound(values) : null;
    }

    private static int positional(FunctionCatalog.Fn f) {
        return (int) f.params().stream().filter(p -> !p.keywordOnly()).count();
    }

    private static int indexOf(FunctionCatalog.Fn f, String name) {
        for (int i = 0; i < f.params().size(); i++) {
            if (f.params().get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** One argument against its parameter's type. */
    private DelType argument(FunctionCatalog.Fn f, FunctionCatalog.Param p, DelAst a) {
        String what = f.name() + "(): " + p.name();
        switch (p.type()) {
            case "integer-literal", "number-literal" -> {
                DelAst lit = a instanceof DelAst.Unary u && "-".equals(u.op()) ? u.operand() : a;
                if (!(lit instanceof DelAst.Num n) || "integer-literal".equals(p.type()) && !n.integral()) {
                    return err(LANGUAGE, what + " is a " + ("integer-literal".equals(p.type()) ? "whole number" : "number") + " written in the expression", null, a);
                }
                if ("percentile".equals(f.name()) && (n.value() < 0 || n.value() > 1 || lit != a)) {
                    return err(LANGUAGE, what + " is between 0 and 1 (0.5 is the median)", null, a);
                }
                return n.integral() ? DelType.INTEGER : DelType.DECIMAL;
            }
            case "string-literal" -> {
                if (!(a instanceof DelAst.Str s)) {
                    return err(LANGUAGE, what + " is a text written in the expression ('...')", null, a);
                }
                if (("date".equals(f.name()) || "timestamp".equals(f.name())) && !isoDate(s.value())) {
                    return err(LANGUAGE, f.name() + "('" + s.value() + "') is not an ISO " + f.name(), "date('2026-10-09'), timestamp('2026-10-09T09:30')", a);
                }
                return DelType.STRING;
            }
            case "boolean-literal" -> {
                if (!(a instanceof DelAst.Bool)) {
                    return err(LANGUAGE, what + " is True or False", null, a);
                }
                return DelType.BOOLEAN;
            }
            case "field", "date-field" -> {
                if (!(a instanceof DelAst.Field fl) || "user".equals(fl.parts().get(0))) {
                    return err(LANGUAGE, what + " is a field (alias.column)", null, a);
                }
                DelType t = field(fl, true);
                if ("date-field".equals(p.type()) && !t.fits(DelType.DATE)) {
                    return err(LANGUAGE, what + " is a date field; " + fl.text() + " is a " + t.word(), null, a);
                }
                return t;
            }
            case "fields" -> {
                if (a instanceof DelAst.AllLit) {
                    return err(LATER, "ALL is the value of ignore= and over= (filter context, wave B)", null, a);
                }
                if (!(a instanceof DelAst.ListLit l)) {
                    return err(LANGUAGE, what + " is a list of fields: " + p.name() + "=[alias.column, ...]", null, a);
                }
                for (DelAst item : l.items()) {
                    if (!(item instanceof DelAst.Field fl)) {
                        err(LANGUAGE, what + " lists fields (alias.column)", null, item);
                    } else {
                        field(fl, true);
                    }
                }
                return DelType.LIST;
            }
            case "table-or-any" -> {
                if (a instanceof DelAst.Field fl && fl.parts().size() == 1 && fields.alias(fl.text())) {
                    return DelType.TABLE;
                }
                if (a instanceof DelAst.Field fl && fl.parts().size() == 1 && !fields.known()) {
                    return DelType.TABLE;                                       // count(trades) checked without a model
                }
                return visit(a);
            }
            case "aggregate" -> {
                sawAggregate = false;
                DelType t = visit(a);
                if (!sawAggregate) {
                    err(LANGUAGE, what + " is an aggregate (sum(...), count(...), measure('...'))", "for example " + f.name() + "(sum(trades.mtm), ...)", a);
                }
                sawAggregate = true;
                return t;
            }
            default -> {
                DelType t = visit(a);
                DelType want = switch (p.type()) {
                    case "number" -> DelType.NUMBER;
                    case "string" -> DelType.STRING;
                    case "boolean" -> DelType.BOOLEAN;
                    case "date" -> DelType.DATE;
                    default -> DelType.ANY;
                };
                if ("date".equals(p.type()) && a instanceof DelAst.Str s && isoDate(s.value())) {
                    warn(LANGUAGE, "a text read as a date", "write date('" + s.value() + "')", a);
                    return DelType.DATE;
                }
                if (!t.fits(want)) {
                    return err(LANGUAGE, what + " is a " + want.word() + "; this is a " + t.word() + " (DEL has no implicit casts)", null, a);
                }
                if ("any".equals(p.type()) && t == DelType.LIST) {
                    return err(LANGUAGE, what + " is one value, not a list", null, a);
                }
                return t;
            }
        }
    }
}
