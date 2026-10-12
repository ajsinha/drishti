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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A data model (docs/architecture/BI_MODEL_SPEC.md section 2): datasets in a star under aliases, relationships, measures, hierarchies, model security (only
 * tighter than the datasets'), a contract with semver and stable ids, and a lifecycle. It never copies data. Produced by {@link ModelDefParser}, made for a
 * consumer of one dataset by {@link ImplicitModels}, or read from a version-1 dataset by {@link CompatReader}. Immutable.
 *
 * @param implicitFrom for an implicit model (or one written out from it), the dataset it was made from; else null
 * @param placeholders the later-wave keys that are used though their wave has not come ({@code calc-groups}, {@code parameters}): refused with DRS-9129
 */
public record ModelDef(String name, String version, String id, String title, String description, String owner, boolean sample, String endorsement,
        Map<String, Use> datasets, List<Rel> relationships, Map<String, Meas> measures, Map<String, List<String>> hierarchies, Security security, Contract contract,
        String lifecycle, String replacedBy, List<Dataset.Rollup> rollups, List<JsonNode> tests, List<JsonNode> golden, JsonNode ai, String implicitFrom,
        List<String> placeholders) {

    public static final Set<String> ROLES = Set.of("fact", "dimension", "bridge");
    public static final Set<String> LIFECYCLES = Set.of("draft", "published", "deprecated", "retired");

    public ModelDef {
        datasets = Collections.unmodifiableMap(new LinkedHashMap<>(datasets));
        relationships = List.copyOf(relationships);
        measures = Collections.unmodifiableMap(new LinkedHashMap<>(measures));
        hierarchies = Collections.unmodifiableMap(new LinkedHashMap<>(hierarchies));
        rollups = rollups == null ? List.of() : List.copyOf(rollups);
        tests = tests == null ? List.of() : List.copyOf(tests);
        golden = golden == null ? List.of() : List.copyOf(golden);
        placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
    }

    public int major() {
        Semver v = Semver.parse(version);
        return v == null ? 0 : v.major();
    }

    public boolean implicit() {
        return implicitFrom != null;
    }

    /** A dataset in the model: the reference ({@code pack.trading.trade@^1}) and its star role. */
    public record Use(String alias, String dataset, Semver.Range range, String role) {

        public boolean fact() {
            return "fact".equals(role);
        }
    }

    /**
     * A relationship, {@code from} the many side ({@code alias.column}), {@code to} the one side.
     *
     * @param suggested where a designer's suggestion came from ({@code pack-reference}, {@code values}, {@code name}), or null
     */
    public record Rel(String id, String fromAlias, String fromColumn, String toAlias, String toColumn, String cardinality, String direction, boolean active, String name,
            String suggested) {

        public String label() {
            return fromAlias + "." + fromColumn + " -> " + toAlias + "." + toColumn;
        }
    }

    /**
     * A measure: an expression over the model's aliases, or a Python measure.
     *
     * @param currency the column (or ISO code) a measure's amounts are in when that is per row ({@code trades.currency}), for the currency guard
     * @param additive {@code true}, {@code false}, an over-time rule, or null (derived from its expression and columns)
     * @param via an inactive relationship's id (wave W7)
     * @param implicit a default measure the model got without writing it ({@code Sum of notional})
     */
    public record Meas(String name, String id, String expr, String format, String description, String currency, String additive, String python, List<String> inputs,
            List<String> by, String via, boolean implicit) {

        public Meas {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            by = by == null ? List.of() : List.copyOf(by);
        }
    }

    /**
     * Model security: row rules per alias (ANDed with the dataset's own), extra masks, and {@code maySeeAll}: per alias, the roles restricted elsewhere in the model
     * that the author has said may see every row of that dataset (the safeguard's explicit, audited note).
     */
    public record Security(Map<String, List<DatasetDef.Rule>> rows, List<String> masked, String propagate, Map<String, List<String>> maySeeAll) {

        public static final Security NONE = new Security(Map.of(), List.of(), "dimension-to-fact", Map.of());

        public Security {
            rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
            masked = List.copyOf(masked);
            maySeeAll = maySeeAll == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(maySeeAll));
        }
    }

    /**
     * What consumers may use: everything ({@code exposes: all}) or the listed fields and measures; deprecated names with the version they were deprecated in and
     * what to use instead.
     */
    public record Contract(String exposes, List<String> fields, List<String> measures, Map<String, Deprecation> deprecated) {

        public static final Contract ALL = new Contract("all", List.of(), List.of(), Map.of());

        public Contract {
            fields = List.copyOf(fields);
            measures = List.copyOf(measures);
            deprecated = Collections.unmodifiableMap(new LinkedHashMap<>(deprecated));
        }

        public boolean listed() {
            return "listed".equals(exposes);
        }
    }

    public record Deprecation(String since, String use) {}
}
