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
package com.ash.drishti.engine.search;

import com.ash.drishti.rachana.model.PivotSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Structured search limits.
 *
 * @param maxScan at most this many entities of the kind are read per search (the result says when it stopped short)
 * @param budget the time a search may take to list and read them; slower sources are left out
 * @param columns the key fields shown for each kind in pick lists and searches ({@code trade: [productType,
 *     counterparty.name, notional, currency]}); packs declare them ({@code columns:} in pack.yaml)
 * @param pivot the kinds whose search results and pick lists offer a Pivot tab: {@code true} (by the kind's key fields) or
 *     the pivot as JSON ({@code {"fields": [...], "rows": [...], ...}}); packs declare them ({@code pivot:} in pack.yaml)
 */
@ConfigurationProperties("drishti.search")
public record SearchProperties(Integer maxScan, Duration budget, Map<String, List<String>> columns, Map<String, String> pivot) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public SearchProperties(Integer maxScan, Duration budget) {
        this(maxScan, budget, null, null);
    }

    public SearchProperties(Integer maxScan, Duration budget, Map<String, List<String>> columns) {
        this(maxScan, budget, columns, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public SearchProperties {
        maxScan = maxScan == null || maxScan <= 0 ? 20_000 : maxScan;
        budget = budget == null ? Duration.ofSeconds(3) : budget;
        columns = columns == null ? Map.of() : Map.copyOf(columns);
        pivot = pivot == null ? Map.of() : Map.copyOf(pivot);
    }

    /** The key fields of a kind as document paths ({@code $.productType}); empty when its pack names none. */
    public List<String> columnsOf(String kind) {
        return columns.getOrDefault(kind, List.of()).stream().map(String::trim).filter(c -> !c.isEmpty())
                .map(c -> c.startsWith("$") ? c : "$." + c).toList();
    }

    /**
     * The pivot a kind's search results offer, read against its key fields: empty when its pack does not opt in (or says
     * false). A declaration that cannot be read is a {@link IllegalArgumentException} naming the problems.
     */
    public Optional<PivotSpec> pivotOf(String kind) {
        String raw = pivot.get(kind);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Object value;
        String t = raw.strip();
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
            value = Boolean.parseBoolean(t);
        } else {
            try {
                value = JSON.readValue(t, Object.class);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("drishti.search.pivot." + kind + " is not true, false or a JSON object: " + e.getMessage(), e);
            }
        }
        PivotSpec.Parsed p = PivotSpec.parse(value, PivotSpec.Mode.KIND, PivotSpec.fromPaths(columnsOf(kind)));
        if (!p.problems().isEmpty()) {
            throw new IllegalArgumentException("the pivot of " + kind + " search results: " + String.join("; ", p.problems()));
        }
        return Optional.ofNullable(p.spec());
    }
}
