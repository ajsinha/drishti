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

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.ssl.SslEngineFactory;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * The driver's TLS from Drishti's shared {@code tls.*} settings (docs/connectors/TLS.md): the trust store or CA file, the
 * client certificate for mutual TLS, protocols, cipher suites and the host-name check. The engine is created for the node's
 * host name and port, and the shared material's parameters (with {@code HTTPS} endpoint identification unless
 * {@code tls.verify-hostname: false}) are applied to it, so the check is the JVM's own, as for every other connector.
 */
final class CassandraTls {

    private CassandraTls() {}

    /** The material, or null when TLS is off; fails the start naming the setting when {@code tls.*} is half configured. */
    static TlsMaterial plan(Map<String, String> settings, Function<String, String> env) {
        TlsSettings ts = TlsSettings.parse(settings, "tls.", env);
        if (!ts.enabled()) {
            if (TlsSettings.anyGiven(settings, "tls.")) {
                throw new TlsException("tls.* is set but tls.enabled is not true: set tls.enabled: true to connect over TLS");
            }
            return null;
        }
        return TlsContexts.build(ts, env, java.time.Clock.systemUTC());
    }

    /** The driver's engine factory over the material. */
    static SslEngineFactory engines(TlsMaterial material) {
        return new Engines(material);
    }

    /** Engines for the node being connected to, with the material's protocols, cipher suites and host-name check. */
    static final class Engines implements SslEngineFactory {
        private final TlsMaterial material;

        Engines(TlsMaterial material) {
            this.material = material;
        }

        @Override
        public SSLEngine newSslEngine(EndPoint remote) {
            SocketAddress address = remote.resolve();
            SSLEngine engine;
            if (address instanceof InetSocketAddress a) {
                engine = material.sslContext().createSSLEngine(a.getHostString(), a.getPort());
            } else {
                engine = material.sslContext().createSSLEngine();
            }
            engine.setUseClientMode(true);
            SSLParameters p = material.sslParameters();
            engine.setSSLParameters(p);
            return engine;
        }

        TlsMaterial material() {
            return material;
        }

        @Override
        public void close() {
            // nothing held: the context belongs to the material
        }
    }
}
