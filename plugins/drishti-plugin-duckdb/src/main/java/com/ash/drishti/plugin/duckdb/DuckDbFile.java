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
package com.ash.drishti.plugin.duckdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.duckdb.DuckDBConnection;

/**
 * One DuckDB database file opened read-only, shared by every connector of the process that reads it (the six data
 * domains of the banking packs are six schemas of one file): one DuckDB instance, one memory limit, one buffer pool.
 *
 * <p>DuckDB lets one process write a file or several processes read it, never both, so {@link DuckDbLoader} writes a
 * new file beside it and renames it over the old one. {@link #check()} notices that (the file's identity or
 * modification time changed) and opens the new file as a new <em>generation</em>: new reads go to it at once, and the
 * old instance is closed when its last read in flight returns. Until the swap the old file stays open and readable
 * (on Linux and macOS a renamed-over file lives on while it is open).
 *
 * <p>Connections are duplicated from the generation's instance on demand ({@link DuckDBConnection#duplicate()}), kept
 * for reuse, and bounded by a semaphore of {@code pool-size} permits per attached connector. DuckDB runs the
 * statements of different connections of one instance at the same time, each using up to {@code threads} cores.
 */
final class DuckDbFile {

    /** Work on a pooled connection. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection c) throws Exception;
    }

    private static final System.Logger LOG = System.getLogger(DuckDbFile.class.getName());
    private static final Map<Path, DuckDbFile> OPEN = new HashMap<>();

    /** One opened instance of the file as it was at one moment. */
    private static final class Generation {
        final long number;
        final DuckDBConnection root;
        final Object identity;
        final String database;
        final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
        final AtomicInteger busy = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean retired;

        Generation(long number, DuckDBConnection root, Object identity, String database) {
            this.number = number;
            this.root = root;
            this.identity = identity;
            this.database = database;
        }

        void release() {
            if (busy.decrementAndGet() == 0 && retired) {
                closeAll();
            }
        }

        void retire() {
            retired = true;
            if (busy.get() == 0) {
                closeAll();
            }
        }

        void closeAll() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Connection c;
            while ((c = idle.poll()) != null) {
                quietly(c);
            }
            quietly(root);
        }
    }

    private final Path path;
    private final Properties properties;
    private final Semaphore permits = new Semaphore(0);
    private int attached;
    private volatile Generation current;
    private volatile String problem;
    private long generations;
    private final ReentrantLock checkLock = new ReentrantLock();

    private DuckDbFile(Path path, Properties properties) {
        this.path = path;
        this.properties = properties;
    }

    /**
     * The shared file at {@code path}, opened when first asked for; {@code poolSize} more reads may run at once. The
     * first connector's {@code memoryLimit} and {@code threads} configure the instance.
     */
    static DuckDbFile attach(Path path, String memoryLimit, int threads, int poolSize) {
        Path key = path.toAbsolutePath().normalize();
        DuckDbFile f;
        synchronized (OPEN) {
            f = OPEN.get(key);
            if (f == null) {
                Properties p = new Properties();
                p.setProperty("duckdb.read_only", "true");
                p.setProperty("jdbc_instance_cache", "false");                // a swapped file must open as a new instance
                p.setProperty("jdbc_stream_results", "true");                 // a day of columns streams, never materialised whole
                p.setProperty("memory_limit", memoryLimit);
                p.setProperty("temp_directory", Path.of(System.getProperty("java.io.tmpdir"), "drishti-duckdb").toString());
                if (threads > 0) {
                    p.setProperty("threads", String.valueOf(threads));
                }
                f = new DuckDbFile(key, p);
                OPEN.put(key, f);
            }
            f.attached++;
        }
        f.permits.release(poolSize);
        f.check();
        return f;
    }

    /** A connector lets go; the last one closes the file. */
    void detach(int poolSize) {
        permits.acquireUninterruptibly(Math.min(poolSize, permits.availablePermits()));
        synchronized (OPEN) {
            if (--attached > 0) {
                return;
            }
            OPEN.remove(path);
        }
        Generation g = current;
        current = null;
        if (g != null) {
            g.retire();
        }
    }

    Path path() {
        return path;
    }

    /** The generation now read (0 before the file was first opened). */
    long generation() {
        Generation g = current;
        return g == null ? 0 : g.number;
    }

    /** The name DuckDB gives the open file's database (catalog), for fully qualified table names; null when not open. */
    String database() {
        Generation g = current;
        return g == null ? null : g.database;
    }

    /** Why the file is not open or was not reopened, or null. */
    String problem() {
        return problem;
    }

    /**
     * Opens the file when it is not open or has been replaced since; cheap when nothing changed (one {@code stat}).
     * A file that is missing or does not open leaves the current generation serving, and says why in {@link #problem()}.
     */
    void check() {
        checkLock.lock();                                             // a ReentrantLock, not synchronized: stat + open + query block, which would pin a virtual thread's carrier on Java 21
        try {
            checkLocked();
        } finally {
            checkLock.unlock();
        }
    }

    private void checkLocked() {
        Object identity;
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
            identity = java.util.List.of(Objects.requireNonNullElse(a.fileKey(), ""), a.lastModifiedTime(), a.size());
        } catch (IOException e) {
            problem = "no DuckDB file at " + path + (current == null ? "" : " (serving the file opened before)");
            return;
        }
        Generation old = current;
        if (old != null && old.identity.equals(identity)) {
            problem = null;
            return;
        }
        try {
            DuckDBConnection root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + path, properties);
            String database;
            try (var st = root.createStatement(); var rs = st.executeQuery("SELECT current_database()")) {
                rs.next();
                database = rs.getString(1);
            } catch (SQLException e) {
                quietly(root);
                throw e;
            }
            current = new Generation(++generations, root, identity, database);
            problem = null;
            if (old != null) {
                LOG.log(System.Logger.Level.INFO, "{0}: replaced by a new load, reopened (generation {1})", path, generations);
                old.retire();                                         // closed when its last read returns
            }
        } catch (SQLException e) {
            problem = "DuckDB file " + path + " not opened: " + e.getMessage();
            LOG.log(System.Logger.Level.WARNING, problem);
        }
    }

    /** Runs {@code work} on a connection of the current generation. */
    <T> T with(Work<T> work) throws Exception {
        if (!permits.tryAcquire(5, TimeUnit.SECONDS)) {
            throw new SQLException("no free connection to " + path);
        }
        try {
            Generation g;
            while (true) {
                g = current;
                if (g == null) {
                    throw new SQLException(problem != null ? problem : "no DuckDB file at " + path);
                }
                g.busy.incrementAndGet();
                if (!g.retired) {
                    break;
                }
                g.release();                                          // swapped meanwhile: take the new one
            }
            try {
                Connection c = g.idle.poll();
                if (c == null) {
                    c = g.root.duplicate();
                }
                try {
                    return work.run(c);
                } finally {
                    g.idle.offer(c);
                }
            } finally {
                g.release();
            }
        } finally {
            permits.release();
        }
    }

    private static void quietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            // closing anyway
        }
    }
}
