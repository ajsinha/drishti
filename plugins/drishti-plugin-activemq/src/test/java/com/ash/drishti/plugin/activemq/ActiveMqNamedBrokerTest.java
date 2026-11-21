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
package com.ash.drishti.plugin.activemq;

import com.ash.drishti.api.DrishtiTools;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.tools.net.TlsSettings;
import com.ash.drishti.common.NamedResources;
import com.ash.tools.settings.SettingsFile;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.tools.testkit.TestPki;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSslConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An ActiveMQ connector that says {@code broker: <name>} is given that {@code [broker.<name>]} section of the settings files: the URL,
 * the user and the password (from a second file), TLS with the private CA, mutual TLS from a key store, hostname verification off;
 * every one as the connection factory the plugin builds (no broker is reached: the port is closed, the plugin waits calmly).
 */
class ActiveMqNamedBrokerTest {

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
        System.clearProperty(DrishtiTools.SETTINGS_FILE);
    }

    private void ini(String... files) throws Exception {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            Path f = dir.resolve("settings-" + i + ".ini");
            Files.writeString(f, files[i]);
            paths.add(f.toString());
        }
        System.setProperty(DrishtiTools.SETTINGS_FILE, String.join(",", paths));
        set.addAll(DrishtiTools.applySettingsFile());
    }

    static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** The connector's settings: only its own (destinations, kind), the rest from the named broker. */
    private Map<String, String> connector(String broker) throws Exception {
        Map<String, String> own = new HashMap<>(Map.of("broker", broker, "destinations", "queue:trades", "kind", "trade"));
        own.put("state.dir", Files.createTempDirectory(dir, "state").toString());
        own.put("source-name", "amq-named");
        return NamedResources.connector("amq-named", "activemq", own);
    }

    @Test
    void plainOpenWireWithUserAndPasswordFromTheSecretsFile() throws Exception {
        int port = closedPort();
        ini("[broker.amq]\ntype=activemq\nbroker-url=failover:(tcp://127.0.0.1:" + port + ")\nuser=drishti\n", "[broker.amq]\npassword=s3cret\n");
        ActiveMqSourcePlugin p = new ActiveMqSourcePlugin();
        try {
            p.start(DatedSourceContract.context(connector("amq")));
            ActiveMQConnectionFactory f = p.factory();
            assertThat(f).isNotInstanceOf(ActiveMQSslConnectionFactory.class);
            assertThat(f.getBrokerURL()).isEqualTo("failover:(tcp://127.0.0.1:" + port + ")");
            assertThat(f.getUserName()).isEqualTo("drishti");
            assertThat(f.getPassword()).isEqualTo("s3cret");
        } finally {
            p.close();
        }
        assertThat(SettingsFile.isSecret("broker.amq.password")).isTrue();
    }

    @Test
    void tlsWithThePrivateCaAndHostnameVerificationOff() throws Exception {
        int port = closedPort();
        TestPki.Identity ca = TestPki.ca("amq-named-ca");
        Path caPem = dir.resolve("ca.pem");
        ca.writeCertPem(caPem);
        ini("[broker.amq-tls]\ntype=activemq\nbroker-url=failover:(ssl://127.0.0.1:" + port + ")\nuser=drishti\ntls.ca-file=" + caPem
                + "\ntls.verify-hostname=false\n", "[broker.amq-tls]\npassword=s3cret\n");
        ActiveMqSourcePlugin p = new ActiveMqSourcePlugin();
        try {
            p.start(DatedSourceContract.context(connector("amq-tls")));
            ActiveMQConnectionFactory f = p.factory();
            assertThat(f).isInstanceOf(ActiveMQSslConnectionFactory.class);
            assertThat(f.getBrokerURL()).isEqualTo("failover:(ssl://127.0.0.1:" + port + "?socket.verifyHostName=false)");
            assertThat(f.getUserName()).isEqualTo("drishti");
            assertThat(f.getPassword()).isEqualTo("s3cret");
        } finally {
            p.close();
        }
    }

    @Test
    void mutualTlsFromAKeyStoreWithItsPasswordInTheSecretsFile() throws Exception {
        int port = closedPort();
        TestPki.Identity ca = TestPki.ca("amq-named-ca2");
        TestPki.Identity client = TestPki.issue(ca, "drishti", "RSA", 30);
        Path ks = dir.resolve("drishti.p12");
        client.writeKeystore(ks, "PKCS12", "kspw", "drishti", "kspw");
        Path caPem = dir.resolve("ca2.pem");
        ca.writeCertPem(caPem);
        ini("[broker.amq-mtls]\ntype=activemq\nbroker-url=ssl://127.0.0.1:" + port + "\ntls.ca-file=" + caPem + "\ntls.keystore=" + ks
                + "\ntls.keystore-type=PKCS12\n", "[broker.amq-mtls]\ntls.keystore-password=kspw\n");
        Map<String, String> settings = connector("amq-mtls");
        assertThat(DrishtiTools.tls(TlsSettings.from(settings)).clientKey()).as("the client certificate is loaded").isNotNull();
        ActiveMqSourcePlugin p = new ActiveMqSourcePlugin();
        try {
            p.start(DatedSourceContract.context(settings));
            assertThat(p.factory()).isInstanceOf(ActiveMQSslConnectionFactory.class);
            assertThat(p.factory().getBrokerURL()).isEqualTo("ssl://127.0.0.1:" + port);
        } finally {
            p.close();
        }
    }
}
