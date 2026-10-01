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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Structured search limits.
 *
 * @param maxScan at most this many entities of the kind are read per search (the result says when it stopped short)
 * @param budget the time a search may take to list and read them; slower sources are left out
 * @param columns the key fields shown for each kind in pick lists and searches ({@code trade: [productType,
 *     counterparty.name, notional, currency]}); packs declare them ({@code columns:} in pack.yaml)
 */
@ConfigurationProperties("drishti.search")
public record SearchProperties(Integer maxScan, Duration budget, java.util.Map<String, java.util.List<String>> columns) {

    public SearchProperties(Integer maxScan, Duration budget) {
        this(maxScan, budget, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public SearchProperties {
        maxScan = maxScan == null || maxScan <= 0 ? 20_000 : maxScan;
        budget = budget == null ? Duration.ofSeconds(3) : budget;
        columns = columns == null ? java.util.Map.of() : java.util.Map.copyOf(columns);
    }

    /** The key fields of a kind as document paths ({@code $.productType}); empty when its pack names none. */
    public java.util.List<String> columnsOf(String kind) {
        return columns.getOrDefault(kind, java.util.List.of()).stream().map(String::trim).filter(c -> !c.isEmpty())
                .map(c -> c.startsWith("$") ? c : "$." + c).toList();
    }
}
