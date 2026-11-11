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
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.engine.Footprints;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** BIR-09: the run object's {@code data.words} and {@code data.tables} come from the planned footprint (tables, dates, row counts, versions). */
class RunDataWordsTest {

    static final LocalDate D1 = LocalDate.of(2026, 7, 10);
    static final LocalDate D2 = LocalDate.of(2026, 7, 13);

    static Footprints.Footprint footprint() {
        RunEstimator.Table trades = new RunEstimator.Table("trades", "trade-endur", RunEstimator.BuildPath.SCAN,
                List.of(RunEstimator.DateRows.built(D1, 6_000L), RunEstimator.DateRows.built(D2, 6_500L)), 1, 0);
        RunEstimator.Table desks = new RunEstimator.Table("desks", "desk-lake", RunEstimator.BuildPath.SCAN, List.of(RunEstimator.DateRows.unknown(D2)), 1, 0);
        DataVersionVector data = new DataVersionVector().put("trade-endur", "trade", D1, "411").put("trade-endur", "trade", D2, "412")
                .put("desk-lake", "desk", D2, Optional.empty());
        return new Footprints.Footprint(new RunEstimator.Plan(List.of(trades, desks), Optional.empty(), "desk", List.of(), null, false), data, List.of(D1, D2), null);
    }

    @Test
    void theTablesTheirDatesRowsAndVersionsInWords() {
        List<Map<String, Object>> t = RunDataWords.tables(footprint());
        assertThat(t).hasSize(2);
        assertThat(t.get(0)).containsEntry("table", "trades").containsEntry("connector", "trade-endur").containsEntry("dates", 2).containsEntry("rows", 12_500L)
                .containsEntry("versions", "v411, v412 (2 dates)");
        assertThat(t.get(1)).containsEntry("rows", null).containsEntry("versions", "no version (1 date)");
        assertThat(RunDataWords.words(t)).isEqualTo("trades 13 K rows, v411, v412 (2 dates) · desks, no version (1 date)");
        assertThat(RunDataWords.words(List.of())).isNull();
    }

    @Test
    void theRunObjectCarriesThemAndTheEstimateStaysAsItWas() {
        List<Map<String, Object>> t = RunDataWords.tables(footprint());
        Map<String, Object> estimate = new LinkedHashMap<>(Map.of("basis", "layers", "readRows", 12_500));
        estimate.put(RunDataWords.KEY, t);
        Instant now = Instant.now();
        Run r = Run.planned("r-java-1-01JA0000000000000000000001", "rita", "rita", "mq", "{}", "ab".repeat(32), "sig", Tier.INTERACTIVE, "local",
                RunJson.write(estimate), "java-1", now);
        Map<String, Object> json = RunJson.of(r, RunJson.View.of("rita"), now);
        Map<?, ?> data = (Map<?, ?>) json.get("data");
        assertThat(data.get("words")).isEqualTo("trades 13 K rows, v411, v412 (2 dates) · desks, no version (1 date)");
        assertThat((List<?>) data.get("tables")).hasSize(2);
        assertThat(new java.util.TreeSet<>(((Map<?, ?>) json.get("estimate")).keySet()).toString()).isEqualTo("[basis, readRows]");
        // a run planned before (no tables kept): words null, tables empty, as before
        Run old = Run.planned("r-java-1-01JA0000000000000000000002", "rita", "rita", "mq", "{}", "ab".repeat(32), "sig", Tier.INTERACTIVE, "local",
                "{\"basis\":\"unknown\"}", "java-1", now);
        Map<?, ?> oldData = (Map<?, ?>) RunJson.of(old, RunJson.View.of("rita"), now).get("data");
        assertThat(oldData.get("words")).isNull();
        assertThat((List<?>) oldData.get("tables")).isEmpty();
    }
}
