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

import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The shared TLS material for a {@link HttpClient} (the JDK client the REST and feed connectors use).
 *
 * <pre>{@code
 * HttpClient.Builder b = HttpClient.newBuilder();
 * TlsHttp.apply(b, TlsContexts.build(TlsSettings.from(settings)));
 * }</pre>
 *
 * <p>The JDK client always checks the server's name against its certificate. When {@code tls.verify-hostname} is false the
 * trust managers are wrapped so that the chain is still verified but the name is not, which is the one thing the setting means.
 */
public final class TlsHttp {

    private TlsHttp() {}

    /**
     * The TLS material for a connector that talks HTTP(S) to {@code url} (named {@code urlSetting} in messages), or null for
     * plain HTTP. {@code https://} turns TLS on (trusting the JVM's authorities unless {@code tls.*} says otherwise);
     * {@code tls.enabled: true} or any {@code tls.*} key against an {@code http://} URL is a start-up error, never a silent
     * downgrade.
     */
    public static TlsMaterial material(java.util.Map<String, String> settings, String url, String urlSetting) {
        return material(settings, url, urlSetting, System::getenv);
    }

    public static TlsMaterial material(java.util.Map<String, String> settings, String url, String urlSetting,
            java.util.function.Function<String, String> env) {
        TlsSettings ts = TlsSettings.parse(settings, "tls.", env);
        boolean https = url != null && url.regionMatches(true, 0, "https://", 0, 8);
        if (!https) {
            if (ts.enabled()) {
                throw new TlsException("tls.enabled is true but " + urlSetting + " is not https:// (" + url + ")");
            }
            if (TlsSettings.anyGiven(settings, "tls.")) {
                throw new TlsException("tls.* is set but " + urlSetting + " is not https:// (" + url + "): use an https:// address");
            }
            return null;
        }
        return TlsContexts.build(ts, env, java.time.Clock.systemUTC());
    }

    /** Gives the builder the context (trust, client identity), the protocols and the cipher suites of {@code m}. */
    public static HttpClient.Builder apply(HttpClient.Builder b, TlsMaterial m) {
        return b.sslContext(m.settings().verifyHostname() ? m.sslContext() : withoutHostnameCheck(m)).sslParameters(m.sslParameters());
    }

    /** A context with the same identity and trust as {@code m} whose trust managers do not look at the host name. */
    static SSLContext withoutHostnameCheck(TlsMaterial m) {
        TrustManager[] wrapped = new TrustManager[m.trustManagers().length];
        for (int i = 0; i < wrapped.length; i++) {
            wrapped[i] = m.trustManagers()[i] instanceof X509TrustManager t ? new NameBlind(t) : m.trustManagers()[i];
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(m.keyManagers(), wrapped, new SecureRandom());
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new TlsException("cannot create the TLS context (" + e.getMessage() + ")", e);
        }
    }

    /** Verifies the chain with the delegate's plain {@code X509TrustManager} methods, which never see the socket or its host. */
    private static final class NameBlind extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;

        NameBlind(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
