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
package com.ash.drishti.plugin.deltasharing;

import static com.ash.drishti.lakehouse.SparkDeltaTable.D29;
import static com.ash.drishti.lakehouse.SparkDeltaTable.D30;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.lakehouse.MockSharingServer;
import com.ash.drishti.lakehouse.SparkDeltaTable;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A table another organisation shares through Delta Sharing, served as a kind: no storage keys, only the sharing server's links. */
class DeltaSharingPluginTest {

    private static final JsonCodec CODEC = new JsonCodec();

    @TempDir
    Path dir;

    @Test
    void aSharedTableServesDocumentsWithNoStorageKeys() throws Exception {
        try (MockSharingServer server = new MockSharingServer()) {
            server.share("markets", "risk", "trades", SparkDeltaTable.write(dir.resolve("trades"), false));
            Map<String, String> s = new HashMap<>(Map.of("endpoint", server.endpoint(), "bearer-token", MockSharingServer.TOKEN,
                    "tables.trade.table", "markets.risk.trades", "tables.trade.id", "TRADE_ID", "hints.markets.risk.trades", "business_date >= 2026-09-29"));
            DeltaSharingPlugin p = new DeltaSharingPlugin();
            p.start(DatedSourceContract.context(s));
            try {
                assertThat(p.manifest().name()).isEqualTo("delta-sharing");
                assertThat(p.health()).isEqualTo("UP");
                String d29 = CODEC.toJson(p.fetch(EntityRef.of("trade", "T-2"), AsOf.of(D29)).orElseThrow().data());
                assertThat(SparkDeltaTable.JSON.readTree(d29).get("NOTIONAL").asDouble()).isEqualTo(2_500_000.0);
                assertThat(p.fetch(EntityRef.of("trade", "T-4"), AsOf.of(D30))).isPresent();
                assertThat(p.search("trade", "T-", 10)).isNotEmpty();
                assertThat(server.hints).anyMatch(h -> h.contains("greaterThanOrEqual"));
            } finally {
                p.close();
            }
        }
    }

    @Test
    void settingsThatCannotWorkAreRefusedInWords() {
        DeltaSharingPlugin p = new DeltaSharingPlugin();
        assertThatThrownBy(() -> p.start(DatedSourceContract.context(Map.of("tables.trade.id", "TRADE_ID")))).hasMessageContaining("settings.profile");
        assertThatThrownBy(() -> new DeltaSharingPlugin().start(DatedSourceContract.context(Map.of("profile", "x.share", "s3.access-key", "k"))))
                .hasMessageContaining("no storage keys");
    }
}
