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
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.def.TableName;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Checks one model against the tables it reads (docs/architecture/ARCHITECTURE.md §28 section 3.3; Q10, Q21, Q29): aliases, tables found at the major
 * asked, relationships between real columns of the same type, measures in aggregate scope over {@code alias.COLUMN}, hierarchies of real
 * fields, and model security over real aliases. The fan-out refusal, grain safety and the security safeguards ({@code DRS-9130},
 * {@code DRS-9131}) stay the query compiler's, which applies them per query.
 */
public final class ModelChecks {

    private static final Pattern ALIAS = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final ExpressionChecker expressions;

    public ModelChecks(ExpressionChecker expressions) {
        this.expressions = expressions;
    }

    public ModelChecks() {
        this(new LayerExpressionChecker());
    }

    /**
     * Every problem of {@code m}.
     *
     * @param tables resolves a reference to the table it names (major range honoured), or empty
     */
    public List<Problem> check(ModelDef m, Function<TableName.Ref, Optional<TableDef>> tables) {
        List<Problem> out = new ArrayList<>();
        if (!TableName.isModel(m.name())) {
            out.add(Problem.error(TableCodes.NAME, "model", "'" + m.name() + "' is not a model name (lower case, letters, digits, hyphens; a domain before a dot)"));
        }
        if (m.tables().isEmpty()) {
            out.add(Problem.error(TableCodes.UNREADABLE, "tables", "a model reads at least one table"));
        }
        if (m.extras().containsKey("scan-warn-rows") && m.scanWarnRows() == null) {
            out.add(Problem.error(TableCodes.UNREADABLE, "scan-warn-rows", "scan-warn-rows is a whole number of rows above zero (the model's own"
                    + " threshold for the warning on a join across connectors; drishti.tables.scan-warn-rows when not given)"));
        }
        Map<String, TableDef> byAlias = new LinkedHashMap<>();
        Set<String> columns = new LinkedHashSet<>();
        Map<String, String> types = new LinkedHashMap<>();
        Set<String> masked = new LinkedHashSet<>();
        for (ModelDef.Use u : m.tables().values()) {
            String p = "tables." + u.alias();
            if (!ALIAS.matcher(u.alias()).matches()) {
                out.add(Problem.error(TableCodes.NAME, p, "an alias is lower case, letters, digits and underscores ('" + u.alias() + "')"));
            }
            if (!ModelDef.Use.ROLES.contains(u.role())) {
                out.add(Problem.error(TableCodes.UNREADABLE, p + ".role", "a table is a fact or a dimension"));
            }
            Optional<TableDef> t = tables.apply(u.table());
            if (t.isEmpty()) {
                out.add(Problem.error(TableCodes.REFERENCE, p + ".table", "no table '" + u.table() + "' in the catalogue"));
                continue;
            }
            byAlias.put(u.alias(), t.get());
            for (String b : TableDef.BUILT_INS) {
                columns.add(u.alias() + "." + b);
            }
            for (ColumnDef c : t.get().columns().values()) {
                columns.add(u.alias() + "." + c.name());
                if (c.type() != null) {
                    types.put(u.alias() + "." + c.name(), c.type());
                }
            }
            TableChecks.masked(t.get()).forEach(c -> masked.add(u.alias() + "." + c));
        }
        types.put("business_date", "date");
        Set<String> aliases = new LinkedHashSet<>(m.tables().keySet());
        if (m.extras().get("history") instanceof Map<?, ?> h) {
            aliases.add("history");                                         // the sampled measures (Q14): history.ts and one column per measure
            columns.add("history.ts");
            for (String k : List.of("measures", "by")) {
                if (h.get(k) instanceof List<?> l) {
                    l.forEach(x -> columns.add("history." + x));
                }
            }
        }
        relationships(m, byAlias, columns, types, out);
        Set<String> params = m.extras().get("parameters") instanceof Map<?, ?> ps ? ps.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                : Set.of();
        ExpressionChecker.Env env = new ExpressionChecker.Env(columns, aliases, m.measures().keySet(), types, masked, 0, params, Map.of());
        for (ModelDef.Measure x : m.measures().values()) {
            if (x.expr() != null) {
                out.addAll(expressions.check(x.expr(), ExpressionChecker.Scope.AGGREGATE, env, "measures." + x.name() + ".expr"));
            }
        }
        m.hierarchies().forEach((h, levels) -> {
            for (String level : levels) {
                if (!columns.contains(level) && !columns.contains(LayerExpressionChecker.withoutDatePart(level))) {
                    out.add(Problem.error(TableCodes.REFERENCE, "hierarchies." + h, "'" + level + "' is not a field of the model (alias.COLUMN, or a date by year, quarter, month, week, day)"));
                }
            }
        });
        security(m, columns, env, out);
        return out;
    }

