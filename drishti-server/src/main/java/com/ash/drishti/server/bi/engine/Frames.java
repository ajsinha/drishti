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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rows of a composed or derived dataset's one table (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.10), supplied lazily by whoever resolved the sources as the reader: the engine
 * asks for them only when the result cache misses. {@link #fingerprint()} is cheap and changes whenever the inputs do (a source's layers were rebuilt, a
 * source dataset was republished), so a cached result of the composition never outlives its inputs.
 */
public interface Frames {

    /** What the inputs are right now, as text; part of the result-cache key. */
    String fingerprint();

    /** Runs the sources as the reader (or reads the Transform's result) and returns the rows. */
    Rows load();

    /**
     * The rows. {@code answers} are the sources' answers by alias, each already masked and row-ruled for the reader, for a composed dataset; {@code parquet} is
     * the Transform's result file for a derived one; {@code warnings} are what the sources said; {@code masked} the columns of the joined table that a source
     * masked for the reader (so the composed dataset shows them as masked too, and a measure over one is null).
     */
    record Rows(Map<String, QueryService.Result> answers, Path parquet, List<String> warnings, List<String> dates, Set<String> masked) {

        public static Rows ofAnswers(Map<String, QueryService.Result> answers, List<String> warnings, List<String> dates, Set<String> masked) {
            return new Rows(answers, null, warnings, dates, masked);
        }

        public static Rows ofParquet(Path file, List<String> warnings) {
            return new Rows(Map.of(), file, warnings, List.of(), Set.of());
        }
    }
}
