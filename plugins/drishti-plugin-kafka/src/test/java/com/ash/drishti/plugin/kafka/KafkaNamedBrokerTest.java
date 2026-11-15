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
package com.ash.drishti.plugin.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.NamedResources;
import com.ash.drishti.settingsfile.SettingsFile;
import com.ash.drishti.testkit.TestPki;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every security form a {@code [broker.<name>]} section of the settings files can carry, from the ini file to the exact Kafka client
 * properties a connector that says {@code broker: <name>} is given (no broker is reached): PLAINTEXT, TLS with a PEM CA, mutual TLS
 * from PEM files, from a key store (with and without an alias) and a trust store, hostname verification off, SASL PLAIN,
 * SCRAM-SHA-256/512, OAUTHBEARER, GSSAPI with a keytab or the ticket cache, Confluent's API key, {@code client.*} pass-through, and the
 * passwords from a second file (secrets.ini), its {@code -file} twin, every one masked.
 */
class KafkaNamedBrokerTest {

    static TestPki.Identity ca;
    static TestPki.Identity client;
    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();
    private String allowedUrls;
    private String krb5;

    @BeforeAll
    static void pki() {
        ca = TestPki.ca("named-broker-ca");
        client = TestPki.issue(ca, "drishti", "RSA", 365);
    }

    @BeforeEach
    void before() {
        NamedResources.useView(null, null);
        allowedUrls = System.getProperty("org.apache.kafka.sasl.oauthbearer.allowed.urls");
        krb5 = System.getProperty("java.security.krb5.conf");
    }

    @AfterEach
    void after() {
        set.forEach(System::clearProperty);
        System.clearProperty(SettingsFile.PROPERTY);
        restore("org.apache.kafka.sasl.oauthbearer.allowed.urls", allowedUrls);
        restore("java.security.krb5.conf", krb5);
    }

