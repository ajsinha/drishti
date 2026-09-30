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
package com.ash.drishti.plugin.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DemoSourcePluginTest {

    static final DemoSourcePlugin PLUGIN = new DemoSourcePlugin();

    @BeforeAll
    static void start() throws Exception {
        JsonCodec codec = new JsonCodec();
        PLUGIN.start(new SourceContext() {
            public Map<String, String> settings() {
                return Map.of("dirs", java.nio.file.Path.of("../../packs/finance/samples").toAbsolutePath().toString(), "ticking", "false");
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
    }

    @Test
    void servesTheFourReferenceEntitiesWithProvenance() throws Exception {
        EntityDocument irs = PLUGIN.fetch(EntityRef.of("trade", "IRS-48213")).orElseThrow();
        assertThat(irs.data().at("legs[0].cashflows").size()).isEqualTo(5);
        assertThat(irs.data().get("_meta").isMissing()).isTrue();
        assertThat(irs.provenance().source()).isEqualTo("aero-risk");
        assertThat(irs.provenance().generation()).isEqualTo(1742);
        assertThat(PLUGIN.fetch(EntityRef.of("trade", "FXS-20931")).orElseThrow().provenance().source()).isEqualTo("aero-fx");
        assertThat(PLUGIN.fetch(EntityRef.of("trade", "CFT-77120")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(409500);
        assertThat(PLUGIN.fetch(EntityRef.of("netting-set", "NS-NORTH-01")).orElseThrow().data().get("trades").asDouble()).isEqualTo(14);
        assertThat(PLUGIN.fetch(EntityRef.of("trade", "NOPE"))).isEmpty();
    }

    @Test
    void searchAndReverseLookup() {
        assertThat(PLUGIN.search("trade", "IRS-48", 5)).extracting(h -> h.ref().id()).first().isEqualTo("IRS-48213");
        assertThat(PLUGIN.search(null, "northbridge", 50).size()).isGreaterThan(10);
        assertThat(PLUGIN.reverse(EntityRef.of("netting-set", "NS-NORTH-01"), "trade")).hasSize(14);
    }
}
