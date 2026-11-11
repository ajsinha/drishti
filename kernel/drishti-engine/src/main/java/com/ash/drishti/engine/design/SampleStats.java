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
package com.ash.drishti.engine.design;

import com.ash.drishti.engine.shape.Sample;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the samples say about a path that the merged shape does not: in how many documents it is present and non-empty,
 * how long its lists typically are, how many distinct values it takes and how much a number varies. Auto-design uses it to
 * choose and to explain ("a median of 20 points over 3 samples"). Read-only; safe to share.
 */
final class SampleStats {

    private final List<JsonNode> docs = new ArrayList<>();

    SampleStats(List<Sample> samples) {
        if (samples != null) {
            samples.forEach(s -> docs.add(s.document()));
        }
    }

    int count() {
        return docs.size();
    }

    /** Every non-null value at {@code path} in one document ({@code $.a[].b} fans out over the list). */
    static List<JsonNode> values(JsonNode doc, String path) {
        List<JsonNode> now = List.of(doc);
        String rest = path.startsWith("$") ? path.substring(1) : path;
        for (String seg : rest.replace("[]", ".[]").split("\\.")) {
            if (seg.isEmpty()) {
                continue;
            }
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode n : now) {
                if ("[]".equals(seg)) {
                    if (n.isArray()) {
                        n.forEach(next::add);
                    }
                } else if (n.isObject() && n.hasNonNull(seg)) {
                    next.add(n.get(seg));
                }
            }
            now = next;
        }
        return now;
    }

    /** In how many samples the path has a value, and, for a list or text, one that is not empty. */
    int present(String path) {
        int n = 0;
        for (JsonNode d : docs) {
            if (values(d, path).stream().anyMatch(SampleStats::nonEmpty)) {
                n++;
            }
        }
        return n;
    }

    /** The median list length at {@code path} over the samples that have it, or -1 without samples. */
    int typicalLength(String path) {
        List<Integer> lens = new ArrayList<>();
        for (JsonNode d : docs) {
            List<JsonNode> v = values(d, path);
            if (!v.isEmpty() && v.get(0).isArray()) {
                lens.add(v.get(0).size());
            }
        }
        if (lens.isEmpty()) {
            return -1;
        }
        lens.sort(null);
        return lens.get(lens.size() / 2);
    }

    /** Distinct values (as text) over all samples, up to {@code cap}. */
    int distinct(String path, int cap) {
        Set<String> seen = new HashSet<>();
        for (JsonNode d : docs) {
            for (JsonNode v : values(d, path)) {
                seen.add(v.asText());
                if (seen.size() >= cap) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }

    /** The spread of a top-level number across samples as standard deviation over |mean|; 0 when it never changes or has fewer than two values. */
    double variation(String path) {
        List<Double> xs = new ArrayList<>();
        for (JsonNode d : docs) {
            for (JsonNode v : values(d, path)) {
                if (v.isNumber()) {
                    xs.add(v.asDouble());
                }
            }
        }
        if (xs.size() < 2) {
            return 0;
        }
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = xs.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0);
        double sd = Math.sqrt(var);
        return sd == 0 ? 0 : sd / Math.max(Math.abs(mean), 1e-9);
    }

    private static boolean nonEmpty(JsonNode v) {
        return !(v.isNull() || v.isMissingNode() || v.isArray() && v.isEmpty() || v.isObject() && v.isEmpty()
                || v.isTextual() && v.asText().isBlank());
    }
}
