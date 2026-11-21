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
package com.ash.drishti.plugin.rabbitmq;

import com.ash.drishti.api.DrishtiTools;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.tools.net.TlsMaterial;
import com.ash.tools.net.TlsSettings;
import com.ash.drishti.common.NamedResources;
import com.ash.tools.settings.SettingsFile;
import com.ash.tools.testkit.TestPki;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultSaslConfig;
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
 * A RabbitMQ connector that says {@code broker: <name>} is given that {@code [broker.<name>]} section of the settings files: the
 * {@code amqps://} uri with its password from a second file (a placeholder naming the secret's key), TLS with the private CA, and the
 * client certificate as the log-in (EXTERNAL); each as the connection factory the plugin configures (no broker is reached).
 */
class RabbitMqNamedBrokerTest {

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

    /** The factory as the plugin sets it up for these settings: the uri, then the TLS material and the log-in mechanism. */
    private static ConnectionFactory factory(Map<String, String> s) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(s.get("uri"));
        boolean amqps = s.get("uri").startsWith("amqps:");
        TlsMaterial m = amqps ? DrishtiTools.tls(TlsSettings.from(s)) : null;
        RabbitMqSourcePlugin.secure(f, m, "external".equalsIgnoreCase(s.getOrDefault("auth-mechanism", "plain")));
        return f;
    }

    @Test
    void tlsWithUserAndPasswordWhoseSecretIsInTheSecretsFile() throws Exception {
        TestPki.Identity ca = TestPki.ca("rabbit-named-ca");
        Path caPem = dir.resolve("ca.pem");
        ca.writeCertPem(caPem);
        ini("[broker.rabbit]\ntype=rabbitmq\nuri=amqps://drishti:${broker.rabbit.password}@rabbit.bank.internal:5671/risk\ntls.ca-file=" + caPem + "\n",
                "[broker.rabbit]\npassword=s3cret\n");
        Map<String, String> s = NamedResources.connector("rabbit-trades", "rabbitmq", Map.of("broker", "rabbit", "queues", "trades"));
        assertThat(s).containsEntry("uri", "amqps://drishti:s3cret@rabbit.bank.internal:5671/risk").containsEntry("queues", "trades");
        ConnectionFactory f = factory(s);
        assertThat(f.isSSL()).isTrue();
        assertThat(f.getHost()).isEqualTo("rabbit.bank.internal");
        assertThat(f.getPort()).isEqualTo(5671);
        assertThat(f.getVirtualHost()).isEqualTo("risk");
        assertThat(f.getUsername()).isEqualTo("drishti");
        assertThat(f.getPassword()).isEqualTo("s3cret");
        assertThat(SettingsFile.shown("broker.rabbit.password", "s3cret")).isEqualTo(SettingsFile.MASK);
    }

    @Test
    void theClientCertificateIsTheLogIn() throws Exception {
        TestPki.Identity ca = TestPki.ca("rabbit-named-ca2");
        TestPki.Identity client = TestPki.issue(ca, "drishti", "EC", 30);
        Path caPem = dir.resolve("ca2.pem");
        ca.writeCertPem(caPem);
        Path cert = dir.resolve("client.pem");
        client.writeCertPem(cert);
        Path key = dir.resolve("client.key");
        client.writeKeyPem(key, TestPki.KeyFormat.PKCS8, null);
        ini("[broker.rabbit-cert]\ntype=rabbitmq\nuri=amqps://rabbit.bank.internal:5671/risk\nauth-mechanism=external\ntls.ca-file=" + caPem
                + "\ntls.cert-file=" + cert + "\ntls.key-file=" + key + "\n");
        Map<String, String> s = NamedResources.connector("rabbit-cert", "rabbitmq", Map.of("broker", "rabbit-cert", "queues", "trades"));
        assertThat(DrishtiTools.tls(TlsSettings.from(s)).clientKey()).isNotNull();
        ConnectionFactory f = factory(s);
        assertThat(f.isSSL()).isTrue();
        assertThat(f.getSaslConfig()).isSameAs(DefaultSaslConfig.EXTERNAL);
    }

    @Test
    void aRabbitConnectorCannotNameAKafkaBroker() throws Exception {
        ini("[broker.kafka-eu]\ntype=kafka\nbootstrap-servers=eu:9092\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> NamedResources.connector("q", "rabbitmq", Map.of("broker", "kafka-eu")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("of type kafka");
    }
}
