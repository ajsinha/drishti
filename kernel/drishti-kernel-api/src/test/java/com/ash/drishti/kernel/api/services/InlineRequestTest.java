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
package com.ash.drishti.kernel.api.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link InlineRequest}: fields only; the rules of the body stay in InlinePivot. */
class InlineRequestTest {

    private static final Map<String, Object> VIEW = Map.of("rows", List.of("DESK"), "values", List.of(Map.of("measure", "sum(MTM)", "as", "m")));

    @Test
    void aGoodRequestIsKeptAndTheLabelDefaultsToTheName() {
        InlineRequest r = new InlineRequest(List.of(new InlineRequest.Field("DESK", "string", null), new InlineRequest.Field("MTM", "number", "MTM")),
                List.of(List.of("Rates", 1.5)), VIEW);

        assertThat(r.fields().get(0).label()).isEqualTo("DESK");
        assertThat(r.rows()).hasSize(1);
    }

    @Test
    void theRulesOfTheBodyAreNotHeldHereAndTheLimitIsTheEnginesOne() {
        assertThat(InlineRequest.MAX_ROWS).isEqualTo(com.ash.drishti.kernel.api.module.InlineFigures.MAX_ROWS);
        assertThat(new InlineRequest(List.of(), null, VIEW).rows()).isEmpty();
        assertThatThrownBy(() -> new InlineRequest(List.of(), List.of(), null)).isInstanceOf(NullPointerException.class);
    }
}
