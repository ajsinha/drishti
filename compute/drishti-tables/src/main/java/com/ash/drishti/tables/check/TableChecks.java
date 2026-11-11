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

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.def.TableName;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.tables.lang.Trees;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks one virtual table on its own (HOW_IT_FITS.md §A28 sections 3.1 and 3.2; Q9, Q11 to Q14, Q29): its name, source, grain,
 * filter, columns (paths, expressions, types, built-ins), keys and de-duplication, materialisation, rules and tests. What needs other
 * definitions (a base table, a model, {@code related(...)}) is {@code CatalogueChecks}'s.
 */
public final class TableChecks {

    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern AGE = Pattern.compile("\\d+[smhd]|P(T)?[0-9A-Z.]+");
    private static final Set<String> TESTS = Set.of("unique", "not-null", "references-exist", "agg", "expect", "name", "description", "columns", "column", "tolerance",
            "by", "row", "filters", "date", "role");
    private static final Set<String> AGGS = Set.of("count", "distinct", "sum", "avg", "min", "max");

    private final ExpressionChecker expressions;

    public TableChecks(ExpressionChecker expressions) {
        this.expressions = expressions;
    }

    /** With the layer's own expression checks only. */
    public TableChecks() {
        this(new LayerExpressionChecker());
    }

    /**
     * Every problem of {@code t}.
     *
     * @param folderDomain the folder the file is in ({@code trading} for {@code config/tables/trading/x.table.yaml}), or null for a stored item
     */
    public List<Problem> check(TableDef t, String folderDomain) {
        List<Problem> out = new ArrayList<>();
        if (!TableName.isTable(t.name())) {
            out.add(Problem.error(TableCodes.NAME, "table", "'" + t.name() + "' is not <domain>.<name> (lower case, letters, digits and underscores)"));
        } else if (folderDomain != null && !folderDomain.equals(t.domain())) {
            out.add(Problem.error(TableCodes.NAME, "table", "the file is in the folder '" + folderDomain + "' but names the domain '" + t.domain() + "'"));
        }
        if (!SEMVER.matcher(t.version()).matches()) {
            out.add(Problem.error(TableCodes.UNREADABLE, "version", "a version is major.minor.patch, not '" + t.version() + "'"));
        }
        TableDef.Source.Form form = t.from() == null ? TableDef.Source.Form.NONE : t.from().form();
        boolean documents = form == TableDef.Source.Form.KIND || form == TableDef.Source.Form.LAKE || form == TableDef.Source.Form.TABLE;
        if (form == TableDef.Source.Form.MODEL && t.from().modelQuery().isEmpty()) {
            out.add(Problem.error(TableCodes.UNREADABLE, "from.query", "a summary table asks its model a question: from: { model, query: { fields, measures } }"));
        }
        grain(t, documents, out);
        Map<String, String> paths = new java.util.LinkedHashMap<>();
        t.columns().values().stream().filter(c -> c.path() != null).forEach(c -> paths.putIfAbsent(c.path(), c.name()));
        ExpressionChecker.Env env = new ExpressionChecker.Env(t.columns().keySet(), Set.of(), Set.of(), types(t), masked(t), t.depth(), Set.of(), paths);
        if (t.filter() != null) {
            out.addAll(expressions.check(t.filter(), ExpressionChecker.Scope.ROW, env, "filter"));
        }
        columns(t, documents, form, env, out);
        keys(t, out);
        materialize(t, form, out);
        rules(t, env, out);
        for (int i = 0; i < t.tests().size(); i++) {
            Map<String, Object> test = t.tests().get(i);
            if (test.keySet().stream().noneMatch(k -> Set.of("unique", "not-null", "references-exist", "agg").contains(k))) {
                out.add(Problem.warning(TableCodes.UNREADABLE, "tests[" + i + "]", "a test is unique, not-null, references-exist or agg"));
            }
            String at = "tests[" + i + "].";
            test.keySet().stream().filter(k -> !TESTS.contains(k)).forEach(k -> out.add(Problem.warning(TableCodes.UNREADABLE, at + k, "unknown key")));
            if (test.containsKey("agg")) {                                   // an aggregate test: what it aggregates, what it expects, how near is near
                if (!AGGS.contains(String.valueOf(test.get("agg")))) {
                    out.add(Problem.error(TableCodes.UNREADABLE, at + "agg", "agg is one of " + String.join(", ", AGGS.stream().sorted().toList()) + ", not '" + test.get("agg") + "'"));
                }
                if (!test.containsKey("expect")) {
                    out.add(Problem.error(TableCodes.UNREADABLE, at + "expect", "an aggregate test says what it expects: expect: <number> or { min, max }"));
                }
                Object tol = test.get("tolerance");
                if (tol != null && !(tol instanceof Number) && !String.valueOf(tol).matches("\\d+(\\.\\d+)?%?")) {
                    out.add(Problem.error(TableCodes.UNREADABLE, at + "tolerance", "a tolerance is a number or a percentage such as 1%, not '" + tol + "'"));
                }
            }
        }
        return out;
    }

