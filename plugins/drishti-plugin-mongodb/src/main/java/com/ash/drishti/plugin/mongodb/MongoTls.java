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
package com.ash.drishti.plugin.mongodb;

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * MongoDB over TLS with the shared {@code tls.*} settings ({@link TlsSettings}), and certificate log-in.
 *
 * <p>TLS is on when {@code tls.enabled: true}, or the URI says so ({@code tls=true}, {@code ssl=true}, {@code mongodb+srv://}).
 * {@code tls.*} keys without any of those are a start-up error (the URI would otherwise connect in plain text), and so is
 * {@code tls.enabled: true} beside {@code tls=false}. The trust, the client certificate and the host name check come from the
 * module; the driver is handed the {@code SSLContext}. The driver takes the protocol versions from the JVM, so
 * {@code tls.protocols} and {@code tls.cipher-suites} are checked at start but cannot narrow them.
 *
 * <p>{@code auth-mechanism: x509} logs in with the client certificate ({@code MONGODB-X509}: the user is the certificate's
 * subject, which must exist in {@code $external}); {@code x509-user} gives the subject explicitly. It needs a client identity
 * ({@code tls.cert-file} + {@code tls.key-file}, or {@code tls.keystore}).
 */
final class MongoTls {

    private MongoTls() {}

    /** What the settings asked for. */
    record Plan(TlsMaterial tls, boolean x509, String x509User) {}

    static Plan plan(Map<String, String> settings, String uri, Function<String, String> env) {
        TlsSettings ts = TlsSettings.parse(settings, "tls.", env);
        ConnectionString cs = new ConnectionString(uri);
        Boolean uriTls = cs.getSslEnabled();
        boolean uriOn = Boolean.TRUE.equals(uriTls) || cs.isSrvProtocol() && !Boolean.FALSE.equals(uriTls);
        if (ts.enabled() && Boolean.FALSE.equals(uriTls)) {
            throw new TlsException("tls.enabled is true but the uri says tls=false (or ssl=false): remove one of them");
        }
        boolean on = ts.enabled() || uriOn;
        boolean given = TlsSettings.anyGiven(settings, "tls.");
        if (!on && given) {
            throw new TlsException("tls.* is set but TLS is not switched on: set tls.enabled: true (or put tls=true in the uri)");
        }
        String mechanism = settings.getOrDefault("auth-mechanism", "").strip().toLowerCase(Locale.ROOT);
        boolean x509 = mechanism.equals("x509") || mechanism.equals("mongodb-x509");
        if (!mechanism.isEmpty() && !x509) {
            throw new TlsException("auth-mechanism '" + settings.get("auth-mechanism") + "' is not x509 (leave it out to log in as the uri says)");
        }
        TlsMaterial m = on ? TlsContexts.build(ts, env, java.time.Clock.systemUTC()) : null;
        if (x509 && (m == null || m.clientKey() == null)) {
            throw new TlsException("auth-mechanism: x509 logs in with a client certificate: switch TLS on (tls.enabled: true) and set tls.cert-file "
                    + "and tls.key-file, or tls.keystore");
        }
        String user = settings.get("x509-user");
        return new Plan(m, x509, user == null || user.isBlank() ? null : user.strip());
    }

    /** Applies the plan to the client settings: the SSL context and name check, and the X.509 credential. */
    static void apply(MongoClientSettings.Builder b, Plan p) {
        if (p.tls() != null) {
            b.applyToSslSettings(s -> s.enabled(true).context(p.tls().sslContext()).invalidHostNameAllowed(!p.tls().settings().verifyHostname()));
        }
        if (p.x509()) {
            b.credential(p.x509User() == null ? MongoCredential.createMongoX509Credential() : MongoCredential.createMongoX509Credential(p.x509User()));
        }
    }
}
