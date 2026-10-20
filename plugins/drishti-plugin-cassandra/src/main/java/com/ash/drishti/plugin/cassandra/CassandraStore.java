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

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The session as the connector uses it: connecting in the background (a cluster down at start is retried, and reads fail
 * with a clear message until it answers), statements prepared once each, every request counted while in flight (for the
 * pool page), a day's partitions read {@code scan-threads} at a time, and the schema as the driver's metadata knows it. Every
 * statement text ever prepared in this JVM is remembered, so a test can check that each names a whole partition key.
 */
final class CassandraStore implements AutoCloseable {

    /** Every CQL text prepared in this JVM (connector and loader): {@code CassandraPartitionKeyTest} checks each. */
    static final Set<String> PREPARED = ConcurrentHashMap.newKeySet();

    private final CompletableFuture<CqlSession> session;
    private final Map<String, PreparedStatement> prepared = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Semaphore scans;
    private final Duration wait;
    private volatile String lastError;

    CassandraStore(CompletionStage<CqlSession> connecting, int scanThreads, Duration wait) {
        this.session = connecting.toCompletableFuture();
        this.session.whenComplete((s, e) -> lastError = e == null ? null : String.valueOf(e.getMessage()));
        this.scans = new Semaphore(Math.max(1, scanThreads));
        this.wait = wait;
    }

    /** The session, waiting at most the connect timeout for a cluster still being reached. */
    CqlSession session() {
        CqlSession s = session.getNow(null);
        if (s != null) {
            return s;
        }
        try {
            return session.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Cassandra is not reachable yet (still connecting" + (lastError == null ? "" : ": " + lastError) + ")");
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cassandra session failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** True once connected. */
    boolean connected() {
        return session.getNow(null) != null;
    }

    String lastError() {
        return lastError;
    }

    PreparedStatement prepare(String cql) {
        return prepared.computeIfAbsent(cql, k -> {
            PREPARED.add(k);
            return session().prepare(k);
        });
    }

    /** Runs one statement (prepared once, bound with {@code values}), counted while in flight. */
    ResultSet execute(String cql, Object... values) {
        BoundStatement b = prepare(cql).bind(values).setIdempotent(true);
        inFlight.incrementAndGet();
        try {
            return session().execute(b);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** Every row of one statement, page by page (the driver fetches the next page as the last one is consumed). */
    void forEach(String cql, Consumer<Row> each, Object... values) {
        inFlight.incrementAndGet();
        try {
            for (Row r : session().execute(prepare(cql).bind(values).setIdempotent(true))) {
                each.accept(r);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Runs {@code perPartition} for each of a day's buckets, {@code scan-threads} at a time across the connector (so two
     * searches on different days share the bound, as on Aerospike), on virtual threads. The first failure is thrown.
     */
    void eachBucket(int buckets, java.util.function.IntConsumer perPartition) {
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>(buckets);
            for (int b = 0; b < buckets; b++) {
                int bucket = b;
                running.add(pool.submit(() -> {
                    scans.acquireUninterruptibly();
                    try {
                        perPartition.accept(bucket);
                    } finally {
                        scans.release();
                    }
                }));
            }
            for (Future<?> f : running) {
                f.get();
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    int inFlight() {
        return inFlight.get();
    }

    Optional<KeyspaceMetadata> keyspace(String keyspace) {
        return session().getMetadata().getKeyspace(CqlIdentifier.fromInternal(keyspace));
    }

    Optional<TableMetadata> table(String keyspace, String table) {
        return keyspace(keyspace).flatMap(k -> k.getTable(CqlIdentifier.fromInternal(table)));
    }

    /** A table's non-key columns other than the document, by name, with whether each is a double (else text). */
    static Map<String, Boolean> promotedColumns(TableMetadata t) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (ColumnMetadata c : t.getColumns().values()) {
            String n = c.getName().asInternal();
            if (!CassandraLayout.PARTITION_KEY.contains(n) && !n.equals(CassandraLayout.ID) && !n.equals(CassandraLayout.DOC)) {
                out.put(n, c.getType().equals(DataTypes.DOUBLE));
            }
        }
        return out;
    }

    /** The kinds of a keyspace's data tables, by the comment the loader gives each ({@code drishti kind: netting-set}), else the table's name. */
    Map<String, String> kindTables(String keyspace) {
        Map<String, String> out = new LinkedHashMap<>();
        keyspace(keyspace).ifPresent(k -> {
            for (TableMetadata t : k.getTables().values()) {
                String name = t.getName().asInternal();
                if (name.equals(CassandraLayout.ENTITY_DAYS) || name.equals(CassandraLayout.KIND_DAYS) || !t.getColumns().containsKey(CqlIdentifier.fromInternal(CassandraLayout.DOC))) {
                    continue;
                }
                Object comment = t.getOptions().get(CqlIdentifier.fromInternal("comment"));
                String c = comment == null ? "" : comment.toString();
                out.put(c.startsWith(CassandraLayout.COMMENT_PREFIX) ? c.substring(CassandraLayout.COMMENT_PREFIX.length()) : name, name);
            }
        });
        return out;
    }

    /** Nodes up and known; {0, 0} before the session connects. */
    int[] nodes() {
        CqlSession s = session.getNow(null);
        if (s == null) {
            return new int[] {0, 0};
        }
        Collection<Node> all = s.getMetadata().getNodes().values();
        int up = (int) all.stream().filter(n -> n.getState() == NodeState.UP).count();
        return new int[] {up, all.size()};
    }

    /** Connections open over the nodes. */
    int openConnections() {
        CqlSession s = session.getNow(null);
        return s == null ? 0 : s.getMetadata().getNodes().values().stream().mapToInt(Node::getOpenConnections).sum();
    }

    Optional<String> clusterName() {
        CqlSession s = session.getNow(null);
        return s == null ? Optional.empty() : s.getMetadata().getClusterName();
    }

    @Override
    public void close() {
        CqlSession s = session.isCompletedExceptionally() ? null : session.getNow(null);
        if (s != null) {
            s.close();
        } else {
            session.thenAccept(CqlSession::close);                 // still connecting: close it when it does
        }
    }
}
