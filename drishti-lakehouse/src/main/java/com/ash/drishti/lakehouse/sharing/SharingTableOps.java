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
package com.ash.drishti.lakehouse.sharing;

import com.ash.drishti.iceberg.catalog.TableOps;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.lakehouse.delta.DeltaFiles;
import com.ash.drishti.lakehouse.delta.DeltaSchemas;
import com.ash.drishti.lakehouse.delta.DeltaView;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One shared table read as an Iceberg table: its schema from the server's metadata ({@link DeltaSchemas}), its files from a query
 * ({@link SharingClient#query}), each named {@code delta-sharing://<key>/<file id>} and read by its pre-signed URL ({@link SharedFiles}),
 * renewed when it nears expiry. Only the current version is served: a share's history is not asked for, so <em>known at</em> reads the
 * current version too. Never written. Thread-safe.
 */
public final class SharingTableOps implements TableOps, AutoCloseable, SharedFiles.Source {

    private final SharingClient client;
    private final String share;
    private final String schema;
    private final String table;
    private final JsonNode hints;
    private final FileAccess io;
    private final String key = UUID.randomUUID().toString();
    private final ReentrantLock renewing = new ReentrantLock();
    private final Map<String, SharingClient.SharedFile> files = new ConcurrentHashMap<>();
    private volatile DeltaView view;
    private volatile long version = -1;
    private volatile List<FileTask> tasks;

    /**
     * @param hints the predicate hints sent with every query (null: none), from the connector's {@code hints.<table>}
     */
    public SharingTableOps(SharingClient client, String share, String schema, String table, JsonNode hints, FileAccess io) {
        this.client = client;
        this.share = share;
        this.schema = schema;
        this.table = table;
        this.hints = hints;
        this.io = io;
        SharedFiles.register(key, this);
    }

    private String name() {
        return share + "." + schema + "." + table;
    }

    @Override
    public Loaded load() throws IOException {
        SharingClient.Metadata md = client.metadata(share, schema, table);
        DeltaSchemas.Converted c;
        try {
            c = DeltaSchemas.convert(md.schemaString(), md.partitionColumns());
        } catch (IllegalArgumentException e) {
            throw new IOException("the shared table " + name() + ": " + e.getMessage(), e);
        }
        // a share's history is not asked for: one snapshot, from the start of time, so every known-at reads it
        DeltaView v = DeltaView.of(client.profile().endpoint() + "/" + name(), md.id(), c, List.of(new DeltaView.Version(md.version(), 0L)));
        view = v;
        return new Loaded(v.metadata(), "delta-sharing:" + name() + "@v" + md.version(), null);
    }

    @Override
    public Optional<Loaded> refresh(Loaded current) throws IOException {
        long now = client.version(share, schema, table);
        long had = Long.parseLong(current.location().substring(current.location().lastIndexOf("@v") + 2));
        return now == had ? Optional.empty() : Optional.of(load());
    }

    @Override
    public Optional<List<FileTask>> plan(Loaded current, Snapshot snapshot, Expr filter, ExecutorService pool) {
        long wanted = DeltaView.version(snapshot.snapshotId());
        if (tasks == null || version != wanted) {
            try {
                query(wanted);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Optional.of(tasks);
    }

    /** Asks the server for the files of {@code wanted}: their tasks, and their URLs for the reader. */
    private void query(long wanted) throws IOException {
        renewing.lock();
        try {
            SharingClient.Answer a = client.query(share, schema, table, wanted, hints);
            DeltaView v = view;
            List<FileTask> out = new ArrayList<>();
            Map<String, SharingClient.SharedFile> urls = new java.util.HashMap<>();
            for (SharingClient.SharedFile f : a.files()) {
                urls.put(f.id(), f);
                out.add(DeltaFiles.task(v.converted(), v.spec(), SharedFiles.path(key, f.id()), f.size(), f.partitionValues(), f.stats(),
                        DeltaView.snapshotId(wanted), List.of()));
            }
            files.putAll(urls);
            files.keySet().retainAll(urls.keySet());
            tasks = List.copyOf(out);
            version = wanted;
        } finally {
            renewing.unlock();
        }
    }

    @Override
    public SharingClient.SharedFile file(String id) throws IOException {
        SharingClient.SharedFile f = files.get(id);
        if (f == null) {
            throw new FileNotFoundException("the shared table " + name() + " has no file " + id + " in version " + version
                    + " (the share changed: it is read again at the next refresh)");
        }
        return f;
    }

    @Override
    public void renew() throws IOException {
        long v = version;
        long before = files.values().stream().mapToLong(SharingClient.SharedFile::expiresAtMs).min().orElse(Long.MAX_VALUE);
        renewing.lock();                                         // another reader renewing first: wait for it, then use its URLs
        try {
            long now = files.values().stream().mapToLong(SharingClient.SharedFile::expiresAtMs).min().orElse(Long.MAX_VALUE);
            if (now != before) {
                return;
            }
            query(v);
        } finally {
            renewing.unlock();
        }
    }

    @Override
    public HttpClient http() {
        return client.http().client();
    }

    @Override
    public Loaded commit(Loaded base, TableUpdate update) {
        throw new UnsupportedOperationException("a shared table (" + name() + ") is read only");
    }

    @Override
    public FileAccess io(Loaded current) {
        return io;
    }

    @Override
    public String describe() {
        return name() + " (Delta Sharing, " + client.profile().endpoint() + ")";
    }

    @Override
    public void close() {
        SharedFiles.unregister(key);
    }
}
