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
package com.ash.drishti.api.tls;

import com.ash.drishti.api.SettingSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The TLS settings of one connector, read from its settings under a prefix (normally {@code tls.}). Every connector reads
 * the same keys, so an operator learns them once ({@code docs/connectors/TLS.md}).
 *
 * <p>Keys (all optional; {@code enabled} defaults to false):
 * <ul>
 *   <li>{@code enabled}: use TLS.
 *   <li>Trust: {@code ca-file} (a PEM bundle of one or many certificates; a path, or the PEM text itself),
 *       {@code truststore} + {@code truststore-password} (+ {@code truststore-type}: PKCS12 or JKS, detected when absent),
 *       {@code trust-jvm-default} (default true only when neither of the others is given; true beside them merges the JVM's
 *       trusted authorities into the set).
 *   <li>Client identity (mutual TLS): {@code cert-file} (PEM certificate chain) + {@code key-file} (PEM private key: PKCS#8,
 *       PKCS#1 RSA, SEC1 EC, or encrypted PKCS#8 with {@code key-password}); or {@code keystore} + {@code keystore-password}
 *       (+ {@code keystore-type}, {@code key-alias}, {@code key-password}).
 *   <li>{@code protocols} (default {@code TLSv1.3,TLSv1.2}), {@code cipher-suites} (default: the JVM's),
 *       {@code verify-hostname} (default true), {@code insecure-trust-all} (development only; refused unless the environment
 *       variable {@code DRISHTI_ALLOW_INSECURE_TLS} is {@code true}).
 * </ul>
 * A password is never written in a document: use a {@code ${ENV}} placeholder, or the {@code -file} twin of the key
 * ({@code keystore-password-file: /run/secrets/ks}), which reads the first line of the file.
 *
 * @param prefix the prefix the values were read under, kept for error messages
 */
public record TlsSettings(
        String prefix,
        boolean enabled,
        String caFile,
        String truststore,
        String truststorePassword,
        String truststoreType,
        boolean trustJvmDefault,
        String certFile,
        String keyFile,
        String keystore,
        String keystorePassword,
        String keystoreType,
        String keyAlias,
        String keyPassword,
        List<String> protocols,
        List<String> cipherSuites,
        boolean verifyHostname,
        boolean insecureTrustAll) {

    public static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");
    public static final String ALLOW_INSECURE_ENV = "DRISHTI_ALLOW_INSECURE_TLS";

    /** TLS off. */
    public static TlsSettings disabled() {
        return parse(Map.of(), "tls.");
    }

    /** Reads the settings under {@code tls.}, with the process environment. */
    public static TlsSettings from(Map<String, String> settings) {
        return parse(settings, "tls.");
    }

    public static TlsSettings parse(Map<String, String> settings, String prefix) {
        return parse(settings, prefix, System::getenv);
    }

    public static TlsSettings parse(Map<String, String> settings, String prefix, Function<String, String> env) {
        Function<String, String> get = key -> {
            String v = settings.get(prefix + key);
            return v == null || v.isBlank() ? null : Secrets.expand(v.strip(), env, prefix + key);
        };
        boolean enabled = bool(get.apply("enabled"), false, prefix + "enabled");
        String ca = get.apply("ca-file");
        String ts = get.apply("truststore");
        boolean trustJvm = bool(get.apply("trust-jvm-default"), ca == null && ts == null, prefix + "trust-jvm-default");
        String ks = get.apply("keystore");
        String keyPassword = Secrets.secret(get, prefix, "key-password");
        return new TlsSettings(prefix, enabled, ca, ts, Secrets.secret(get, prefix, "truststore-password"),
                get.apply("truststore-type"), trustJvm, get.apply("cert-file"), get.apply("key-file"), ks,
                Secrets.secret(get, prefix, "keystore-password"), get.apply("keystore-type"), get.apply("key-alias"),
                keyPassword, list(get.apply("protocols"), DEFAULT_PROTOCOLS), list(get.apply("cipher-suites"), List.of()),
                bool(get.apply("verify-hostname"), true, prefix + "verify-hostname"),
                bool(get.apply("insecure-trust-all"), false, prefix + "insecure-trust-all"));
    }

    /**
     * The keys {@link #parse} reads, as form/validator specs under {@code prefix}. The connector settings catalogue takes its
     * {@code tls.*} section from here, so the Admin form cannot drift from what the code reads (a test compares this list with
     * the record's components). Passwords are secrets, each with its {@code -file} twin.
     */
    public static List<SettingSpec> settingSpecs(String prefix) {
        String g = "tls";
        List<SettingSpec> out = new ArrayList<>();
        out.add(new SettingSpec(prefix + "enabled", "boolean", false, "false",
                "Use TLS. A connector whose address already says so (amqps://, ssl://) uses it without this; true against a plain address is a start-up error", false, g));
        out.add(new SettingSpec(prefix + "ca-file", "path", false, null,
                "Trust: a PEM file of one or many CA certificates, or the PEM text itself", false, g));
        out.add(new SettingSpec(prefix + "truststore", "path", false, null, "Trust: a PKCS12 or JKS truststore", false, g));
        out.add(new SettingSpec(prefix + "truststore-password", "string", false, null,
                "Truststore password: ${ENV} placeholder, or use truststore-password-file", true, g));
        out.add(new SettingSpec(prefix + "truststore-password-file", "path", false, null,
                "File whose first line is the truststore password", false, g));
        out.add(new SettingSpec(prefix + "truststore-type", "enum:PKCS12|JKS", false, "detected", "Truststore format", false, g));
        out.add(new SettingSpec(prefix + "trust-jvm-default", "boolean", false, "true when no ca-file or truststore",
                "Also trust the JVM's public authorities (true beside ca-file or truststore merges them)", false, g));
        out.add(new SettingSpec(prefix + "cert-file", "path", false, null,
                "Client identity (mutual TLS): PEM certificate chain, leaf first", false, g));
        out.add(new SettingSpec(prefix + "key-file", "path", false, null,
                "Client identity: PEM private key (PKCS#8, PKCS#1 RSA, SEC1 EC, or encrypted PKCS#8)", false, g));
        out.add(new SettingSpec(prefix + "key-password", "string", false, null,
                "Password of an encrypted PEM key or of the key in a keystore: ${ENV} placeholder, or key-password-file", true, g));
        out.add(new SettingSpec(prefix + "key-password-file", "path", false, null, "File whose first line is the key password", false, g));
        out.add(new SettingSpec(prefix + "keystore", "path", false, null,
                "Client identity: a PKCS12 or JKS keystore (use this or cert-file with key-file)", false, g));
        out.add(new SettingSpec(prefix + "keystore-password", "string", false, null,
                "Keystore password: ${ENV} placeholder, or use keystore-password-file", true, g));
        out.add(new SettingSpec(prefix + "keystore-password-file", "path", false, null,
                "File whose first line is the keystore password", false, g));
        out.add(new SettingSpec(prefix + "keystore-type", "enum:PKCS12|JKS", false, "detected", "Keystore format", false, g));
        out.add(new SettingSpec(prefix + "key-alias", "string", false, "the only key", "Which key of the keystore to present", false, g));
        out.add(new SettingSpec(prefix + "protocols", "list", false, "TLSv1.3,TLSv1.2", "Protocol versions offered", false, g));
        out.add(new SettingSpec(prefix + "cipher-suites", "list", false, "the JVM's", "Cipher suites offered", false, g));
        out.add(new SettingSpec(prefix + "verify-hostname", "boolean", false, "true",
                "Check the server's name against its certificate (false logs a warning at every start)", false, g));
        out.add(new SettingSpec(prefix + "insecure-trust-all", "boolean", false, "false",
                "Do not check the server certificate; development only, refused unless DRISHTI_ALLOW_INSECURE_TLS=true", false, g));
        return List.copyOf(out);
    }

    /** True when any key is given under the prefix, whether or not {@code enabled} is set. */
    public static boolean anyGiven(Map<String, String> settings, String prefix) {
        return settings.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    private static boolean bool(String v, boolean fallback, String key) {
        if (v == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return Boolean.parseBoolean(v);
        }
        throw new TlsException(key + ": '" + v + "' is not true or false");
    }

    private static List<String> list(String v, List<String> fallback) {
        if (v == null) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (String s : v.split("[,\\s]+")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out.isEmpty() ? fallback : List.copyOf(out);
    }

    /** True when a client identity (mutual TLS) is configured, from PEM files or a keystore. */
    public boolean hasClientIdentity() {
        return certFile != null || keyFile != null || keystore != null;
    }

    @Override
    public String toString() {
        return "TlsSettings[prefix=" + prefix + ", enabled=" + enabled + ", ca-file=" + caFile + ", truststore=" + truststore
                + ", cert-file=" + certFile + ", key-file=" + keyFile + ", keystore=" + keystore + ", protocols=" + protocols
                + ", verify-hostname=" + verifyHostname + ", insecure-trust-all=" + insecureTrustAll + "]";   // never the passwords
    }
}
