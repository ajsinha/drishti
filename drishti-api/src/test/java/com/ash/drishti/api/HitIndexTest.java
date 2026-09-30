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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HitIndexTest {

    @Test
    void ranksIdPrefixFirstAndFiltersByKind() {
        HitIndex idx = new HitIndex();
        idx.add(new EntityHit(EntityRef.of("trade", "XIRS-1"), "XIRS-1", "exotic"));
        idx.add(new EntityHit(EntityRef.of("trade", "IRS-48213"), "IRS-48213", "Interest rate swap"));
        idx.add(new EntityHit(EntityRef.of("netting-set", "NS-NORTH-01"), "NS-NORTH-01", "Netting set · IRS book"));
        assertThat(idx.search("trade", "irs", 10)).extracting(h -> h.ref().id()).containsExactly("IRS-48213", "XIRS-1");
        assertThat(idx.search(null, "irs", 10)).hasSize(3);
        assertThat(idx.search(null, "north", 1)).extracting(h -> h.ref().id()).containsExactly("NS-NORTH-01");
    }
}
