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
import com.aerospike.client.Key;
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
        try (AerospikeClient c = ready(host, port)) {
            for (Row r : ROWS) {
                c.put(null, new Key("test", "desk", DatedRecords.key(r.kind(), r.id())), DatedRecords.bins(r.kind(), r.id(), r.date(), r.json()));
            }
        }
        AerospikeSourcePlugin p = new AerospikeSourcePlugin();
        p.start(context(Map.of("hosts", host + ":" + port, "namespace", "test", "set", "desk", "mode.counterparty", "effective",
                "source-name", "desk-aerospike")));
        plugin = p;
        return p;
    }
}
