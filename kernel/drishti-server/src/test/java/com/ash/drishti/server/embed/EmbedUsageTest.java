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
package com.ash.drishti.server.embed;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The per-application counters of Admin → Embedding: the numbers, the open streams that never go below zero, and the Micrometer meters. */
class EmbedUsageTest {

    @Test
    void countsPerApplicationAndExportsMeters() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EmbedUsage usage = new EmbedUsage(meters, Clock.systemUTC());
        usage.tokenIssued("crm");
        usage.call("crm");
        usage.call("crm");
        usage.refused("crm", "DRS-8002");
        usage.refused(EmbedUsage.UNKNOWN, "DRS-8003");
        usage.streamOpened("crm");
        usage.streamOpened("crm");
        usage.streamClosed("crm");
        EmbedUsage.Snapshot s = usage.snapshot("crm");
        assertThat(s.tokensIssued()).isEqualTo(1);
        assertThat(s.calls()).isEqualTo(2);
        assertThat(s.refusalsByCode()).containsEntry("DRS-8002", 1L);
        assertThat(s.streamsOpen()).isEqualTo(1);
        assertThat(s.lastUsedAt()).isNotNull();
        assertThat(meters.get("drishti.embed.calls").tag("app", "crm").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("drishti.embed.refusals").tag("code", "DRS-8002").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("drishti.embed.streams").tag("app", "crm").gauge().value()).isEqualTo(1.0);
        usage.streamClosed("crm");
        usage.streamClosed("crm");
        assertThat(usage.snapshot("crm").streamsOpen()).isZero();
        assertThat(usage.snapshots(List.of("crm", "quiet")).keySet()).containsExactly("crm", "quiet", EmbedUsage.UNKNOWN);
        assertThat(usage.snapshot("quiet").lastUsedAt()).isNull();
    }
}
