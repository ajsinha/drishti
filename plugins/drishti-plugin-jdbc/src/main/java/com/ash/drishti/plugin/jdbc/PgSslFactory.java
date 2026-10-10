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
package com.ash.drishti.plugin.jdbc;

import com.ash.drishti.api.tls.TlsMaterial;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.postgresql.ssl.WrappedFactory;

/**
 * The PostgreSQL driver's way in for the shared TLS module. The driver loads its socket factory by class name
 * ({@code sslfactory}) and gives it one string ({@code sslfactoryarg}); the connector registers the {@link TlsMaterial} under a
 * generated id and passes the id. The driver then connects with the module's trust (a private CA, a PKCS12/JKS store), client
 * certificate (any PEM key format) and protocol versions, and does the host name check itself ({@code sslmode=verify-full})
 * unless {@code tls.verify-hostname} is false.
 *
 * <p>Public because the driver instantiates it by name. It is not a setting: users never name this class.
 */
public final class PgSslFactory extends WrappedFactory {

    private static final Map<String, TlsMaterial> REGISTRY = new ConcurrentHashMap<>();

    /** Registers the material; the returned id is the {@code sslfactoryarg}. */
    static String register(TlsMaterial material) {
        String id = java.util.UUID.randomUUID().toString();
        REGISTRY.put(id, material);
        return id;
    }

    static void unregister(String id) {
        if (id != null) {
            REGISTRY.remove(id);
        }
    }

    /** Called by the driver with the {@code sslfactoryarg}. */
    public PgSslFactory(String id) throws GeneralSecurityException {
        TlsMaterial m = REGISTRY.get(id);
        if (m == null) {
            throw new GeneralSecurityException("no TLS material registered for '" + id + "' (the connector was closed)");
        }
        this.factory = new Parameterised(m.socketFactory(), m);
    }

    /** Applies protocols, cipher suites and the endpoint identification of the material to every socket the driver makes. */
    private static final class Parameterised extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final TlsMaterial material;

        Parameterised(SSLSocketFactory delegate, TlsMaterial material) {
            this.delegate = delegate;
            this.material = material;
        }

        private Socket apply(Socket s) {
            if (s instanceof SSLSocket ssl) {
                javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
                p.setProtocols(material.sslParameters().getProtocols());
                if (material.sslParameters().getCipherSuites() != null) {
                    p.setCipherSuites(material.sslParameters().getCipherSuites());
                }
                // the driver checks the host name itself (sslmode=verify-full); the JSSE check would only repeat it
                ssl.setSSLParameters(p);
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return apply(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return apply(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return apply(delegate.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return apply(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            return apply(delegate.createSocket(address, port, local, localPort));
        }
    }
}
