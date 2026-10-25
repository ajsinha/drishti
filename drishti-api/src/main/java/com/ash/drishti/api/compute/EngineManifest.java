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
package com.ash.drishti.api.compute;

import java.util.List;
import java.util.Set;

/**
 * What a {@link ComputeEngine} is: its name and version (both go into every result's stamp and cache key, so a new version never serves an
 * answer the old one computed), the kinds it computes, and those it can update incrementally from events.
 *
 * @param name the engine's name ({@code sample-sum}, {@code drishti_var.connector:VarEngine})
 * @param version its version
 * @param kinds the kinds it computes
 * @param incremental the kinds whose answers it can update from events ({@link ComputeEngine#incremental})
 */
public record EngineManifest(String name, String version, List<String> kinds, Set<String> incremental) {

    public EngineManifest {
        name = name == null ? "" : name;
        version = version == null || version.isBlank() ? "0" : version;
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        incremental = incremental == null ? Set.of() : Set.copyOf(incremental);
    }

    public EngineManifest(String name, String version, List<String> kinds) {
        this(name, version, kinds, Set.of());
    }
}
