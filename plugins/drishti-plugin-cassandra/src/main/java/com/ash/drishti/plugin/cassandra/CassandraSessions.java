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

import com.ash.drishti.api.tls.TlsMaterial;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.auth.AuthProvider;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Builds the driver's session from a connector's settings (the loader passes the same keys), so the connector and the loader
 * connect alike: contact points and the local data centre, or Astra's secure connect bundle; a user name and password, an
 * Astra token, or Amazon Keyspaces' SigV4 log-in; TLS from the shared {@code tls.*} settings; the pool, timeouts,
 * consistency, page size, a bound on concurrent requests and (off by default) speculative executions. The native protocol
 * is TCP, so an HTTP proxy cannot carry it: {@code proxy.*} settings are refused with that reason.
 */
final class CassandraSessions {

    /** The class of AWS's SigV4 plug-in for the driver (aws-sigv4-auth-cassandra-java-driver-plugin), put on the plugin class path by the site. */
    static final String SIGV4_PROVIDER = "software.aws.mcs.auth.SigV4AuthProvider";

    /** The settings that decide how the session connects, with what it resolved them to. */
    record Plan(List<InetSocketAddress> contactPoints, String localDatacenter, Path bundle, TlsMaterial tls, int connectionsPerNode,
            Duration requestTimeout, String consistency) {}

    private CassandraSessions() {}

