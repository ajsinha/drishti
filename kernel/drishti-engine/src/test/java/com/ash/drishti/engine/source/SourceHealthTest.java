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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The words of a plugin's health text, including the calm WAITING of a stream whose broker is not there. */
class SourceHealthTest {

    @Test
    void waitingIsNeitherUpNorDown() {
        assertThat(SourceHealth.of("WAITING: broker not reachable at h:9092 - live updates paused; retrying")).isEqualTo(SourceHealth.WAITING);
        assertThat(SourceHealth.of("UP (catching up)")).isEqualTo(SourceHealth.UP);
        assertThat(SourceHealth.of("DEGRADED: x")).isEqualTo(SourceHealth.DEGRADED);
        assertThat(SourceHealth.of("DOWN: x")).isEqualTo(SourceHealth.DOWN);
        assertThat(SourceHealth.of((String) null)).isEqualTo(SourceHealth.UP);
    }
}
