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
package com.ash.drishti.tables.check;

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.expr.Node;
import com.ash.drishti.tables.expr.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * The layer's own checks of an expression (BI_FOUNDATION_2911E.md sections 2.2 and 2.8), without types:
 *
 * <ul>
 *   <li>it reads ({@code DRS-9600} with the offset);</li>
 *   <li>row and rule scope: no aggregate of rows ({@code DRS-9127}: "an aggregate belongs in a measure"), except an aggregate over a list
 *       ({@code sum(@.FLOWS[*].AMOUNT)}, Rachana's list form); no bare column name (an expression reads the source: write the column's
 *       path); {@code @} and {@code ^} only under a grain deep enough; {@code user.x} only in rules;</li>
 *   <li>aggregate scope: no document path ({@code DRS-9616}, Q21); {@code alias.COLUMN} must be a column of the model; a bare alias only
 *       as {@code count(alias)}; {@code measure('name')} must name a measure of the model; context functions at most three deep;</li>
 *   <li>everywhere: no {@code ctl.x}, {@code link(...)}, {@code fmt(...)} ({@code DRS-9615});</li>
 *   <li>row and rule scope: Rachana compiles it today ({@code ElCompiler}); when it does not only because of what checked mode adds
 *       ({@code in}, lists, named arguments, {@code ^}), that is {@code DRS-9612} (info), decided once part A's checker is chained.</li>
 * </ul>
 */
public final class LayerExpressionChecker implements ExpressionChecker {

    private static final ElCompiler RACHANA = new ElCompiler(2_000);

    @Override
    public List<Problem> check(String text, Scope scope, Env env, String path) {
        List<Problem> out = new ArrayList<>();
        Node tree;
        try {
            tree = Reader.read(text);
        } catch (Reader.ReadException e) {
            out.add(Problem.error(TableCodes.UNREADABLE, path, "the expression does not read: " + e.getMessage()));
            return out;
        }
        boolean[] checkedMode = {false};
        tree.walk(n -> {
            switch (n) {
                case Node.Call c -> call(c, scope, env, path, out);
                case Node.Name x -> name(x, scope, env, path, out);
                case Node.Path p -> pathNode(p, scope, env, path, out, checkedMode);
                case Node.ListOf l -> checkedMode[0] = true;
                case Node.Binary b when b.op().equals("in") || b.op().equals("not-in") -> checkedMode[0] = true;
                default -> { }
            }
            if (n instanceof Node.Call c && c.args().stream().anyMatch(a -> a.name() != null)) {
                checkedMode[0] = true;
            }
        });
        if (scope == Scope.AGGREGATE && contextDepth(tree) > 3) {
            out.add(Problem.error(TableCodes.SCOPE, path, "calc, share_of, parent_of and as_of nest at most three deep"));
        }
        if ((scope == Scope.ROW || scope == Scope.RULE) && out.stream().noneMatch(Problem::isError)) {
            try {
                RACHANA.compile(text);
            } catch (RuntimeException e) {
                if (checkedMode[0]) {
                    out.add(Problem.info(TableCodes.PENDING, path, "uses what Rachana's checked mode adds (in, lists, named arguments, ^): "
                            + "decided when checked mode is chained (2.9.11.e part A)"));
                } else {
                    out.add(Problem.error(TableCodes.UNREADABLE, path, "Rachana does not read it: " + e.getMessage()));
                }
            }
        }
        return out;
    }

    private static void call(Node.Call c, Scope scope, Env env, String path, List<Problem> out) {
        String f = c.function();
        if (FunctionNames.DISPLAY.contains(f)) {
            out.add(Problem.error(TableCodes.DISPLAY, path, f + "(...) is a display concern of a Sutra: a column or a measure has a format"));
            return;
        }
        boolean rowish = scope != Scope.AGGREGATE;
        if (rowish && FunctionNames.measureOnly(f)) {
            boolean listForm = FunctionNames.AGGREGATES.contains(f) && !c.args().isEmpty() && isList(c.args().get(0).value());
            boolean leastGreatest = (f.equals("min") || f.equals("max")) && c.positional().size() >= 2;
            if (!listForm && !leastGreatest) {
                out.add(Problem.error(TableCodes.SCOPE, path, f + "(...) aggregates rows: an aggregate belongs in a measure of a model"));
            }
        }
        if (!rowish && f.equals("measure") && !c.args().isEmpty() && c.args().get(0).value() instanceof Node.Lit l && l.value() instanceof String m
                && c.args().size() == 1 && !env.measures().contains(m)) {
            out.add(Problem.error(TableCodes.REFERENCE, path, "measure('" + m + "'): the model has no measure of that name"));
        }
        if (f.equals("related")) {
            related(c, scope, path, out);
        }
    }

