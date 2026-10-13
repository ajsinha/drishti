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
package com.ash.drishti.server.bi.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A node of the logical plan, the one IR every language compiles to (docs/architecture/BI_DESIGN.md section 3.6): MQ, Drishti SQL, a report visual, an alert.
 * Each node says what it is ({@link #op}), what it reads ({@link #inputs}), the properties that make the question ({@link #props}: what the plan hash covers)
 * and, for a scan, the reader's security ({@link #security}: what the security signature covers, never the hash). Immutable.
 */
public sealed interface PlanNode {

    String op();

    List<PlanNode> inputs();

    /** The properties the plan hash covers (names in canonical form: ids where the model has them). */
    Map<String, Object> props();

    /** The reader's security on this node (row predicates, masks, hidden columns): covered by the security signature, never by the plan hash. */
    default Map<String, Object> security() {
        return Map.of();
    }

    /** A copy with other inputs (same kind, same properties). */
    PlanNode withInputs(List<PlanNode> inputs);

    /** A row filter of the query on one field: {@code field op values}, as the engine takes it. {@code order} keeps the query's order for lowering. */
    record Cond(String field, String op, List<Object> values, int order) {
        public Cond {
            values = values == null ? List.of() : List.copyOf(values.stream().map(v -> v == null ? "\u0000null" : v).toList());
        }

        public String text() {
            return field + " " + op + " " + values;
        }

        /** The canonical form: field, operator, values. */
        public List<Object> canonical() {
            return List.of(field, op, values);
        }
    }

    /**
     * One dataset's rows for this reader at this time (section 3.6): the row rules ANDed (each layer the OR of the reader's matching rules), masks projected,
     * hidden columns removed, the query's own filters pushed in.
     *
     * @param rules the reader's resolved predicates ({@code user.*} as values), one per layer; {@code denied} when a layer names none of the reader's roles
     */
    record SecureScan(String alias, String dataset, String version, String time, List<String> columns, List<Cond> pushed, List<String> rules, List<String> masked,
            List<String> hidden, boolean denied) implements PlanNode {
        public SecureScan {
            columns = List.copyOf(columns);
            pushed = List.copyOf(pushed);
            rules = List.copyOf(rules);
            masked = List.copyOf(masked);
            hidden = List.copyOf(hidden);
        }

        @Override
        public String op() {
            return "SecureScan";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of();
        }

        @Override
        public Map<String, Object> props() {
            return sorted("alias", alias, "dataset", dataset, "version", version, "time", time, "columns", columns.stream().sorted().toList(), "pushed",
                    pushed.stream().map(Cond::canonical).sorted(java.util.Comparator.comparing(Object::toString)).toList());
        }

        @Override
        public Map<String, Object> security() {
            return sorted("rules", rules, "masked", masked.stream().sorted().toList(), "hidden", hidden.stream().sorted().toList(), "denied", denied);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return this;
        }

        public SecureScan withPushed(List<Cond> p) {
            return new SecureScan(alias, dataset, version, time, columns, p, rules, masked, hidden, denied);
        }

        public SecureScan withColumns(List<String> c) {
            return new SecureScan(alias, dataset, version, time, c, pushed, rules, masked, hidden, denied);
        }
    }

    /** A dimension's rows (its security or the query's filters) propagated to a fact along a many-to-one relationship. */
    record SemiJoin(PlanNode fact, PlanNode dimension, String relationship) implements PlanNode {
        @Override
        public String op() {
            return "SemiJoin";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(fact, dimension);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("relationship", relationship);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new SemiJoin(inputs.get(0), inputs.get(1), relationship);
        }
    }

    /** The query's row filters above the scans, before the optimiser pushes each into the scan of its dataset. */
    record Filter(List<PlanNode> scans, List<Cond> conditions) implements PlanNode {
        public Filter {
            scans = List.copyOf(scans);
            conditions = List.copyOf(conditions);
        }

        @Override
        public String op() {
            return "Filter";
        }

        @Override
        public List<PlanNode> inputs() {
            return scans;
        }

        @Override
        public Map<String, Object> props() {
            return sorted("conditions", conditions.stream().map(Cond::canonical).sorted(java.util.Comparator.comparing(Object::toString)).toList());
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Filter(inputs, conditions);
        }
    }

    /** Row-scope columns of a dataset (DEL {@code derive}, {@code columns.*.expr}, {@code related()}). */
    record Derive(PlanNode input, Map<String, String> columns) implements PlanNode {
        public Derive {
            columns = new TreeMap<>(columns);
        }

        @Override
        public String op() {
            return "Derive";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("columns", columns);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Derive(inputs.get(0), columns);
        }
    }

    /** An explicit de-duplication to one row per key (a dataset's {@code grain: { one-row-per, keep }}). */
    record Dedup(PlanNode input, List<String> keys, String keep) implements PlanNode {
        @Override
        public String op() {
            return "Dedup";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("keys", keys, "keep", keep);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Dedup(inputs.get(0), keys, keep);
        }
    }

    /** A dataset grouped to a coarser grain it declares ({@code group: { by, columns }}). */
    record Regroup(PlanNode input, List<String> by, Map<String, String> columns) implements PlanNode {
        @Override
        public String op() {
            return "Regroup";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("by", by, "columns", new TreeMap<>(columns));
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Regroup(inputs.get(0), by, columns);
        }
    }

    /**
     * A measure's leaves at the query's fields: the fact's rows (and the dimensions it reaches many-to-one) grouped by {@code by}; {@code rollup} names the rollup
     * that answers it (the optimiser's match), or null for the rows.
     */
    record Aggregate(List<PlanNode> sources, String fact, List<String> by, List<String> measures, String rollup) implements PlanNode {
        public Aggregate {
            sources = List.copyOf(sources);
            by = List.copyOf(by);
            measures = List.copyOf(measures);
        }

        @Override
        public String op() {
            return "Aggregate";
        }

        @Override
        public List<PlanNode> inputs() {
            return sources;
        }

        @Override
        public Map<String, Object> props() {
            return sorted("fact", fact, "by", by, "measures", measures.stream().sorted().toList());
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Aggregate(inputs, fact, by, measures, rollup);
        }

        public Aggregate withRollup(String r) {
            return new Aggregate(sources, fact, by, measures, r);
        }
    }

    /** The full outer join of per-fact aggregates on the fields they share (a question across facts). */
    record Meet(List<PlanNode> parts, List<String> on) implements PlanNode {
        public Meet {
            parts = List.copyOf(parts);
            on = List.copyOf(on);
        }

        @Override
        public String op() {
            return "Meet";
        }

        @Override
        public List<PlanNode> inputs() {
            return parts;
        }

        @Override
        public Map<String, Object> props() {
            return sorted("on", on);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Meet(inputs, on);
        }
    }

    /** After aggregation: window measures over the answer's rows ({@code rank}, {@code lag}, {@code moving_*}, {@code running}). */
    record Window(PlanNode input, List<String> measures) implements PlanNode {
        @Override
        public String op() {
            return "Window";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("measures", measures.stream().sorted().toList());
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Window(inputs.get(0), measures);
        }
    }

    /** Filters on measures, after aggregation. */
    record Having(PlanNode input, List<Cond> conditions) implements PlanNode {
        @Override
        public String op() {
            return "Having";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("conditions", conditions.stream().map(Cond::canonical).sorted(java.util.Comparator.comparing(Object::toString)).toList());
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Having(inputs.get(0), conditions);
        }
    }

    /** The order of the answer: {@code by} with {@code desc}. */
    record Sort(PlanNode input, List<String> keys) implements PlanNode {
        @Override
        public String op() {
            return "Sort";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("keys", keys);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Sort(inputs.get(0), keys);
        }
    }

    record Limit(PlanNode input, int n) implements PlanNode {
        @Override
        public String op() {
            return "Limit";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("n", n);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Limit(inputs.get(0), n);
        }
    }

    /** Totals at levels of the fields (grand {@code []}, subtotals), for the measures that do not add up. */
    record Totals(PlanNode input, List<List<String>> levels) implements PlanNode {
        @Override
        public String op() {
            return "Totals";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("levels", levels);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Totals(inputs.get(0), levels);
        }
    }

    /**
     * A relational operator over already secured answers (composite Drishti SQL: {@code Join}, {@code Union}, {@code Project}, {@code Filter}, {@code Window},
     * {@code Sort}, {@code Limit}), run on the Java tier. Its leaves are whole plans (each an answer, secured and masked), so it can never see more than they do.
     */
    record Rel(String operator, List<PlanNode> sources, Map<String, Object> args) implements PlanNode {
        public Rel {
            sources = List.copyOf(sources);
            args = new TreeMap<>(args);
        }

        @Override
        public String op() {
            return "Rel." + operator;
        }

        @Override
        public List<PlanNode> inputs() {
            return sources;
        }

        @Override
        public Map<String, Object> props() {
            return new TreeMap<>(args);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Rel(operator, inputs, args);
        }
    }

    /** The typed, masked rows the caller gets: the columns in order, and the shape ({@code grouped} or {@code rows}). */
    record Answer(PlanNode input, List<String> columns, String shape) implements PlanNode {
        @Override
        public String op() {
            return "Answer";
        }

        @Override
        public List<PlanNode> inputs() {
            return List.of(input);
        }

        @Override
        public Map<String, Object> props() {
            return sorted("columns", columns, "shape", shape);
        }

        @Override
        public PlanNode withInputs(List<PlanNode> inputs) {
            return new Answer(inputs.get(0), columns, shape);
        }
    }

    /** The nodes of a tree, parents first. */
    static List<PlanNode> all(PlanNode root) {
        List<PlanNode> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(PlanNode n, List<PlanNode> out) {
        out.add(n);
        n.inputs().forEach(i -> collect(i, out));
    }

    /** The scans of a tree. */
    static List<SecureScan> scans(PlanNode root) {
        return all(root).stream().filter(SecureScan.class::isInstance).map(SecureScan.class::cast).toList();
    }

    /** A sorted map of key-value pairs (null values left out). */
    static Map<String, Object> sorted(Object... kv) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    /** Insertion-ordered key-value pairs. */
    static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
