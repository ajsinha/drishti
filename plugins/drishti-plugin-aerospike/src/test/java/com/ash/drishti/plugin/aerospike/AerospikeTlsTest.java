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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerospike.client.Host;
import com.aerospike.client.policy.AuthMode;
import com.aerospike.client.policy.ClientPolicy;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.testkit.TlsFixture;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Aerospike TLS wiring: what the settings become in the client policy and the seed hosts, and the start-up errors. The server
 * side needs Aerospike Enterprise Edition (the repository's container is the Community Edition, which has no TLS), so the
 * live check is a manual procedure: {@code docs/connectors/AEROSPIKE_CONNECTOR.md}, section Security.
 */
class AerospikeTlsTest {

    private static TlsFixture pki;

    @BeforeAll
    static void pki() {
        pki = new TlsFixture();
    }

    @AfterAll
    static void clean() {
        pki.delete();
    }

    private static Map<String, String> on(Map<String, String> extra) {
        Map<String, String> s = new LinkedHashMap<>(pki.clientSettings());
        s.put("tls.enabled", "true");
        s.putAll(extra);
        return s;
    }

    @Test
    void withoutTlsSettingsNothingChanges() {
        AerospikeTls.Plan p = AerospikeTls.plan(Map.of(), System::getenv);
        ClientPolicy policy = new ClientPolicy();
        AerospikeTls.apply(policy, p);
        assertThat(p.tls()).isNull();
        assertThat(policy.tlsPolicy).isNull();
        assertThat(AerospikeTls.hosts("a:3000,b:3001", p)).extracting(h -> h.tlsName).containsOnlyNulls();
    }

    @Test
    void theClientPolicyGetsTheContextProtocolsAndCiphers() {
        AerospikeTls.Plan p = AerospikeTls.plan(on(Map.of("tls.protocols", "TLSv1.3")), System::getenv);
        ClientPolicy policy = new ClientPolicy();
        AerospikeTls.apply(policy, p);
        assertThat(policy.tlsPolicy).isNotNull();
        assertThat(policy.tlsPolicy.context).isSameAs(p.tls().sslContext());
        assertThat(policy.tlsPolicy.protocols).containsExactly("TLSv1.3");
    }

    @Test
    void eachHostIsCheckedAgainstItsOwnNameUnlessATlsNameIsGiven() {
        AerospikeTls.Plan own = AerospikeTls.plan(on(Map.of()), System::getenv);
        Host[] hosts = AerospikeTls.hosts("as1.example.com:4333,as2.example.com", own);
        assertThat(hosts).extracting(h -> h.tlsName).containsExactly("as1.example.com", "as2.example.com");
        assertThat(hosts[0].port).isEqualTo(4333);
        AerospikeTls.Plan named = AerospikeTls.plan(on(Map.of("tls-name", "aerospike-cluster")), System::getenv);
        assertThat(AerospikeTls.hosts("10.0.0.1:4333,10.0.0.2:4333", named)).extracting(h -> h.tlsName).containsOnly("aerospike-cluster");
        AerospikeTls.Plan lax = AerospikeTls.plan(on(Map.of("tls.verify-hostname", "false")), System::getenv);
        assertThat(AerospikeTls.hosts("10.0.0.1:4333", lax)).extracting(h -> h.tlsName).containsOnlyNulls();
    }

    @Test
    void tlsSettingsWithoutEnabledAreRefused() {
        assertThatThrownBy(() -> AerospikeTls.plan(Map.of("tls.ca-file", "/x.pem"), System::getenv))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.enabled is not true");
        assertThatThrownBy(() -> AerospikeTls.plan(Map.of("tls-name", "n"), System::getenv))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.enabled is not true");
    }

    @Test
    void aMissingCaFileFailsTheStartNamingTheFile() {
        assertThatThrownBy(() -> AerospikeTls.plan(Map.of("tls.enabled", "true", "tls.ca-file", "/etc/drishti/nope.pem"), System::getenv))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.ca-file '/etc/drishti/nope.pem'");
    }

    @Test
    void pkiLoginNeedsAClientCertificate() {
        assertThatThrownBy(() -> AerospikeTls.plan(Map.of("auth-mode", "pki"), System::getenv))
                .isInstanceOf(TlsException.class).hasMessageContaining("auth-mode: pki logs in with a client certificate");
        assertThatThrownBy(() -> AerospikeTls.plan(Map.of("auth-mode", "pki", "tls.enabled", "true"), System::getenv))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.cert-file and tls.key-file");
        AerospikeTls.Plan p = AerospikeTls.plan(on(Map.of("auth-mode", "pki")), System::getenv);
        ClientPolicy policy = new ClientPolicy();
        AerospikeTls.applyAuthMode(policy, on(Map.of("auth-mode", "pki")), p);
        assertThat(policy.authMode).isEqualTo(AuthMode.PKI);
        assertThatThrownBy(() -> AerospikeTls.plan(Map.of("auth-mode", "ldap"), System::getenv))
                .isInstanceOf(TlsException.class).hasMessageContaining("is not internal, external or pki");
    }
}
