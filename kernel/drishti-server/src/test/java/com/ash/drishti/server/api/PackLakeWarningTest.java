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
package com.ash.drishti.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Health's warning when a pack's connectors read more than one lake (docs/guides/DATA_LAKE_GUIDE.md section 6): demo and real never mix. */
class PackLakeWarningTest {

    @Test
    void onePackTwoLakesIsAWarningOneLakeIsNone() {
        assertThat(HealthController.lakeWarning("trading", Map.of())).isNull();
        assertThat(HealthController.lakeWarning("trading", Map.of("demo", List.of("trade-endur", "trade-calypso")))).isNull();
        Map<String, List<String>> mixed = new TreeMap<>(Map.of("demo", List.of("trade-calypso", "trade-murex"), "risklake", List.of("trade-endur")));
        assertThat(HealthController.lakeWarning("trading", mixed)).isEqualTo("trading's connectors read different lakes: demo (trade-calypso, "
                + "trade-murex); risklake (trade-endur). Its screens mix them: point every connector of trading at one lake (lake: in each "
                + "connector file) and restart");
    }
}
