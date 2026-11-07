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
package com.ash.drishti.lakehouse.unity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.lakehouse.DeltaAsIcebergTest;
import com.ash.drishti.lakehouse.LakehouseCatalogs;
import com.ash.drishti.lakehouse.MockUnityCatalog;
import com.ash.drishti.lakehouse.SparkDeltaTable;
import com.ash.drishti.lakeio.CredentialSource;
import com.ash.drishti.testkit.s3.S3MockServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;

/**
 * Delta tables named in Unity Catalog: the table's storage location and vended credentials from a mock Unity Catalog (Databricks' REST
 * answers), the files in S3 (S3Mock in this JVM) or on local disk; the credentials renewed before expiry; the refusals in words.
 */
class UnityCatalogTest {

    @TempDir
    Path dir;

    private MockUnityCatalog uc;

    @BeforeEach
    void start() throws Exception {
        uc = new MockUnityCatalog();
    }

    @AfterEach
    void stop() {
        uc.close();
    }

    private Map<String, String> settings(Map<String, String> more) {
        Map<String, String> s = new HashMap<>(Map.of("unity.host", uc.host(), "unity.token", MockUnityCatalog.TOKEN, "unity.catalog", "main"));
        s.putAll(more);
        return s;
    }

    @Test
    void aTableNamedInUnityIsReadFromS3WithItsVendedCredentials() throws Exception {
        Path local = dir.resolve("lake").resolve("trades");
        SparkDeltaTable.write(local, false);
        try (S3MockServer s3 = S3MockServer.start(dir.resolve("s3"), "lakehouse")) {
            var client = s3.endpoint().client();
            try (var files = Files.walk(local)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String key = "risk/trades/" + local.relativize(f).toString().replace('\\', '/');
                    client.putObject(b -> b.bucket("lakehouse").key(key), RequestBody.fromFile(f));
                }
            }
            uc.table("main.trading.trades", "s3://lakehouse/risk/trades");
            Map<String, String> s = settings(Map.of("s3.endpoint", s3.endpoint().endpoint(), "s3.region", "us-east-1", "s3.path-style", "true"));
            try (Catalog c = LakehouseCatalogs.delta(s, List.of("main", "trading"))) {
                assertThat(c.tables()).containsExactly("trades");
                Table t = c.load("trades").orElseThrow();
                assertThat(DeltaAsIcebergTest.rows(t, t.currentSnapshot().orElseThrow())).hasSize(6);
                assertThat(uc.credentialCalls.get()).isEqualTo(1);              // vended once, used for the log and the files
            }
        }
    }

    @Test
    void aLocalTableOfTheOpenSourceServerAndOAuthMachineToMachine() throws Exception {
        SparkDeltaTable.write(dir.resolve("trades"), true);
        uc.table("main.trading.trades", dir.resolve("trades").toUri().toString());
        Map<String, String> s = new HashMap<>(Map.of("unity.host", uc.host(), "unity.client-id", MockUnityCatalog.CLIENT_ID, "unity.client-secret",
                MockUnityCatalog.CLIENT_SECRET, "unity.catalog", "main", "unity.schema", "trading"));
        try (Catalog c = LakehouseCatalogs.delta(s, List.of())) {
            Table t = c.load("trades").orElseThrow();
            assertThat(DeltaAsIcebergTest.rows(t, t.currentSnapshot().orElseThrow())).hasSize(6);
            assertThat(uc.oauthCalls.get()).isEqualTo(1);                      // one sign-in, its token reused
        }
    }

    @Test
    void credentialsAreRenewedBeforeTheyExpireAndTheOldOnesServeWhileUnityIsAway() throws Exception {
        uc.table("main.trading.trades", "s3://lakehouse/risk/trades");
        uc.credentialTtlMs = 600_000;
        UnityTables unity = UnityTables.from(settings(Map.of()));
        UnityClient.TableInfo info = unity.client().table("main.trading.trades");
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        UnityCredentials creds = new UnityCredentials(unity.client(), info, unity.client().credentials(info), 300_000, now::get);
        CredentialSource.Key first = creds.current();
        assertThat(first.accessKey()).isEqualTo("ASIA-VENDED-1");
        now.addAndGet(200_000);                                                // 400 s left: still good
        assertThat(creds.current()).isSameAs(first);
        now.addAndGet(150_000);                                                // 250 s left: inside the renewal window
        assertThat(creds.current().accessKey()).isEqualTo("ASIA-VENDED-2");
        assertThat(uc.credentialCalls.get()).isEqualTo(2);
        uc.vendingOff = true;                                                  // Unity refuses from now on
        now.set(creds.expiresAtMs() - 100_000);
        assertThat(creds.current().accessKey()).isEqualTo("ASIA-VENDED-2");    // the old keys serve until they really expire
        assertThat(creds.lastError()).contains("credential vending is off");
        now.set(creds.expiresAtMs() + 1);
        assertThatThrownBy(creds::current).hasMessageContaining("expired").hasMessageContaining("credential vending is off");
    }

    @Test
    void refusalsAreSaidInWords() throws Exception {
        uc.table("main.trading.trades", "s3://lakehouse/risk/trades");
        uc.table("main.trading.secret_trades", "s3://lakehouse/risk/secret");
        uc.denied.add("main.trading.secret_trades");
        uc.table("main.trading.trade_view", new MockUnityCatalog.UcTable("v1", "", "", "VIEW"));
        uc.table("main.trading.ice", new MockUnityCatalog.UcTable("i1", "s3://lakehouse/ice", "ICEBERG", "MANAGED"));
        uc.table("main.trading.adls", new MockUnityCatalog.UcTable("a1", "abfss://lake@acct.dfs.core.windows.net/t", "DELTA", "EXTERNAL"));
        UnityTables unity = UnityTables.from(settings(Map.of()));
        assertThatThrownBy(() -> unity.resolve("trading.nothing")).isInstanceOfSatisfying(UnityException.class,
                e -> assertThat(e.reason()).isEqualTo(UnityException.Reason.NOT_FOUND)).hasMessageContaining("table not found")
                .hasMessageContaining("main.trading.nothing").hasMessageContaining("TABLE_DOES_NOT_EXIST");
        assertThatThrownBy(() -> unity.resolve("trading.secret_trades")).isInstanceOfSatisfying(UnityException.class,
                e -> assertThat(e.reason()).isEqualTo(UnityException.Reason.NO_GRANT)).hasMessageContaining("no grant")
                .hasMessageContaining("SELECT");
        uc.vendingOff = true;
        assertThatThrownBy(() -> unity.resolve("trading.trades")).isInstanceOfSatisfying(UnityException.class,
                e -> assertThat(e.reason()).isEqualTo(UnityException.Reason.VENDING_OFF)).hasMessageContaining("credential vending is off")
                .hasMessageContaining("EXTERNAL USE SCHEMA");
        uc.vendingOff = false;
        assertThatThrownBy(() -> unity.resolve("trading.trade_view")).hasMessageContaining("a view has no files");
        assertThatThrownBy(() -> unity.resolve("trading.ice")).hasMessageContaining("not Delta").hasMessageContaining("iceberg-external");
        assertThatThrownBy(() -> unity.resolve("trading.adls")).hasMessageContaining("abfss").hasMessageContaining("under construction");
        assertThatThrownBy(() -> unity.resolve("trades")).hasMessageContaining("unity.schema");
        UnityTables badToken = UnityTables.from(settings(Map.of("unity.token", "bad-tok-123")));
        assertThatThrownBy(() -> badToken.resolve("trading.trades")).isInstanceOfSatisfying(UnityException.class,
                e -> assertThat(e.reason()).isEqualTo(UnityException.Reason.SIGN_IN)).hasMessageContaining("unity.token")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("bad-tok-123"));       // never the secret
        UnityTables badClient = UnityTables.from(settings(Map.of("unity.token", "", "unity.client-id", "x", "unity.client-secret", "y")));
        assertThatThrownBy(() -> badClient.resolve("trading.trades")).hasMessageContaining("OAuth sign-in of client x");
        assertThatThrownBy(() -> UnityTables.from(Map.of("unity.host", uc.host()))).hasMessageContaining("unity.token");
        try (Catalog c = LakehouseCatalogs.delta(settings(Map.of()), List.of("main", "trading"))) {
            assertThat(c.load("nothing")).isEmpty();
            assertThat(c.missing("nothing")).contains("table not found");
            assertThatThrownBy(() -> c.load("secret_trades")).hasMessageContaining("no grant");
        }
    }
}
