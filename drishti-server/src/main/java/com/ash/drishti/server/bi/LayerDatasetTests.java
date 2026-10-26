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
package com.ash.drishti.server.bi;

import com.ash.drishti.common.Branding;
import com.ash.drishti.server.bi.dataset.ExpectedValue;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code tests:} of a {@code rachana: 2} dataset (docs/architecture/BI_MODEL_SPEC.md section 1.8), each asked as Drishti SQL of the dataset's implicit model
 * as the test's role (else the person checking): an expected value with an inline aggregate ({@code agg: count}, {@code agg: sum, column: mtm}, with the
 * expected-value keys {@code expect tolerance by row filters date role}), and the data tests that need no model: {@code unique} (the grain test),
 * {@code not-null} and {@code references-exist} (every value of a column is a key of another dataset). The statements are built from the dataset's own names,
 * each checked to be a plain name, and its values written as literals; the SQL binder then checks every name against the model as the reader sees it.
 */
final class LayerDatasetTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> EXPECTED_KEYS = Set.of("name", "by", "row", "filters", "date", "expect", "tolerance", "role");

    private final ExpectedValueRunner runner;
    private final ExpectedValueRunner.DatasetTests sql;

    LayerDatasetTests(ExpectedValueRunner runner, ExpectedValueRunner.DatasetTests sql) {
        this.runner = runner;
        this.sql = sql;
    }

    List<ExpectedValueRunner.Outcome> run(Principal p, String body, String chosen) {
        DatasetDefParser.Result r = DatasetDefParser.parse(body);
        if (r.dataset() == null) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.LAYER_DATASET_INVALID, "the dataset has errors: " + r.firstError());
        }
        DatasetDef d = r.dataset();
        List<ExpectedValueRunner.Outcome> out = new ArrayList<>();
        for (JsonNode t : d.tests()) {
            out.add(one(p, body, d.defaultAlias(), t, chosen));
        }
        return out;
    }

    private ExpectedValueRunner.Outcome one(Principal p, String body, String alias, JsonNode t, String chosen) {
        String kind = t.has("unique") ? "unique" : t.has("not-null") ? "not-null" : t.has("references-exist") ? "references-exist" : t.has("agg") ? "agg" : "?";
        String role = t.hasNonNull("role") && !t.path("role").asText().isBlank() ? t.path("role").asText() : chosen == null || chosen.isBlank() ? null : chosen;
        String label = t.path("name").asText(kind);
        if (sql == null) {
            return error(label, kind, "", "", Branding.sqlName() + " is not available on this server, so a dataset's tests cannot run", role);
        }
        try {
            Principal as = runner.as(p, role);
            return switch (kind) {
                case "unique" -> unique(as, body, alias, label, names(t.path("unique")), role);
                case "not-null" -> notNull(as, body, alias, label, names(t.path("not-null")), role);
                case "references-exist" -> references(as, body, alias, label, t.path("references-exist"), role);
                case "agg" -> aggregate(as, body, alias, label, t, chosen);
                default -> error(label, kind, "", "", "a test is { name, unique | not-null | references-exist | agg, expect }", role);
            };
        } catch (RuntimeException e) {
            return error(label, kind, target(t, kind), "", e.getMessage(), role);
        }
    }

    // ---- data tests --------------------------------------------------------------------------------------------------

    private ExpectedValueRunner.Outcome unique(Principal as, String body, String alias, String label, List<String> cols, String role) {
        List<String> refs = cols.stream().map(c -> alias + "." + c).toList();
        String text = "SELECT " + String.join(", ", refs) + ", COUNT(*) AS n FROM " + alias + " GROUP BY " + String.join(", ", refs) + " ORDER BY n DESC LIMIT 1";
        QueryService.Result r = sql.run(as, body, null, text);
        double worst = r.rows() == 0 ? 0 : number(r, "n", 0);
        String target = String.join(", ", cols);
        if (worst <= 1) {
            return new ExpectedValueRunner.Outcome(label, "unique", target, "one row per key", 1.0 * Math.min(1, r.rows()), "pass", "", role);
        }
        List<String> key = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            key.add(cols.get(i) + " = " + r.columns().get(i).values()[0]);
        }
        return new ExpectedValueRunner.Outcome(label, "unique", target, "one row per key", worst, "fail",
                "the key is not unique: " + String.join(", ", key) + " has " + ExpectedValueRunner.trim(worst) + " rows", role);
    }

    private ExpectedValueRunner.Outcome notNull(Principal as, String body, String alias, String label, List<String> cols, String role) {
        List<String> empty = new ArrayList<>();
        double total = 0;
        for (String c : cols) {
            QueryService.Result r = sql.run(as, body, null, "SELECT COUNT(*) AS n FROM " + alias + " WHERE " + alias + "." + c + " IS NULL");
            double n = r.rows() == 0 ? 0 : number(r, "n", 0);
            if (n > 0) {
                empty.add(c + " is empty in " + ExpectedValueRunner.trim(n) + " row" + (n == 1 ? "" : "s"));
            }
            total += n;
        }
        return new ExpectedValueRunner.Outcome(label, "not-null", String.join(", ", cols), "no empty value", total, empty.isEmpty() ? "pass" : "fail", String.join("; ", empty), role);
    }

    private ExpectedValueRunner.Outcome references(Principal as, String body, String alias, String label, JsonNode spec, String role) {
        String column = name(spec.path("column").asText(""));
        String other = spec.path("dataset").asText("");
        if (other.isBlank() || !other.matches("[A-Za-z0-9_.@^~-]+")) {
            throw new IllegalArgumentException("references-exist names the dataset whose keys the column holds: { column, dataset, key }");
        }
        String key = name(spec.path("key").asText("id"));
        String otherName = other.contains("@") ? other.substring(0, other.indexOf('@')) : other;
        String otherAlias = aliasOf(otherName);
        QueryService.Result mine = sql.run(as, body, null, "SELECT " + alias + "." + column + ", COUNT(*) AS n FROM " + alias + " WHERE " + alias + "." + column
                + " IS NOT NULL GROUP BY " + alias + "." + column);
        QueryService.Result keys = sql.run(as, null, "dataset:" + otherName, "SELECT " + otherAlias + "." + key + ", COUNT(*) AS n FROM " + otherAlias + " GROUP BY " + otherAlias + "." + key);
        Set<String> known = new LinkedHashSet<>();
        for (Object v : keys.columns().get(0).values()) {
            known.add(String.valueOf(v));
        }
        List<String> missing = new ArrayList<>();
        Object[] values = mine.columns().get(0).values();
        for (int i = 0; i < mine.rows(); i++) {
            if (values[i] != null && !known.contains(String.valueOf(values[i]))) {
                missing.add(String.valueOf(values[i]));
            }
        }
        String target = column + " -> " + otherName + "." + key;
        return new ExpectedValueRunner.Outcome(label, "references-exist", target, "every value is a key", (double) missing.size(), missing.isEmpty() ? "pass" : "fail",
                missing.isEmpty() ? "" : missing.size() + " value" + (missing.size() == 1 ? "" : "s") + " of " + column + " " + (missing.size() == 1 ? "is" : "are") + " not a key of "
                        + otherName + ": " + String.join(", ", missing.stream().limit(5).toList()) + (missing.size() > 5 ? ", ..." : ""), role);
    }

    // ---- an expected value with an inline aggregate --------------------------------------------------------------------

    private ExpectedValueRunner.Outcome aggregate(Principal as, String body, String alias, String label, JsonNode t, String chosen) {
        String agg = t.path("agg").asText("");
        String column = t.hasNonNull("column") ? name(t.path("column").asText()) : null;
        String expr = switch (agg) {
            case "count" -> column == null ? "COUNT(*)" : "COUNT(" + alias + "." + column + ")";
            case "distinct" -> "COUNT(DISTINCT " + alias + "." + need(column, agg) + ")";
            case "sum", "avg", "min", "max" -> agg.toUpperCase(java.util.Locale.ROOT) + "(" + alias + "." + need(column, agg) + ")";
            default -> throw new IllegalArgumentException("agg is one of " + DatasetDef.AGGREGATES + ", not '" + agg + "'");
        };
        ObjectNode copy = JSON.createObjectNode();
        t.fields().forEachRemaining(e -> {
            if (EXPECTED_KEYS.contains(e.getKey())) {
                copy.set(e.getKey(), e.getValue());
            }
        });
        copy.put("measure", "value");
        List<ExpectedValue.Problem> problems = new ArrayList<>();
        List<ExpectedValue> parsed = ExpectedValue.parse(JSON.createArrayNode().add(copy), "tests", false, problems);
        if (parsed.isEmpty() || !problems.isEmpty()) {
            throw new IllegalArgumentException(problems.isEmpty() ? "the test is not valid" : problems.get(0).message());
        }
        ExpectedValue e = parsed.get(0);
        ExpectedValue named = new ExpectedValue(label, null, null, List.of(), e.row(), "value", List.of(), null, e.expect(), e.min(), e.max(), e.absolute(), e.percent(), e.role());
        List<String> by = e.by().stream().map(LayerDatasetTests::name).toList();
        StringBuilder text = new StringBuilder("SELECT ");
        by.forEach(b -> text.append(alias).append('.').append(b).append(" AS ").append(b).append(", "));
        text.append(expr).append(" AS value FROM ").append(alias);
        List<String> where = new ArrayList<>();
        for (ExpectedValue.Cond c : e.filters()) {
            where.add(condition(alias, c));
        }
        if (!where.isEmpty()) {
            text.append(" WHERE ").append(String.join(" AND ", where));
        }
        if (!by.isEmpty()) {
            text.append(" GROUP BY ").append(String.join(", ", by.stream().map(b -> alias + "." + b).toList()));
        }
        if (e.date() != null) {
            text.append(" AS OF DATE '").append(e.date().replace("'", "")).append('\'');
        }
        String role = ExpectedValueRunner.roleOf(e, chosen);
        return ExpectedValueRunner.judge(named, "agg", agg + (column == null ? "" : "(" + column + ")"), sql.run(as, body, null, text.toString()), "value", role);
    }

    private static String condition(String alias, ExpectedValue.Cond c) {
        String f = alias + "." + name(c.field().contains(".") ? c.field().substring(c.field().lastIndexOf('.') + 1) : c.field());
        List<String> vs = c.values().stream().map(LayerDatasetTests::literal).toList();
        return switch (c.op()) {
            case "eq" -> f + " = " + vs.get(0);
            case "ne" -> f + " <> " + vs.get(0);
            case "lt" -> f + " < " + vs.get(0);
            case "le" -> f + " <= " + vs.get(0);
            case "gt" -> f + " > " + vs.get(0);
            case "ge" -> f + " >= " + vs.get(0);
            case "in" -> f + " IN (" + String.join(", ", vs) + ")";
            case "not-in" -> f + " NOT IN (" + String.join(", ", vs) + ")";
            case "is-null" -> f + " IS NULL";
            case "not-null" -> f + " IS NOT NULL";
            default -> throw new IllegalArgumentException("a dataset test's filter op is eq ne lt le gt ge in not-in is-null not-null, not '" + c.op() + "'");
        };
    }

    // ---- pieces ------------------------------------------------------------------------------------------------------

    private static String literal(Object v) {
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    private static String need(String column, String agg) {
        if (column == null) {
            throw new IllegalArgumentException("agg: " + agg + " needs column: <a column of the dataset>");
        }
        return column;
    }

    private static List<String> names(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isTextual()) {
            out.add(name(n.asText()));
        } else {
            n.forEach(x -> out.add(name(x.asText())));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("name at least one column");
        }
        return out;
    }

    /** A column name as written, which must be a plain name (it is put into the statement). */
    private static String name(String s) {
        if (!NAME.matcher(s).matches()) {
            throw new IllegalArgumentException("'" + s + "' is not a column name");
        }
        return s;
    }

    /** The alias of a dataset in its implicit model (DatasetDef#defaultAlias). */
    static String aliasOf(String datasetName) {
        String last = datasetName.substring(datasetName.lastIndexOf('.') + 1);
        String a = last.replaceAll("[^A-Za-z0-9_]", "_");
        return a.isEmpty() || !Character.isLetter(a.charAt(0)) ? "t_" + a : a;
    }

    private static double number(QueryService.Result r, String column, int row) {
        ArrowIpcWriter.Column c = r.columns().stream().filter(x -> x.name().equals(column)).findFirst().orElse(r.columns().get(r.columns().size() - 1));
        Object v = c.values()[row];
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static String target(JsonNode t, String kind) {
        JsonNode v = t.path(kind);
        return v.isMissingNode() ? "" : v.isTextual() ? v.asText() : v.toString();
    }

    private static ExpectedValueRunner.Outcome error(String label, String kind, String target, String expect, String message, String role) {
        return new ExpectedValueRunner.Outcome(label, kind, target, expect, null, "error", message, role);
    }
}
