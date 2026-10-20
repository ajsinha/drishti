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

import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.TlsFixture;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A Cassandra connector that cannot connect (no Docker needed: a closed port and small local TLS servers stand in for the node).
 * QA3B-05: every restart of a connector that could not connect left its driver session retrying for good ("too many session instances:
 * 9 active", a reconnect WARN a minute from each, even after the connector file was deleted); now a close stops it. QA3B-11: Health said
 * "connecting to Cassandra at …" alike for a closed port and a TLS refusal; now it names a TLS, handshake or certificate failure with the
 * {@code tls.*} settings to look at.
 */
class CassandraConnectFailureTest {

    private static TlsFixture pki;

    @BeforeAll
    static void pki() {
        pki = new TlsFixture();
    }

    @AfterAll
    static void clean() {
        pki.delete();
    }

    /** The driver's count of sessions alive in this JVM (the number its "too many session instances" warning gives). */
    private static int sessions() throws Exception {
        var f = Class.forName("com.datastax.oss.driver.internal.core.session.DefaultSession").getDeclaredField("INSTANCE_COUNT");
        f.setAccessible(true);
        return ((AtomicInteger) f.get(null)).get();
    }

    /** Threads of driver sessions ({@code s3-admin-0}, {@code s3-io-1}, {@code s3-timer-0}). */
    private static long driverThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(t -> t.getName().matches("s\\d+-(admin|io|timer)-\\d+")).count();
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();                         // free now, nothing listens once it is closed
        }
    }

    private static Map<String, String> settings(String hostPort, String name) {
        Map<String, String> s = new HashMap<>(CassandraNode.settings(hostPort, "nowhere", name));
        s.put("connect-timeout-ms", "500");
        return s;
    }

    @Test
    void restartingAConnectorThatCannotConnectLeavesNoSessionBehind() throws Exception {
        int before = sessions();
        String hostPort = "127.0.0.1:" + closedPort();
        for (int i = 0; i < 5; i++) {                        // the retry board restarts it: close, start again
            CassandraSourcePlugin p = new CassandraSourcePlugin();
            p.start(DatedSourceContract.context(settings(hostPort, "nocass-" + i)));
            assertThat(p.health()).startsWith("DOWN: connecting to Cassandra at " + hostPort).contains("no connection to " + hostPort);
            long settle = System.currentTimeMillis() + 5_000;    // the one closed before goes within moments (the driver closes it on its thread)
            while (sessions() > before + 1 && System.currentTimeMillis() < settle) {
                Thread.sleep(50);
            }
            assertThat(sessions()).as("one session while it runs, never one per restart").isLessThanOrEqualTo(before + 1);
            p.close();                                        // stopped, or its file deleted
        }
        long deadline = System.currentTimeMillis() + 15_000;
        while ((sessions() > before || driverThreads() > 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(sessions()).as("sessions alive after five restarts and a close").isEqualTo(before);
        assertThat(driverThreads()).as("driver threads left reconnecting").isZero();
    }

    /** A node that demands TLS with a client certificate: the handshake is done (and refused) for each connection. */
    private static SSLServerSocket tlsNode(boolean needClientAuth) throws Exception {
        SSLServerSocket server = (SSLServerSocket) pki.serverContext(needClientAuth).getServerSocketFactory().createServerSocket(0, 50,
                InetAddress.getLoopbackAddress());
        server.setNeedClientAuth(needClientAuth);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket c = server.accept();
                    Thread.ofVirtual().start(() -> {
                        try (SSLSocket s = (SSLSocket) c) {
                            s.setSoTimeout(3_000);
                            s.startHandshake();
                            InputStream in = s.getInputStream();
                            in.readNBytes(9);
                        } catch (Exception ignored) {
                            // the refusal is the point
                        }
                    });
                } catch (Exception e) {
                    return;
                }
            }
        });
        return server;
    }

    @Test
    void aNodeThatAsksForAClientCertificateIsSaidSo() throws Exception {
        try (SSLServerSocket node = tlsNode(true)) {
            Map<String, String> s = settings("localhost:" + node.getLocalPort(), "qa-cass-nocert");
            s.putAll(pki.trustOnlySettings());
            s.put("tls.enabled", "true");
            try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
                p.start(DatedSourceContract.context(s));
                assertThat(p.health()).startsWith("DOWN: connecting to Cassandra at localhost:" + node.getLocalPort())
                        .contains("TLS to localhost:" + node.getLocalPort() + " failed")
                        .contains("the node asks for a client certificate (mutual TLS) and none is set: set tls.cert-file and tls.key-file");
            }
        }
    }

    @Test
    void aNodeThatSpeaksOnlyTlsToAPlainConnectorIsSaidSo() throws Exception {
        try (SSLServerSocket node = tlsNode(false)) {
            try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
                p.start(DatedSourceContract.context(settings("localhost:" + node.getLocalPort(), "qa-cass-plain")));
                assertThat(p.health()).contains("requires TLS (client_encryption_options): set tls.enabled: true");
            }
        }
    }

    @Test
    void aTlsConnectorAtAPlainPortAndAnUntrustedNodeAreSaidSo() throws Exception {
        // a node that answers CQL in plain text (the response header byte 0x84) on the port a TLS connector was given
        try (ServerSocket plain = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket c = plain.accept()) {
                    c.getInputStream().readNBytes(5);
                    c.getOutputStream().write(new byte[] {(byte) 0x84, 0, 0, 0, 6, 0, 0, 0, 0});
                    c.getOutputStream().flush();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // the client gave up
                }
            });
            Map<String, String> s = new HashMap<>(pki.clientSettings());
            s.put("tls.enabled", "true");
            var tls = CassandraTls.plan(s, System::getenv);
            String why = CassandraProbe.diagnose(java.util.List.of(java.net.InetSocketAddress.createUnresolved("localhost", plain.getLocalPort())), tls,
                    java.time.Duration.ofSeconds(2)).orElseThrow();
            assertThat(why).contains("the node does not speak TLS on this port");
        }
        try (SSLServerSocket node = tlsNode(false)) {
            Map<String, String> s = new HashMap<>(pki.wrongCaSettings());
            s.put("tls.enabled", "true");
            var tls = CassandraTls.plan(s, System::getenv);
            String why = CassandraProbe.diagnose(java.util.List.of(java.net.InetSocketAddress.createUnresolved("localhost", node.getLocalPort())), tls,
                    java.time.Duration.ofSeconds(2)).orElseThrow();
            assertThat(why).contains("the node's certificate is not trusted: set tls.ca-file (or tls.truststore)");
        }
    }
}
