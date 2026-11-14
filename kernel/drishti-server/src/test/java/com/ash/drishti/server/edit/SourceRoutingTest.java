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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Write-back routing when several trading systems feed one kind (DATA_LAKE_GUIDE.md, "Several source systems"): a blotter edit goes to the
 * write path of the system the record came from (its connector's where or set), never another system's.
 */
class SourceRoutingTest {

    /** One shared table: two Endur trades, one Calypso trade, one SwapOne trade without SOURCE_SYSTEM (its connector stamps it). */
    public static final class Shared implements SourcePlugin {
        private String table;

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("shared", "t", Set.of("trade"), new SourceCapabilities(false, false, true));
        }

        @Override
        public void start(SourceContext c) {
            table = c.setting("table", "trades");
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            Map<String, Object> row = switch (table + "/" + ref.id()) {
                case "trades/E1" -> Map.of("TRADE_ID", "E1", "SOURCE_SYSTEM", "ENDUR");
                case "trades/C1" -> Map.of("TRADE_ID", "C1", "SOURCE_SYSTEM", "CALYPSO");
                case "swapone/S1" -> Map.of("TRADE_ID", "S1");
                default -> null;
            };
            return row == null ? Optional.empty() : Optional.of(new EntityDocument(ref, DataNode.of(row), new Provenance("x", 1, Instant.now(), false)));
        }
    }

    private static SourceRouter router() {
        var cfg = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        cfg.put("trade-endur", new SourcesProperties.ConnectorSettings("shared", true, List.of("trade"), Map.of("where", "SOURCE_SYSTEM = 'ENDUR'")));
        cfg.put("trade-endur-api", new SourcesProperties.ConnectorSettings("shared", true, List.of("trade"), Map.of("table", "none",
                "set.SOURCE_SYSTEM", "ENDUR")));
        cfg.put("trade-calypso", new SourcesProperties.ConnectorSettings("shared", true, List.of("trade"), Map.of("where", "SOURCE_SYSTEM = 'CALYPSO'")));
        cfg.put("trade-swapone", new SourcesProperties.ConnectorSettings("shared", true, List.of("trade"), Map.of("table", "swapone",
                "set.SOURCE_SYSTEM", "SWAPONE")));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, cfg);
        SourceRouter r = new SourceRouter(new SourceRegistry(List.of(new Shared()), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
        r.bind(Map.of("trade", List.of("trade-endur", "trade-calypso", "trade-swapone")));
        return r;
    }

    @Test
    void anEditGoesToTheSystemTheRecordCameFromNeverAnother() {
        SourceRoute route = EditConfiguration.sourceRoute(router());
        assertThat(route.systemOf("trade", "E1")).contains("ENDUR");
        assertThat(route.systemOf("trade", "C1")).contains("CALYPSO");
        assertThat(route.systemOf("trade", "S1")).as("stamped by its connector's set").contains("SWAPONE");
        assertThat(route.connectorsOf("ENDUR")).containsExactly("trade-endur", "trade-endur-api");

        // only trade-endur-api and trade-calypso carry the command amend-trade
        EditService.Commands commands = (c, cmd, kind) -> Set.of("trade-endur-api", "trade-calypso").contains(c) ? Optional.empty()
                : Optional.of(c + " has no command " + cmd);
        assertThat(EditService.routed(route, commands, "trade", "ENDUR", "trade-calypso", "amend-trade"))
                .as("a form naming another system's connector is routed to the record's own").isEqualTo("trade-endur-api");
        assertThat(EditService.routed(route, commands, "trade", "CALYPSO", null, "amend-trade")).isEqualTo("trade-calypso");
        assertThat(EditService.routed(route, commands, "trade", "ENDUR", "trade-endur", "amend-trade"))
                .as("the form's connector when it is the record's system's").isEqualTo("trade-endur");
        assertThat(EditService.routed(route, commands, "trade", "SWAPONE", "trade-calypso", "amend-trade"))
                .as("no write path of its own: refused, never another system's").isNull();
    }
}
