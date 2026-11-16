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
package com.ash.drishti.engine.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import org.junit.jupiter.api.Test;

/** The parts a control change rebuilds: a panel reads a control through its columns, its options or its own source. */
class ControlReadsTest {

    private static Sutra parse(String text) {
        return new SutraParser().parse(text, "t.sutra.yaml", "x");
    }

    @Test
    void aPanelWhoseSourceParameterReadsAControlIsRebuiltWhenItChanges() {
        Sutra s = parse("""
                rachana: 1
                sutra: corr
                version: 1
                match: { kind: correlation-matrix }
                controls:
                  - { id: estimator, kind: segmented, options: ["pearson", "ledoit-wolf"], default: "pearson" }
                  - { id: unused, kind: segmented, options: ["a", "b"], default: "a" }
                panels:
                  - id: grid
                    kind: kv
                    source: { kind: correlation-result, id: "$.MATRIX_ID", params: { estimator: ctl.estimator } }
                    columns: [ { label: Pairs, bind: $src.PAIRS } ]
                  - { id: plain, kind: kv, columns: [ { label: Name, bind: $.NAME } ] }
                """);
        assertThat(ControlReads.affected(s, "estimator")).containsExactly("grid");
        assertThat(ControlReads.affected(s, "unused")).isEmpty();
    }
}
