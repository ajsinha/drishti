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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.testkit.TestPki;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Every combination of the first-class security settings, and what each becomes as Kafka client properties. */
class KafkaSecurityTest {

    static TestPki.Identity ca;
    static TestPki.Identity client;
    @TempDir
    Path dir;
    Path caPem;

    @BeforeAll
    static void pki() {
        ca = TestPki.ca("kafka-test-ca");
        client = TestPki.issue(ca, "drishti", "RSA", 365);
    }

    Map<String, String> m(String... kv) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    Map<String, String> props(Map<String, String> settings) {
        return KafkaSecurity.translate(settings, k -> switch (k) {
            case "KAFKA_KEY" -> "the-key";
            case "KAFKA_SECRET" -> "the-secret";
            default -> null;
        }, Clock.systemUTC()).properties();
    }

    Path ca() throws IOException {
        if (caPem == null) {
            caPem = dir.resolve("ca.pem");
            ca.writeCertPem(caPem);
        }
        return caPem;
    }

    // ---------------------------------------------------------------- protocol

    @Test
    void nothingGivenIsPlaintextAndAddsNothingElse() throws Exception {
        assertThat(props(m())).containsOnly(Map.entry("security.protocol", "PLAINTEXT"));
    }

    @ParameterizedTest
    @CsvSource({"tls.enabled,true,,SSL", "tls.enabled,true,PLAIN,SASL_SSL", "x,y,PLAIN,SASL_PLAINTEXT", "x,y,,PLAINTEXT"})
    void theProtocolIsInferredFromTlsAndTheMechanism(String k, String v, String mechanism, String expected) throws Exception {
        Map<String, String> s = m(k, v, "sasl.username", "u", "sasl.password", "p");
        if (mechanism != null && !mechanism.isEmpty()) {
            s.put("sasl.mechanism", mechanism);
        }
        assertThat(props(s)).containsEntry("security.protocol", expected);
    }

    @Test
    void anExplicitProtocolIsChecked() throws Exception {
        assertThatThrownBy(() -> props(m("security.protocol", "TLS"))).isInstanceOf(TlsException.class).hasMessageContaining("is not one of");
        assertThatThrownBy(() -> props(m("security.protocol", "SASL_SSL"))).isInstanceOf(TlsException.class).hasMessageContaining("needs sasl.mechanism");
        assertThatThrownBy(() -> props(m("security.protocol", "SSL", "sasl.mechanism", "PLAIN"))).isInstanceOf(TlsException.class)
                .hasMessageContaining("use SASL_SSL or SASL_PLAINTEXT");
        assertThatThrownBy(() -> props(m("security.protocol", "PLAINTEXT", "tls.enabled", "true"))).isInstanceOf(TlsException.class)
                .hasMessageContaining("use SSL or SASL_SSL");
        assertThatThrownBy(() -> props(m("security.protocol", "SASL_SSL", "sasl.mechanism", "MD5"))).isInstanceOf(TlsException.class)
                .hasMessageContaining("is not one of PLAIN");
        assertThatThrownBy(() -> props(m("flavour", "redpanda"))).isInstanceOf(TlsException.class).hasMessageContaining("apache or confluent");
    }

    // ---------------------------------------------------------------- TLS trust

    @Test
    void onlyTheJvmDefaultTrustSetsNoTruststore() throws Exception {
        Map<String, String> p = props(m("security.protocol", "SSL"));
        assertThat(p).containsEntry("ssl.endpoint.identification.algorithm", "https").containsEntry("ssl.enabled.protocols", "TLSv1.3,TLSv1.2")
                .containsEntry("ssl.protocol", "TLSv1.3").doesNotContainKey("ssl.truststore.type").doesNotContainKey("ssl.keystore.type");
    }

