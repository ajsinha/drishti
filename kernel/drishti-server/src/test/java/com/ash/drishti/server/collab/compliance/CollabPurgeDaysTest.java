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
package com.ash.drishti.server.collab.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.server.security.PackAccess;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Retention days are configuration, most specific first: the kind, then its pack, then the default; 0 keeps forever. */
class CollabPurgeDaysTest {

    private static CollabPurge purge(CollabProperties.Retention retention, Map<String, Map<String, Object>> packs) {
        PackAccess access = mock(PackAccess.class);
        when(access.ownerOf("trade")).thenReturn("finance");
        when(access.ownerOf("gene")).thenReturn("genomics");
        when(access.ownerOf("loose")).thenReturn(null);
        CollabProperties props = new CollabProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, retention,
                null, packs, null, null);
        return new CollabPurge(props, null, null, null, access, null, null, null, null);
    }

    @Test
    void theDefaultKeepsForeverAndEachLevelOverridesTheOneBelow() {
        assertThat(purge(new CollabProperties.Retention(null, null, null), Map.of()).days("trade")).as("nothing configured").isZero();
        CollabPurge p = purge(new CollabProperties.Retention(365, Map.of("trade", 2555, "gene", 0), Duration.ofHours(6)),
                Map.of("genomics", Map.of("retention-days", 30), "finance", Map.of("retention-days", "90")));
        assertThat(p.days("trade")).as("the kind beats its pack").isEqualTo(2555);
        assertThat(p.days("gene")).as("0 for a kind keeps it forever, whatever the pack says").isZero();
        assertThat(p.days("loose")).as("a kind no pack owns gets the default").isEqualTo(365);
        CollabPurge byPack = purge(new CollabProperties.Retention(365, null, null), Map.of("genomics", Map.of("retention-days", 30),
                "finance", Map.of("retention-days", "90")));
        assertThat(byPack.days("trade")).as("a pack's days may be written as text").isEqualTo(90);
        assertThat(byPack.days("gene")).isEqualTo(30);
        assertThat(byPack.days("loose")).isEqualTo(365);
    }
}
