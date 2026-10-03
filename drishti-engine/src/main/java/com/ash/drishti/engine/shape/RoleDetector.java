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
package com.ash.drishti.engine.shape;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.Role;
import com.ash.drishti.inference.Semantics;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What each path means to a screen designer (id, link, measure, dimension, status, date, series, ohlc, distribution,
 * grid, steps, graph, tree, events, text), with the reason it was chosen. The field-name hints and the date and
 * tenor patterns are the runtime inference's ({@link Semantics}); links come from the packs' reference catalogue;
 * the vocabulary (state words, id names, candle names...) is {@link BuilderProperties}. Stateless: safe to share.
 */
final class RoleDetector {

    private static final Pattern ID_VALUE = Pattern.compile("^[A-Za-z]{2,}[-_]?\\d{3,}.*$|^[A-Za-z]{1,6}-[A-Za-z0-9-]{3,}$");
    private static final double SUM_TOLERANCE = 0.01;

    private final BuilderProperties props;
    private final Semantics semantics;
    private final ReferenceCatalog references;
    private final Pattern statusNames;
    private final Pattern idNames;
    private final Set<String> statusWords;

    RoleDetector(BuilderProperties props, Semantics semantics, ReferenceCatalog references) {
        this.props = props;
        this.semantics = semantics;
        this.references = references;
        this.statusNames = Pattern.compile(props.statusNames(), Pattern.CASE_INSENSITIVE);
        this.idNames = Pattern.compile(props.idNames(), Pattern.CASE_INSENSITIVE);
        this.statusWords = Set.copyOf(props.statusWords().stream().map(w -> w.toLowerCase(Locale.ROOT)).toList());
    }

    /** The role of {@code f} at {@code path}, or empty when it has none worth a screen's attention. */
    Optional<RoleInfo> detect(Facts f, String path, List<JsonNode> documents) {
        if (f.isMap() || f.conflict()) {
            return Optional.empty();
        }
        if (f.allMasked()) {
            return Optional.of(new RoleInfo("plain", "values are masked for this author", null));
        }
        String name = f.name;
        if (f.only("object")) {
            return object(f);
        }
        if (f.only("array")) {
            return array(f, path, documents);
        }
        if (f.only("string")) {
            return string(f, name);
        }
        if (f.only("number")) {
            return number(f, name);
        }
        if (f.only("boolean")) {
            return Optional.of(new RoleInfo("plain", "a flag", null));
        }
        return Optional.empty();
    }

    // ----------------------------------------------------------------------------------------------------- objects

    private Optional<RoleInfo> object(Facts f) {
        Facts nodes = firstOf(f, props.graphNodeNames());
        Facts edges = firstOf(f, props.graphEdgeNames());
        if (nodes != null && edges != null && nodes.items != null && edges.items != null && nodes.items.onlyObjects()
                && edges.items.onlyObjects()) {
            return Optional.of(new RoleInfo("graph", "has '" + nodes.name + "' and '" + edges.name + "' lists of records", null));
        }
        Facts id = f.props.get("id");
        if (id != null && (f.props.containsKey("name") || f.props.containsKey("kind") || f.props.containsKey("label")) && id.only("string")) {
            String kind = f.props.containsKey("kind") ? first(f.props.get("kind").examples) : kindOfValues(id).orElse(null);
            return Optional.of(new RoleInfo("link", "an {id, name} record" + (kind == null ? "" : ", kind " + kind), kind));
        }
        return Optional.empty();
    }