    private static void restore(String key, String was) {
        if (was == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, was);
        }
    }

    /** Writes the ini files, names them with -Ddrishti.settings.file (a later one wins) and applies them. */
    private void ini(String... files) throws Exception {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            Path f = dir.resolve("settings-" + i + ".ini");
            Files.writeString(f, files[i]);
            paths.add(f.toString());
        }
        System.setProperty(SettingsFile.PROPERTY, String.join(",", paths));
        set.addAll(SettingsFile.applyFromSystemProperty());
    }

    /** The consumer properties of a kafka connector named {@code orders} that says {@code broker: <name>}. */
    private static Properties props(String broker) {
        Map<String, String> settings = NamedResources.connector("orders", "kafka", Map.of("broker", broker, "topics", "orders"));
        return KafkaClients.consumer(settings, "orders");
    }

    private Path pem(String name, TestPki.Identity id) throws Exception {
        Path p = dir.resolve(name);
        id.writeCertPem(p);
        return p;
    }

    private static int count(String text, String what) {
        return text == null ? 0 : text.split(what, -1).length - 1;
    }

    @Test
    void plaintext() throws Exception {
        ini("[broker.dev]\ntype=kafka\nbootstrap-servers=dev-1:9092,dev-2:9092\n");
        Properties p = props("dev");
        assertThat(p.getProperty("bootstrap.servers")).isEqualTo("dev-1:9092,dev-2:9092");
        assertThat(p.getProperty("security.protocol")).isEqualTo("PLAINTEXT");
        assertThat(p.getProperty("client.id")).isEqualTo("drishti-orders");
        assertThat(p.stringPropertyNames()).noneMatch(k -> k.startsWith("ssl.") || k.startsWith("sasl."));
    }

    @Test
    void tlsWithAPemCaAndHostnameVerification() throws Exception {
        ini("[broker.tls]\ntype=kafka\nbootstrap-servers=k:9093\nsecurity.protocol=SSL\ntls.ca-file=" + pem("ca.pem", ca) + "\n");
        Properties p = props("tls");
        assertThat(p.getProperty("security.protocol")).isEqualTo("SSL");
        assertThat(p.getProperty("ssl.endpoint.identification.algorithm")).isEqualTo("https");
        assertThat(p.getProperty("ssl.truststore.type")).isEqualTo("PEM");
        assertThat(count(p.getProperty("ssl.truststore.certificates"), "BEGIN CERTIFICATE")).isEqualTo(1);
        assertThat(p.getProperty("ssl.keystore.type")).isNull();
    }

    @Test
    void mutualTlsFromPemFilesWithAnEncryptedKeyWhosePasswordIsInTheSecretsFile() throws Exception {
        Path key = dir.resolve("client.key");
        client.writeKeyPem(key, TestPki.KeyFormat.ENCRYPTED_PKCS8, "kpw");
        ini("[broker.mtls]\ntype=kafka\nbootstrap-servers=k:9093\nsecurity.protocol=SSL\ntls.ca-file=" + pem("ca.pem", ca)
                + "\ntls.cert-file=" + pem("client.pem", client) + "\ntls.key-file=" + key + "\n", "[broker.mtls]\ntls.key-password=kpw\n");
        Properties p = props("mtls");
        assertThat(p.getProperty("ssl.keystore.type")).isEqualTo("PEM");
        assertThat(p.getProperty("ssl.keystore.key")).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(count(p.getProperty("ssl.keystore.certificate.chain"), "BEGIN CERTIFICATE")).isEqualTo(2);
        assertThat(SettingsFile.isSecret("broker.mtls.tls.key-password")).isTrue();
        assertThat(SettingsFile.sourceOf("broker.mtls.tls.key-password")).endsWith("settings-1.ini");
    }

    @ParameterizedTest
    @CsvSource({"PKCS12", "JKS"})
    void mutualTlsFromAKeyStoreAndATrustStoreHandedOverAsTheyAre(String type) throws Exception {
        Path ks = dir.resolve("client." + type);
        client.writeKeystore(ks, type, "kspw", "drishti", "keypw");
        Path ts = dir.resolve("trust." + type);
        TestPki.writeTruststore(ts, type, "tspw", ca.cert());
        ini("[broker.stores]\ntype=kafka\nbootstrap-servers=k:9093\nsecurity.protocol=SSL\ntls.truststore=" + ts + "\ntls.truststore-type=" + type
                + "\ntls.keystore=" + ks + "\ntls.keystore-type=" + type + "\n",
                "[broker.stores]\ntls.truststore-password=tspw\ntls.keystore-password=kspw\ntls.key-password=keypw\n");
        Properties p = props("stores");
        assertThat(p.getProperty("ssl.truststore.location")).isEqualTo(ts.toString());
        assertThat(p.getProperty("ssl.truststore.password")).isEqualTo("tspw");
        assertThat(p.getProperty("ssl.truststore.type")).isEqualTo(type);
        assertThat(p.getProperty("ssl.keystore.location")).isEqualTo(ks.toString());
        assertThat(p.getProperty("ssl.keystore.password")).isEqualTo("kspw");
        assertThat(p.getProperty("ssl.keystore.type")).isEqualTo(type);
        assertThat(p.getProperty("ssl.key.password")).isEqualTo("keypw");
    }

    @Test
    void oneAliasOfAKeyStoreIsHandedOverAsPemAndHostnameVerificationCanBeOff() throws Exception {
        Path ks = dir.resolve("client.p12");
        client.writeKeystore(ks, "PKCS12", "kspw", "drishti", "kspw");
        ini("[broker.alias]\ntype=kafka\nbootstrap-servers=10.0.0.1:9093\nsecurity.protocol=SSL\ntls.ca-file=" + pem("ca.pem", ca)
                + "\ntls.keystore=" + ks + "\ntls.keystore-type=PKCS12\ntls.keystore-password=kspw\ntls.key-password=kspw\ntls.key-alias=drishti\ntls.verify-hostname=false\n");
        Properties p = props("alias");
        assertThat(p.getProperty("ssl.keystore.type")).isEqualTo("PEM");
        assertThat(p.getProperty("ssl.keystore.location")).isNull();
        assertThat(p.getProperty("ssl.endpoint.identification.algorithm")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"PLAIN,org.apache.kafka.common.security.plain.PlainLoginModule", "SCRAM-SHA-256,org.apache.kafka.common.security.scram.ScramLoginModule",
            "SCRAM-SHA-512,org.apache.kafka.common.security.scram.ScramLoginModule"})
    void saslPasswordMechanismsWithThePasswordFromTheSecretsFile(String mechanism, String module) throws Exception {
        ini("[broker.sasl]\ntype=kafka\nbootstrap-servers=k:9094\nsecurity.protocol=SASL_SSL\nsasl.mechanism=" + mechanism + "\nsasl.username=drishti\n"
                + "tls.ca-file=" + pem("ca.pem", ca) + "\n", "[broker.sasl]\nsasl.password=s3cret\n");
        Properties p = props("sasl");
        assertThat(p.getProperty("security.protocol")).isEqualTo("SASL_SSL");
        assertThat(p.getProperty("sasl.mechanism")).isEqualTo(mechanism);
        assertThat(p.getProperty("sasl.jaas.config")).isEqualTo(module + " required username=\"drishti\" password=\"s3cret\";");
        assertThat(SettingsFile.shown("broker.sasl.sasl.password", System.getProperty("broker.sasl.sasl.password"))).isEqualTo(SettingsFile.MASK);
    }

    @Test
    void aSaslPasswordFromItsFileTwinNamedInTheSecretsFile() throws Exception {
        Path pw = dir.resolve("kafka-scram");
        Files.writeString(pw, "from-file\n");
        ini("[broker.scram]\ntype=kafka\nbootstrap-servers=k:9094\nsecurity.protocol=SASL_PLAINTEXT\nsasl.mechanism=SCRAM-SHA-512\nsasl.username=drishti\n",
                "[broker.scram]\nsasl.password-file=" + pw + "\n");
        assertThat(props("scram").getProperty("sasl.jaas.config"))
                .isEqualTo("org.apache.kafka.common.security.scram.ScramLoginModule required username=\"drishti\" password=\"from-file\";");
    }

    @Test
    void oauthBearerClientCredentials() throws Exception {
        ini("[broker.oauth]\ntype=kafka\nbootstrap-servers=k:9095\nsecurity.protocol=SASL_SSL\nsasl.mechanism=OAUTHBEARER\n"
                + "sasl.oauth.token-endpoint=https://idp.example.com/oauth2/token\nsasl.oauth.client-id=drishti\nsasl.oauth.scope=kafka\n",
                "[broker.oauth]\nsasl.oauth.client-secret=cs\n");
        Properties p = props("oauth");
        assertThat(p.getProperty("sasl.mechanism")).isEqualTo("OAUTHBEARER");
        assertThat(p.getProperty("sasl.oauthbearer.token.endpoint.url")).isEqualTo("https://idp.example.com/oauth2/token");
        assertThat(p.getProperty("sasl.login.callback.handler.class"))
                .isEqualTo("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler");
        assertThat(p.getProperty("sasl.jaas.config")).isEqualTo("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required "
                + "clientId=\"drishti\" clientSecret=\"cs\" scope=\"kafka\";");
        assertThat(SettingsFile.isSecret("broker.oauth.sasl.oauth.client-secret")).isTrue();
    }

    @Test
    void kerberosWithAKeytabAndKrb5Conf() throws Exception {
        Path keytab = dir.resolve("drishti.keytab");
        Files.writeString(keytab, "x");
        Path conf = dir.resolve("krb5.conf");
        Files.writeString(conf, "[libdefaults]\n");
        ini("[broker.krb]\ntype=kafka\nbootstrap-servers=k:9096\nsecurity.protocol=SASL_SSL\nsasl.mechanism=GSSAPI\nsasl.kerberos.principal=drishti@BANK.INTERNAL\n"
                + "sasl.kerberos.keytab=" + keytab + "\nsasl.kerberos.service-name=kafka\nsasl.kerberos.krb5-conf=" + conf + "\n");
        System.clearProperty("java.security.krb5.conf");
        Properties p = props("krb");
        assertThat(p.getProperty("sasl.mechanism")).isEqualTo("GSSAPI");
        assertThat(p.getProperty("sasl.kerberos.service.name")).isEqualTo("kafka");
        assertThat(p.getProperty("sasl.jaas.config")).isEqualTo("com.sun.security.auth.module.Krb5LoginModule required useKeyTab=true storeKey=true keyTab=\""
                + keytab + "\" principal=\"drishti@BANK.INTERNAL\";");
        assertThat(System.getProperty("java.security.krb5.conf")).isEqualTo(conf.toString());
    }

    @Test
    void kerberosFromTheTicketCache() throws Exception {
        ini("[broker.kinit]\ntype=kafka\nbootstrap-servers=k:9097\nsecurity.protocol=SASL_PLAINTEXT\nsasl.mechanism=GSSAPI\n"
                + "sasl.kerberos.use-ticket-cache=true\n");
        Properties p = props("kinit");
        assertThat(p.getProperty("security.protocol")).isEqualTo("SASL_PLAINTEXT");
        assertThat(p.getProperty("sasl.jaas.config")).isEqualTo("com.sun.security.auth.module.Krb5LoginModule required useTicketCache=true;");
    }

    @Test
    void confluentCloudWithItsApiKeyAndClientPassThrough() throws Exception {
        ini("[broker.confluent]\ntype=kafka\nflavour=confluent\nbootstrap-servers=pkc-x.us-east-1.aws.confluent.cloud:9092\n"
                + "client.max.poll.records=500\nclient.client.rack=use1-az1\n", "[broker.confluent]\napi-key=KEY\napi-secret=SECRET\n");
        Properties p = props("confluent");
        assertThat(p.getProperty("bootstrap.servers")).isEqualTo("pkc-x.us-east-1.aws.confluent.cloud:9092");
        assertThat(p.getProperty("security.protocol")).isEqualTo("SASL_SSL");
        assertThat(p.getProperty("sasl.mechanism")).isEqualTo("PLAIN");
        assertThat(p.getProperty("sasl.jaas.config"))
                .isEqualTo("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"KEY\" password=\"SECRET\";");
        assertThat(p.getProperty("client.dns.lookup")).isEqualTo("use_all_dns_ips");
        assertThat(p.getProperty("max.poll.records")).isEqualTo("500");
        assertThat(p.getProperty("client.rack")).isEqualTo("use1-az1");
        assertThat(SettingsFile.isSecret("broker.confluent.api-key")).isTrue();
        assertThat(SettingsFile.isSecret("broker.confluent.api-secret")).isTrue();
    }

    @Test
    void twoBrokersInOneFileGiveTwoConnectorsTheirOwnClusters() throws Exception {
        ini("[broker.eu]\ntype=kafka\nbootstrap-servers=eu:9092\n[broker.us]\ntype=kafka\nbootstrap-servers=us:9092\n"
                + "[connector.us-orders]\nbroker=us\n");
        assertThat(KafkaClients.consumer(NamedResources.connector("eu-orders", "kafka", Map.of("broker", "eu")), "eu-orders")
                .getProperty("bootstrap.servers")).isEqualTo("eu:9092");
        assertThat(KafkaClients.consumer(NamedResources.connector("us-orders", "kafka", Map.of("broker", "eu")), "us-orders")
                .getProperty("bootstrap.servers")).as("its connector section rebinds it").isEqualTo("us:9092");
    }
}
