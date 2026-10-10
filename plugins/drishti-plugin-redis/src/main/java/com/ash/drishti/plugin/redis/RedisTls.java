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
package com.ash.drishti.plugin.redis;

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

/**
 * Redis over TLS with the shared {@code tls.*} settings ({@link TlsSettings}). A {@code rediss://} URI turns TLS on; the trust,
 * the client certificate (Redis {@code tls-auth-clients}) and the protocols come from the module, and Lettuce is handed them as
 * {@link SslOptions}. {@code tls.*} against a {@code redis://} URI is a start-up error, never a silent downgrade.
 *
 * <p>Compatibility: a {@code verifyPeer=...} option in the URI (Lettuce's own form) still works, with a deprecation warning that
 * names {@code tls.verify-hostname}.
 */
final class RedisTls {

    private static final System.Logger LOG = System.getLogger("com.ash.drishti.plugin.redis");

    private RedisTls() {}

    /** True when any of the comma-separated URIs is {@code rediss://}. */
    static boolean secureScheme(String uris) {
        return Arrays.stream(uris.split(",")).map(String::trim).anyMatch(u -> u.regionMatches(true, 0, "rediss:", 0, 7));
    }

    /**
     * The TLS material for these settings, or null for plain text. Throws {@link TlsException} (naming the setting and the file)
     * when the settings are wrong, so a misconfigured connector fails to start rather than at its first read.
     */
    static TlsMaterial material(Map<String, String> settings, String uris, Function<String, String> env) {
        TlsSettings ts = TlsSettings.parse(settings, "tls.", env);
        boolean secure = secureScheme(uris);
        if (ts.enabled() && !secure) {
            throw new TlsException("tls.enabled is true but uri is not rediss:// (Redis over TLS is rediss://host:6380): "
                    + RedisConnection.describe(uris));
        }
        if (!secure) {
            if (TlsSettings.anyGiven(settings, "tls.")) {
                throw new TlsException("tls.* is set but uri is not rediss://: use rediss://host:port for Redis over TLS");
            }
            return null;
        }
        if (uris.toLowerCase(java.util.Locale.ROOT).contains("verifypeer=")) {
            LOG.log(System.Logger.Level.WARNING,
                    "redis: 'verifyPeer' in the uri is deprecated; use tls.verify-hostname: false (or tls.insecure-trust-all for development)");
        }
        return TlsContexts.build(ts, env, java.time.Clock.systemUTC());
    }

    /** Lettuce's SSL options carrying the module's trust, identity, protocols and cipher suites. */
    static SslOptions options(TlsMaterial m) {
        SslOptions.Builder b = SslOptions.builder().jdkSslProvider();
        b.sslContext(ctx -> {
            for (TrustManager tm : m.trustManagers()) {
                ctx.trustManager(tm);
            }
            if (m.keyManagers() != null) {
                for (KeyManager km : m.keyManagers()) {
                    ctx.keyManager(km);
                }
            }
        });
        b.protocols(m.sslParameters().getProtocols());
        if (m.sslParameters().getCipherSuites() != null) {
            b.cipherSuites(m.sslParameters().getCipherSuites());
        }
        return b.build();
    }

    /** Applies the verification level: full (name and chain) by default, chain only when {@code tls.verify-hostname} is false. */
    static void apply(RedisURI u, TlsMaterial m) {
        u.setSsl(true);
        if (u.getVerifyMode() == SslVerifyMode.FULL) {            // the URI did not ask for less (the deprecated verifyPeer option)
            u.setVerifyPeer(m.settings().insecureTrustAll() ? SslVerifyMode.NONE
                    : m.settings().verifyHostname() ? SslVerifyMode.FULL : SslVerifyMode.CA);
        }
    }
}
