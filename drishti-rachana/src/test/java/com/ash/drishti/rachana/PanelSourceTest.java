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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.model.PanelKind;
import org.junit.jupiter.api.Test;

/** Every panel kind that reads data may read a linked entity's instead ({@code source}); view-describing kinds may not. */
class PanelSourceTest {

    @Test
    void everyKindThatReadsDataTakesASource() {
        for (PanelKind k : PanelKind.values()) {
            boolean describesTheView = k == PanelKind.LINKS || k == PanelKind.PROVENANCE || k == PanelKind.MARKDOWN;
            assertThat(k.accepts("source")).as(k.id()).isEqualTo(!describesTheView);
            assertThat(SutraExpressions.expressionOptions(k).contains("source")).as(k.id()).isEqualTo(!describesTheView);
        }
    }
}
