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
package com.ash.drishti.server.bi.layer;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.Node;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A table of the table layer ({@code rachana: 3} with {@code table:}, BI_GUIDE.md) as BI's {@link DatasetDef}: read and checked by the layer's
 * own reader and said in BI's terms by {@link LayerTables}. BI's own dataset files ({@code rachana: 2}) are not read: {@code drishti.py tables
 * migrate} writes them as tables ({@code DRS-9196}). Also the checks a model's row rules share with a table's: a rule's text, and a rule naming
 * only its own table's columns.
 */
public final class DatasetDefParser {

    /** The outcome: the dataset (null when it could not be read at all) and every problem found. */
    public record Result(DatasetDef dataset, List<DatasetParser.Problem> problems) {

        public boolean ok() {
            return dataset != null && problems.stream().noneMatch(DatasetParser.Problem::error);
        }

        public String firstError() {
            return problems.stream().filter(DatasetParser.Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("");
        }
    }

    private DatasetDefParser() {}

    /** A table of the layer in BI's terms; any other text is refused with the migration's command ({@code DRS-9196}). */
    public static Result parse(String yaml) {
        if (LayerTables.isTable(yaml)) {
            return LayerTables.read(yaml);
        }
        return new Result(null, List.of(new DatasetParser.Problem("error", DatasetParser.MIGRATE_FIRST, "", "not a table of the table layer (rachana: 3 with "
                + "table:): a dataset in BI's old form is migrated by drishti.py tables migrate (BI_GUIDE.md, the migration)")));
    }

    /** True when the text is a table of the layer ({@code rachana: 3} with {@code table:}). */
    public static boolean isTable(String yaml) {
        return LayerTables.isTable(yaml);
    }

    /** The role a column has when it does not say: decimals are amounts, dates dates, everything else a dimension. */
    static String defaultRole(String type, String expr) {
        return switch (type) {
            case "decimal", "integer" -> "amount";
            case "date", "timestamp" -> "date";
            default -> "dimension";
        };
    }

    static Node expression(String text, String path, String code, LayerYaml y) {
        try {
            return com.ash.drishti.server.bi.expr.Expressions.node(text);
        } catch (RuntimeException e) {
            y.error(e instanceof com.ash.drishti.server.bi.expr.ExpressionException d && com.ash.drishti.server.bi.expr.Expressions.OLD_SYNTAX.equals(d.code()) ? d.code()
                    : code, path, "the expression does not parse: " + e.getMessage()
                            + (e instanceof com.ash.drishti.server.bi.expr.ExpressionException d && d.fix() != null ? " (" + d.fix() + ")" : ""));
            return null;
        }
    }

    /** Row rules: {@code - { role: desk-head, where: "..." }}, the role one name or a list. */
    static List<DatasetDef.Rule> rules(JsonNode n, String path, LayerYaml y) {
        List<DatasetDef.Rule> out = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return out;
        }
        if (!n.isArray()) {
            y.error("DRS-9106", path, "row rules are a list of { role, where }");
            return out;
        }
        int i = 0;
        for (JsonNode r : n) {
            List<String> roles = y.strings(r.path("role"), path + "[" + i + "].role", "DRS-9106");
            String where = LayerYaml.text(r, "where", "");
            if (roles.isEmpty() || where.isBlank()) {
                y.error("DRS-9106", path + "[" + i + "]", "a row rule is { role: <role or list>, where: <condition> }");
            } else {
                out.add(new DatasetDef.Rule(roles, where));
            }
            i++;
        }
        return out;
    }

    /**
     * A rule of a dataset names only that dataset's columns: {@code col}, {@code <name>.col} (the dataset's own name, or its last part) and {@code user.*}
     * attributes. Anything else is the given code.
     */
    static void ownColumnsOnly(Node n, String name, Map<String, DatasetDef.Col> columns, boolean lenient, String path, String code, LayerYaml y) {
        Set<String> own = new LinkedHashSet<>(List.of(name, name.substring(name.lastIndexOf('.') + 1)));
        Node.walk(n, x -> {
            if (x instanceof Node.Ref r) {
                List<String> parts = r.parts();
                String column = parts.size() == 1 ? parts.get(0) : parts.get(parts.size() - 1);
                String table = parts.size() == 1 ? null : String.join(".", parts.subList(0, parts.size() - 1));
                if (table != null && !own.contains(table)) {
                    y.error(code, path, "a rule of '" + name + "' names only its own columns; '" + r.text() + "' belongs to another table (write that rule in a model)");
                } else if (!lenient && !columns.containsKey(column) && !DatasetDef.BUILT_IN.contains(column)) {
                    y.error(code, path, "the rule names '" + column + "', which is not a column of '" + name + "'");
                }
            }
        });
    }
}
