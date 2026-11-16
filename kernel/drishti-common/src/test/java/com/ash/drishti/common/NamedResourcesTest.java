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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.settingsfile.SettingsFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Named lakes, databases and brokers from the settings files: two of each in one configuration, every connector and pack bound to a
 * different one, and changing one never moving another; connector sections key by key; the default broker; the errors; relative files
 * under the install folder; secrets masked.
 */
class NamedResourcesTest {

    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();

    @BeforeEach
    void processView() {
        NamedResources.useView(null, null);
    }

    @AfterEach
    void clear() {
        set.forEach(System::clearProperty);
        System.clearProperty(SettingsFile.PROPERTY);
        System.clearProperty("drishti.home");
    }

    /** Writes the files, names them with -Ddrishti.settings.file and applies them, as a server's start does. */
    private void settings(String... files) throws Exception {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            Path f = dir.resolve("s" + i + ".ini");
            Files.writeString(f, files[i]);
            paths.add(f.toString());
        }
        System.setProperty(SettingsFile.PROPERTY, String.join(",", paths));
        set.addAll(SettingsFile.applyFromSystemProperty());
    }

    private static final String TWO_OF_EACH = """
            [lake.risk]
            root=/lakes/risk
            [lake.trading]
            root=/lakes/trading
            [db.reporting]
            url=jdbc:postgresql://rep:5432/risk
            user=reader
            [db.archive]
            url=jdbc:postgresql://arc:5432/old
            user=archivist
            [broker.kafka-eu]
            type=kafka
            bootstrap-servers=eu-1:9093
            security.protocol=SSL
            [broker.kafka-us]
            type=kafka
            bootstrap-servers=us-1:9092
            [pack.market-risk]
            lake=risk
            [pack.trading]
            lake=trading
            """;

    @Test
    void twoBrokersTwoDatabasesTwoPacksEachBoundToItsOwnAndIsolated() throws Exception {
        settings(TWO_OF_EACH);
        Map<String, String> eu = NamedResources.connector("trade-murex-live", "kafka", Map.of("broker", "kafka-eu", "topics", "murex"));
        Map<String, String> us = NamedResources.connector("trade-endur-live", "kafka", Map.of("broker", "kafka-us", "topics", "endur"));
        assertThat(eu).containsEntry("bootstrap-servers", "eu-1:9093").containsEntry("security.protocol", "SSL").containsEntry("topics", "murex")
                .doesNotContainKey("type");
        assertThat(us).containsEntry("bootstrap-servers", "us-1:9092").doesNotContainKey("security.protocol").containsEntry("topics", "endur");
        Map<String, String> rep = NamedResources.connector("reporting", "jdbc", Map.of("db", "reporting", "table", "VAR"));
        Map<String, String> arc = NamedResources.connector("archive", "jdbc", Map.of("db", "archive", "table", "VAR"));
        assertThat(rep).containsEntry("url", "jdbc:postgresql://rep:5432/risk").containsEntry("user", "reader");
        assertThat(arc).containsEntry("url", "jdbc:postgresql://arc:5432/old").containsEntry("user", "archivist");
        assertThat(NamedResources.packLake("market-risk", "demo")).isEqualTo("risk");
        assertThat(NamedResources.packLake("trading", "demo")).isEqualTo("trading");
        assertThat(NamedResources.packLake("core", "demo")).as("a pack nothing binds stays on the demo lake").isEqualTo("demo");
        assertThat(NamedResources.packLake("market-risk", "var-official")).as("only the shared demo lake is rebound").isEqualTo("var-official");

        System.setProperty("broker.kafka-eu.bootstrap-servers", "eu-2:9093");      // one broker moves (an explicit -D)
        set.add("broker.kafka-eu.bootstrap-servers");
        System.setProperty("db.reporting.url", "jdbc:postgresql://rep2:5432/risk");
        set.add("db.reporting.url");
        System.setProperty("pack.market-risk.lake", "risk2");
        set.add("pack.market-risk.lake");
        assertThat(NamedResources.connector("trade-murex-live", "kafka", Map.of("broker", "kafka-eu"))).containsEntry("bootstrap-servers", "eu-2:9093");
        assertThat(NamedResources.connector("trade-endur-live", "kafka", Map.of("broker", "kafka-us"))).as("the other broker never moves")
                .containsEntry("bootstrap-servers", "us-1:9092");
        assertThat(NamedResources.connector("archive", "jdbc", Map.of("db", "archive"))).containsEntry("url", "jdbc:postgresql://arc:5432/old");
        assertThat(NamedResources.packLake("trading", "demo")).isEqualTo("trading");
        assertThat(NamedResources.packLake("market-risk", "demo")).isEqualTo("risk2");
    }

    @Test
    void aConnectorSectionWinsKeyByKeyAndCanRebindItsBrokerOrLake() throws Exception {
        settings(TWO_OF_EACH + """
                [connector.trade-murex-live]
                broker=kafka-us
                topics=murex.v2
                [connector.trade-store]
                lake=trading
                """);
        Map<String, String> c = NamedResources.connector("trade-murex-live", "kafka", Map.of("broker", "kafka-eu", "topics", "murex", "kind", "trade"));
        assertThat(c).containsEntry("bootstrap-servers", "us-1:9092").containsEntry("topics", "murex.v2").containsEntry("kind", "trade");
        assertThat(NamedResources.connector("trade-store", "delta", Map.of("lake", "demo"))).containsEntry("lake", "trading");
        assertThat(NamedResources.connector("other", "delta", Map.of("lake", "demo"))).containsEntry("lake", "demo");
    }

    @Test
    void theConnectorsOwnValueWinsOverTheSectionButABlankOneDoesNotHideIt() throws Exception {
        settings(TWO_OF_EACH);
        assertThat(NamedResources.connector("x", "kafka", Map.of("broker", "kafka-eu", "bootstrap-servers", "own:9092")))
                .containsEntry("bootstrap-servers", "own:9092");
        assertThat(NamedResources.connector("x", "kafka", Map.of("broker", "kafka-eu", "bootstrap-servers", "")))
                .containsEntry("bootstrap-servers", "eu-1:9093");
    }

    @Test
    void theDefaultBrokerIsALocalKafkaUntilItsSectionSaysOtherwise() throws Exception {
        assertThat(NamedResources.connector("trading-stream", "kafka", Map.of("broker", "default")))
                .containsEntry("bootstrap-servers", "localhost:9092").doesNotContainKey("type");
        settings("[broker.default]\nbootstrap-servers=kafka.bank:9092\n");
        assertThat(NamedResources.connector("trading-stream", "kafka", Map.of("broker", "default"))).containsEntry("bootstrap-servers", "kafka.bank:9092");
    }

    @Test
    void anUnknownNameABadNameOrABrokerOfAnotherTypeIsRefusedNamingTheSection() throws Exception {
        settings(TWO_OF_EACH + "[broker.amq]\ntype=activemq\nbroker-url=ssl://amq:61617\n");
        assertThatThrownBy(() -> NamedResources.connector("c", "kafka", Map.of("broker", "nowhere"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[broker.nowhere]");
        assertThatThrownBy(() -> NamedResources.connector("c", "jdbc", Map.of("db", "nowhere"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[db.nowhere]");
        assertThatThrownBy(() -> NamedResources.connector("c", "kafka", Map.of("broker", "Bad Name"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a name");
        assertThatThrownBy(() -> NamedResources.connector("c", "kafka", Map.of("broker", "amq"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("of type activemq");
        assertThat(NamedResources.connector("c", "activemq", Map.of("broker", "amq"))).containsEntry("broker-url", "ssl://amq:61617");
    }

    @Test
    void aSectionValueResolvesPlaceholdersFromAnotherIniKey() throws Exception {
        settings("[broker.rabbit]\ntype=rabbitmq\nuri=amqps://drishti:${broker.rabbit.password}@rabbit:5671/risk\n", "[broker.rabbit]\npassword=s3cret\n");
        assertThat(NamedResources.connector("q", "rabbitmq", Map.of("broker", "rabbit")))
                .containsEntry("uri", "amqps://drishti:s3cret@rabbit:5671/risk").containsEntry("password", "s3cret");
    }

    @Test
    void theEnvironmentFormOfADocumentedKeyIsReadThroughTheView() {
        NamedResources.useView(k -> k.equals("BROKER_EDGE_BOOTSTRAP_SERVERS") ? "edge:9092" : null, () -> List.of());
        assertThat(NamedResources.section("broker", "edge")).containsEntry("bootstrap-servers", "edge:9092");
    }

    @Test
    void relativeFilesAndFoldersAreTakenUnderTheInstallFolderNeverTheWorkingFolder() throws Exception {
        System.setProperty("drishti.home", dir.toString());
        settings("""
                [broker.kafka-tls]
                type=kafka
                bootstrap-servers=k:9093
                tls.ca-file=tls/ca.pem
                sasl.kerberos.keytab=/abs/drishti.keytab
                """);
        Map<String, String> c = NamedResources.connector("c", "kafka", Map.of("broker", "kafka-tls", "disk-cache.dir", "cache/c",
                "root", "s3://bucket/x", "url", "jdbc:sqlite:data/x.db", "path", "trading/trade_endur"));
        assertThat(c).containsEntry("tls.ca-file", dir.resolve("tls/ca.pem").toString())
                .containsEntry("sasl.kerberos.keytab", "/abs/drishti.keytab")
                .containsEntry("disk-cache.dir", dir.resolve("cache/c").toString())
                .containsEntry("root", "s3://bucket/x")
                .containsEntry("url", "jdbc:sqlite:" + dir.resolve("data/x.db"))
                .as("a table path is relative to its lake, not a file").containsEntry("path", "trading/trade_endur");
    }

    @Test
    void secretsAreMaskedWhenShown() {
        assertThat(NamedResources.shown(Map.of("sasl.password", "p", "api-secret", "s", "tls.key-password", "k", "bootstrap-servers", "b:9092")))
                .containsEntry("sasl.password", SettingsFile.MASK).containsEntry("api-secret", SettingsFile.MASK)
                .containsEntry("tls.key-password", SettingsFile.MASK).containsEntry("bootstrap-servers", "b:9092");
    }
}
