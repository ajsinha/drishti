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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** QA2-202: a read of a kind whose connector is being restarted (its file swapped) waits for the new instance instead of "no source". */
class SourceRouterRestartTest {

    /** Holds every trade; says which instance answered (its setting {@code store}). */
    public static final class Holder implements SourcePlugin {
        private String store = "?";

        public PluginManifest manifest() {
            return new PluginManifest("holder", "t", Set.of("trade"), SourceCapabilities.FETCH_ONLY);
        }

        public void start(SourceContext c) {
            store = c.setting("store", "-");
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.of(new EntityDocument(ref, DataNode.of(Map.of("store", store)), new Provenance("trade-endur", 1, Instant.now(), false)));
        }
    }

    private static SourcesProperties.ConnectorSettings cs(String store) {
        return new SourcesProperties.ConnectorSettings("holder", true, List.of("trade"), Map.of("store", store));
    }

    @Test
    void aReadDuringAConnectorFileSwapWaitsForTheNewConnector() throws Exception {
        var props = new SourcesProperties(Map.of("trade", "trade-endur"), null, Map.of("holder", new SourcesProperties.PluginSettings(false, Map.of())),
                Duration.ofSeconds(2), null, Map.of("trade-endur", cs("delta")), null, "off", null, Duration.ofMillis(600), null);
        SourceRegistry registry = new SourceRegistry(List.of(new Holder()), props, new JsonCodec());
        SourceRouter router = new SourceRouter(registry, props, Executors.newVirtualThreadPerTaskExecutor());
        EntityRef ref = EntityRef.of("trade", "1000001");
        assertThat(router.fetch(ref, AsOf.LATEST).join().data().get("store").asText()).isEqualTo("delta");

        Thread swap = Thread.ofVirtual().start(() -> registry.apply("trade-endur", cs("cassandra")));
        Thread.sleep(200);                                                         // mid-drain: the connector is out of routing
        assertThat(registry.plugin("trade-endur")).isEmpty();
        assertThat(registry.restartingFor("trade", "trade-endur")).containsExactly("trade-endur");
        EntityDocument d = router.fetch(ref, AsOf.LATEST).join();                  // was 404 DRS-1002 "connector trade-endur is running"
        assertThat(d.data().get("store").asText()).isEqualTo("cassandra");
        swap.join();
        assertThat(registry.restartingFor("trade", "trade-endur")).isEmpty();
        assertThat(router.fetch(ref, AsOf.LATEST).join().data().get("store").asText()).isEqualTo("cassandra");
    }
}
