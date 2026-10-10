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

import com.ash.drishti.api.tls.PemReader;
import com.ash.drishti.api.tls.Secrets;
import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Turns the connector's first-class security settings into Kafka client properties, for Apache Kafka and Confluent alike.
 *
 * <p>Settings (all optional): {@code flavour} ({@code apache}, default, or {@code confluent}); {@code security.protocol}
 * ({@code PLAINTEXT}, {@code SSL}, {@code SASL_PLAINTEXT}, {@code SASL_SSL}); {@code tls.*} (the shared module, see
 * {@link TlsSettings}); {@code sasl.mechanism} ({@code PLAIN}, {@code SCRAM-SHA-256}, {@code SCRAM-SHA-512},
 * {@code OAUTHBEARER}, {@code GSSAPI}) with {@code sasl.username} / {@code sasl.password} (Confluent Cloud: {@code api-key} /
 * {@code api-secret}), {@code sasl.oauth.token-endpoint} / {@code .client-id} / {@code .client-secret} / {@code .scope},
 * {@code sasl.kerberos.principal} / {@code .keytab} / {@code .service-name} / {@code .use-ticket-cache} / {@code .krb5-conf}.
 * The protocol is inferred when absent: {@code tls.enabled} and a mechanism give {@code SASL_SSL}, and so on.
 *
 * <p>The properties this produces are the base: {@code client.<property>} settings are applied after them and win.
 */
final class KafkaSecurity {

    private static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    private static final Set<String> MECHANISMS = Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512", "OAUTHBEARER", "GSSAPI");

    /** What was derived: the properties, and the TLS material (null when TLS is not used) for health and expiry. */
    record Result(Map<String, String> properties, TlsMaterial tls) {}

    private KafkaSecurity() {}

    static Result translate(Map<String, String> settings) {
        return translate(settings, System::getenv, Clock.systemUTC());
    }

    static Result translate(Map<String, String> settings, Function<String, String> env, Clock clock) {
        Map<String, String> out = new LinkedHashMap<>();
        String flavour = value(settings, "flavour", env, "apache").toLowerCase(Locale.ROOT);
        if (!flavour.equals("apache") && !flavour.equals("confluent")) {
            throw new TlsException("flavour '" + flavour + "' is not apache or confluent");
        }
        boolean confluent = flavour.equals("confluent");
        if (confluent) {
            out.put("client.dns.lookup", "use_all_dns_ips");
            out.put("request.timeout.ms", "30000");
            out.put("session.timeout.ms", "45000");
            out.put("reconnect.backoff.max.ms", "10000");
        }
        TlsSettings tls = TlsSettings.parse(settings, "tls.", env);
        String mechanism = value(settings, "sasl.mechanism", env, confluent ? "PLAIN" : null);
        mechanism = mechanism == null ? null : mechanism.toUpperCase(Locale.ROOT);
        if (mechanism != null && !MECHANISMS.contains(mechanism)) {
            throw new TlsException("sasl.mechanism '" + mechanism + "' is not one of PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER, GSSAPI");
        }
        String protocol = value(settings, "security.protocol", env, null);
        if (protocol == null) {
            boolean ssl = tls.enabled() || confluent;
            protocol = mechanism != null ? (ssl ? "SASL_SSL" : "SASL_PLAINTEXT") : (ssl ? "SSL" : "PLAINTEXT");
        }
        protocol = protocol.toUpperCase(Locale.ROOT);
        if (!PROTOCOLS.contains(protocol)) {
            throw new TlsException("security.protocol '" + protocol + "' is not one of PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL");
        }
        boolean sasl = protocol.startsWith("SASL");
        boolean useTls = protocol.endsWith("SSL");
        if (sasl && mechanism == null) {
            throw new TlsException("security.protocol " + protocol + " needs sasl.mechanism (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER or GSSAPI)");
        }
        if (!sasl && mechanism != null && settings.containsKey("sasl.mechanism")) {
            throw new TlsException("sasl.mechanism is set but security.protocol is " + protocol + ": use SASL_SSL or SASL_PLAINTEXT");
        }
        if (!useTls && TlsSettings.anyGiven(settings, "tls.") && tls.enabled()) {
            throw new TlsException("tls.enabled is true but security.protocol is " + protocol + ": use SSL or SASL_SSL");
        }
        out.put("security.protocol", protocol);
        TlsMaterial material = null;
        if (useTls) {
            if (tls.insecureTrustAll()) {
                throw new TlsException("tls.insecure-trust-all is not supported for Kafka (the client builds its own TLS); trust the "
                        + "broker's CA with tls.ca-file instead, and use tls.verify-hostname: false if the names do not match");
            }
            material = TlsContexts.build(tls, env, clock);
            tlsProperties(out, tls, material);
        }
        if (sasl) {
            out.put("sasl.mechanism", mechanism);
            saslProperties(out, settings, mechanism, env);
        }
        return new Result(out, material);
    }

