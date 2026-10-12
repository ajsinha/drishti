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
import com.ash.drishti.api.pool.PoolSettings;
import com.ash.drishti.api.pool.PoolStats;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One Redis deployment, standalone or a cluster, through Lettuce. Lettuce multiplexes: one connection carries any number of
 * commands from any number of virtual threads, queued and answered in order. This class keeps a small pool of such connections
 * (at most {@code pool-size}, default 20 from {@code drishti.db.pool.size}): the first is opened at start, and another is opened
 * only when every open one already has {@code pool-pipeline-depth} (default 32) commands in flight, so a quiet connector holds one
 * connection and a busy one spreads its commands over up to {@code pool-size}. Each command goes to the least busy connection and
 * is counted until it completes (that is the "borrow": it lasts one command, which {@code timeout-ms} bounds). Connections beyond
 * {@code pool-min-idle} that have been idle {@code pool-idle-timeout} are closed by {@link #sweep}; a command in flight longer
 * than {@code pool-leak-threshold} is logged. {@code pool-max-lifetime}, {@code pool-connection-timeout} and
 * {@code statement-timeout} do not apply: commands wait in Lettuce's queue rather than for a connection, and {@code timeout-ms}
 * is the command timeout. A pub/sub connection is opened on demand for a subscription (long-lived by nature) and is not pooled.
 * {@code uri} takes Lettuce's form, with credentials and TLS:
 * {@code redis://localhost:6379}, {@code rediss://user:secret@redis.internal:6380/0}; several comma-separated URIs, or
 * {@code cluster: true}, make it a Redis Cluster client (the URIs are seed nodes; the topology is discovered and
 * refreshed every minute and on redirects).
 */
final class RedisConnection implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RedisConnection.class.getName());

    /** One pooled connection and the commands now running on it. */
    private static final class Member {
        final StatefulConnection<byte[], byte[]> connection;
        final RedisClusterAsyncCommands<byte[], byte[]> async;
        final AtomicInteger running = new AtomicInteger();
        volatile long idleSince = System.nanoTime();
        volatile boolean retired;

        Member(StatefulConnection<byte[], byte[]> connection, RedisClusterAsyncCommands<byte[], byte[]> async) {
            this.connection = connection;
            this.async = async;
        }
    }

    /** Opens one more connection. */
    private interface Opener {
        Member open();
    }

    private final AbstractRedisClient client;
    private final Opener opener;
    private final PoolSettings pool;
    private final int pipelineDepth;
    private final List<Member> members = new CopyOnWriteArrayList<>();
    private final ReentrantLock growing = new ReentrantLock();
    private final Map<Object, long[]> running = new ConcurrentHashMap<>();   // command -> start nanos, and whether it was logged
    private final AtomicLong leaks = new AtomicLong();
    private final RedisClusterAsyncCommands<byte[], byte[]> async;
    private final boolean cluster;
    private final String describe;
    private volatile boolean closed;

    private RedisConnection(AbstractRedisClient client, Opener opener, PoolSettings pool, int pipelineDepth, boolean cluster, String describe) {
        this.client = client;
        this.opener = opener;
        this.pool = pool;
        this.pipelineDepth = Math.max(1, pipelineDepth);
        this.cluster = cluster;
        this.describe = describe;
        this.members.add(opener.open());                 // the first connection: a Redis that cannot be reached fails here
        this.async = commands();
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
        return open(uris, cluster, user, password, timeout, tls, PoolSettings.resolve(Map.of()), 32);
    }

    /** As above with the connector's pool settings and how many commands a connection carries before another is opened. */
    static RedisConnection open(String uris, boolean cluster, String user, String password, Duration timeout, TlsMaterial tls, PoolSettings pool,
            int pipelineDepth) {
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
                return new RedisConnection(c, () -> {
                    StatefulRedisClusterConnection<byte[], byte[]> conn = c.connect(ByteArrayCodec.INSTANCE);
                    return new Member(conn, conn.async());
                }, pool, pipelineDepth, true, describe(uris));
            } catch (RuntimeException e) {
                c.shutdown();
                throw e;
            }
        }
        RedisClient c = RedisClient.create(nodes.get(0));
        c.setOptions(ClientOptions.builder().autoReconnect(true).sslOptions(tls == null ? SslOptions.builder().build() : RedisTls.options(tls)).build());
        try {
            return new RedisConnection(c, () -> {
                StatefulRedisConnection<byte[], byte[]> conn = c.connect(ByteArrayCodec.INSTANCE);
                return new Member(conn, conn.async());
            }, pool, pipelineDepth, false, describe(uris));
        } catch (RuntimeException e) {
            c.shutdown();
            throw e;
        }
    }

    /** The commands: each call goes to the least busy pooled connection and returns at once with a future; callers on virtual threads wait on it. */
    RedisClusterAsyncCommands<byte[], byte[]> async() {
        return async;
    }

    @SuppressWarnings("unchecked")
    private RedisClusterAsyncCommands<byte[], byte[]> commands() {
        return (RedisClusterAsyncCommands<byte[], byte[]>) Proxy.newProxyInstance(RedisConnection.class.getClassLoader(),
                new Class<?>[] {RedisClusterAsyncCommands.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    Member m = pick();
                    m.running.incrementAndGet();
                    while (m.retired) {                      // the sweep is closing this one: take another
                        done(m, null);
                        m = pick();
                        m.running.incrementAndGet();
                    }
                    Member chosen = m;
                    Object result;
                    try {
                        result = method.invoke(chosen.async, args);
                    } catch (InvocationTargetException e) {
                        done(chosen, null);
                        throw e.getCause();
                    }
                    if (result instanceof CompletionStage<?> stage) {
                        long[] mark = {System.nanoTime(), 0};
                        running.put(stage, mark);
                        stage.whenComplete((v, e) -> done(chosen, stage));
                    } else {
                        done(chosen, null);
                    }
                    return result;
                });
    }

    private void done(Member m, Object stage) {
        if (stage != null) {
            running.remove(stage);
        }
        if (m.running.decrementAndGet() == 0) {
            m.idleSince = System.nanoTime();
        }
    }

    /** The least busy connection; another is opened first when all are carrying {@code pool-pipeline-depth} commands and there is room. */
    private Member pick() {
        Member best = least();
        if (best.running.get() >= pipelineDepth && members.size() < pool.size()) {
            growing.lock();
            try {
                best = least();
                if (best.running.get() >= pipelineDepth && members.size() < pool.size() && !closed) {
                    Member extra = opener.open();
                    members.add(extra);
                    best = extra;
                }
            } finally {
                growing.unlock();
            }
        }
        return best;
    }

    private Member least() {
        Member best = null;
        for (Member m : members) {
            if (!m.retired && (best == null || m.running.get() < best.running.get())) {
                best = m;
            }
        }
        return best != null ? best : members.get(0);
    }

    /** Closes connections beyond {@code pool-min-idle} that have been idle {@code pool-idle-timeout}; logs commands in flight past {@code pool-leak-threshold}. */
    void sweep() {
        long now = System.nanoTime();
        if (!pool.leakThreshold().isZero()) {
            running.forEach((stage, mark) -> {
                if (mark[1] == 0 && now - mark[0] > pool.leakThreshold().toNanos()) {
                    mark[1] = 1;
                    leaks.incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING, "slow Redis command in " + describe + ": in flight for " + (now - mark[0]) / 1_000_000
                            + " ms (pool-leak-threshold " + pool.leakThreshold().toMillis() + " ms)");
                }
            });
        }
        growing.lock();
        try {
            for (Member m : members) {
                if (members.size() > Math.max(1, pool.minIdle()) && m.running.get() == 0 && now - m.idleSince > pool.idleTimeout().toNanos()) {
                    m.retired = true;
                    if (m.running.get() == 0) {                    // nothing slipped in between
                        members.remove(m);
                        m.connection.close();
                    } else {
                        m.retired = false;
                    }
                }
            }
        } finally {
            growing.unlock();
        }
    }

    /** Open connections, those running commands now, and the oldest command in flight. */
    PoolStats stats() {
        long now = System.nanoTime();
        long longest = 0;
        for (long[] mark : running.values()) {
            longest = Math.max(longest, (now - mark[0]) / 1_000_000);
        }
        int open = members.size();
        int busy = (int) members.stream().filter(m -> m.running.get() > 0).count();
        return new PoolStats("redis", pool.size(), open, busy, open - busy, 0, longest, leaks.get(), 0);
    }

    boolean cluster() {
        return cluster;
    }

    boolean isOpen() {
        return members.stream().anyMatch(m -> m.connection.isOpen());
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
        closed = true;
        try {
            for (Member m : members) {
                m.connection.close();
            }
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
    }
}
