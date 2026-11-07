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
package com.ash.drishti.lakehouse.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.lakehouse.DeltaAsIcebergTest;
import com.ash.drishti.lakehouse.LakehouseCatalogs;
import com.ash.drishti.lakehouse.MockSharingServer;
import com.ash.drishti.lakehouse.SparkDeltaTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A table shared through the Delta Sharing protocol, from a mock sharing server: listed, its files asked for with predicate hints, its
 * Parquet read through pre-signed-style URLs by ranged GETs, the URLs renewed near expiry and after an early refusal; the refusals in
 * words; a profile never holding the token itself.
 */
class DeltaSharingTest {

    @TempDir
    Path dir;

    private MockSharingServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockSharingServer();
        server.share("markets", "risk", "trades", SparkDeltaTable.write(dir.resolve("trades"), false));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Map<String, String> settings(Map<String, String> more) {
        Map<String, String> s = new HashMap<>(Map.of("endpoint", server.endpoint(), "bearer-token", MockSharingServer.TOKEN));
        s.putAll(more);
        return s;
    }

    @Test
    void aSharedTableIsListedQueriedWithHintsAndReadThroughItsLinks() throws Exception {
        try (Catalog c = LakehouseCatalogs.sharing(settings(Map.of("hints.trades", "business_date >= 2026-09-30 and DESK != FX")),
                List.of("markets", "risk"))) {
            assertThat(c.tables()).containsExactly("trades");
            assertThat(c.reachable()).isTrue();
            Table t = c.load("trades").orElseThrow();
            assertThat(t.metadata().schema().columns()).hasSize(8);
            assertThat(DeltaAsIcebergTest.rows(t, t.currentSnapshot().orElseThrow()).keySet()).containsExactly("2026-09-29/T-1", "2026-09-29/T-2",
                    "2026-09-29/T-3", "2026-09-30/T-1", "2026-09-30/T-2", "2026-09-30/T-4");     // a hint, not a filter: the server sent all
            assertThat(server.hints).isNotEmpty();
            assertThat(server.hints.get(0)).contains("\"op\":\"and\"").contains("greaterThanOrEqual").contains("\"valueType\":\"date\"")
                    .contains("\"op\":\"not\"");
            assertThat(server.rangedReads.get()).isPositive();
            assertThat(t.refresh()).isFalse();                                    // the same version
        }
    }

    @Test
    void linksNearExpiryOrRefusedEarlyAreRenewed() throws Exception {
        server.urlTtlMs = 30_000;                                                 // inside the minute before expiry: renewed before use
        try (Catalog c = LakehouseCatalogs.sharing(settings(Map.of("share", "markets")), List.of("risk"))) {
            Table t = c.load("trades").orElseThrow();
            assertThat(DeltaAsIcebergTest.rows(t, t.currentSnapshot().orElseThrow())).hasSize(6);
            assertThat(server.queries.get()).isGreaterThan(1);
        }
        server.urlTtlMs = 3_600_000;
        int before = server.queries.get();
        try (Catalog c = LakehouseCatalogs.sharing(settings(Map.of()), List.of("markets", "risk"))) {
            Table t = c.load("trades").orElseThrow();
            server.rejectNext.set(1);                                             // the store refuses a valid link once
            assertThat(DeltaAsIcebergTest.rows(t, t.currentSnapshot().orElseThrow())).hasSize(6);
            assertThat(server.expiredRefusals.get()).isEqualTo(1);
            assertThat(server.queries.get() - before).isEqualTo(2);              // the plan, and the one renewal
        }
    }

    @Test
    void refusalsAreSaidInWords() throws Exception {
        try (Catalog c = LakehouseCatalogs.sharing(settings(Map.of("bearer-token", "bad-tok-123")), List.of("markets", "risk"))) {
            assertThatThrownBy(c::tables).hasMessageContaining("refused the bearer token").hasMessageContaining("401")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("bad-tok-123"));
        }
        try (Catalog c = LakehouseCatalogs.sharing(settings(Map.of()), List.of("markets", "risk"))) {
            assertThat(c.load("positions")).isEmpty();
            assertThat(c.missing("positions")).contains("no table positions");
        }
        try (Catalog c = LakehouseCatalogs.sharing(settings(Map.of()), List.of("markets", "nothing"))) {
            assertThatThrownBy(() -> c.load("trades")).hasMessageContaining("not found").hasMessageContaining("nothing");
        }
        try (Catalog c = LakehouseCatalogs.sharing(settings(Map.of()), List.of("risk"))) {
            assertThatThrownBy(c::tables).hasMessageContaining("share.schema.table");
        }
        assertThatThrownBy(() -> SharingCatalog.hints("DESK like FX")).hasMessageContaining("comparisons joined by 'and'");
        assertThatThrownBy(() -> LakehouseCatalogs.sharing(Map.of(), List.of())).hasMessageContaining("settings.profile");
    }

    @Test
    void aProfileNamesItsTokenByAnEnvironmentVariableNeverItself() throws Exception {
        Path profile = server.profile(dir, "PROVIDER_SHARING_TOKEN");
        SharingProfile p = SharingProfile.read(profile, k -> k.equals("PROVIDER_SHARING_TOKEN") ? MockSharingServer.TOKEN : null);
        assertThat(p.endpoint()).isEqualTo(server.endpoint());
        assertThat(p.toString()).doesNotContain(MockSharingServer.TOKEN);
        assertThat(new SharingClient(p, Map.of()).shares()).containsExactly("markets");
        assertThatThrownBy(() -> SharingProfile.read(profile, k -> null)).hasMessageContaining("PROVIDER_SHARING_TOKEN is not set");
        Path literal = dir.resolve("literal.share");
        Files.writeString(literal, "{\"shareCredentialsVersion\":1,\"endpoint\":\"" + server.endpoint() + "\",\"bearerToken\":\"abc123\"}");
        assertThatThrownBy(() -> SharingProfile.read(literal, k -> null)).hasMessageContaining("holds the bearer token itself")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("abc123"));
        Path oauth = dir.resolve("oauth.share");
        Files.writeString(oauth, "{\"shareCredentialsVersion\":2,\"type\":\"oauth_client_credentials\",\"endpoint\":\"" + server.endpoint() + "\"}");
        assertThatThrownBy(() -> SharingProfile.read(oauth, k -> null)).hasMessageContaining("under construction");
    }
}