    private static Facts firstOf(Facts f, List<String> names) {
        for (String n : names) {
            for (var e : f.props.entrySet()) {
                if (e.getKey().equalsIgnoreCase(n) && e.getValue().only("array")) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------ arrays

    private Optional<RoleInfo> array(Facts f, String path, List<JsonNode> documents) {
        if (f.tree) {
            return Optional.of(new RoleInfo("tree", "the recursive list of the same records (" + f.name + ")", null));
        }
        Facts item = f.items;
        if (item == null) {
            return Optional.empty();
        }
        Optional<String> treeChild = item.props.values().stream().filter(p -> p.tree).map(p -> p.name).findFirst();
        if (treeChild.isPresent()) {
            return Optional.of(new RoleInfo("tree", "each item holds a list '" + treeChild.get() + "' of the same shape", null));
        }
        if (item.only("number") && f.maxLen >= 5) {
            return Optional.of(new RoleInfo("distribution", "a list of " + f.maxLen + " numbers", null));
        }
        if (item.only("string")) {
            Optional<String> kind = references.kindOfField(f.name);
            return kind.map(k -> new RoleInfo("link", "a list of ids; field '" + f.name + "' refers to " + k, k));
        }
        if (!item.onlyObjects()) {
            return Optional.empty();
        }
        return Optional.of(rows(f, item, path, documents));
    }

    private RoleInfo rows(Facts f, Facts item, String path, List<JsonNode> documents) {
        List<Facts> numbers = new ArrayList<>();
        List<Facts> texts = new ArrayList<>();
        List<Facts> dates = new ArrayList<>();
        for (Facts p : item.props.values()) {
            if (p.only("number") && !p.allMasked()) {
                numbers.add(p);
            } else if (p.only("string") && p.plainStrings() > 0) {
                (axisKind(p) != null ? dates : texts).add(p);
            }
        }
        long ohlc = props.ohlcNames().stream().filter(n -> item.props.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(n))).count();
        if (ohlc == props.ohlcNames().size()) {
            return new RoleInfo("ohlc", "records with " + String.join("/", props.ohlcNames()), null);
        }
        if (numbers.size() == 1 && item.props.size() == 1) {
            return new RoleInfo("distribution", "records with one measure, '" + numbers.get(0).name + "'", null);
        }
        if (dates.isEmpty() && numbers.size() == 1 && texts.size() == 1 && item.props.size() == 2 && f.maxLen >= 3) {
            Facts value = numbers.get(0);
            String sum = sumTarget(path, value.name, documents);
            if (sum != null) {
                return new RoleInfo("steps", "'" + texts.get(0).name + "' with signed '" + value.name + "' that sums to " + sum, null);
            }
            if (value.min < 0 && value.max > 0) {
                return new RoleInfo("steps", "'" + texts.get(0).name + "' with signed '" + value.name + "' (gains and losses)", null);
            }
        }
        if (dates.size() >= 1 && !numbers.isEmpty()) {
            Facts axis = dates.get(0);
            return new RoleInfo("series", "'" + axis.name + "' (" + axisKind(axis) + ") with " + numbers.size() + " number column"
                    + (numbers.size() == 1 ? "" : "s"), null);
        }
        if (dates.isEmpty() && numbers.size() >= 3 && texts.size() == 1) {
            return new RoleInfo("grid", numbers.size() + " number columns along '" + texts.get(0).name + "'", null);
        }
        if (dates.size() >= 1 && numbers.isEmpty() && texts.stream().anyMatch(t -> isLabelName(t.name))) {
            return new RoleInfo("events", "'" + dates.get(0).name + "' with a label", null);
        }
        return new RoleInfo("table", "a list of " + item.props.size() + "-field records", null);
    }

    private boolean isLabelName(String n) {
        return props.labelNames().stream().anyMatch(l -> l.equalsIgnoreCase(n));
    }

    /** "date" or "tenor" when every plain text of {@code p} is one; null otherwise. */
    private String axisKind(Facts p) {
        int plain = p.plainStrings();
        if (plain > 0 && p.dates + p.datetimes == plain) {
            return "date";
        }
        if (plain > 0 && !p.overflow && !p.distinct.isEmpty() && p.distinct.keySet().stream().allMatch(semantics::isTenor)) {
            return "tenor";
        }
        return null;
    }

    /**
     * For a top-level list, the top-level number its values add up to in every document (within one per cent), as
     * a path; null when there is none or the list is nested.
     */
    private String sumTarget(String path, String valueName, List<JsonNode> documents) {
        if (!path.matches("\\$\\.[^.\\[\\]{}]+") || documents.isEmpty()) {
            return null;
        }
        String list = path.substring(2);
        Set<String> candidates = null;
        for (JsonNode doc : documents) {
            JsonNode rows = doc.get(list);
            if (rows == null || !rows.isArray()) {
                continue;
            }
            double sum = 0;
            for (JsonNode r : rows) {
                sum += r.path(valueName).asDouble();
            }
            Set<String> hit = new TreeSet<>();
            for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) doc::fields) {
                if (e.getValue().isNumber() && !e.getKey().equals(list)
                        && Math.abs(sum - e.getValue().asDouble()) <= SUM_TOLERANCE * Math.max(1, Math.abs(e.getValue().asDouble()))) {
                    hit.add("$." + e.getKey());
                }
            }
            if (candidates == null) {
                candidates = hit;
            } else {
                candidates.retainAll(hit);
            }
        }
        return candidates == null || candidates.isEmpty() ? null : candidates.iterator().next();
    }

