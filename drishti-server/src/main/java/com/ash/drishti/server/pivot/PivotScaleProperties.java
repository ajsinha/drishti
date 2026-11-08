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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.cluster.ClusterRoute;
import com.ash.drishti.bicompute.functions.Sketches;
import com.ash.drishti.bicompute.scale.Progressive;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The pivot engine's scale settings (PIVOT_GUIDE.md, scale; ARCHITECTURE.md section 26.6), the {@code drishti.pivot} keys of phase P3, bound
 * beside {@link PivotProperties} (each record reads its own keys of the section).
 *
 * @param sketches HyperLogLog's precision, KLL's k, Space-Saving's counters, and when distinct counts and percentiles answer ≈
 * @param progressive ≈ first, then exact, for views estimated over {@code after-ms}
 * @param rollups rollups of partial states: definitions, refresh, advice
 * @param cluster scatter and gather across the nodes
 */
@ConfigurationProperties("drishti.pivot")
public record PivotScaleProperties(SketchKeys sketches, ProgressiveKeys progressive, RollupKeys rollups, ClusterKeys cluster) {

    /**
     * {@code drishti.pivot.sketches}.
     *
     * @param hllPrecision p: 2^p HyperLogLog registers (11 to 16; 12: 4,096 registers, ±1.6%)
     * @param kllK KLL's k (200: ±1.65% of rank)
     * @param topCapacityFactor Space-Saving's counters per value shown
     * @param approximateAboveRows distinct counts and percentiles answer ≈ when a view reads more rows (0: never by size)
     */
    public record SketchKeys(Integer hllPrecision, Integer kllK, Integer topCapacityFactor, Long approximateAboveRows) {}

    /** {@code drishti.pivot.progressive}. */
    public record ProgressiveKeys(Boolean enabled, Long afterMs, Double sampleFraction) {}

    /**
     * {@code drishti.pivot.rollups}.
     *
     * @param enabled rollups answer views (aggregate awareness) and refresh
     * @param refreshSeconds how often each rollup's source version is checked (a load is seen within this)
     * @param adviseAfterScans a grain scanned this many times is proposed as a rollup
     * @param dir where rollups are kept (blank: {@code <data dir>/pivot/rollups})
     * @param definitions the rollups (id, source, grain, functions, dates, title)
     */
    public record RollupKeys(Boolean enabled, Integer refreshSeconds, Integer adviseAfterScans, String dir, List<Map<String, Object>> definitions) {}

    /** {@code drishti.pivot.cluster}. */
    public record ClusterKeys(Boolean enabled, Integer hedgeAfterSeconds, Integer deadlineSeconds, Long minRows) {}

    public PivotScaleProperties {
        sketches = sketches == null ? new SketchKeys(null, null, null, null) : sketches;
        progressive = progressive == null ? new ProgressiveKeys(null, null, null) : progressive;
        rollups = rollups == null ? new RollupKeys(null, null, null, null, null) : rollups;
        cluster = cluster == null ? new ClusterKeys(null, null, null, null) : cluster;
    }

    public Sketches.Settings sketchSettings() {
        return new Sketches.Settings(or(sketches.hllPrecision(), 12), or(sketches.kllK(), 200), or(sketches.topCapacityFactor(), 10));
    }

    public long approximateAboveRows() {
        return sketches.approximateAboveRows() == null ? 10_000_000L : Math.max(0, sketches.approximateAboveRows());
    }

    public Progressive.Settings progressiveSettings() {
        return new Progressive.Settings(progressive.enabled() == null || progressive.enabled(), progressive.afterMs() == null ? 2_000
                : progressive.afterMs(), progressive.sampleFraction() == null ? 0.02 : progressive.sampleFraction());
    }

    public boolean rollupsEnabled() {
        return rollups.enabled() == null || rollups.enabled();
    }

    public int refreshSeconds() {
        return or(rollups.refreshSeconds(), 60);
    }

    public int adviseAfterScans() {
        return or(rollups.adviseAfterScans(), 10);
    }

    public List<Map<String, Object>> definitions() {
        return rollups.definitions() == null ? List.of() : rollups.definitions();
    }

    public ClusterRoute.Settings clusterSettings() {
        return new ClusterRoute.Settings(cluster.enabled() == null || cluster.enabled(), 1000L * or(cluster.hedgeAfterSeconds(), 20),
                1000L * or(cluster.deadlineSeconds(), 300), cluster.minRows() == null ? 5_000_000L : Math.max(0, cluster.minRows()));
    }

    private static int or(Integer v, int d) {
        return v == null || v <= 0 ? d : v;
    }
}
