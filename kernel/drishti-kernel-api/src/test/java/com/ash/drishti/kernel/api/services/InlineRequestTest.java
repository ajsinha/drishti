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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link InlineRequest}: the rules of {@code POST /api/v1/pivot/inline}'s body, held by the record. */
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
    void aFieldNameOrTypeTheEngineDoesNotTakeIsRefused() {
        assertThatThrownBy(() -> new InlineRequest.Field("1desk", "string", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InlineRequest.Field("DESK", "money", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string, number or date");
    }

    @Test
    void noFieldsOrTooManyRowsAreRefused() {
        assertThatThrownBy(() -> new InlineRequest(List.of(), List.of(), VIEW)).hasMessageContaining("needs its fields");
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i <= InlineRequest.MAX_ROWS; i++) {
            rows.add(List.of("x"));
        }
        assertThatThrownBy(() -> new InlineRequest(List.of(new InlineRequest.Field("A", "string", null)), rows, VIEW))
                .hasMessageContaining("at most 100000 rows");
    }
}
