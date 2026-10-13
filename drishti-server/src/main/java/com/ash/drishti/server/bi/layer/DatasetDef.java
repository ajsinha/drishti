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

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dataset of the model layer, {@code rachana: 2} (docs/architecture/BI_MODEL_SPEC.md section 1): one governed table with a source, a grain, typed columns
 * with roles, meanings, units and additivity, a time mode, refresh and its own security. It never joins and declares no measures (a model does both). Produced
 * by {@link DatasetDefParser}, or generated from a pack kind by {@link PackDatasets}. Immutable.
 *
 * @param name the {@code dataset:} name ({@code pack.<pack>.<kind>} for a generated one)
 * @param version the semver of its contract
 * @param id the stable id ({@code ds_...}), blank until written
 * @param generated who generated it ({@code by: pack}); null for an authored dataset
 * @param extendsRef the dataset this one narrows ({@code pack.trading.trade@1}), or null
 * @param where for an {@code extends} dataset: a row filter on the base (not a security rule), or null
 * @param ai the {@code ai:} block, kept for wave W5
 * @param tests the {@code tests:} entries as written
 * @param rollups the {@code rollups:} entries as written
 */
public record DatasetDef(String name, String version, String id, String title, String description, String owner, boolean sample, String endorsement,
        Generated generated, Source source, Grain grain, Map<String, Col> columns, Time time, Refresh refresh, Security security, String extendsRef, String where,
        JsonNode ai, List<JsonNode> tests, List<JsonNode> rollups) {

    /** The language version of the model layer's items. */
    public static final int RACHANA = 2;
    /** The prefix of a pack-generated dataset's name; no authored dataset may use it. */
    public static final String PACK_PREFIX = "pack.";
    public static final Set<String> TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "timestamp");
    public static final Set<String> ROLES = Set.of("key", "dimension", "attribute", "date", "amount", "quantity", "ratio", "text");
    public static final Set<String> AGGREGATES = Set.of("sum", "avg", "min", "max", "count", "distinct");
    public static final Set<String> OVER_TIME = Set.of("last", "first", "avg", "none");
    public static final Set<String> MODES = Set.of("live", "snapshot", "history");
    /** The only values a connector query may bind. */
    public static final Set<String> PARAMS = Set.of("as-of", "business-date", "from", "to");
    /** Columns every table has and no dataset declares. */
    public static final Set<String> BUILT_IN = Set.of("id", "businessDate", "index", "updateSeq", "updatedAt");

    public DatasetDef {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        tests = tests == null ? List.of() : List.copyOf(tests);
        rollups = rollups == null ? List.of() : List.copyOf(rollups);
    }

    /** The major version (what {@code @1} binds to); 0 when the version is not semver. */
    public int major() {
        Semver v = Semver.parse(version);
        return v == null ? 0 : v.major();
    }

    public boolean isGenerated() {
        return generated != null;
    }

    /** The name a model gives it by default: the last part of its name, {@code -} turned into {@code _} ({@code pack.trading.trade} is {@code trade}). */
    public String defaultAlias() {
        String last = name.substring(name.lastIndexOf('.') + 1);
        String a = last.replaceAll("[^A-Za-z0-9_]", "_");
        return a.isEmpty() || !Character.isLetter(a.charAt(0)) ? "t_" + a : a;
    }

    /** Who generated a dataset: {@code by: pack}, the pack and its version. */
    public record Generated(String by, String pack, String packVersion) {}

    /**
     * Where the table comes from: exactly one of a pack kind ({@code pack}/{@code kind}, read through {@code connector} or whichever serves it, one row per
     * element of {@code each}), a connector query ({@code connector} + {@code query} with {@code params} bound to the four named dates), a Python Transform
     * ({@code python} with model-query {@code inputs}) or another model's answer ({@code model} + {@code modelQuery}).
     */
    public record Source(String type, String pack, String kind, String connector, String each, String query, Map<String, String> params, String python,
            Map<String, JsonNode> inputs, String runWhere, String model, JsonNode modelQuery, Map<String, Object> pythonParams) {

        public static final String PACK = "pack";
        public static final String CONNECTOR = "connector";
        public static final String PYTHON = "python";
        public static final String MODEL = "model";
        /** A SQL view: a reviewed Drishti SQL statement over a model ({@code source: { sql, model }}); its answer is the table. */
        public static final String SQL = "sql";

        public Source {
            params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
            inputs = inputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            pythonParams = pythonParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pythonParams));
        }

        /** A pack kind's source. */
        public static Source ofKind(String pack, String kind, String connector, String each) {
            return new Source(PACK, pack, kind, connector, each, null, null, null, null, null, null, null, null);
        }
    }

    /** What one row is: in words, the key columns within one business date, and the row's date column. */
    public record Grain(String says, List<String> keys, String date) {

        public Grain {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }
    }

    /**
     * A column.
     *
     * @param additive {@code true}, {@code false}, or the over-time rule of a semi-additive column ({@code last}, {@code first}, {@code avg}, {@code none})
     * @param unitCurrency the column (or ISO code) the amounts are in, or null
     */
    public record Col(String name, String type, String path, String expr, String role, String meaning, String references, String of, String unitCurrency,
            String unitOf, String additive, String aggregate, String format, String id, String description) {

        public Col {
            id = id == null ? "" : id;
            description = description == null ? "" : description;
            additive = additive == null ? ("amount".equals(role) || "quantity".equals(role) ? "true" : "false") : additive;
        }

        /** The document path: the name when none is given. */
        public String pathOrName() {
            return path == null || path.isBlank() ? name : path;
        }

        /** True when the amounts of the column are in a currency named per row (a column), not one fixed ISO code. */
        public boolean perRowCurrency(Map<String, Col> columns) {
            return unitCurrency != null && columns.containsKey(unitCurrency);
        }

        public boolean semiAdditive() {
            return OVER_TIME.contains(additive);
        }
    }

    /** The time mode (live, snapshot or history) and what each keeps. */
    public record Time(String mode, String asOf, String keep, String storage, Integer liveRows, Duration liveAge, History history) {

        public static final Time SNAPSHOT = new Time("snapshot", "business-date", null, null, null, null, null);

        public boolean direct() {
            return "direct".equals(storage);
        }
    }

    /** A history of samples: every {@code every}, the newest {@code keep}, grouped by {@code by}, of the listed measures. */
    public record History(Duration every, int keep, List<String> by, List<String> measures) {

        public History {
            by = by == null ? List.of() : List.copyOf(by);
            measures = measures == null ? List.of() : List.copyOf(measures);
        }
    }

    /** When the layers are rebuilt: a schedule in words and whether a data load rebuilds them. */
    public record Refresh(String schedule, boolean onLoad) {

        public static final Refresh NONE = new Refresh("", false);
    }

    /** A row rule: readers holding any of {@code roles} see the rows where {@code where} is true. */
    public record Rule(List<String> roles, String where) {

        public Rule {
            roles = List.copyOf(roles);
        }
    }

    /** The dataset's own security: row rules, masked columns, and (wave W2) columns hidden from some roles. */
    public record Security(List<Rule> rows, List<String> masked, List<String> hiddenRoles, List<String> hiddenColumns) {

        public static final Security NONE = new Security(List.of(), List.of(), List.of(), List.of());

        public Security {
            rows = List.copyOf(rows);
            masked = List.copyOf(masked);
            hiddenRoles = hiddenRoles == null ? List.of() : List.copyOf(hiddenRoles);
            hiddenColumns = hiddenColumns == null ? List.of() : List.copyOf(hiddenColumns);
        }

        /** Every role a rule names. */
        public Set<String> ruleRoles() {
            Set<String> out = new java.util.LinkedHashSet<>();
            rows.forEach(r -> out.addAll(r.roles()));
            return out;
        }
    }
}
