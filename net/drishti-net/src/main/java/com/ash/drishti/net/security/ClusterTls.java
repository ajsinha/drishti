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
package com.ash.drishti.net.security;

import com.ash.drishti.api.tls.PemReader;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.net.config.ClusterSettings;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The cluster's TLS material from {@code [net.tls]} (CLUSTER_I5_DESIGN.md section 10): one certificate per node for the server port, the
 * cluster port and the agreement port, trusting the cluster's authority only (never the JVM's default authorities: a certificate of another
 * cluster's or a public authority never joins). PEM files, read with the product's one PEM reader; pure JDK.
 *
 * @param key this node's private key
 * @param chain this node's certificate chain, leaf first
 * @param authorities the cluster's certificate authorities
 * @param clientAuthNeeded true for mutual TLS ({@code client-auth=need})
 */
public record ClusterTls(PrivateKey key, List<X509Certificate> chain, List<X509Certificate> authorities, boolean clientAuthNeeded) {

    public ClusterTls {
        chain = List.copyOf(chain);
        authorities = List.copyOf(authorities);
        if (chain.isEmpty()) {
            throw new TlsException("[net.tls] cert-file holds no certificate");
        }
        if (authorities.isEmpty()) {
            throw new TlsException("[net.tls] ca-file holds no certificate");
        }
    }

    /** Reads the three files of {@code [net.tls]}; throws {@link TlsException} naming the file and the reason. */
    public static ClusterTls read(ClusterSettings.Tls tls) {
        if (!tls.configured()) {
            throw new TlsException("[net.tls] is not configured: name ca-file, cert-file and key-file (absolute PEM files)");
        }
        PrivateKey key = PemReader.privateKey(tls.keyFile(), tls.keyPassword().isEmpty() ? null : tls.keyPassword(), "[net.tls] key-file");
        List<X509Certificate> chain = PemReader.certificates(tls.certFile(), "[net.tls] cert-file");
        List<X509Certificate> cas = PemReader.certificates(tls.caFile(), "[net.tls] ca-file");
        return new ClusterTls(key, chain, cas, !"want".equals(tls.clientAuth()));
    }

    /** This node's leaf certificate. */
    public X509Certificate certificate() {
        return chain.get(0);
    }

    /** One context for both sides of a connection: this node's identity, and trust in the cluster's authorities only. */
    public SSLContext context() {
        try {
            char[] pass = new char[0];
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("node", key, pass, chain.toArray(X509Certificate[]::new));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            KeyStore ts = KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            for (int i = 0; i < authorities.size(); i++) {
                ts.setCertificateEntry("ca-" + i, authorities.get(i));
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new TlsException("[net.tls]: the key and certificates cannot be used together (" + e.getMessage() + ")", e);
        }
    }
}
