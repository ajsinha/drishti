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
package com.ash.drishti.server.bi.layer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.packs.Pack;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A pack kind served by a union of connectors (one per source system) is generated as a table over all of them, not over the routed one. */
class PackDatasetsUnionTest {

    static Pack trading() {
        return new Pack("trading", "1.0.0", "Trading", "", Path.of("."), Map.of("connectors", Map.of(
                "trade-endur", Map.of("plugin", "delta", "settings", Map.of("union", true, "domain", "trading")),
                "trade-pages", Map.of("plugin", "derived", "settings", Map.of()))));
    }

    @Test
    void aUnionMemberIsReadWithItsSiblingsAndAnyOtherConnectorAsNamed() {
        assertThat(PackDatasets.unionMember(trading(), "trade-endur")).isTrue();
        assertThat(PackDatasets.unionMember(trading(), "trade-pages")).isFalse();
        assertThat(PackDatasets.unionMember(trading(), "no-such")).isFalse();
        assertThat(PackDatasets.unionMember(trading(), null)).isFalse();
    }
}
