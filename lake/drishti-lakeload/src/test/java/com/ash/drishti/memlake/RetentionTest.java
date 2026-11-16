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
package com.ash.drishti.memlake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The guard's rules (docs/architecture/DATA_ARCHITECTURE.md section 8): only downloads and peer answers may carry memory-only data; refusals in words. */
class RetentionTest {

    /** A guard whose memory-only kinds are given (no connectors needed). */
    private static Retention with(Map<String, String> kinds) {
        return Retention.of(kinds);
    }

    @Test
    void onlyDownloadsAndPeersMayCarryMemoryOnlyData() {
        Retention r = with(Map.of("news", "VX-2026-07", "news-mnemonic", "VX-2026-07", "connector:news-store", "VX-2026-07"));
        for (Retention.Sink s : Retention.Sink.values()) {
            boolean allowed = s == Retention.Sink.DOWNLOAD || s == Retention.Sink.PEER_NODE;
            assertThat(r.mayPersist(s, List.of("news"))).as(s.name()).isEqualTo(allowed);
            assertThat(r.mayPersist(s, List.of("trade"))).as(s + " of disk data").isTrue();
        }
        assertThat(r.memoryOnly(List.of("news", "trade", "NEWS"))).isEqualTo(Set.of("news", "NEWS"));
        assertThat(r.memoryOnlyConnector("news-store")).isTrue();
        r.require(Retention.Sink.DOWNLOAD, List.of("news"), "This download");
        assertThat(r.downloads()).isEqualTo(1);
    }

    @Test
    void aRefusalSaysWhyWithTheContract() {
        Retention r = with(Map.of("news", "VX-2026-07"));
        assertThatThrownBy(() -> r.require(Retention.Sink.REPORT, List.of("news"), "This report")).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("This report uses news data held in memory only (contract VX-2026-07)")
                .hasMessageContaining("cannot be delivered by a scheduled report").hasMessageContaining("Only a download by you may leave");
        assertThat(r.refusals()).isEqualTo(1);
        r.require(Retention.Sink.REPORT, List.of("trade"), "This report");          // disk data: nothing refused
    }

    @Test
    void aQuestionMentioningAMemoryKindAsAWordCounts() {
        Retention r = with(Map.of("news", "", "connector:news-store", ""));
        assertThat(r.mentions("{\"kind\":\"news\",\"measure\":\"COUNT\"}")).isTrue();
        assertThat(r.mentions("news-store/news@2026-10-02=v3")).isTrue();
        assertThat(r.mentions("{\"kind\":\"newsletter\"}")).isFalse();
        assertThat(r.mentions("")).isFalse();
        assertThat(with(Map.of()).mentions("news")).isFalse();
    }
}
