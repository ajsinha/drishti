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

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * TLS for the JDBC connector. For {@code jdbc:postgresql://} the shared {@code tls.*} settings ({@link TlsSettings}) are mapped
 * onto the driver: the module's SSL context is handed to the driver through {@link PgSslFactory}, {@code ssl=true}, and
 * {@code sslmode=verify-full} (the driver checks the host name) or {@code sslmode=require} when {@code tls.verify-hostname} is
 * false (the chain is still verified by the module). A URL that already carries driver options ({@code sslmode}, {@code
 * sslrootcert}, ...) and no {@code tls.*} keeps working unchanged; both together are refused.
 *
 * <p>The other drivers have their own options and no hook for a shared context, so {@code tls.*} against them is a start-up
 * error that says where their settings go (the URL), see {@code docs/connectors/POSTGRES_CONNECTOR.md}.
 */
final class JdbcTls {

    /** What the connector connects with: the extra driver properties and the material (null when TLS is not used here). */
    record Plan(TlsMaterial tls, Properties properties, String factoryId) {
        static final Plan NONE = new Plan(null, new Properties(), null);
    }

    private JdbcTls() {}

    static Plan plan(Map<String, String> settings, String url, Function<String, String> env) {
        boolean given = TlsSettings.anyGiven(settings, "tls.");
        if (!given) {
            return Plan.NONE;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:postgresql:")) {
            throw new TlsException("tls.* is only mapped for jdbc:postgresql:// urls; this driver takes its TLS options in the url "
                    + "(MySQL: sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=...; SQL Server: encrypt=true&trustServerCertificate=false"
                    + "&trustStore=...; Oracle: a tcps:// url with an Oracle wallet). See docs/connectors/POSTGRES_CONNECTOR.md");
        }
        TlsSettings ts = TlsSettings.parse(settings, "tls.", env);
        if (lower.contains("sslmode=") || lower.contains("sslrootcert=") || lower.contains("sslfactory=") || lower.contains("ssl=true")) {
            throw new TlsException("tls.* is set and the url also carries driver TLS options (sslmode, sslrootcert, ...): give TLS one way");
        }
        if (!ts.enabled()) {
            throw new TlsException("tls.* is set but tls.enabled is not true: set tls.enabled: true to connect over TLS");
        }
        TlsMaterial m = TlsContexts.build(ts, env, java.time.Clock.systemUTC());
        String id = PgSslFactory.register(m);
        Properties p = new Properties();
        p.setProperty("ssl", "true");
        p.setProperty("sslmode", ts.verifyHostname() && !ts.insecureTrustAll() ? "verify-full" : "require");
        p.setProperty("sslfactory", PgSslFactory.class.getName());
        p.setProperty("sslfactoryarg", id);
        return new Plan(m, p, id);
    }
}
