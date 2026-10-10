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
