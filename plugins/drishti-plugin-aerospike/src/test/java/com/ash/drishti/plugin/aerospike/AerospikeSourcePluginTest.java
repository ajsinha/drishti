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
package com.ash.drishti.plugin.aerospike;

import com.aerospike.client.AerospikeClient;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.time.Duration;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Aerospike connector against Aerospike Community Edition in Docker, loaded with the contract's rows: the same
 * tests as the Delta Lake and PostgreSQL connectors. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class AerospikeSourcePluginTest extends DatedSourceContract {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> AEROSPIKE = new GenericContainer<>("aerospike/aerospike-server:8.1.2.5")
            .withExposedPorts(3000)
            // Aerospike refuses to start with fewer than 15,000 file descriptors; Docker's default is often 1,024
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withUlimits(
                    new com.github.dockerjava.api.model.Ulimit[] {new com.github.dockerjava.api.model.Ulimit("nofile", 15_000L, 15_000L)}))
            .waitingFor(Wait.forLogMessage(".*service ready: soon there will be cake!.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    private static AerospikeSourcePlugin plugin;

    /** A client once the node answers: Aerospike 8 logs "ready" a little before it finishes initialising. */
    private static AerospikeClient ready(String host, int port) throws InterruptedException {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try {
                return new AerospikeClient(host, port);
            } catch (com.aerospike.client.AerospikeException e) {
                if (System.nanoTime() > until) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        String host = AEROSPIKE.getHost();
        int port = AEROSPIKE.getMappedPort(3000);
        com.ash.drishti.common.JsonCodec codec = new com.ash.drishti.common.JsonCodec();
        try (AerospikeClient c = ready(host, port)) {
            java.util.Map<String, java.util.Set<java.time.LocalDate>> dates = new java.util.HashMap<>();
            for (Row r : ROWS) {
                java.util.Map<String, Object> promoted = new java.util.HashMap<>();
                if (r.kind().equals("trade")) {                        // the layout below promotes these two
                    var doc = codec.read(r.json());
                    promoted.put("mtm", doc.get("mtm").isNull() ? null : doc.get("mtm").asDouble());
                    promoted.put("nettingSet", doc.get("nettingSet").isNull() ? null : doc.get("nettingSet").asText());
                }
                AerospikeLayout.write(c, null, "test", "desk", r.kind(), r.id(), r.date(), r.json(), promoted);
                dates.computeIfAbsent(r.kind(), k -> new java.util.TreeSet<>()).add(r.date());
            }
            dates.forEach((kind, ds) -> AerospikeLayout.addDates(c, null, "test", "desk", kind, ds));
        }
        AerospikeSourcePlugin p = new AerospikeSourcePlugin();
        p.start(context(Map.of("hosts", host + ":" + port, "namespace", "test", "set", "desk", "mode.counterparty", "effective",
                "source-name", "desk-aerospike", "layout.trade.columns", "mtm,nettingSet")));
        plugin = p;
        return p;
    }

    @org.junit.jupiter.api.Test
    void aDaysPromotedBinsAnswerSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        SourcePlugin p = plugin();
        org.assertj.core.api.Assertions.assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        com.ash.drishti.api.ColumnSet c = p.columns("trade", java.util.List.of("mtm", "nettingSet"), com.ash.drishti.api.AsOf.LATEST).orElseThrow();
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(com.ash.drishti.api.EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            org.assertj.core.api.Assertions.assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            org.assertj.core.api.Assertions.assertThat(c.value("nettingSet", i)).isEqualTo(doc.get("nettingSet").asText());
        }
        org.assertj.core.api.Assertions.assertThat(p.columns("trade", java.util.List.of("notional"), com.ash.drishti.api.AsOf.LATEST)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(p.reverse(com.ash.drishti.api.EntityRef.of("netting-set", "NS-A"), "trade", com.ash.drishti.api.AsOf.LATEST))
                .isNotEmpty().allSatisfy(r -> org.assertj.core.api.Assertions.assertThat(r.kind()).isEqualTo("trade"));
    }

    @org.junit.jupiter.api.Test
    void binNamesStayWithinFifteenCharacters() {
        org.assertj.core.api.Assertions.assertThat(AerospikeLayout.bin("counterparty.id")).isEqualTo("counterparty_id");
        org.assertj.core.api.Assertions.assertThat(AerospikeLayout.bin("counterparty.name")).hasSize(15).startsWith("counterpar_");
        org.assertj.core.api.Assertions.assertThat(AerospikeLayout.bin("counterparty.name")).isNotEqualTo(AerospikeLayout.bin("counterparty.names"));
        org.assertj.core.api.Assertions.assertThat(AerospikeLayout.bin("doc")).isNotEqualTo("doc");
        org.assertj.core.api.Assertions.assertThat(AerospikeLayout.date(AerospikeLayout.day(java.time.LocalDate.of(2026, 9, 30)))).isEqualTo("2026-09-30");
    }
}
