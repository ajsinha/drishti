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
package com.ash.drishti.tables.def;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A model of the layer (BI_FOUNDATION_2911E.md section 3.3): virtual tables under aliases (Q10), related many-to-one, with measures in
 * Rachana's aggregate scope, hierarchies, model security that only tightens, and the keys BI's models have kept as written. MQ asks it
 * as it asks a BI model today.
 *
 * @param name the model's name ({@code trading-risk}); consumers bind by name and major ({@code trading-risk@1})
 * @param version semver, {@code 1.0.0} when not given
 * @param about the descriptive keys kept as written ({@code id}, {@code title}, {@code description}, {@code owner}, {@code tags},
 *     {@code sample}, {@code endorsement}, {@code lifecycle}, {@code layout}, {@code ai}, {@code golden}, {@code contract},
 *     {@code perspectives}, {@code fields})
 * @param tables alias to the table it reads and its role, in the order written
 * @param relationships the relationships, from the many side to the one side
 * @param measures name to measure, in the order written
 * @param hierarchies name to its levels (model fields: {@code trades.TRADE_DATE.month} is a field name)
 * @param security the model's row rules per alias, masks and exceptions
 * @param extras the keys the layer keeps as written for their consumers: {@code calc-groups}, {@code parameters}, {@code calendars},
 *     {@code fx}, {@code tests}, {@code rollups}, {@code history}, {@code scan-warn-rows} (this model's own threshold for the scan
 *     estimate's warning on a join across connectors, over the installation's {@code drishti.tables.scan-warn-rows})
 */
public record ModelDef(String name, String version, Map<String, Object> about, Map<String, Use> tables, List<Relationship> relationships,
        Map<String, Measure> measures, Map<String, List<String>> hierarchies, Security security, Map<String, Object> extras) {

    /** The keys {@link #extras} holds. */
    public static final Set<String> EXTRA_KEYS = Set.of("calc-groups", "parameters", "calendars", "fx", "tests", "rollups", "history",
            "scan-warn-rows");

    /** The model's own {@code scan-warn-rows} (a whole number above zero), or null when it has none or it is not one. */
    public Long scanWarnRows() {
        Object v = extras.get("scan-warn-rows");
        if (v instanceof Number n && n.longValue() > 0 && n.doubleValue() == n.longValue()) {
            return n.longValue();
        }
        return null;
    }

    public ModelDef {
        about = about == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(about));
        tables = tables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        measures = measures == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(measures));
        hierarchies = hierarchies == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(hierarchies));
        security = security == null ? Security.NONE : security;
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        version = version == null ? "1.0.0" : version;
    }

    /** The major version; 1 when it cannot be read. */
    public int major() {
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * One table of the model under its alias.
     *
     * @param alias the name measures, fields and rules use ({@code trades})
     * @param table the table and version range ({@code banking.trading_risk_trades}, {@code trading.trade@^1})
     * @param role {@code fact} (added up) or {@code dimension} (groups and filters)
     */
    public record Use(String alias, TableName.Ref table, String role) {
        public static final Set<String> ROLES = Set.of("fact", "dimension");
    }

    /**
     * A relationship from the many side to the one side.
     *
     * @param id its id ({@code r_5}), or null
     * @param from {@code alias.COLUMN} on the many side
     * @param to {@code alias.COLUMN} on the one side
     * @param cardinality {@code many-to-one} when not given
     * @param direction {@code single} or {@code both}, or null
     * @param active false for a relationship only {@code calc(..., via = 'r_5')} follows
     * @param label its name in words, or null
     */
    public record Relationship(String id, String from, String to, String cardinality, String direction, boolean active, String label) {
        public static final Set<String> CARDINALITIES = Set.of("many-to-one", "one-to-many", "one-to-one", "many-to-many");
    }

    /**
     * A measure.
     *
     * @param name its name ({@code Open MTM})
     * @param expr the aggregate-scope Rachana expression, or null for a Python measure
     * @param python the Python measure as written ({@code python}, {@code inputs}, {@code by}), or empty
     * @param keys the other keys as written ({@code format}, {@code description}, {@code currency}, {@code additive}, {@code id},
     *     {@code title}, {@code display}, {@code folder}, {@code hidden})
     */
    public record Measure(String name, String expr, Map<String, Object> python, Map<String, Object> keys) {
        public Measure {
            python = python == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(python));
            keys = keys == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        }
    }

    /**
     * The model's own security: it only tightens what the tables' rules allow (BI_DESIGN.md section 15; the safeguards {@code DRS-9130}
     * and {@code DRS-9131} unchanged).
     *
     * @param rows alias to its rules (rule scope over the alias's columns and {@code user.x})
     * @param masked {@code alias.COLUMN} masked in this model beside the tables' own masks
     * @param maySeeAll alias to the roles that see all of it whatever the facts' rules (propagation stops there)
     * @param others the other keys as written ({@code propagate}, {@code unmask}, {@code hidden-for})
     */
    public record Security(Map<String, List<TableDef.Rule>> rows, List<String> masked, Map<String, List<String>> maySeeAll, Map<String, Object> others) {

        public static final Security NONE = new Security(Map.of(), List.of(), Map.of(), Map.of());

        public Security {
            rows = rows == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(rows));
            masked = masked == null ? List.of() : List.copyOf(masked);
            maySeeAll = maySeeAll == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(maySeeAll));
            others = others == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(others));
        }

        public boolean isEmpty() {
            return rows.isEmpty() && masked.isEmpty() && maySeeAll.isEmpty() && others.isEmpty();
        }
    }
}
