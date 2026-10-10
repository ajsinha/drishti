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
package com.ash.drishti.plugin.aerospike;

import com.aerospike.client.Host;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.TlsPolicy;
import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import java.util.Map;
import java.util.function.Function;

/**
 * Aerospike over TLS with the shared {@code tls.*} settings ({@link TlsSettings}). TLS is on when {@code tls.enabled: true}
 * (an Aerospike address does not say so); {@code tls.*} keys without it are a start-up error. Aerospike names the server
 * certificate with a <i>TLS name</i> (the {@code tls-name} of the server's {@code network.tls} section), which the client checks
 * against the certificate: {@code tls-name} sets it for every host; when it is absent and {@code tls.verify-hostname} is true,
 * each host's own name is used; with {@code tls.verify-hostname: false} none is given and the name is not checked. A host in
 * {@code hosts} may carry its own as {@code host:tls-name:port}. Mutual TLS (the server's {@code mode: mutual}, certificate
 * log-in with {@code authMode: pki}) uses {@code tls.cert-file} + {@code tls.key-file} or {@code tls.keystore}; with the
 * {@code pki} auth mode, leave {@code user} and {@code password} out.
 *
 * <p>TLS in the server is an Enterprise Edition feature.
 */
final class AerospikeTls {

    /** The material (null when TLS is off) and the TLS name every host gets (null: each host's own name). */
    record Plan(TlsMaterial tls, String tlsName, boolean pki) {}

    private AerospikeTls() {}

    static Plan plan(Map<String, String> settings, Function<String, String> env) {
        TlsSettings ts = TlsSettings.parse(settings, "tls.", env);
        String name = settings.get("tls-name");
        boolean named = name != null && !name.isBlank();
        String mode = settings.getOrDefault("auth-mode", "").strip().toLowerCase(java.util.Locale.ROOT);
        if (!mode.isEmpty() && !mode.equals("pki") && !mode.equals("internal") && !mode.equals("external")) {
            throw new TlsException("auth-mode '" + settings.get("auth-mode") + "' is not internal, external or pki");
        }
        boolean pki = mode.equals("pki");
        if (!ts.enabled()) {
            if (TlsSettings.anyGiven(settings, "tls.") || named) {
                throw new TlsException("tls.* (or tls-name) is set but tls.enabled is not true: set tls.enabled: true to connect over TLS");
            }
            if (pki) {
                throw new TlsException("auth-mode: pki logs in with a client certificate: set tls.enabled: true and tls.cert-file with tls.key-file, or tls.keystore");
            }
            return new Plan(null, null, false);
        }
        TlsMaterial m = TlsContexts.build(ts, env, java.time.Clock.systemUTC());
        if (pki && m.clientKey() == null) {
            throw new TlsException("auth-mode: pki logs in with a client certificate: set tls.cert-file and tls.key-file, or tls.keystore");
        }
        return new Plan(m, named ? name.strip() : null, pki);
    }

    /** Gives the client policy the module's context, protocol versions and cipher suites. */
    static void apply(ClientPolicy policy, Plan plan) {
        if (plan.tls() == null) {
            return;
        }
        TlsPolicy p = new TlsPolicy();
        p.context = plan.tls().sslContext();
        p.protocols = plan.tls().sslParameters().getProtocols();
        if (plan.tls().sslParameters().getCipherSuites() != null) {
            p.ciphers = plan.tls().sslParameters().getCipherSuites();
        }
        policy.tlsPolicy = p;
    }

    /** Applies {@code auth-mode} (also without TLS: {@code external} and {@code internal} are password log-ins). */
    static void applyAuthMode(ClientPolicy policy, Map<String, String> settings, Plan plan) {
        String mode = settings.getOrDefault("auth-mode", "").strip().toLowerCase(java.util.Locale.ROOT);
        switch (mode) {
            case "pki" -> policy.authMode = com.aerospike.client.policy.AuthMode.PKI;
            case "external" -> policy.authMode = com.aerospike.client.policy.AuthMode.EXTERNAL;
            case "internal" -> policy.authMode = com.aerospike.client.policy.AuthMode.INTERNAL;
            default -> {
                // the client's default (internal)
            }
        }
    }

    /** The seed hosts, each with its TLS name when TLS is on (see the class comment). */
    static Host[] hosts(String spec, Plan plan) {
        Host[] parsed = Host.parseHosts(spec, 3000);
        if (plan.tls() == null) {
            return parsed;
        }
        Host[] out = new Host[parsed.length];
        for (int i = 0; i < parsed.length; i++) {
            Host h = parsed[i];
            String tlsName = h.tlsName != null ? h.tlsName : plan.tlsName() != null ? plan.tlsName()
                    : plan.tls().settings().verifyHostname() ? h.name : null;
            out[i] = new Host(h.name, tlsName, h.port);
        }
        return out;
    }
}