    // ------------------------------------------------------------------------------------------------------ scalars

    private Optional<RoleInfo> string(Facts f, String name) {
        Optional<String> byField = references.kindOfField(name);
        Optional<String> byValue = kindOfValues(f);
        if (byField.isPresent() || byValue.isPresent()) {
            String kind = byField.orElseGet(byValue::get);
            return Optional.of(new RoleInfo("link", byField.isPresent() ? "field '" + name + "' refers to " + kind + " in the packs' catalogue"
                    : "every value is the id of a " + kind, kind));
        }
        int plain = f.plainStrings();
        if (plain > 0 && f.dates == plain) {
            return Optional.of(new RoleInfo("date", "every value is an ISO date", null));
        }
        if (plain > 0 && f.dates + f.datetimes == plain) {
            return Optional.of(new RoleInfo("date", "every value is an ISO date or date-time", null));
        }
        if (plain > 0 && !f.repeats && idNames.matcher(name).find()) {
            return Optional.of(new RoleInfo("id", "named like an id, and no value repeats", null));
        }
        if (plain >= 2 && !f.repeats && !f.overflow && f.distinct.keySet().stream().allMatch(v -> ID_VALUE.matcher(v).matches())) {
            return Optional.of(new RoleInfo("id", "values look like ids (" + first(f.examples) + ") and none repeats", null));
        }
        boolean lowCardinality = plain > 0 && !f.overflow && f.distinct.size() <= props.enumMaxDistinct();
        if (lowCardinality) {
            long states = f.distinct.keySet().stream().filter(v -> statusWords.contains(v.toLowerCase(Locale.ROOT))).count();
            boolean named = statusNames.matcher(name).find();
            if (named || states * 2 >= f.distinct.size()) {
                return Optional.of(new RoleInfo("status", named ? "named '" + name + "' with " + f.distinct.size() + " distinct value(s)"
                        : "values are states (" + first(f.examples) + "...)", null));
            }
            if (f.repeats && plain >= props.enumMinSeen()) {
                return Optional.of(new RoleInfo("dimension", f.distinct.size() + " distinct value(s) over " + plain + " occurrences", null));
            }
            Role hint = semantics.role(name, DataNode.of("x"));
            if ("label".equals(hint.name()) && !isLabelName(name)) {
                return Optional.of(new RoleInfo("dimension", "named like a category ('" + name + "')", null));
            }
        }
        if (f.longest >= props.longTextChars() || f.multiline) {
            return Optional.of(new RoleInfo("text", "long text (up to " + f.longest + " characters)", null));
        }
        return Optional.empty();
    }

    private Optional<RoleInfo> number(Facts f, String name) {
        if (f.ints == f.occ - f.nulls && !f.repeats && f.numbers.size() == f.ints && f.ints >= 2 && idNames.matcher(name).find()) {
            return Optional.of(new RoleInfo("id", "named like an id, and no value repeats", null));
        }
        Role hint = semantics.role(name, DataNode.of(Math.abs(f.max) < 1 && Math.abs(f.min) < 1 ? 0.5 : 1000));
        if (!"number".equals(hint.name()) && !"plain".equals(hint.name())) {
            return Optional.of(new RoleInfo("measure", "name reads as " + hint.name() + " (" + name + ")", null));
        }
        if (f.numbers.size() >= 5) {
            return Optional.of(new RoleInfo("measure", "numbers that vary (" + f.numbers.size() + "+ distinct values)", null));
        }
        return Optional.of(new RoleInfo("measure", "a number", null));
    }

    /** The one kind every plain text value is an id of, per the packs' id patterns. */
    private Optional<String> kindOfValues(Facts f) {
        if (f.plainStrings() == 0 || f.distinct.isEmpty()) {
            return Optional.empty();
        }
        String kind = null;
        for (String v : f.distinct.keySet()) {
            Optional<String> k = references.kindOf(v);
            if (k.isEmpty() || kind != null && !kind.equals(k.get())) {
                return Optional.empty();
            }
            kind = k.get();
        }
        return Optional.ofNullable(kind);
    }

    private static String first(java.util.Collection<String> c) {
        return c.isEmpty() ? null : c.iterator().next();
    }
}