    /** Reads and checks the settings; fails naming the setting (and, for TLS, the file) before anything connects. */
    static Plan plan(Map<String, String> settings, Function<String, String> env) {
        for (String k : settings.keySet()) {
            if (k.startsWith("proxy.")) {
                throw new IllegalArgumentException("proxy." + k.substring(6) + ": Cassandra's native protocol is TCP and cannot pass an HTTP proxy; "
                        + "reach the cluster directly or through a TCP tunnel, and remove the proxy settings");
            }
        }
        String bundleSetting = settings.getOrDefault("secure-connect-bundle", "").strip();
        Path bundle = bundleSetting.isEmpty() ? null : Path.of(bundleSetting);
        if (bundle != null && !Files.isRegularFile(bundle)) {
            throw new IllegalArgumentException("secure-connect-bundle: no such file " + bundle);
        }
        List<InetSocketAddress> points = contactPoints(settings.getOrDefault("contact-points", ""));
        String dc = settings.getOrDefault("local-datacenter", "").strip();
        if (bundle == null) {
            if (points.isEmpty()) {
                throw new IllegalArgumentException("contact-points is required (host:port list, port 9042 by default) unless secure-connect-bundle is set");
            }
            if (dc.isEmpty()) {
                throw new IllegalArgumentException("local-datacenter is required: the data centre the driver prefers (nodetool status names it; datacenter1 on a single node)");
            }
        }
        String consistency = settings.getOrDefault("consistency", "LOCAL_QUORUM").strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ONE", "TWO", "THREE", "QUORUM", "ALL", "LOCAL_QUORUM", "EACH_QUORUM", "LOCAL_ONE").contains(consistency)) {
            throw new IllegalArgumentException("consistency '" + consistency + "' is not a read consistency level (LOCAL_QUORUM, LOCAL_ONE, QUORUM, ONE …)");
        }
        TlsMaterial tls = CassandraTls.plan(settings, env);
        int perNode = Integer.parseInt(settings.getOrDefault("connections-per-node", "2"));
        Duration timeout = Duration.ofMillis(Long.parseLong(settings.getOrDefault("request-timeout-ms", "5000")));
        return new Plan(points, dc, bundle, tls, perNode, timeout, consistency);
    }

    /** {@code host:port,host2} (port 9042 when left out; an IPv6 address in brackets). */
    static List<InetSocketAddress> contactPoints(String text) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String p : text.split(",")) {
            String s = p.strip();
            if (s.isEmpty()) {
                continue;
            }
            String host = s;
            int port = 9042;
            int colon = s.lastIndexOf(':');
            if (s.startsWith("[")) {
                int close = s.indexOf(']');
                host = s.substring(1, close);
                if (close + 1 < s.length() && s.charAt(close + 1) == ':') {
                    port = Integer.parseInt(s.substring(close + 2));
                }
            } else if (colon > 0 && s.indexOf(':') == colon) {
                host = s.substring(0, colon);
                port = Integer.parseInt(s.substring(colon + 1));
            }
            out.add(InetSocketAddress.createUnresolved(host, port));
        }
        return out;
    }

    /** The driver's configuration: everything from the settings, nothing from an application.conf. */
    static DriverConfigLoader config(Map<String, String> settings, Plan plan) {
        Duration connect = Duration.ofMillis(Long.parseLong(settings.getOrDefault("connect-timeout-ms", "5000")));
        ProgrammaticDriverConfigLoaderBuilder c = DriverConfigLoader.programmaticBuilder()
                // the driver's log lines and threads carry the connector's name ([trade-cass] Error connecting …), not s0, s1, …
                .withString(DefaultDriverOption.SESSION_NAME, settings.getOrDefault("source-name", "cassandra").replaceAll("[^A-Za-z0-9._-]", "_"))
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, plan.requestTimeout())
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, plan.consistency())
                .withInt(DefaultDriverOption.REQUEST_PAGE_SIZE, Integer.parseInt(settings.getOrDefault("page-size", "5000")))
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, plan.connectionsPerNode())
                .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, plan.connectionsPerNode())
                .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, connect)
                .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, connect)
                .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, connect)
                .withDuration(DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, plan.requestTimeout().plus(connect))
                // a cluster down at start is retried in the background; the connector starts and says DOWN until it answers
                .withBoolean(DefaultDriverOption.RECONNECT_ON_INIT, true)
                // every read is idempotent; the loader's writes too (same keys, same values), so retries and speculation are safe
                .withBoolean(DefaultDriverOption.REQUEST_DEFAULT_IDEMPOTENCE, true)
                .withString(DefaultDriverOption.REQUEST_THROTTLER_CLASS, "ConcurrencyLimitingRequestThrottler")
                .withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_CONCURRENT_REQUESTS, Integer.parseInt(settings.getOrDefault("max-concurrent-reads", "256")))
                .withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_QUEUE_SIZE, 100_000);
        int speculative = Integer.parseInt(settings.getOrDefault("speculative-executions", "0"));
        if (speculative > 0) {
            c.withString(DefaultDriverOption.SPECULATIVE_EXECUTION_POLICY_CLASS, "ConstantSpeculativeExecutionPolicy")
                    .withInt(DefaultDriverOption.SPECULATIVE_EXECUTION_MAX, speculative + 1)
                    .withDuration(DefaultDriverOption.SPECULATIVE_EXECUTION_DELAY,
                            Duration.ofMillis(Long.parseLong(settings.getOrDefault("speculative-delay-ms", "100"))));
        }
        return c.build();
    }

    /** Starts connecting; completes when a node answers (retrying in the background meanwhile). */
    static CompletionStage<CqlSession> open(Map<String, String> settings, Plan plan, Function<String, String> env) {
        CqlSessionBuilder b = CqlSession.builder().withConfigLoader(config(settings, plan));
        if (plan.bundle() != null) {
            b.withCloudSecureConnectBundle(plan.bundle());
        } else {
            b.addContactPoints(plan.contactPoints()).withLocalDatacenter(plan.localDatacenter());
        }
        if (plan.tls() != null) {
            b.withSslEngineFactory(CassandraTls.engines(plan.tls()));
        }
        String token = settings.getOrDefault("token", "").strip();
        String user = settings.getOrDefault("username", "").strip();
        if (Boolean.parseBoolean(settings.getOrDefault("sigv4", "false"))) {
            b.withAuthProvider(sigv4(settings.getOrDefault("sigv4-region", "").strip(), env));
        } else if (!token.isEmpty()) {
            b.withAuthCredentials("token", token);                          // Astra: the user name is the word "token"
        } else if (!user.isEmpty()) {
            b.withAuthCredentials(user, settings.getOrDefault("password", ""));
        }
        return b.buildAsync();
    }

    /** Amazon Keyspaces' SigV4 log-in through AWS's driver plug-in (the default AWS credential chain), loaded only when asked for. */
    private static AuthProvider sigv4(String region, Function<String, String> env) {
        String r = region.isEmpty() ? String.valueOf(env.apply("AWS_REGION")) : region;
        if (r.isEmpty() || r.equals("null")) {
            throw new IllegalArgumentException("sigv4: set sigv4-region (or AWS_REGION) to the Keyspaces region, e.g. us-east-1");
        }
        try {
            Class<?> c = Class.forName(SIGV4_PROVIDER, true, CassandraSessions.class.getClassLoader());
            return (AuthProvider) c.getConstructor(String.class).newInstance(r);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("sigv4: true needs AWS's SigV4 plug-in for the Cassandra driver (software.aws.mcs:"
                    + "aws-sigv4-auth-cassandra-java-driver-plugin) in the plugin folder; it is not shipped with the server", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("sigv4: AWS's SigV4 plug-in could not be started: " + e, e);
        }
    }
}