    /** {@code related(table.COLUMN, on = key)} in a table (Q29); {@code related(alias.COLUMN)} in a model. */
    private static void related(Node.Call c, Scope scope, String path, List<Problem> out) {
        if (c.positional().size() != 1 || !(c.positional().get(0) instanceof Node.Name)) {
            out.add(Problem.error(TableCodes.SCOPE, path, "related(...) reads one column of the table it points at: related(domain.table.COLUMN, on = KEY)"));
            return;
        }
        if (scope != Scope.AGGREGATE && c.named("on") == null) {
            out.add(Problem.error(TableCodes.SCOPE, path, "related(...) in a table says which of its columns holds the key: related(domain.table.COLUMN, on = KEY)"));
        }
    }

    private static void name(Node.Name x, Scope scope, Env env, String path, List<Problem> out) {
        String n = x.name();
        String head = x.head();
        if (head.equals("ctl")) {
            out.add(Problem.error(TableCodes.DISPLAY, path, n + " is a Sutra control: tables and models have no controls"));
            return;
        }
        if (head.equals("param") || n.equals("ALL") || n.equals("SELECTED")) {
            return;
        }
        if (head.equals("user")) {
            if (scope == Scope.ROW || scope == Scope.AGGREGATE) {
                out.add(Problem.error(TableCodes.SCOPE, path, n + " belongs in a row rule, never in a column or a measure"));
            }
            return;
        }
        switch (scope) {
            case ROW, RULE -> {
                if (env.columns().contains(n)) {
                    out.add(Problem.error(TableCodes.SCOPE, path, "'" + n + "' is a column of this table: an expression reads the source, so write the column's path"
                            + " ($." + n + " or @." + n + ")"));
                } else if (!n.contains(".")) {
                    out.add(Problem.error(TableCodes.UNKNOWN_NAME, path, "'" + n + "' is not a name here: a table's expressions read paths ($, @, ^), param.x"
                            + (scope == Scope.RULE ? " and user.x" : "")));
                }
            }
            case AGGREGATE, MODEL_RULE -> {
                if (env.aliases().contains(n)) {
                    return;                                                 // count(trades): the alias's rows (checked mode decides where it may stand)
                }
                if (!env.columns().contains(n) && !env.columns().contains(withoutDatePart(n))) {
                    out.add(Problem.error(TableCodes.UNKNOWN_NAME, path, "'" + n + "' is not a column of the model (alias.COLUMN)"));
                }
            }
            default -> { }
        }
    }

    private static void pathNode(Node.Path p, Scope scope, Env env, String path, List<Problem> out, boolean[] checkedMode) {
        if (scope == Scope.AGGREGATE || scope == Scope.MODEL_RULE) {
            out.add(Problem.error(TableCodes.PATH_IN_MEASURE, path, "a document path (" + p.text() + ") belongs in a table's column, defined once; "
                    + "a model reads alias.COLUMN"));
            return;
        }
        int need = switch (p.root()) {
            case "@" -> 1;
            case "^" -> 1;
            case "^^" -> 2;
            default -> 0;
        };
        if (p.root().startsWith("^")) {
            checkedMode[0] = true;
        }
        if (env.grainDepth() < need) {
            out.add(Problem.error(TableCodes.PATH, path, p.root() + " names the grain's element" + (p.root().startsWith("^") ? "'s parent" : "")
                    + ", and this table " + (env.grainDepth() == 0 ? "has no grain" : "has a grain one list deep")));
        }
    }

    /** {@code trades.TRADE_DATE.month} is the field {@code trades.TRADE_DATE} by month. */
    static String withoutDatePart(String n) {
        for (String part : List.of(".year", ".quarter", ".month", ".week", ".day")) {
            if (n.endsWith(part)) {
                return n.substring(0, n.length() - part.length());
            }
        }
        return n;
    }

    private static boolean isList(Node n) {
        return n instanceof Node.ListOf || (n instanceof Node.Path p && p.rest().contains("[*]"));
    }

    private static int contextDepth(Node n) {
        int below = 0;
        for (Node c : n.children()) {
            below = Math.max(below, contextDepth(c));
        }
        return below + (n instanceof Node.Call c && FunctionNames.CONTEXT.contains(c.function()) ? 1 : 0);
    }
}