    private static void grain(TableDef t, boolean documents, List<Problem> out) {
        if (t.grain() == null) {
            return;
        }
        if (!documents) {
            out.add(Problem.error(TableCodes.PATH, "grain", "a grain reads lists inside documents: this table's source is not documents"));
            return;
        }
        String name;
        try {
            name = Trees.name(Trees.parse(t.grain()));
        } catch (RuntimeException e) {
            out.add(Problem.error(TableCodes.PATH, "grain", "the grain does not read: " + e.getMessage()));
            return;
        }
        if (name == null || !name.startsWith("$.") || !name.endsWith("[*]") || name.matches(".*\\[\\d+].*")) {
            out.add(Problem.error(TableCodes.PATH, "grain", "a grain is a path from $ that ends in a list: $.LEGS[*] or $.LEGS[*].CASH_FLOWS[*]"));
        }
    }

    private void columns(TableDef t, boolean documents, TableDef.Source.Form form, ExpressionChecker.Env env, List<Problem> out) {
        if (t.columns().isEmpty() && form != TableDef.Source.Form.TABLE) {                 // a table over a table reads its base's columns
            out.add(Problem.error(TableCodes.COLUMN, "columns", "a table has at least one column"));
        }
        Set<String> masked = masked(t);
        for (ColumnDef c : t.columns().values()) {
            String p = "columns." + c.name();
            if (TableDef.BUILT_INS.contains(c.name())) {
                out.add(Problem.error(TableCodes.BUILT_IN, p, "'" + c.name() + "' is a built-in column of every table (" + String.join(", ", TableDef.BUILT_INS.stream().sorted().toList())
                        + "): it is never declared"));
                continue;
            }
            if (c.forms() > 1) {
                out.add(Problem.error(TableCodes.COLUMN, p, "a column is one of path, expr or python, not several"));
            } else if (c.forms() == 0 && documents) {
                out.add(Problem.error(TableCodes.COLUMN, p, "give the column's path ($." + c.name() + (t.depth() > 0 ? " or @." + c.name() : "") + ") or an expr"));
            } else if (c.forms() == 1 && c.path() == null && c.expr() == null && form == TableDef.Source.Form.MODEL) {
                out.add(Problem.error(TableCodes.COLUMN, p, "a summary table's columns are its model answer's: no Python columns"));
            }
            if (c.path() != null) {
                path(c, t.depth(), p, out);
            }
            if (c.expr() != null) {
                out.addAll(expressions.check(c.expr(), ExpressionChecker.Scope.ROW, env, p + ".expr"));
                if (!c.masked() && readsMasked(c.expr(), t, masked)) {
                    out.add(Problem.info(TableCodes.COLUMN, p, "built from a masked column's path, so it is masked too"));
                }
            }
            if (c.type() != null && !ColumnDef.TYPES.contains(c.type())) {
                out.add(Problem.error(TableCodes.TYPE, p + ".type", "a type is one of " + new java.util.TreeSet<>(ColumnDef.TYPES) + ", not '" + c.type() + "'"));
            }
            if (c.role() != null && !ColumnDef.ROLES.contains(c.role())) {
                out.add(Problem.warning(TableCodes.UNREADABLE, p + ".role", "a role is one of " + new java.util.TreeSet<>(ColumnDef.ROLES)));
            }
            if (c.aggregate() != null && Set.of("sum", "avg").contains(c.aggregate()) && c.type() != null && !Set.of("integer", "decimal").contains(c.type())) {
                out.add(Problem.error(TableCodes.TYPE, p + ".aggregate", c.aggregate() + " needs a number: the column is " + c.type()));
            }
        }
    }

    private static void path(ColumnDef c, int depth, String p, List<Problem> out) {
        Expr n;
        try {
            n = Trees.parse(c.path());
        } catch (RuntimeException e) {
            out.add(Problem.error(TableCodes.PATH, p + ".path", "the path does not read: " + e.getMessage()));
            return;
        }
        String text = Trees.name(n);
        String root = Trees.root(n);
        if (text == null || root == null) {
            out.add(Problem.error(TableCodes.PATH, p + ".path", "a path starts at $, @ or ^ ($.MTM, @.NOTIONAL, ^.LEG_ID); an expression goes under expr"));
            return;
        }
        if (root.isEmpty()) {
            out.add(Problem.error(TableCodes.PATH, p + ".path", "a path starts at $, @ or ^ ($." + text + ")"));
            return;
        }
        if (text.contains("[*]")) {
            out.add(Problem.error(TableCodes.PATH, p + ".path", "a column holds one value: a list ([*]) is a grain, or an aggregate of the list in an expr "
                    + "(sum(" + text + "))"));
        }
        String rest = text.substring(root.length());
        int need = root.equals("@") || root.equals("^") ? 1 : root.equals("^^") ? 2 : 0;
        if (depth < need) {
            out.add(Problem.error(TableCodes.PATH, p + ".path", root + " names " + (root.equals("@") ? "the grain's element" : "a parent of the grain's element")
                    + ": this table's grain is " + (depth == 0 ? "the entity (use $)" : "one list deep")));
        } else if ((root.equals("^") && depth == 1) || (root.equals("^^") && depth == 2)) {
            out.add(Problem.warning(TableCodes.PATH, p + ".path", root + " is the entity itself at this grain: write $" + rest));
        }
    }

