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
package com.ash.drishti.server.exposure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The CCR cube's settings, {@code drishti.cube.ccr} (EXPOSURE_GUIDE.md section 30; engine {@code ccr-cube}, COMPUTE_ENGINES.md: needs
 * {@code exposure} and {@code pivot}, uses {@code sa-ccr} and {@code fva}). The cube is the pivot engine's source {@code ccr} with the CCR
 * lens; the switch is {@code drishti.engines.ccr-cube.enabled} ({@code config/engines/ccr-cube.yaml}); the pivot engine's budget, busy
 * protection and cache ({@code drishti.pivot.*}) apply to it as to every source.
 *
 * @param buckets the limit board's tenor buckets ({@code TENOR_BUCKET}), each {@code label: from-to} in years, a bucket holding the primary
 *     dates with {@code from < t <= to} ({@code 5-}: open); the order is the columns' order (COUNTERPARTY_ENGINES.md decision 14: three)
 * @param warningAt the limit use from which a bucket is near its limit (amber); over 1 is a breach (red)
 * @param maxCells the most cells one evaluation computes (each cell's statistics are computed on its own summed paths)
 * @param headlineNames the most counterparties the headline names in its over-limit and near-limit tiles
 */
@ConfigurationProperties("drishti.cube.ccr")
public record CcrCubeProperties(Map<String, String> buckets, Double warningAt, Integer maxCells, Integer headlineNames) {

    public CcrCubeProperties {
        buckets = buckets == null || buckets.isEmpty() ? defaultBuckets() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(buckets));
        warningAt = warningAt == null || warningAt <= 0 || warningAt >= 1 ? 0.8 : warningAt;
        maxCells = maxCells == null || maxCells < 1 ? 2000 : maxCells;
        headlineNames = headlineNames == null || headlineNames < 1 ? 5 : headlineNames;
    }

    /** The defaults (tests, and a server without the block). */
    public static CcrCubeProperties defaults() {
        return new CcrCubeProperties(null, null, null, null);
    }

    static Map<String, String> defaultBuckets() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("0-1Y", "0-1");
        m.put("1-5Y", "1-5");
        m.put("5Y+", "5-");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** One tenor bucket: the primary dates with {@code from < t <= to} (years). */
    record Bucket(String label, double from, double to) {
        boolean holds(double t) {
            return t > from + 1e-9 && t <= to + 1e-9;
        }
    }

    /** The buckets, parsed; a bad one is refused at start in words. */
    List<Bucket> parsed() {
        List<Bucket> out = new ArrayList<>();
        for (Map.Entry<String, String> e : buckets.entrySet()) {
            String[] p = e.getValue().split("-", -1);
            try {
                double from = p[0].isBlank() ? 0 : Double.parseDouble(p[0].strip());
                double to = p.length < 2 || p[1].isBlank() ? Double.POSITIVE_INFINITY : Double.parseDouble(p[1].strip());
                if (!(to > from)) {
                    throw new NumberFormatException("to must be after from");
                }
                out.add(new Bucket(e.getKey(), from, to));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                throw new IllegalStateException("drishti.cube.ccr.buckets." + e.getKey() + ": '" + e.getValue() + "' is not from-to in years (1-5, 5-)");
            }
        }
        return out;
    }
}
