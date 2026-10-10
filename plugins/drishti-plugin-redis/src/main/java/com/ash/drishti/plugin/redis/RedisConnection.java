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

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import com.ash.drishti.api.tls.TlsMaterial;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * One Redis deployment, standalone or a cluster, through Lettuce: a single multiplexed connection that any number of
 * virtual threads share (each command is queued and answered in order, so concurrent callers are pipelined on the
 * wire), plus a pub/sub connection on demand. {@code uri} takes Lettuce's form, with credentials and TLS:
 * {@code redis://localhost:6379}, {@code rediss://user:secret@redis.internal:6380/0}; several comma-separated URIs, or
 * {@code cluster: true}, make it a Redis Cluster client (the URIs are seed nodes; the topology is discovered and
 * refreshed every minute and on redirects).
 */
final class RedisConnection implements AutoCloseable {

    private final AbstractRedisClient client;
    private final StatefulConnection<byte[], byte[]> connection;
    private final RedisClusterAsyncCommands<byte[], byte[]> async;
    private final boolean cluster;
    private final String describe;

    private RedisConnection(AbstractRedisClient client, StatefulConnection<byte[], byte[]> connection, RedisClusterAsyncCommands<byte[], byte[]> async,
            boolean cluster, String describe) {
        this.client = client;
        this.connection = connection;
        this.async = async;
        this.cluster = cluster;
        this.describe = describe;
    }

    /** Where the connector points, without credentials (for health messages). */
    static String describe(String uris) {
        return Arrays.stream(uris.split(",")).map(String::trim).map(u -> u.replaceAll("//[^@/]*@", "//")).reduce((a, b) -> a + "," + b).orElse(uris);
    }

    /** Connects (throws when Redis cannot be reached); {@code user}/{@code password} override the URI's credentials. */
    static RedisConnection open(String uris, boolean cluster, String user, String password, Duration timeout) {
        return open(uris, cluster, user, password, timeout, null);
    }

    /** As above over TLS when {@code tls} is given ({@link RedisTls}): the trust, the client certificate and the protocols come from it. */
    static RedisConnection open(String uris, boolean cluster, String user, String password, Duration timeout, TlsMaterial tls) {
        List<RedisURI> nodes = Arrays.stream(uris.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> {
            RedisURI u = RedisURI.create(s.contains("://") ? s : "redis://" + s);
            u.setTimeout(timeout);
            if (tls != null) {
                RedisTls.apply(u, tls);
            }
            if (password != null) {
                u.setCredentialsProvider(RedisCredentialsProvider.from(() -> RedisCredentials.just(user, password)));
            }
            return u;
        }).toList();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("no Redis URI");
        }
        if (cluster || nodes.size() > 1) {
            RedisClusterClient c = RedisClusterClient.create(nodes);
            c.setOptions(ClusterClientOptions.builder()
                    .sslOptions(tls == null ? SslOptions.builder().build() : RedisTls.options(tls))
                    .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder().enablePeriodicRefresh(Duration.ofMinutes(1))
                            .enableAllAdaptiveRefreshTriggers().build())
                    .build());
            try {
                StatefulRedisClusterConnection<byte[], byte[]> conn = c.connect(ByteArrayCodec.INSTANCE);
                return new RedisConnection(c, conn, conn.async(), true, describe(uris));
            } catch (RuntimeException e) {
                c.shutdown();
                throw e;
            }
        }
        RedisClient c = RedisClient.create(nodes.get(0));
        c.setOptions(ClientOptions.builder().autoReconnect(true).sslOptions(tls == null ? SslOptions.builder().build() : RedisTls.options(tls)).build());
        try {
            StatefulRedisConnection<byte[], byte[]> conn = c.connect(ByteArrayCodec.INSTANCE);
            return new RedisConnection(c, conn, conn.async(), false, describe(uris));
        } catch (RuntimeException e) {
            c.shutdown();
            throw e;
        }
    }

    /** The commands: every call returns at once with a future; callers on virtual threads wait on it. */
    RedisClusterAsyncCommands<byte[], byte[]> async() {
        return async;
    }

    boolean cluster() {
        return cluster;
    }

    boolean isOpen() {
        return connection.isOpen();
    }

    String describe() {
        return describe;
    }

    /** A connection for SUBSCRIBE (a cluster's pub/sub reaches every node's publishers). */
    StatefulRedisPubSubConnection<String, String> pubSub() {
        return client instanceof RedisClusterClient c ? c.connectPubSub(StringCodec.UTF8) : ((RedisClient) client).connectPubSub(StringCodec.UTF8);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
    }
}