    private static void relationships(ModelDef m, Map<String, TableDef> byAlias, Set<String> columns, Map<String, String> types, List<Problem> out) {
        Set<String> activePairs = new HashSet<>();
        for (int i = 0; i < m.relationships().size(); i++) {
            ModelDef.Relationship r = m.relationships().get(i);
            String p = "relationships[" + i + "]";
            boolean ends = true;
            for (String end : new String[] {r.from(), r.to()}) {
                if (end == null || !columns.contains(end)) {
                    out.add(Problem.error(TableCodes.RELATIONSHIP, p, "'" + end + "' is not alias.COLUMN of the model"));
                    ends = false;
                }
            }
            if (!ModelDef.Relationship.CARDINALITIES.contains(r.cardinality())) {
                out.add(Problem.error(TableCodes.RELATIONSHIP, p + ".cardinality", "one of " + ModelDef.Relationship.CARDINALITIES));
            }
            if (!ends) {
                continue;
            }
            String a = types.get(r.from());
            String b = types.get(r.to());
            if (a != null && b != null && !a.equals(b)) {
                out.add(Problem.error(TableCodes.RELATIONSHIP, p, r.from() + " is " + a + " and " + r.to() + " is " + b + ": a key joins values of one type"));
            }
            String pair = r.from().split("\\.")[0] + ">" + r.to().split("\\.")[0];
            if (r.active() && !activePairs.add(pair)) {
                out.add(Problem.error(TableCodes.RELATIONSHIP, p, "a second active relationship between " + pair.replace(">", " and ")
                        + ": make one active: false and name it with calc(..., via = 'id')"));
            }
        }
    }

    private void security(ModelDef m, Set<String> columns, ExpressionChecker.Env env, List<Problem> out) {
        ModelDef.Security s = m.security();
        s.rows().forEach((alias, rules) -> {
            if (!m.tables().containsKey(alias)) {
                out.add(Problem.error(TableCodes.REFERENCE, "security.rows." + alias, "no alias '" + alias + "' in the model"));
                return;
            }
            for (int i = 0; i < rules.size(); i++) {
                TableDef.Rule r = rules.get(i);
                String p = "security.rows." + alias + "[" + i + "]";
                if (r.roles().isEmpty() || r.where() == null) {
                    out.add(Problem.error(TableCodes.RULE, p, "a rule is { role, where }"));
                    continue;
                }
                out.addAll(expressions.check(r.where(), ExpressionChecker.Scope.MODEL_RULE, env, p + ".where"));
            }
        });
        s.maySeeAll().keySet().stream().filter(a -> !m.tables().containsKey(a))
                .forEach(a -> out.add(Problem.error(TableCodes.REFERENCE, "security.may-see-all." + a, "no alias '" + a + "' in the model")));
        s.masked().stream().filter(c -> !columns.contains(c))
                .forEach(c -> out.add(Problem.error(TableCodes.REFERENCE, "security.masked", "'" + c + "' is not alias.COLUMN of the model")));
    }
}
