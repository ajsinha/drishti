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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.TlsFixture;
import com.datastax.oss.driver.internal.core.metadata.DefaultEndPoint;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Cassandra TLS: what the shared {@code tls.*} settings become in the driver's engines, the start-up errors, and a live
 * connection to Cassandra 5 in Docker that demands TLS with a client certificate (its own container, removed after the
 * class; the live test is skipped without Docker).
 */
class CassandraTlsTest {

    private static TlsFixture pki;
    private static GenericContainer<?> node;

    @BeforeAll
    static void pki() {
        pki = new TlsFixture();
    }

    @AfterAll
    static void clean() {
        if (node != null) {
            node.stop();
        }
        pki.delete();
    }

    private static Map<String, String> on(Map<String, String> extra) {
        Map<String, String> s = new LinkedHashMap<>(pki.clientSettings());
        s.put("tls.enabled", "true");
        s.putAll(extra);
        return s;
    }

    @Test
    void withoutTlsSettingsThereIsNoEngine() {
        assertThat(CassandraTls.plan(Map.of(), System::getenv)).isNull();
    }

    @Test
    void tlsSettingsWithoutEnabledAreRefused() {
        assertThatThrownBy(() -> CassandraTls.plan(pki.trustOnlySettings(), System::getenv)).isInstanceOf(TlsException.class)
                .hasMessageContaining("tls.enabled");
    }

    @Test
    void enginesCarryTheHostTheProtocolsAndTheHostNameCheck() {
        TlsMaterial m = CassandraTls.plan(on(Map.of("tls.protocols", "TLSv1.3")), System::getenv);
        SSLEngine e = CassandraTls.engines(m).newSslEngine(new DefaultEndPoint(InetSocketAddress.createUnresolved("cass1.example.com", 9142)));
        assertThat(e.getUseClientMode()).isTrue();
        assertThat(e.getPeerHost()).isEqualTo("cass1.example.com");
        assertThat(e.getPeerPort()).isEqualTo(9142);
        assertThat(e.getSSLParameters().getProtocols()).containsExactly("TLSv1.3");
        assertThat(e.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        TlsMaterial lax = CassandraTls.plan(on(Map.of("tls.verify-hostname", "false")), System::getenv);
        SSLEngine l = CassandraTls.engines(lax).newSslEngine(new DefaultEndPoint(InetSocketAddress.createUnresolved("10.0.0.7", 9042)));
        assertThat(l.getSSLParameters().getEndpointIdentificationAlgorithm()).isNull();
    }

    @Test
    void aMissingCertificateFileFailsTheStartNamingIt() {
        assertThatThrownBy(() -> CassandraTls.plan(on(Map.of("tls.ca-file", "/no/such/ca.pem")), System::getenv)).isInstanceOf(TlsException.class)
                .hasMessageContaining("/no/such/ca.pem");
    }

    /** Cassandra 5 with client encryption on and client certificates required, from the fixture's PKCS12 stores. */
    @SuppressWarnings("resource")
    private static String tlsNode() {
        if (node == null) {
            // the image's client_encryption_options block replaced by one that demands TLS and a client certificate
            String edit = "sed -i -e '/^client_encryption_options:/,/^[a-z]/{/^ /d;/^$/d}'"
                    + " -e 's#^client_encryption_options:.*#client_encryption_options:\\n  enabled: true\\n  optional: false\\n"
                    + "  keystore: /tls/server.p12\\n  keystore_password: changeit\\n  store_type: PKCS12\\n  require_client_auth: true\\n"
                    + "  truststore: /tls/ca.p12\\n  truststore_password: changeit\\n#' /etc/cassandra/cassandra.yaml";
            GenericContainer<?> c = new GenericContainer<>(CassandraNode.CASSANDRA_IMAGE).withExposedPorts(9042)
                    .withEnv("MAX_HEAP_SIZE", "1G").withEnv("HEAP_NEWSIZE", "256M")
                    .withEnv("JVM_EXTRA_OPTS", "-Dcassandra.skip_wait_for_gossip_to_settle=0 -Dcassandra.initial_token=0 -Dcassandra.num_tokens=1")
                    .withCopyFileToContainer(MountableFile.forHostPath(pki.path("server.p12"), 0644), "/tls/server.p12")
                    .withCopyFileToContainer(MountableFile.forHostPath(pki.path("ca.p12"), 0644), "/tls/ca.p12")
                    .withCommand("bash", "-c", edit + " && exec docker-entrypoint.sh cassandra -f")
                    .withLogConsumer(f -> {
                        String s = f.getUtf8String();
                        if (s.contains("ERROR") || s.contains("Exception") || s.contains("encryption")) {
                            System.err.print("cassandra-tls: " + s);          // why a misconfigured node stops, in the test's output
                        }
                    })
                    .waitingFor(Wait.forLogMessage(".*Starting listening for CQL clients.*", 1).withStartupTimeout(Duration.ofMinutes(4)));
            c.start();
            node = c;
        }
        return "localhost:" + node.getMappedPort(9042);
    }

    @Test
    void theConnectorReadsOverMutualTls() throws Exception {
        boolean docker;
        try {
            docker = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            docker = false;
        }
        assumeTrue(docker, "Docker is not reachable");
        String hostPort = tlsNode();
        Map<String, String> tls = on(Map.of());
        String[] args = CassandraNode.args(hostPort, "tls_desk", tls.entrySet().stream().flatMap(e -> java.util.stream.Stream.of("--setting", e.getKey() + "=" + e.getValue()))
                .toArray(String[]::new));
        CassandraNode.load(args, CassandraNode.contractLines("desk"));
        Map<String, String> settings = new java.util.HashMap<>(CassandraNode.settings(hostPort, "tls_desk", "tls-cassandra"));
        settings.putAll(tls);
        try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
            p.start(DatedSourceContract.context(settings));
            assertThat(p.fetch(EntityRef.of("trade", "T-1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(125);
            assertThat(p.health()).startsWith("UP");
            assertThat(p.health()).contains("expires");                         // the client certificate is inside the 30-day warning
        }
        // without the client certificate the node refuses the connection: the connector starts and says DOWN
        Map<String, String> noCert = new java.util.HashMap<>(CassandraNode.settings(hostPort, "tls_desk", "tls-cassandra-nocert"));
        noCert.putAll(pki.trustOnlySettings());
        noCert.put("tls.enabled", "true");
        noCert.put("connect-timeout-ms", "3000");
        try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
            p.start(DatedSourceContract.context(noCert));
            assertThat(p.health()).startsWith("DOWN");
        }
    }
}
