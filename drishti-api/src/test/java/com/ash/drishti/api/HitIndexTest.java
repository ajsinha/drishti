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

    @Test
    void aMillionEntitiesSearchInMilliseconds() {
        HitIndex index = new HitIndex();
        java.util.List<EntityHit> hits = new java.util.ArrayList<>();
        for (int i = 0; i < 1_000_000; i++) {
            String id = (i % 2 == 0 ? "MX-" : "CLY-") + (30_000_000 + i);
            hits.add(new EntityHit(EntityRef.of("trade", id), id, "trade · lake"));
        }
        index.replaceAll(hits);
        index.add(new EntityHit(EntityRef.of("trade", "MX-99999999"), "MX-99999999", "trade · stream"));
        long t0 = System.nanoTime();
        java.util.List<EntityHit> prefix = index.search("trade", "mx-300001", 25);
        java.util.List<EntityHit> all = index.search("trade", "", 25);
        java.util.List<EntityHit> inner = index.search("trade", "0000123", 5);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        org.assertj.core.api.Assertions.assertThat(prefix).hasSize(25).allSatisfy(h -> org.assertj.core.api.Assertions.assertThat(h.ref().id()).startsWith("MX-300001"));
        org.assertj.core.api.Assertions.assertThat(prefix.get(0).ref().id()).isEqualTo("MX-30000100");
        org.assertj.core.api.Assertions.assertThat(all.get(0).ref().id()).isEqualTo("CLY-30000001");            // id order, no sort of a million
        org.assertj.core.api.Assertions.assertThat(inner).isNotEmpty().allSatisfy(h -> org.assertj.core.api.Assertions.assertThat(h.ref().id()).contains("0000123"));
        org.assertj.core.api.Assertions.assertThat(index.search("trade", "MX-99999999", 5)).extracting(h -> h.ref().id()).containsExactly("MX-99999999");
        org.assertj.core.api.Assertions.assertThat(ms).isLessThan(500);
    }
}
