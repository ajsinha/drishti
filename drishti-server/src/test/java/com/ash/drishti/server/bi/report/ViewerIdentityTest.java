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
package com.ash.drishti.server.bi.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** BI_DESIGN › BI runs, 18.2b: what makes a report visual depend on who views it, so its answer stays personal. */
class ViewerIdentityTest {

    @Test
    void aReferenceToTheViewerAnywhereMakesItPersonal() throws Exception {
        assertThat(ViewerIdentity.dependent(Map.of("filters", List.of(Map.of("field", "trades.trader", "op", "eq", "value", "user.name"))))).isTrue();
        assertThat(ViewerIdentity.dependent(Map.of("measures", Map.of("Mine", "sum(if(trades.trader = user.name, 1, 0))")))).isTrue();
        assertThat(ViewerIdentity.dependent(new ObjectMapper().readTree("{\"slicers\":{\"trader\":[\"me\"]}}"))).isTrue();
        assertThat(ViewerIdentity.dependent("trades.owner = ${user}")).isTrue();
        assertThat(ViewerIdentity.dependent("USERNAME()")).isTrue();
        assertThat(ViewerIdentity.dependent(List.of("@me"))).isTrue();
        assertThat(ViewerIdentity.dependent("CURRENT_USER")).isTrue();
    }

    @Test
    void ordinaryFieldsAndWordsDoNotMakeItPersonal() throws Exception {
        assertThat(ViewerIdentity.dependent(Map.of("wells", Map.of("axis", List.of("trades.desk", "trades.user.name", "trades.username")), "title", "Notional by member"),
                new ObjectMapper().readTree("{\"dates\":{\"from\":\"2026-10-08\"},\"limit\":5}"), null, 42, true)).isFalse();
        assertThat(ViewerIdentity.dependent("Some measure", "users by desk", "theme")).isFalse();
    }
}
