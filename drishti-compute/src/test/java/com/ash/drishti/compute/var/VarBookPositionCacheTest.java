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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The heap tier's position vectors are kept within a byte limit (2.9.7, RESOURCES_AND_SCALE.md section 3.9 item 8); past it a vector is
 * summed on each ask, to the same bits.
 */
class VarBookPositionCacheTest {

    @BeforeAll
    static void data() {
        VarIntradayTest.data();
    }

    @AfterAll
    static void close() {
        VarIntradayTest.close();
    }

    @Test
    void keptWithinTheLimitAndEqualPastIt() {
        long was = VarBook.positionCacheBytes();
        try {
            VarBook free = VarIntradayTest.build(VarIntradayTest.MORNING, null);
            double[][] all = new double[free.positionCount()][];
            for (int p = 0; p < free.positionCount(); p++) {
                all[p] = free.positionVector(p);
            }
            long one = 16L + 8L * all[0].length;
            VarBook.positionCacheBytes(5 * one);
            VarBook capped = VarIntradayTest.build(VarIntradayTest.MORNING, null);
            for (int round = 0; round < 2; round++) {
                for (int p = 0; p < capped.positionCount(); p++) {
                    assertThat(capped.positionVector(p)).containsExactly(all[p]);
                }
            }
            assertThat(capped.positionCached()).isEqualTo(5 * one);
            assertThat(free.positionCached()).isEqualTo(free.positionCount() * one);
        } finally {
            VarBook.positionCacheBytes(was);
        }
    }
}
