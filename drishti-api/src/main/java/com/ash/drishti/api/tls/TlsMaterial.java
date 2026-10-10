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

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * What {@link TlsContexts#build} produced from a connector's {@link TlsSettings}: the objects a client library takes, and the
 * raw parts (for a library that wants PEM text or a key store instead of an {@code SSLContext}).
 *
 * @param settings     the settings it was built from
 * @param sslContext   an initialised context (client identity, trust, no hostname checking of its own)
 * @param keyManagers  the client identity, or {@code null} when none is configured (the JVM then sends none)
 * @param trustManagers the trust decision
 * @param sslParameters protocols, cipher suites and the hostname check ({@code HTTPS} endpoint identification when on): apply
 *                     to every {@code SSLSocket} or {@code SSLEngine} you create
 * @param clientKey    the client private key, or {@code null}
 * @param clientChain  the client certificate chain (leaf first), empty when no identity
 * @param trusted      the authorities trusted besides the JVM's own default ones (empty when only the JVM default is used)
 * @param usesJvmDefaultTrust true when the JVM's default authorities are part of the trust
 * @param certificates the configured certificates with their expiry
 * @param warnings     what start-up warned about (expired or soon-expiring certificate, hostname check off, ...)
 */
public record TlsMaterial(
        TlsSettings settings,
        SSLContext sslContext,
        KeyManager[] keyManagers,
        TrustManager[] trustManagers,
        SSLParameters sslParameters,
        PrivateKey clientKey,
        List<X509Certificate> clientChain,
        List<X509Certificate> trusted,
        boolean usesJvmDefaultTrust,
        List<CertInfo> certificates,
        List<String> warnings) {

    /** Days before expiry from which a certificate is warned about. */
    public static final long WARN_DAYS = 30;

    public SSLSocketFactory socketFactory() {
        return sslContext.getSocketFactory();
    }

    /** The configured certificate that expires first. */
    public Optional<CertInfo> soonestExpiry() {
        return certificates.stream().min(Comparator.comparing(CertInfo::notAfter));
    }

    /**
     * A short sentence for a health page: empty when nothing configured expires within {@link #WARN_DAYS} days, otherwise
     * e.g. {@code TLS certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22)}.
     */
    public String expiryNote(Instant now) {
        return soonestExpiry().map(c -> {
            long days = c.daysLeft(now);
            if (c.expired(now)) {
                return "TLS certificate " + c.subject() + " (" + c.source() + ") expired on " + c.notAfter().toString().substring(0, 10);
            }
            return days < WARN_DAYS
                    ? "TLS certificate " + c.subject() + " (" + c.source() + ") expires in " + days + " days (" + c.notAfter().toString().substring(0, 10) + ")"
                    : "";
        }).orElse("");
    }
}
