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

import com.ash.drishti.rachana.el.check.Checker;
import com.ash.drishti.rachana.el.check.ElType;
import com.ash.drishti.rachana.el.check.Fields;
import com.ash.drishti.rachana.el.check.Problem;
import com.ash.drishti.rachana.el.check.Scope;
import com.ash.drishti.rachana.el.check.Signatures;
import com.ash.drishti.rachana.el.check.Spelling;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * DEL's checker, now Rachana's (BI_FOUNDATION_2911E.md 2.1: the type checker moved into {@code drishti-rachana} as checked mode, and
 * DEL calls it until the migration of part B deletes DEL): the DEL tree is mapped to Rachana's ({@link DelToRachana}) and checked by
 * {@link Checker} with the fixes worded in DEL ({@link Spelling#DEL}). What only DEL has is checked here first: the comparison with
 * {@code None} (never true in DEL's SQL), {@code is} only for {@code None}, {@code None} in a list, the old names and the old
 * {@code if(...)}, {@code fn.x(...)}, and the functions of DEL's own catalogue ({@code bi/expr/functions.yaml}; Rachana's tier-1
 * functions are not DEL). Codes, scopes and types are checked mode's: {@code DRS-9127}, {@code DRS-9129}, {@code DRS-9152},
 * {@code DRS-9161}.
 */
public final class DelChecker {

    public static final String LANGUAGE = DelParser.CODE;
    public static final String LATER = Problem.LATER;
    public static final String NAME = Problem.NAME;
    public static final String MASKED = Problem.MASKED;

    /** The outcome: the result's type, the problems, and the functions (DEL's names), fields and measures the expression uses. */
    public record Checked(ElType type, List<Problem> problems, Set<String> functions, Set<String> fields, Set<String> measures) {
        public boolean ok() {
            return problems.stream().noneMatch(Problem::error);
        }

        public Optional<Problem> firstError() {
            return problems.stream().filter(Problem::error).findFirst();
        }
    }

    private final FunctionCatalog catalog;
    private final List<Problem> problems = new ArrayList<>();

    private DelChecker(FunctionCatalog catalog) {
        this.catalog = catalog;
    }

    /** Checks a tree written in a scope ({@code null}: the language and the catalogue only, whatever the scope); {@code fields} may be {@link Fields#NONE}. */
    public static Checked check(DelAst tree, Scope scope, Fields fields) {
        return check(FunctionCatalog.get(), tree, scope, fields);
    }

    public static Checked check(FunctionCatalog catalog, DelAst tree, Scope scope, Fields fields) {
        DelChecker c = new DelChecker(catalog);
        DelAst.walk(tree, c::delOnly);
        if (c.problems.stream().anyMatch(Problem::error)) {
            Set<String> fns = new LinkedHashSet<>();
            DelAst.walk(tree, n -> {
                if (n instanceof DelAst.Call call) {
                    fns.add(call.name());
                }
            });
            return new Checked(ElType.ANY, List.copyOf(c.problems), Set.copyOf(fns), Set.of(), Set.of());
        }
        DelToRachana.Mapped m = DelToRachana.map(tree);
        Checker.Checked r = Checker.check(Signatures.get(), m.expr(), m.spans(), "", scope, fields, Spelling.DEL);
        List<Problem> all = new ArrayList<>(c.problems);
        all.addAll(r.problems());
        Set<String> fns = new LinkedHashSet<>();
        r.functions().forEach(f -> fns.add(Spelling.DEL.name(f)));
        return new Checked(r.type(), List.copyOf(all), Set.copyOf(fns), r.fields(), r.measures());
    }

    private void err(String code, String message, String fix, DelAst at) {
        problems.add(new Problem(code, Problem.ERROR, message, fix, at.start(), at.end()));
    }

    /** What only DEL refuses (its syntax and its catalogue); everything else is checked mode's. */
    private void delOnly(DelAst n) {
        switch (n) {
            case DelAst.Call c -> call(c);
            case DelAst.Compare c -> {
                DelAst left = c.first();
                for (int i = 0; i < c.ops().size(); i++) {
                    String op = c.ops().get(i);
                    DelAst right = c.rest().get(i);
                    switch (op) {
                        case "is", "is not" -> {
                            if (!(right instanceof DelAst.NoneLit)) {
                                err(LANGUAGE, "'" + op + "' is only for None (x is None, x is not None)", "compare values with == or !=", right);
                            }
                        }
                        case "in", "not in" -> {
                            if (right instanceof DelAst.ListLit l) {
                                l.items().stream().filter(DelAst.NoneLit.class::isInstance)
                                        .forEach(item -> err(LANGUAGE, "None in a list never matches", "test it on its own: x is None", item));
                            }
                        }
                        default -> {
                            if (right instanceof DelAst.NoneLit || left instanceof DelAst.NoneLit) {
                                String x = left instanceof DelAst.NoneLit ? "y" : "x";
                                err(LANGUAGE, "a comparison with None is never True in DEL", "write " + x + ("==".equals(op) ? " is None" : " is not None"), right);
                            }
                        }
                    }
                    left = right;
                }
            }
            default -> { }
        }
    }

    private void call(DelAst.Call c) {
        String name = c.name();
        if (name.startsWith("fn.")) {
            err(LATER, "user functions (" + name + ") arrive in wave B", null, c);
            return;
        }
        if ("measure".equals(name)) {
            return;
        }
        Optional<Signatures.Signature> found = catalog.function(name);
        if (found.isEmpty()) {
            String del = catalog.fromLegacy(name);
            if (!del.equals(name)) {
                err(LANGUAGE, "'" + name + "' is the old name", "write " + del + "(...)", c);
            } else if ("if".equals(name)) {
                err(LANGUAGE, "if(c, a, b) is the old syntax", "write: a if c else b", c);
            } else {
                String near = catalog.names().stream().min(Comparator.comparingInt(x -> Checker.distance(x, name))).orElse("");
                err(LANGUAGE, "unknown function '" + name + "'" + (Checker.distance(near, name) <= 2 ? "; did you mean " + near + "?" : ""),
                        "see GET /api/v1/bi/expr/functions", c);
            }
            return;
        }
        if (!found.get().built()) {
            err(LATER, name + "() arrives in wave " + found.get().status() + " (" + found.get().family() + ")", null, c);
        }
    }
}