    // ---------------------------------------------------------------- TLS

    private static void tlsProperties(Map<String, String> out, TlsSettings tls, TlsMaterial m) {
        out.put("ssl.endpoint.identification.algorithm", tls.verifyHostname() ? "https" : "");
        out.put("ssl.enabled.protocols", String.join(",", tls.protocols()));
        out.put("ssl.protocol", tls.protocols().contains("TLSv1.3") ? "TLSv1.3" : "TLSv1.2");
        if (!tls.cipherSuites().isEmpty()) {
            out.put("ssl.cipher.suites", String.join(",", tls.cipherSuites()));
        }
        // trust: nothing for the JVM default, the store itself when it is the only source, otherwise PEM text of the merged set
        boolean onlyStore = tls.truststore() != null && tls.caFile() == null && !tls.trustJvmDefault();
        if (onlyStore) {
            out.put("ssl.truststore.location", tls.truststore());
            if (tls.truststorePassword() != null) {
                out.put("ssl.truststore.password", tls.truststorePassword());
            }
            if (tls.truststoreType() != null) {
                out.put("ssl.truststore.type", tls.truststoreType().toUpperCase(Locale.ROOT));
            }
        } else if (!m.trusted().isEmpty()) {
            List<X509Certificate> all = new ArrayList<>(m.trusted());
            if (m.usesJvmDefaultTrust()) {
                all.addAll(jvmDefaultAuthorities());
            }
            out.put("ssl.truststore.type", "PEM");
            out.put("ssl.truststore.certificates", pem(all));
        }
        // identity
        if (tls.keystore() != null && tls.keyAlias() == null) {
            out.put("ssl.keystore.location", tls.keystore());
            if (tls.keystorePassword() != null) {
                out.put("ssl.keystore.password", tls.keystorePassword());
            }
            if (tls.keystoreType() != null) {
                out.put("ssl.keystore.type", tls.keystoreType().toUpperCase(Locale.ROOT));
            }
            if (tls.keyPassword() != null) {
                out.put("ssl.key.password", tls.keyPassword());
            }
        } else if (m.clientKey() != null) {
            // PEM files, or one alias of a store: Kafka's PEM keystore takes a PKCS#8 key and the chain as text
            out.put("ssl.keystore.type", "PEM");
            out.put("ssl.keystore.certificate.chain", pem(m.clientChain()));
            out.put("ssl.keystore.key", PemReader.pem("PRIVATE KEY", m.clientKey().getEncoded()));
        }
    }

    private static String pem(List<X509Certificate> certs) {
        StringBuilder sb = new StringBuilder();
        for (X509Certificate c : certs) {
            try {
                sb.append(PemReader.pem("CERTIFICATE", c.getEncoded()));
            } catch (java.security.cert.CertificateEncodingException e) {
                throw new TlsException("cannot encode a certificate as PEM (" + e.getMessage() + ")", e);
            }
        }
        return sb.toString();
    }

