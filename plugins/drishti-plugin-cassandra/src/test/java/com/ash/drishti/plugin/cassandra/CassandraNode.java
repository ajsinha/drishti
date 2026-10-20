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
package com.ash.drishti.plugin.cassandra;

import com.ash.drishti.api.LoadGuard;
import com.ash.drishti.testkit.DatedSourceContract;
import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One Cassandra 5 node (and, for its own test, one ScyllaDB node) in Docker, started by Testcontainers the first time a
 * test asks and shared by every test class of the module (they run in one JVM: drishti.test.forks is 1 here); Testcontainers
 * removes it when the JVM ends. Nothing else in Docker is touched. Each test class works in its own keyspace.
 */
final class CassandraNode {

    static final String CASSANDRA_IMAGE = "cassandra:5.0.6";
    static final String SCYLLA_IMAGE = "scylladb/scylla:6.2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, GenericContainer<?>> NODES = new HashMap<>();

    private CassandraNode() {}

    /** The Cassandra 5 node's {@code host:port}. */
    static synchronized String cassandra() {
        return hostPort(NODES.computeIfAbsent(CASSANDRA_IMAGE, CassandraNode::startCassandra));
    }

    /** The ScyllaDB node's {@code host:port}. */
    static synchronized String scylla() {
        return hostPort(NODES.computeIfAbsent(SCYLLA_IMAGE, CassandraNode::startScylla));
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> startCassandra(String image) {
        GenericContainer<?> c = new GenericContainer<>(image).withExposedPorts(9042)
                // a small node: 1 GB of heap, one token, no wait for gossip (a single node has nobody to gossip with)
                .withEnv("MAX_HEAP_SIZE", "1G").withEnv("HEAP_NEWSIZE", "256M")
                .withEnv("JVM_EXTRA_OPTS", "-Dcassandra.skip_wait_for_gossip_to_settle=0 -Dcassandra.initial_token=0 -Dcassandra.num_tokens=1")
                .waitingFor(Wait.forLogMessage(".*Starting listening for CQL clients.*", 1).withStartupTimeout(Duration.ofMinutes(4)));
        c.start();
        ready(hostPort(c));
        return c;
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> startScylla(String image) {
        GenericContainer<?> c = new GenericContainer<>(image).withExposedPorts(9042)
                .withCommand("--smp", "1", "--memory", "1G", "--overprovisioned", "1", "--developer-mode", "1", "--skip-wait-for-gossip-to-settle", "0")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)));
        c.start();
        ready(hostPort(c));
        return c;
    }

    private static String hostPort(GenericContainer<?> c) {
        return c.getHost() + ":" + c.getMappedPort(9042);
    }

    /** Waits until the node answers a query (it opens its port a little before it serves). */
    private static void ready(String hostPort) {
        long until = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            try (CqlSession s = session(hostPort)) {
                s.execute("SELECT release_version FROM system.local");
                return;
            } catch (RuntimeException e) {
                if (System.nanoTime() > until) {
                    throw e;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
    }

    /** A plain session for checks the connector does not make (the test's own statements). */
    static CqlSession session(String hostPort) {
        String[] hp = hostPort.split(":");
        return CqlSession.builder().addContactPoint(new InetSocketAddress(hp[0], Integer.parseInt(hp[1]))).withLocalDatacenter("datacenter1").build();
    }

    /** A JSON line of the loader's input: the document and the promoted values the trade layout of the contract declares. */
    static String line(String domain, DatedSourceContract.Row r) {
        try {
            ObjectNode o = JSON.createObjectNode();
            o.put("domain", domain).put("kind", r.kind()).put("id", r.id()).put("date", r.date().toString()).put("doc", r.json());
            if (r.kind().equals("trade")) {
                var doc = JSON.readTree(r.json());
                ObjectNode cols = o.putObject("columns");
                cols.put("mtm", doc.get("mtm").asDouble());
                cols.put("nettingSet", doc.get("nettingSet").asText());
            }
            return JSON.writeValueAsString(o);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The loader's command line for a node and keyspace, with the options given after it. */
    static String[] args(String hostPort, String keyspace, String... more) {
        List<String> a = new ArrayList<>(List.of("-", hostPort, "datacenter1", "--keyspace", keyspace, "--create", "--create-keyspace", "1",
                "--buckets", "4", "--in-flight", "64", "--setting", "connect-timeout-ms=60000"));
        a.addAll(List.of(more));
        return a.toArray(new String[0]);
    }

    /** Loads JSON lines as {@code tools/load-cassandra.sh} would. */
    static CassandraLoader.Result load(String[] args, List<String> lines) throws Exception {
        try (CassandraLoader loader = new CassandraLoader(CassandraLoader.parse(args), LoadGuard.fromArgs(args))) {
            return loader.load(new BufferedReader(new StringReader(String.join("\n", lines))));
        }
    }

    /** The contract's rows, as JSON lines in a domain. */
    static List<String> contractLines(String domain) {
        return DatedSourceContract.ROWS.stream().map(r -> line(domain, r)).toList();
    }

    /** Connector settings for a node and keyspace (the contract's modes and the trade layout). */
    static Map<String, String> settings(String hostPort, String keyspace, String sourceName) {
        Map<String, String> s = new HashMap<>();
        s.put("contact-points", hostPort);
        s.put("local-datacenter", "datacenter1");
        s.put("keyspace", keyspace);
        s.put("mode.counterparty", "effective");
        s.put("source-name", sourceName);
        s.put("layout.trade.columns", "mtm,nettingSet");
        s.put("refresh-seconds", "3600");
        s.put("connect-timeout-ms", "60000");
        return s;
    }
}