    private static void keys(TableDef t, List<Problem> out) {
        for (String k : t.keys()) {
            if (!t.columns().containsKey(k) && !Set.of("id", "index", "business_date").contains(k)) {
                out.add(Problem.error(TableCodes.KEYS, "keys", "'" + k + "' is not a column of the table"));
            }
        }
        if (t.keepLatest() != null) {
            if (t.keys().isEmpty()) {
                out.add(Problem.error(TableCodes.KEYS, "keep", "keep: { latest } chooses one row per key: give keys"));
            }
            if (!t.columns().containsKey(t.keepLatest()) && !TableDef.BUILT_INS.contains(t.keepLatest())) {
                out.add(Problem.error(TableCodes.KEYS, "keep.latest", "'" + t.keepLatest() + "' is not a column of the table"));
            }
        }
    }

    private static void materialize(TableDef t, TableDef.Source.Form form, List<Problem> out) {
        TableDef.Materialize m = t.materialize();
        if (!m.live()) {
            if (TableDef.Materialize.MODE_ON_LOAD.equals(m.mode()) && form == TableDef.Source.Form.MODEL) {
                out.add(Problem.info(TableCodes.MATERIALIZE, "materialize", "a summary table on-load is its model's answer kept per date"));
            }
            return;
        }
        if (t.keys().isEmpty()) {
            out.add(Problem.error(TableCodes.MATERIALIZE, "materialize", "a live table is kept by key: give keys"));
        }
        if (t.grain() != null) {
            out.add(Problem.error(TableCodes.MATERIALIZE, "materialize", "a table over a list (a grain) cannot be live"));
        }
        if (form != TableDef.Source.Form.KIND) {
            out.add(Problem.error(TableCodes.MATERIALIZE, "materialize", "only a kind's pushed entities are live: a " + form.name().toLowerCase(java.util.Locale.ROOT)
                    + " source is read on demand or on load"));
        }
        if (t.keepLatest() != null) {
            out.add(Problem.error(TableCodes.MATERIALIZE, "materialize", "de-duplicating (keep) a live table is not built: keep it on load"));
        }
        if (m.rows() != null && m.rows() <= 0) {
            out.add(Problem.error(TableCodes.MATERIALIZE, "materialize.live.rows", "rows is a positive number"));
        }
        if (m.age() != null && !AGE.matcher(m.age()).matches()) {
            out.add(Problem.error(TableCodes.MATERIALIZE, "materialize.live.age", "an age such as 2h, 30m or PT2H"));
        }
    }

    private void rules(TableDef t, ExpressionChecker.Env env, List<Problem> out) {
        for (int i = 0; i < t.rules().size(); i++) {
            TableDef.Rule r = t.rules().get(i);
            String p = "rules[" + i + "]";
            if (r.roles().isEmpty()) {
                out.add(Problem.error(TableCodes.RULE, p + ".role", "a rule names the roles it is for"));
            }
            if (r.where() == null || r.where().isBlank()) {
                out.add(Problem.error(TableCodes.RULE, p + ".where", "a rule says which rows: where: \"$.DESK in user.desks\" (or \"true\" for every row)"));
                continue;
            }
            out.addAll(expressions.check(r.where(), ExpressionChecker.Scope.RULE, env, p + ".where"));
        }
    }

    /** Column to type, where the column says. */
    static Map<String, String> types(TableDef t) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        t.columns().values().stream().filter(c -> c.type() != null).forEach(c -> m.put(c.name(), c.type()));
        return m;
    }

    /**
     * The columns a reader without the raw power sees as {@code •••}: those marked masked, and every column whose expression reads the path
     * of a masked column (section 3.2: a column built from a masked path is masked).
     */
    public static Set<String> masked(TableDef t) {
        Set<String> out = new LinkedHashSet<>();
        t.columns().values().stream().filter(ColumnDef::masked).forEach(c -> out.add(c.name()));
        for (ColumnDef c : t.columns().values()) {
            if (c.expr() != null && readsMasked(c.expr(), t, out)) {
                out.add(c.name());
            }
        }
        return out;
    }

    private static boolean readsMasked(String expr, TableDef t, Set<String> masked) {
        Set<String> paths = new LinkedHashSet<>();
        try {
            Trees.walk(Trees.parse(expr), n -> {
                String name = Trees.name(n);
                if (name != null) {
                    paths.add(name);
                }
            });
        } catch (RuntimeException e) {
            return false;
        }
        return masked.stream().map(t.columns()::get).filter(c -> c != null && c.path() != null).anyMatch(c -> paths.contains(c.path()));
    }
}
