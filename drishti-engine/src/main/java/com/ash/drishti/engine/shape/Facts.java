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
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything observed at one path across the sample documents: how often, of what types, the numbers' range, the
 * texts' values and formats, the fields of objects and the elements of arrays. Built by walking documents
 * ({@link #see}) and combined with {@link #absorb}; read by the schema writer and the role rules. One merge runs on
 * one thread; nothing here is shared.
 */
final class Facts {

    static final int DISTINCT_CAP = 64;
    private static final Pattern DATETIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> ISO_CURRENCIES = new LinkedHashSet<>();

    static {
        for (java.util.Currency c : java.util.Currency.getAvailableCurrencies()) {
            ISO_CURRENCIES.add(c.getCurrencyCode());
        }
    }

    final String name;
    int occ;
    final BitSet docs = new BitSet();
    final Set<String> files = new LinkedHashSet<>();
    int nulls;
    int bools;
    int ints;
    int floats;
    int strings;
    int objects;
    int arrays;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    /** Exact bounds (integers beyond 2^53 and wide decimals lose digits in a double). */
    java.math.BigDecimal minExact;
    java.math.BigDecimal maxExact;
    /** A sample held a NaN or an infinite number: no bound is written for it. */
    boolean nonFinite;
    final Set<Double> numbers = new LinkedHashSet<>();
    final Map<String, Integer> distinct = new LinkedHashMap<>();
    boolean overflow;
    boolean repeats;
    int masked;
    int dates;
    int datetimes;
    int uuids;
    int emails;
    int currencies;
    int longest;
    boolean multiline;
    final Set<String> examples = new LinkedHashSet<>();
    final Map<String, Facts> props = new LinkedHashMap<>();
    Facts items;
    int minLen = Integer.MAX_VALUE;
    int maxLen;
    int elements;
    /** Set when this is a map: the merged facts of every value. */
    Facts mapValue;
    int mapKeys;
    /** Set on an array whose elements are the object that holds it (a tree): the elements, whose fields were merged across levels. */
    boolean tree;
    /** On a tree: how many of its list elements (at any level) were null; they belong to the list, not to the record. */
    int treeNulls;

    Facts(String name) {
        this.name = name;
    }

    // ------------------------------------------------------------------------------------------------------ walking

    /** Record {@code v}, seen in document {@code doc} (named {@code file}), nested {@code depth} deep. */
    void see(JsonNode v, int doc, String file, int depth, int maxDepth, int maxExamples) {
        if (depth > maxDepth + 1) {
            throw new ShapeException("document '" + file + "' is nested deeper than " + maxDepth + " levels (drishti.builder.max-depth)");
        }
        occ++;
        if (!docs.get(doc)) {
            docs.set(doc);
            files.add(file);
        }
        if (v == null || v.isNull() || v.isMissingNode()) {
            nulls++;
        } else if (v.isObject()) {
            objects++;
            v.fields().forEachRemaining(e -> props.computeIfAbsent(e.getKey(), Facts::new).see(e.getValue(), doc, file, depth + 1, maxDepth,
                    maxExamples));
        } else if (v.isArray()) {
            arrays++;
            minLen = Math.min(minLen, v.size());
            maxLen = Math.max(maxLen, v.size());
            elements += v.size();
            if (items == null && v.size() > 0) {
                items = new Facts("[]");
            }
            for (JsonNode e : v) {
                items.see(e, doc, file, depth + 1, maxDepth, maxExamples);
            }
        } else if (v.isBoolean()) {
            bools++;
            example(v.asText(), maxExamples);
        } else if (v.isNumber()) {
            double d = v.asDouble();
            if (v.isIntegralNumber() || d == Math.rint(d) && !Double.isInfinite(d)) {
                ints++;
            } else {
                floats++;
            }
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                nonFinite = true;
            } else {
                java.math.BigDecimal exact = v.decimalValue();
                minExact = minExact == null || exact.compareTo(minExact) < 0 ? exact : minExact;
                maxExact = maxExact == null || exact.compareTo(maxExact) > 0 ? exact : maxExact;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            if (numbers.size() < DISTINCT_CAP) {
                numbers.add(d);
            }
            example(v.asText(), maxExamples);
        } else {
            text(v.asText(), maxExamples);
        }
    }

    private void text(String s, int maxExamples) {
        strings++;
        example(s, maxExamples);
        if (DataNode.MASK.equals(s)) {
            masked++;
            return;
        }
        int seen = distinct.merge(s, 1, Integer::sum);
        if (seen > 1) {
            repeats = true;
        }
        if (distinct.size() > DISTINCT_CAP) {
            overflow = true;
            distinct.remove(distinct.keySet().iterator().next());
        }
        longest = Math.max(longest, s.length());
        multiline |= s.indexOf('\n') >= 0;
        if (DATE.matcher(s).matches() && validDate(s)) {
            dates++;
        } else if (DATETIME.matcher(s).matches() && validDateTime(s)) {
            datetimes++;
        } else if (isUuid(s)) {
            uuids++;
        } else if (EMAIL.matcher(s).matches()) {
            emails++;
        } else if (CURRENCY.matcher(s).matches() && ISO_CURRENCIES.contains(s)) {
            currencies++;
        }
    }

    private void example(String s, int max) {
        if (examples.size() < max) {
            examples.add(s);
        }
    }

    private static boolean validDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean validDateTime(String s) {
        String t = s.replace(' ', 'T');
        try {
            OffsetDateTime.parse(t);
            return true;
        } catch (RuntimeException e) {
            try {
                LocalDateTime.parse(t);
                return true;
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }

    private static boolean isUuid(String s) {
        if (s.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------ merging

    /** Fold {@code o} into this (the same path seen elsewhere: the values of a map, the levels of a tree). */
    void absorb(Facts o) {
        absorbCounts(o);
        o.props.forEach((k, v) -> props.computeIfAbsent(k, Facts::new).absorb(v));
        absorbArrays(o);
    }

    /** Fold the array lengths and elements of {@code o} in. */
    void absorbArrays(Facts o) {
        minLen = Math.min(minLen, o.minLen);
        maxLen = Math.max(maxLen, o.maxLen);
        elements += o.elements;
        if (o.items != null) {
            if (items == null) {
                items = new Facts("[]");
            }
            items.absorb(o.items);
        }
    }

    void absorbCounts(Facts o) {
        occ += o.occ;
        docs.or(o.docs);
        files.addAll(o.files);
        nulls += o.nulls;
        bools += o.bools;
        ints += o.ints;
        floats += o.floats;
        strings += o.strings;
        objects += o.objects;
        arrays += o.arrays;
        min = Math.min(min, o.min);
        max = Math.max(max, o.max);
        nonFinite |= o.nonFinite;
        if (o.minExact != null) {
            minExact = minExact == null || o.minExact.compareTo(minExact) < 0 ? o.minExact : minExact;
            maxExact = maxExact == null || o.maxExact.compareTo(maxExact) > 0 ? o.maxExact : maxExact;
        }
        for (Double n : o.numbers) {
            if (numbers.size() < DISTINCT_CAP) {
                numbers.add(n);
            }
        }
        o.distinct.forEach((k, c) -> {
            if (distinct.containsKey(k)) {
                repeats = true;
            }
            if (distinct.size() < DISTINCT_CAP || distinct.containsKey(k)) {
                distinct.merge(k, c, Integer::sum);
            } else {
                overflow = true;
            }
        });
        overflow |= o.overflow;
        repeats |= o.repeats;
        masked += o.masked;
        dates += o.dates;
        datetimes += o.datetimes;
        uuids += o.uuids;
        emails += o.emails;
        currencies += o.currencies;
        longest = Math.max(longest, o.longest);
        multiline |= o.multiline;
        for (String e : o.examples) {
            if (examples.size() < 8) {
                examples.add(e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ reading

    int plainStrings() {
        return strings - masked;
    }

    /** Only masked text was seen. */
    boolean allMasked() {
        return strings > 0 && masked == strings && ints + floats + bools + objects + arrays == 0;
    }

    boolean numeric() {
        return ints + floats > 0;
    }

    /** JSON Schema types seen; integer and number merge to number when any fraction was seen. */
    List<String> types() {
        List<String> t = new ArrayList<>();
        if (objects > 0) {
            t.add("object");
        }
        if (arrays > 0) {
            t.add("array");
        }
        if (strings > 0) {
            t.add("string");
        }
        if (floats > 0) {
            t.add("number");
        } else if (ints > 0) {
            t.add("integer");
        }
        if (bools > 0) {
            t.add("boolean");
        }
        if (nulls > 0) {
            t.add("null");
        }
        return t;
    }

    /** More than one kind of non-null value: the samples disagree. */
    boolean conflict() {
        return types().stream().filter(x -> !"null".equals(x)).count() > 1;
    }

    boolean isMap() {
        return mapValue != null;
    }

    /** Every non-null value is an object. */
    boolean onlyObjects() {
        return objects > 0 && objects == occ - nulls;
    }

    /** Only strings, numbers or booleans were seen (and at least one). */
    boolean scalarOnly() {
        return objects == 0 && arrays == 0 && occ > nulls;
    }

    /** True when every non-null value is of type {@code t} (and there is at least one). */
    boolean only(String t) {
        List<String> ty = types().stream().filter(x -> !"null".equals(x)).toList();
        return ty.size() == 1 && ty.get(0).equals(t) || ("number".equals(t) && ty.equals(List.of("integer")));
    }

    /** The share of documents (0..1) holding this path, of {@code total}. */
    double docShare(int total) {
        return total == 0 ? 1 : Math.min(1.0, docs.cardinality() / (double) total);
    }
}
