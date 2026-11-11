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

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.check.Checker;
import com.ash.drishti.rachana.el.check.ElType;
import com.ash.drishti.rachana.el.check.Fields;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.lang.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The layer's expressions checked by <b>Rachana's checked mode</b> (part A of 2.9.11.e: {@link CheckedMode}, its scopes and DEL's codes),
 * with the {@link Fields} of the table or model the expression is written in: a table's source paths ({@code $}, {@code @}, {@code ^})
 * typed and masked as its columns are, a model's {@code alias.COLUMN} (and date parts), its aliases, measures and parameters. The layer
 * adds two rules of its own: in a table, a bare column name is refused with its path to write (an expression reads the source, section
 * 3.2), and {@code @} or {@code ^} need a grain deep enough. A definition reads the source as it is: masks are applied to its readers,
 * so no field is masked here (a reader's query is checked with its masks where it is asked).
 */
public final class LayerExpressionChecker implements ExpressionChecker {

    @Override
    public List<Problem> check(String text, Scope scope, Env env, String path) {
        boolean model = scope == Scope.AGGREGATE || scope == Scope.MODEL_RULE;
        com.ash.drishti.rachana.el.check.Scope rachana = switch (scope) {
            case ROW -> com.ash.drishti.rachana.el.check.Scope.ROW;
            case RULE, MODEL_RULE -> com.ash.drishti.rachana.el.check.Scope.RULE;
            case AGGREGATE -> com.ash.drishti.rachana.el.check.Scope.AGGREGATE;
        };
        Checker.Checked c = CheckedMode.check(text, rachana, model ? modelFields(env) : tableFields(env));
        List<Problem> out = new ArrayList<>();
        for (com.ash.drishti.rachana.el.check.Problem p : c.problems()) {
            String words = p.message() + (p.fix() == null ? "" : " (" + p.fix() + ")");
            out.add(p.error() ? Problem.error(p.code(), path, words) : Problem.warning(p.code(), path, words));
        }
        if (!model) {
            layerRules(text, env, path, out);
        }
        return out;
    }

    /** A bare column name in a table's expression, and {@code @} or {@code ^} without the grain they name. */
    private static void layerRules(String text, Env env, String path, List<Problem> out) {
        Expr tree;
        try {
            tree = Trees.parse(text);
        } catch (RuntimeException e) {
            return;                                                         // reported by checked mode
        }
        Trees.walk(tree, n -> {
            String root = Trees.root(n);
            String name = Trees.name(n);
            if (root == null || name == null || !(n instanceof Expr.Field || n instanceof Expr.Row || n instanceof Expr.Parent)) {
                return;
            }
            if (root.isEmpty() && env.columns().contains(name)) {
                out.add(Problem.error(TableCodes.SCOPE, path, "'" + name + "' is a column of this table: an expression reads the source, so write the column's path ("
                        + env.paths().entrySet().stream().filter(e -> e.getValue().equals(name)).map(e -> e.getKey()).findFirst().orElse("$." + name) + ")"));
            }
            int need = root.equals("@") || root.equals("^") ? 1 : root.equals("^^") ? 2 : 0;
            if (env.grainDepth() < need && (n instanceof Expr.Row || n instanceof Expr.Parent)) {
                out.add(Problem.error(TableCodes.PATH, path, root + " names " + (root.equals("@") ? "the grain's element" : "a parent of the grain's element")
                        + ", and this table " + (env.grainDepth() == 0 ? "has no grain" : "has a grain one list deep")));
            }
        });
    }

    /** A table's names: the source's paths, typed and masked as the columns that read them; any other path of the source untyped. */
    static Fields tableFields(Env env) {
        return new Fields() {
            @Override
            public Optional<Field> field(List<String> parts) {
                String head = parts.get(0);
                if (parts.size() >= 3 && !(head.equals("$") || head.equals("@") || head.startsWith("^"))) {
                    return Optional.of(new Field(String.join(".", parts), ElType.ANY, false));   // another table's column (related): the catalogue checks it
                }
                if (!(head.equals("$") || head.equals("@") || head.startsWith("^"))) {
                    return Optional.empty();                                // a bare name: the layer says which path to write
                }
                String p = head + "." + String.join(".", parts.subList(1, parts.size()));
                String column = env.paths().get(p);
                ElType type = column == null ? ElType.ANY : type(env.types().get(column));
                return Optional.of(new Field(p, type, false));          // a definition reads the source as it is: masks are the readers'

            }

            @Override
            public boolean alias(String name) {
                return false;
            }

            @Override
            public Optional<String> measure(String nameOrId) {
                return Optional.empty();
            }

            @Override
            public List<String> names() {
                return List.copyOf(env.paths().keySet());
            }
        };
    }

    /** A model's names: {@code alias.COLUMN} and its date parts, the aliases, the measures and the parameters. */
    static Fields modelFields(Env env) {
        return new Fields() {
            @Override
            public Optional<Field> field(List<String> parts) {
                String p = String.join(".", parts);
                if (env.columns().contains(p)) {
                    return Optional.of(new Field(p, type(env.types().get(p)), false));
                }
                String base = withoutDatePart(p);
                if (!base.equals(p) && env.columns().contains(base)) {
                    return Optional.of(new Field(p, ElType.INTEGER, false));
                }
                return Optional.empty();
            }

            @Override
            public boolean alias(String name) {
                return env.aliases().contains(name);
            }

            @Override
            public Optional<String> measure(String nameOrId) {
                return env.measures().contains(nameOrId) ? Optional.of(nameOrId) : Optional.empty();
            }

            @Override
            public Optional<ElType> param(String name) {
                return env.params().contains(name) ? Optional.of(ElType.ANY) : Optional.empty();
            }

            @Override
            public List<String> params() {
                return List.copyOf(env.params());
            }

            @Override
            public List<String> names() {
                return List.copyOf(env.columns());
            }
        };
    }

    /** A column's type as checked mode types it. */
    static ElType type(String type) {
        if (type == null) {
            return ElType.ANY;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string" -> ElType.STRING;
            case "integer" -> ElType.INTEGER;
            case "decimal" -> ElType.DECIMAL;
            case "boolean" -> ElType.BOOLEAN;
            case "date" -> ElType.DATE;
            case "timestamp" -> ElType.TIMESTAMP;
            default -> ElType.ANY;
        };
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
}