    @Test
    void aPemCaBundleBecomesPemTruststoreCertificates() throws Exception {
        TestPki.Identity other = TestPki.ca("other");
        Path bundle = dir.resolve("bundle.pem");
        Files.writeString(bundle, Files.readString(ca()) + TestPki.pem("CERTIFICATE", other.cert().getEncoded()));
        Map<String, String> p = props(m("security.protocol", "SSL", "tls.ca-file", bundle.toString()));
        assertThat(p).containsEntry("ssl.truststore.type", "PEM");
        assertThat(count(p.get("ssl.truststore.certificates"), "BEGIN CERTIFICATE")).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"PKCS12", "JKS"})
    void aTruststoreIsHandedToKafkaAsItIs(String type) throws Exception {
        Path ts = dir.resolve("trust." + type);
        TestPki.writeTruststore(ts, type, "tspw", ca.cert());
        Map<String, String> p = props(m("security.protocol", "SSL", "tls.truststore", ts.toString(), "tls.truststore-password", "tspw", "tls.truststore-type", type));
        assertThat(p).containsEntry("ssl.truststore.location", ts.toString()).containsEntry("ssl.truststore.password", "tspw")
                .containsEntry("ssl.truststore.type", type).doesNotContainKey("ssl.truststore.certificates");
    }

    @Test
    void pemAndTruststoreAndTheJvmDefaultAreMergedIntoOnePemSet() throws Exception {
        Path ts = dir.resolve("t.p12");
        TestPki.writeTruststore(ts, "PKCS12", "pw", TestPki.ca("second").cert());
        Map<String, String> both = props(m("security.protocol", "SSL", "tls.ca-file", ca().toString(), "tls.truststore", ts.toString(), "tls.truststore-password", "pw"));
        assertThat(both).containsEntry("ssl.truststore.type", "PEM").doesNotContainKey("ssl.truststore.location");
        assertThat(count(both.get("ssl.truststore.certificates"), "BEGIN CERTIFICATE")).isEqualTo(2);
        Map<String, String> plusJvm = props(m("security.protocol", "SSL", "tls.ca-file", ca().toString(), "tls.trust-jvm-default", "true"));
        assertThat(count(plusJvm.get("ssl.truststore.certificates"), "BEGIN CERTIFICATE")).isGreaterThan(10);
    }

    // ---------------------------------------------------------------- TLS identity

    @ParameterizedTest
    @EnumSource(value = TestPki.KeyFormat.class, names = {"PKCS8", "PKCS1_RSA", "ENCRYPTED_PKCS8"})
    void aPemIdentityOfAnyKeyFormatBecomesAPkcs8PemKeystore(TestPki.KeyFormat format) throws Exception {
        Path cert = dir.resolve("c-" + format + ".pem");
        Path key = dir.resolve("k-" + format + ".pem");
        client.writeCertPem(cert);
        client.writeKeyPem(key, format, "kpw");
        Map<String, String> s = m("security.protocol", "SSL", "tls.ca-file", ca().toString(), "tls.cert-file", cert.toString(), "tls.key-file", key.toString());
        if (format == TestPki.KeyFormat.ENCRYPTED_PKCS8) {
            s.put("tls.key-password", "kpw");
        }
        Map<String, String> p = props(s);
        assertThat(p).containsEntry("ssl.keystore.type", "PEM");
        assertThat(p.get("ssl.keystore.key")).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(count(p.get("ssl.keystore.certificate.chain"), "BEGIN CERTIFICATE")).isEqualTo(2);
        assertThat(p).doesNotContainKey("ssl.key.password");
    }

    @ParameterizedTest
    @CsvSource({"PKCS12", "JKS"})
    void aKeystoreIsHandedToKafkaAsItIs(String type) throws Exception {
        Path ks = dir.resolve("client." + type);
        client.writeKeystore(ks, type, "kspw", "drishti", "keypw");
        Map<String, String> p = props(m("security.protocol", "SSL", "tls.keystore", ks.toString(), "tls.keystore-password", "kspw",
                "tls.key-password", "keypw", "tls.keystore-type", type));
        assertThat(p).containsEntry("ssl.keystore.location", ks.toString()).containsEntry("ssl.keystore.password", "kspw")
                .containsEntry("ssl.key.password", "keypw").containsEntry("ssl.keystore.type", type);
        // a chosen alias cannot be expressed in Kafka's own keystore settings: it is handed over as PEM
        Map<String, String> alias = props(m("security.protocol", "SSL", "tls.keystore", ks.toString(), "tls.keystore-password", "kspw",
                "tls.key-password", "keypw", "tls.key-alias", "drishti"));
        assertThat(alias).containsEntry("ssl.keystore.type", "PEM").doesNotContainKey("ssl.keystore.location");
    }

    @Test
    void hostnameVerificationProtocolsAndCiphersAreTranslated() throws Exception {
        Map<String, String> p = props(m("security.protocol", "SSL", "tls.verify-hostname", "false", "tls.protocols", "TLSv1.2",
                "tls.cipher-suites", "TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256"));
        assertThat(p).containsEntry("ssl.endpoint.identification.algorithm", "").containsEntry("ssl.enabled.protocols", "TLSv1.2")
                .containsEntry("ssl.protocol", "TLSv1.2").containsEntry("ssl.cipher.suites", "TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256");
    }

    @Test
    void trustAllIsNotOfferedForKafkaAndBadFilesAreNamed() throws Exception {
        assertThatThrownBy(() -> props(m("security.protocol", "SSL", "tls.insecure-trust-all", "true"))).isInstanceOf(TlsException.class)
                .hasMessageContaining("not supported for Kafka");
        assertThatThrownBy(() -> props(m("security.protocol", "SSL", "tls.ca-file", dir.resolve("nope.pem").toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("nope.pem").hasMessageContaining("file not found");
    }

    // ---------------------------------------------------------------- SASL

    @ParameterizedTest
    @CsvSource({"PLAIN,org.apache.kafka.common.security.plain.PlainLoginModule", "SCRAM-SHA-256,org.apache.kafka.common.security.scram.ScramLoginModule",
            "SCRAM-SHA-512,org.apache.kafka.common.security.scram.ScramLoginModule"})
    void passwordMechanismsBuildTheJaasLoginWithEnvironmentSecrets(String mechanism, String module) throws Exception {
        Map<String, String> p = props(m("security.protocol", "SASL_SSL", "sasl.mechanism", mechanism, "sasl.username", "${KAFKA_KEY}", "sasl.password", "${KAFKA_SECRET}"));
        assertThat(p).containsEntry("sasl.mechanism", mechanism)
                .containsEntry("sasl.jaas.config", module + " required username=\"the-key\" password=\"the-secret\";");
    }

    @Test
    void aPasswordIsEscapedAndCanComeFromAFile() throws Exception {
        Path f = dir.resolve("pw");
        Files.writeString(f, "pa\"ss\\word\n");
        Map<String, String> p = props(m("security.protocol", "SASL_PLAINTEXT", "sasl.mechanism", "PLAIN", "sasl.username", "u", "sasl.password-file", f.toString()));
        assertThat(p.get("sasl.jaas.config")).endsWith("username=\"u\" password=\"pa\\\"ss\\\\word\";");
        assertThatThrownBy(() -> props(m("security.protocol", "SASL_PLAINTEXT", "sasl.mechanism", "PLAIN", "sasl.username", "u")))
                .isInstanceOf(TlsException.class).hasMessageContaining("needs sasl.username and sasl.password");
    }

    @Test
    void oauthBearerUsesTheClientCredentialsHandler() throws Exception {
        Map<String, String> p = props(m("security.protocol", "SASL_SSL", "sasl.mechanism", "OAUTHBEARER",
                "sasl.oauth.token-endpoint", "https://idp.example.com/oauth2/token", "sasl.oauth.client-id", "${KAFKA_KEY}",
                "sasl.oauth.client-secret", "${KAFKA_SECRET}", "sasl.oauth.scope", "kafka"));
        assertThat(p).containsEntry("sasl.oauthbearer.token.endpoint.url", "https://idp.example.com/oauth2/token")
                .containsEntry("sasl.login.callback.handler.class", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler")
                .containsEntry("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required "
                        + "clientId=\"the-key\" clientSecret=\"the-secret\" scope=\"kafka\";");
        assertThat(System.getProperty("org.apache.kafka.sasl.oauthbearer.allowed.urls")).contains("https://idp.example.com/oauth2/token");
        assertThatThrownBy(() -> props(m("security.protocol", "SASL_SSL", "sasl.mechanism", "OAUTHBEARER"))).isInstanceOf(TlsException.class)
                .hasMessageContaining("sasl.oauth.token-endpoint");
    }

    @Test
    void kerberosUsesAKeytabOrTheTicketCache() throws Exception {
        Path keytab = dir.resolve("drishti.keytab");
        Files.writeString(keytab, "x");
        Map<String, String> p = props(m("security.protocol", "SASL_SSL", "sasl.mechanism", "GSSAPI", "sasl.kerberos.principal", "drishti@EXAMPLE.COM",
                "sasl.kerberos.keytab", keytab.toString()));
        assertThat(p).containsEntry("sasl.kerberos.service.name", "kafka").containsEntry("sasl.jaas.config",
                "com.sun.security.auth.module.Krb5LoginModule required useKeyTab=true storeKey=true keyTab=\"" + keytab
                        + "\" principal=\"drishti@EXAMPLE.COM\";");
        Map<String, String> cache = props(m("security.protocol", "SASL_PLAINTEXT", "sasl.mechanism", "GSSAPI", "sasl.kerberos.use-ticket-cache", "true",
                "sasl.kerberos.service-name", "kfk"));
        assertThat(cache).containsEntry("sasl.kerberos.service.name", "kfk");
        assertThat(cache.get("sasl.jaas.config")).contains("useTicketCache=true");
        assertThatThrownBy(() -> props(m("security.protocol", "SASL_SSL", "sasl.mechanism", "GSSAPI", "sasl.kerberos.principal", "p",
                "sasl.kerberos.keytab", dir.resolve("missing.keytab").toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("missing.keytab");
        assertThatThrownBy(() -> props(m("security.protocol", "SASL_SSL", "sasl.mechanism", "GSSAPI"))).isInstanceOf(TlsException.class)
                .hasMessageContaining("sasl.kerberos.principal and sasl.kerberos.keytab");
    }

    // ---------------------------------------------------------------- Confluent

    @Test
    void theConfluentPresetIsSaslSslPlainWithApiKeysAndSensibleTimeouts() throws Exception {
        Map<String, String> p = props(m("flavour", "confluent", "api-key", "${KAFKA_KEY}", "api-secret", "${KAFKA_SECRET}"));
        assertThat(p).containsEntry("security.protocol", "SASL_SSL").containsEntry("sasl.mechanism", "PLAIN")
                .containsEntry("client.dns.lookup", "use_all_dns_ips").containsEntry("request.timeout.ms", "30000")
                .containsEntry("session.timeout.ms", "45000").containsEntry("ssl.endpoint.identification.algorithm", "https")
                .containsEntry("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"the-key\" password=\"the-secret\";");
        // Confluent Platform with mutual TLS instead of API keys
        Map<String, String> onPrem = props(m("flavour", "confluent", "security.protocol", "SSL", "tls.ca-file", ca().toString()));
        assertThat(onPrem).containsEntry("security.protocol", "SSL").doesNotContainKey("sasl.mechanism").doesNotContainKey("sasl.jaas.config");
    }

    // ---------------------------------------------------------------- precedence

    @Test
    void clientPropertiesWinOverEverythingTranslated() throws Exception {
        Map<String, String> settings = m("bootstrap-servers", "b1:9093", "security.protocol", "SSL", "tls.verify-hostname", "false",
                "client.ssl.endpoint.identification.algorithm", "https", "client.security.protocol", "SASL_SSL", "client.max.poll.records", "7");
        Properties p = KafkaSourcePlugin.consumerProperties(settings, "orders", KafkaSecurity.translate(settings));
        assertThat(p.getProperty("ssl.endpoint.identification.algorithm")).isEqualTo("https");
        assertThat(p.getProperty("security.protocol")).isEqualTo("SASL_SSL");
        assertThat(p.getProperty("max.poll.records")).isEqualTo("7");
        assertThat(p.getProperty("bootstrap.servers")).isEqualTo("b1:9093");
        assertThat(p.getProperty("client.id")).isEqualTo("drishti-orders");
    }

    @Test
    void theKafkaClientAcceptsTheTranslatedProperties() throws Exception {
        // the consumer config validates every key and value it is given; nothing connects (no bootstrap resolves)
        Path cert = dir.resolve("c.pem");
        Path key = dir.resolve("k.pem");
        client.writeCertPem(cert);
        client.writeKeyPem(key, TestPki.KeyFormat.PKCS8, null);
        Map<String, String> settings = m("bootstrap-servers", "kafka.invalid:9093", "security.protocol", "SASL_SSL", "sasl.mechanism", "SCRAM-SHA-512",
                "sasl.username", "u", "sasl.password", "p", "tls.ca-file", ca().toString(), "tls.cert-file", cert.toString(), "tls.key-file", key.toString());
        Properties p = KafkaSourcePlugin.consumerProperties(settings, "t", KafkaSecurity.translate(settings));
        var cfg = new org.apache.kafka.clients.consumer.ConsumerConfig(p);
        assertThat(cfg.getString("security.protocol")).isEqualTo("SASL_SSL");
        assertThat(cfg.getString("ssl.keystore.type")).isEqualTo("PEM");
        assertThat(cfg.getPassword("ssl.keystore.key").value()).contains("BEGIN PRIVATE KEY");
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
