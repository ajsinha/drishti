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

import com.ash.drishti.api.tls.TlsMaterial;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** A socket factory that applies a {@link TlsMaterial}'s protocols and cipher suites to every socket it makes. */
final class ParametersFactory extends SSLSocketFactory {

    private final TlsMaterial tls;
    private final SSLSocketFactory delegate;

    ParametersFactory(TlsMaterial tls) {
        this.tls = tls;
        this.delegate = tls.socketFactory();
    }

    private Socket apply(Socket s) {
        if (s instanceof SSLSocket ssl) {
            var p = tls.sslParameters();
            var current = ssl.getSSLParameters();
            current.setProtocols(p.getProtocols());
            if (p.getCipherSuites() != null) {
                current.setCipherSuites(p.getCipherSuites());
            }
            ssl.setSSLParameters(current);
        }
        return s;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return apply(delegate.createSocket(s, host, port, autoClose)); }
    @Override public Socket createSocket(String host, int port) throws IOException { return apply(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return apply(delegate.createSocket(host, port, localHost, localPort)); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return apply(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return apply(delegate.createSocket(address, port, localAddress, localPort)); }
}
