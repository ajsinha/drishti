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
package com.ash.drishti.server.bi.engine;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Execution, connectors and scale, {@code drishti.bi.exec.*} (wave B, docs/architecture/BI_DESIGN.md sections 6.3, 6.5, 6.6 and 6.7;
 * docs/admin/CONFIGURATION.md). The server's slots and a person's share are {@code drishti.bi.query.max-concurrent} and {@code max-concurrent-per-user}.
 *
 * @param perModelShare the share of the slots one model may hold at once, 0 to 1
 * @param queue how long a source fragment waits for its connector's pool before {@code DRS-9025}
 * @param interactive the longest an interactive query runs ({@code DRS-9024} after)
 * @param background the longest standing-query maintenance and background work runs
 * @param maxEstimatedRows the planner's estimate of rows read past which a query is refused ({@code DRS-9029}) unless its {@code options.budget} allows more
 * @param maxFragments source fragments (layer builds, direct queries) one connector runs at once
 * @param breakerFailures consecutive failures of a connector that open its circuit breaker
 * @param breakerOpen how long an open breaker answers {@code DRS-9026} at once before it lets one fragment try
 * @param connectors per connector name: overrides of {@code max-fragments} ({@code connectors.trading-store.max-fragments: 4})
 * @param node this Java node's name (several nodes: the shard owner of a model; empty: one node)
 * @param nodes every Java node of the site: name to its base URL ({@code java-1: http://10.0.0.5:18480}); empty: one node
 * @param backgroundShare BI runs (docs/architecture/BI_DESIGN.md 10.1): the most of the slots background work (runs, refresh) holds at once (0.25)
 * @param standingShare the most of the slots standing-query maintenance holds at once (0.25)
 * @param fragmentsShare the most of a connector's {@code max-fragments} the layer builds of background runs hold (0.5)
 */
@ConfigurationProperties("drishti.bi.exec")
public record ExecProperties(Double perModelShare, Duration queue, Duration interactive, Duration background, Long maxEstimatedRows, Integer maxFragments,
        Integer breakerFailures, Duration breakerOpen, Map<String, Map<String, Object>> connectors, String node, Map<String, String> nodes,
        Double backgroundShare, Double standingShare, Double fragmentsShare) {

    public ExecProperties {
        perModelShare = perModelShare == null || perModelShare <= 0 || perModelShare > 1 ? 0.5 : perModelShare;
        queue = positive(queue, Duration.ofMillis(2000));
        interactive = positive(interactive, Duration.ofSeconds(30));
        background = positive(background, Duration.ofSeconds(300));
        maxEstimatedRows = maxEstimatedRows == null || maxEstimatedRows <= 0 ? 200_000_000L : maxEstimatedRows;
        maxFragments = maxFragments == null || maxFragments <= 0 ? 8 : maxFragments;
        breakerFailures = breakerFailures == null || breakerFailures <= 0 ? 5 : breakerFailures;
        breakerOpen = positive(breakerOpen, Duration.ofSeconds(30));
        connectors = connectors == null ? Map.of() : Map.copyOf(connectors);
        node = node == null ? "" : node.strip();
        nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
        backgroundShare = share(backgroundShare, 0.25);
        standingShare = share(standingShare, 0.25);
        fragmentsShare = share(fragmentsShare, 0.5);
    }

    private static Double share(Double v, double dflt) {
        return v == null || v <= 0 || v > 1 ? dflt : v;
    }

    private static Duration positive(Duration d, Duration dflt) {
        return d == null || d.isNegative() || d.isZero() ? dflt : d;
    }

    /** The fragments a connector runs at once (its override, else {@code max-fragments}). */
    public int fragmentsOf(String connector) {
        Map<String, Object> o = connectors.get(connector);
        Object v = o == null ? null : o.get("max-fragments");
        if (v == null && o != null) {
            v = o.get("maxFragments");
        }
        return v instanceof Number n && n.intValue() > 0 ? n.intValue() : v != null && String.valueOf(v).matches("\\d+") ? Integer.parseInt(String.valueOf(v)) : maxFragments;
    }

    /** The slots one model may hold at once, of {@code slots}. */
    public int modelSlots(int slots) {
        return Math.max(1, (int) Math.floor(slots * perModelShare));
    }

    public static ExecProperties defaults() {
        return new ExecProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