    private static List<X509Certificate> jvmDefaultAuthorities() {
        try {
            TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            f.init((KeyStore) null);
            List<X509Certificate> out = new ArrayList<>();
            for (TrustManager tm : f.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) {
                    out.addAll(List.of(x.getAcceptedIssuers()));
                }
            }
            return out;
        } catch (java.security.GeneralSecurityException e) {
            throw new TlsException("cannot read the JVM's trusted authorities (" + e.getMessage() + ")", e);
        }
    }

    // ---------------------------------------------------------------- SASL

    private static void saslProperties(Map<String, String> out, Map<String, String> settings, String mechanism, Function<String, String> env) {
        switch (mechanism) {
            case "PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512" -> {
                String user = first(settings, env, "sasl.username", "api-key");
                String pass = firstSecret(settings, env, "sasl.password", "api-secret");
                if (user == null || pass == null) {
                    throw new TlsException("sasl.mechanism " + mechanism + " needs sasl.username and sasl.password"
                            + " (or api-key and api-secret; use ${ENV} placeholders or sasl.password-file)");
                }
                String module = mechanism.equals("PLAIN") ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                        : "org.apache.kafka.common.security.scram.ScramLoginModule";
                out.put("sasl.jaas.config", module + " required username=" + q(user) + " password=" + q(pass) + ";");
            }
            case "OAUTHBEARER" -> {
                String endpoint = Secrets.get(settings, "sasl.oauth.token-endpoint", env);
                String id = Secrets.get(settings, "sasl.oauth.client-id", env);
                String secret = Secrets.secret(settings, "sasl.oauth.client-secret", env);
                if (endpoint == null || id == null || secret == null) {
                    throw new TlsException("sasl.mechanism OAUTHBEARER needs sasl.oauth.token-endpoint, sasl.oauth.client-id and "
                            + "sasl.oauth.client-secret (client-credentials flow)");
                }
                String scope = Secrets.get(settings, "sasl.oauth.scope", env);
                out.put("sasl.oauthbearer.token.endpoint.url", endpoint);
                out.put("sasl.login.callback.handler.class", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler");
                out.put("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId=" + q(id)
                        + " clientSecret=" + q(secret) + (scope == null ? "" : " scope=" + q(scope)) + ";");
                allowUrl(endpoint);
            }
            case "GSSAPI" -> {
                String principal = Secrets.get(settings, "sasl.kerberos.principal", env);
                String keytab = Secrets.get(settings, "sasl.kerberos.keytab", env);
                boolean ticketCache = Boolean.parseBoolean(Secrets.get(settings, "sasl.kerberos.use-ticket-cache", env) == null ? "false"
                        : Secrets.get(settings, "sasl.kerberos.use-ticket-cache", env));
                if (!ticketCache && (principal == null || keytab == null)) {
                    throw new TlsException("sasl.mechanism GSSAPI needs sasl.kerberos.principal and sasl.kerberos.keytab "
                            + "(or sasl.kerberos.use-ticket-cache: true after kinit)");
                }
                if (keytab != null && !java.nio.file.Files.isReadable(java.nio.file.Path.of(keytab))) {
                    throw new TlsException("sasl.kerberos.keytab '" + keytab + "': file not found or not readable");
                }
                out.put("sasl.kerberos.service.name", value(settings, "sasl.kerberos.service-name", env, "kafka"));
                out.put("sasl.jaas.config", "com.sun.security.auth.module.Krb5LoginModule required"
                        + (ticketCache ? " useTicketCache=true" : " useKeyTab=true storeKey=true keyTab=" + q(keytab))
                        + (principal == null ? "" : " principal=" + q(principal)) + ";");
                String krb5 = Secrets.get(settings, "sasl.kerberos.krb5-conf", env);
                if (krb5 != null && System.getProperty("java.security.krb5.conf") == null) {
                    System.setProperty("java.security.krb5.conf", krb5);     // the JVM reads one krb5.conf: the first connector's wins
                }
            }
            default -> throw new TlsException("unsupported sasl.mechanism " + mechanism);
        }
    }

    /** Kafka 3.9.1+ refuses a token endpoint that is not on this allow list (a JVM-wide property). */
    private static void allowUrl(String endpoint) {
        String key = "org.apache.kafka.sasl.oauthbearer.allowed.urls";
        String cur = System.getProperty(key);
        if (cur == null || cur.isBlank()) {
            System.setProperty(key, endpoint);
        } else if (!List.of(cur.split(",")).contains(endpoint)) {
            System.setProperty(key, cur + "," + endpoint);
        }
    }

    /** A JAAS option value: quoted, with backslash and quote escaped. */
    static String q(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String value(Map<String, String> settings, String key, Function<String, String> env, String fallback) {
        String v = Secrets.get(settings, key, env);
        return v == null ? fallback : v;
    }

    private static String first(Map<String, String> settings, Function<String, String> env, String... keys) {
        for (String k : keys) {
            String v = Secrets.get(settings, k, env);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String firstSecret(Map<String, String> settings, Function<String, String> env, String... keys) {
        for (String k : keys) {
            String v = Secrets.secret(settings, k, env);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
