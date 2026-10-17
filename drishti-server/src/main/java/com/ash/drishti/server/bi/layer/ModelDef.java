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
 * consumer of one dataset by {@link ImplicitModels}, (the version-1 compatibility reader was removed in 2.2). Immutable.
 *
 * @param implicitFrom for an implicit model (or one written out from it), the dataset it was made from; else null
 * @param placeholders the later-wave keys that are used though their wave has not come: refused with DRS-9129
 * @param calculation wave B: calculation groups, parameters, the calendar and the FX rates
 */
public record ModelDef(String name, String version, String id, String title, String description, String owner, boolean sample, String endorsement,
        Map<String, Use> datasets, List<Rel> relationships, Map<String, Meas> measures, Map<String, List<String>> hierarchies, Security security, Contract contract,
        String lifecycle, String replacedBy, List<Dataset.Rollup> rollups, List<JsonNode> tests, List<JsonNode> golden, JsonNode ai, String implicitFrom,
        List<String> placeholders, Semantics semantics, Calculation calculation) {

    /** As written before field properties, perspectives and object-level security (none). */
    public ModelDef(String name, String version, String id, String title, String description, String owner, boolean sample, String endorsement, Map<String, Use> datasets,
            List<Rel> relationships, Map<String, Meas> measures, Map<String, List<String>> hierarchies, Security security, Contract contract, String lifecycle, String replacedBy,
            List<Dataset.Rollup> rollups, List<JsonNode> tests, List<JsonNode> golden, JsonNode ai, String implicitFrom, List<String> placeholders) {
        this(name, version, id, title, description, owner, sample, endorsement, datasets, relationships, measures, hierarchies, security, contract, lifecycle, replacedBy, rollups,
                tests, golden, ai, implicitFrom, placeholders, Semantics.NONE, Calculation.NONE);
    }

    /** As written before wave B (no calculation groups, parameters, calendar or FX rates). */
    public ModelDef(String name, String version, String id, String title, String description, String owner, boolean sample, String endorsement, Map<String, Use> datasets,
            List<Rel> relationships, Map<String, Meas> measures, Map<String, List<String>> hierarchies, Security security, Contract contract, String lifecycle, String replacedBy,
            List<Dataset.Rollup> rollups, List<JsonNode> tests, List<JsonNode> golden, JsonNode ai, String implicitFrom, List<String> placeholders, Semantics semantics) {
        this(name, version, id, title, description, owner, sample, endorsement, datasets, relationships, measures, hierarchies, security, contract, lifecycle, replacedBy, rollups,
                tests, golden, ai, implicitFrom, placeholders, semantics, Calculation.NONE);
    }

    /**
     * Wave B's model keys (docs/architecture/BI_DESIGN.md sections 3.3.5 and 12.2): calculation groups (templates applied to any selected measure, with
     * {@code SELECTED} the measure), parameters ({@code param.name} in expressions, what-if numbers or a field chosen by the consumer), the model's calendar
     * (holidays and the fiscal year) and the FX rates {@code fx} and {@code convert} read.
     */
    public record Calculation(Map<String, CalcGroup> calcGroups, Map<String, Parameter> parameters, CalendarDef calendar, FxDef fx) {
        public static final Calculation NONE = new Calculation(Map.of(), Map.of(), null, null);

        public Calculation {
            calcGroups = Collections.unmodifiableMap(new LinkedHashMap<>(calcGroups));
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
    }

    /**
     * A calculation group: its items (name to a DEL template over {@code SELECTED}), its precedence (higher applies first, innermost) and the format an item
     * gives its answer ({@code Share: { expr: "share_of(SELECTED, …)", format: share }}: a share reads as a percent, whatever the measure's own format).
     */
    public record CalcGroup(String name, int precedence, Map<String, String> items, String description, Map<String, String> formats) {
        public CalcGroup {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
            formats = formats == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(formats));
        }

        public CalcGroup(String name, int precedence, Map<String, String> items, String description) {
            this(name, precedence, items, description, Map.of());
        }
    }

    /**
     * A parameter: {@code number}, {@code integer}, {@code text}, {@code date} or {@code boolean} (a what-if value bound as {@code param.name}) or {@code field}
     * (one of {@code fields}, put in a query's fields as {@code param.name}).
     */
    public record Parameter(String name, String kind, Object defaultValue, Double min, Double max, Double step, List<Object> values, List<String> fields, String title,
            String description) {
        public Parameter {
            values = values == null ? List.of() : List.copyOf(values);
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** The model's calendar: the business-day calendar its dates follow ({@code USNY}, {@code USNY+GBLO}) and the month its fiscal year starts. */
    public record CalendarDef(String holidays, int fiscalYearStart) {}

    /**
     * Where the FX rates are: a dataset of the model ({@code rates}, an alias) with the currency converted from and to, the rate (units of {@code to} per one
     * {@code from}) and its date; {@code base} the currency every rate is quoted in when a pair is not stored directly (cross rates through it).
     */
    public record FxDef(String rates, String from, String to, String rate, String date, String base) {}

    /**
     * What a model says about its fields and measures beyond their computation (docs/architecture/BI_DESIGN.md sections 2.2 and 5.1): display properties
     * every consumer inherits ({@code fields:} and the measures' own keys), perspectives (named subsets for a team or task: a view, not a security boundary),
     * and object-level security ({@code security.hidden-for}: a field or measure that does not exist for some roles).
     */
    public record Semantics(Map<String, FieldProps> fields, Map<String, FieldProps> measures, Map<String, Perspective> perspectives, List<HiddenFor> hiddenFor) {
        public static final Semantics NONE = new Semantics(Map.of(), Map.of(), Map.of(), List.of());

        public Semantics {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            measures = Collections.unmodifiableMap(new LinkedHashMap<>(measures));
            perspectives = Collections.unmodifiableMap(new LinkedHashMap<>(perspectives));
            hiddenFor = List.copyOf(hiddenFor);
        }
    }

    /** The data categories a field may carry (what a map, a link or an image visual needs). */
    public static final Set<String> CATEGORIES = Set.of("none", "country", "region", "state", "city", "postcode", "address", "latitude", "longitude", "place", "url",
            "image-url", "currency", "percentage", "identifier");
    /** How a field is summarised when it is dropped on a value well. */
    public static final Set<String> SUMMARISE = Set.of("sum", "avg", "min", "max", "count", "distinct", "none");

    /**
     * Field (or measure) properties. Null means "not said" (the dataset's own, or none).
     *
     * @param display the name shown in field lists, visuals and answers' headers
     * @param folder a display folder, {@code A/B} for nesting
     * @param hidden true: not listed in field lists (still usable; this is not security, see {@link HiddenFor})
     * @param sortBy another field whose order this one sorts by (a month name by the month number)
     * @param category a data category ({@link #CATEGORIES})
     * @param summarise the default summarisation of a field dropped on a value well ({@link #SUMMARISE})
     */
    public record FieldProps(String display, String description, String format, String folder, Boolean hidden, String sortBy, String category, String summarise) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("display", display);
            m.put("description", description);
            m.put("format", format);
            m.put("folder", folder);
            m.put("hidden", hidden != null && hidden);
            m.put("sortBy", sortBy);
            m.put("category", category);
            m.put("summarise", summarise);
            return m;
        }
    }

    /** A perspective: its title, description, and the fields ({@code alias.column} or {@code alias.*}) and measures it shows. */
    public record Perspective(String name, String title, String description, List<String> fields, List<String> measures) {
        public Perspective {
            fields = List.copyOf(fields);
            measures = List.copyOf(measures);
        }

        /** True when the perspective shows the field. */
        public boolean shows(String field) {
            String alias = field.contains(".") ? field.substring(0, field.indexOf('.')) : field;
            String col = field.contains(".") ? field.split("\\.")[1] : field;
            return fields.contains(field) || fields.contains(alias + ".*") || fields.contains(alias + "." + col);
        }
    }

    /** {@code hidden-for}: for readers holding any of {@code roles}, the fields ({@code alias.column}) and measures do not exist. */
    public record HiddenFor(List<String> roles, List<String> fields, List<String> measures) {
        public HiddenFor {
            roles = List.copyOf(roles);
            fields = List.copyOf(fields);
            measures = List.copyOf(measures);
        }
    }

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
        semantics = semantics == null ? Semantics.NONE : semantics;
        calculation = calculation == null ? Calculation.NONE : calculation;
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
