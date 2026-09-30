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
package com.ash.drishti.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentDiffTest {

    private final DocumentDiff diff = new DocumentDiff(100);

    @Test
    void reportsChangedAddedAndRemovedLeavesWithNumericDeltas() {
        DataNode a = DataNode.of(Map.of("mtm", 100, "status", "Live", "old", true, "_meta", Map.of("generation", 1)));
        DataNode b = DataNode.of(Map.of("mtm", 125.5, "status", "Live", "fresh", "x", "_meta", Map.of("generation", 2)));
        DocumentDiff.Result r = diff.diff(a, b);
        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);
        assertThat(r.changes()).filteredOn(c -> c.path().equals("mtm")).singleElement()
                .satisfies(c -> assertThat(c.delta()).isEqualTo(25.5));
        assertThat(r.changes()).extracting(DocumentDiff.Change::path).doesNotContain("_meta.generation");
    }

    @Test
    void arraysOfIdentifiedObjectsAreMatchedByIdentifierNotPosition() {
        DataNode a = DataNode.of(Map.of("cashflows", List.of(Map.of("cfId", "C1", "amount", 10), Map.of("cfId", "C2", "amount", 20))));
        DataNode b = DataNode.of(Map.of("cashflows", List.of(Map.of("cfId", "C0", "amount", 5), Map.of("cfId", "C1", "amount", 10),
                Map.of("cfId", "C2", "amount", 21))));
        DocumentDiff.Result r = diff.diff(a, b);
        // an inserted first cashflow does not make the others look changed: one changed amount, one new cashflow
        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.changes()).extracting(DocumentDiff.Change::path).contains("cashflows[C2].amount", "cashflows[C0].amount");
    }

    @Test
    void nestedPathsAndPositionalArraysAndTheLimit() {
        DataNode a = DataNode.of(Map.of("legs", List.of(Map.of("rate", 0.041), Map.of("rate", 0.0)), "tags", List.of("a", "b")));
        DataNode b = DataNode.of(Map.of("legs", List.of(Map.of("rate", 0.042), Map.of("rate", 0.0)), "tags", List.of("a")));
        DocumentDiff.Result r = diff.diff(a, b);
        assertThat(r.changes()).extracting(DocumentDiff.Change::path).containsExactlyInAnyOrder("legs[0].rate", "tags[1]");
        DocumentDiff tiny = new DocumentDiff(1);
        assertThat(tiny.diff(a, b).truncated()).isTrue();
        assertThat(tiny.diff(a, b).changes()).hasSize(1);
    }

    @Test
    void naturalKeysMatchArraysAndDeltasCarryNoBinaryNoise() {
        DataNode a = DataNode.of(Map.of("rate", 0.040811, "sensitivities", List.of(Map.of("tenor", "2Y", "dv01", -5424), Map.of("tenor", "5Y", "dv01", -10852))));
        DataNode b = DataNode.of(Map.of("rate", 0.040829, "sensitivities", List.of(Map.of("tenor", "1Y", "dv01", -900), Map.of("tenor", "2Y", "dv01", -5424),
                Map.of("tenor", "5Y", "dv01", -10852))));
        DocumentDiff.Result r = diff.diff(a, b);
        assertThat(r.changes()).extracting(DocumentDiff.Change::path).containsExactlyInAnyOrder("rate", "sensitivities[1Y].tenor", "sensitivities[1Y].dv01");
        assertThat(r.changes()).filteredOn(c -> c.path().equals("rate")).singleElement().satisfies(c -> assertThat(c.delta()).isEqualTo(0.000018));
    }
}
