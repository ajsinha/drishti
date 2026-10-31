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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The exposure trade-paths cache key (RESOURCES_AND_SCALE.md section 6.5): two different sets of trades of the same size whose 32-bit
 * list hashes collide must not share a key (before 2.9.7 the key was the hash and the size, so they shared an answer).
 */
class ExposurePathsKeyTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 30);

    @Test
    void collidingHashesGiveDifferentKeys() {
        // "Aa" and "BB" have the same String hash, so these two lists have the same List.hashCode and size
        List<String> a = List.of("Aa", "T-1");
        List<String> b = List.of("BB", "T-1");
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(b);
        assertThat(ExposureService.pathsKey(D, "7@1", a)).isNotEqualTo(ExposureService.pathsKey(D, "7@1", b));
    }

    @Test
    void sameSetSameKeyAndBoundariesCount() {
        assertThat(ExposureService.pathsKey(D, "7@1", List.of("A", "B"))).isEqualTo(ExposureService.pathsKey(D, "7@1", List.of("A", "B")));
        assertThat(ExposureService.pathsKey(D, "7@1", List.of("A", "BC"))).isNotEqualTo(ExposureService.pathsKey(D, "7@1", List.of("AB", "C")));
        assertThat(ExposureService.pathsKey(D, "7@1", List.of("A"))).isNotEqualTo(ExposureService.pathsKey(D, "7@2", List.of("A")));
        assertThat(ExposureService.pathsKey(D, "7@1", List.of("A"))).startsWith(D + "|7@1|").endsWith("|1");
    }
}
