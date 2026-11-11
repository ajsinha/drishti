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
package com.ash.drishti.common;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One operating-system watch service per file system for the whole JVM. Every server context (and the tests open many)
 * used to open its own {@link WatchService} per watched folder set, and on Linux each one is an inotify instance, of
 * which a user has few ({@code fs.inotify.max_user_instances}, often 128). Here any number of folders and listeners
 * share a single service and a single daemon dispatcher thread; the service opens with the first registration and
 * closes with the last.
 *
 * <p>A listener is told that something happened in its folder (a file created, changed or deleted, an event overflow, or
 * the folder going away) and is expected to look again, so it must be quick and must not block: hand off to a queue or
 * flag. When a folder is deleted its registration turns invalid ({@link Registration#valid()}); register again once the
 * folder is back.
 */
public final class SharedWatchService {

    private static final Logger LOG = LoggerFactory.getLogger(SharedWatchService.class);
    private static final SharedWatchService DEFAULT = new SharedWatchService(FileSystems.getDefault());

    /** A live subscription to a folder; closing it stops the notifications and releases the key. */
    public interface Registration extends AutoCloseable {
        /** False once the folder was deleted or the registration closed: register again to resume. */
        boolean valid();

        @Override
        void close();
    }

    private final FileSystem fileSystem;
    private final ReentrantLock lock = new ReentrantLock();
    private Hub hub;                                           // guarded by lock
    private final AtomicInteger opened = new AtomicInteger();

    public SharedWatchService(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    /** The JVM-wide instance for the default file system. */
    public static SharedWatchService defaultInstance() {
        return DEFAULT;
    }

    /** How many operating-system watch services this instance ever opened (tests assert it stays small). */
    public int servicesOpened() {
        return opened.get();
    }

    /** True while an operating-system watch service is open (some registration is live). */
    public boolean open() {
        lock.lock();
        try {
            return hub != null;
        } finally {
            lock.unlock();
        }
    }

    /** Registers {@code listener} for create, modify and delete events directly inside {@code dir}. */
    public Registration register(Path dir, Runnable listener) throws IOException {
        lock.lock();
        try {
            Hub h = hub;
            if (h == null) {
                h = new Hub(fileSystem.newWatchService());
                opened.incrementAndGet();
                hub = h;
            }
            try {
                return h.add(dir, listener);
            } catch (IOException | RuntimeException e) {
                releaseIfEmpty(h);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Callers hold the lock. */
    private void releaseIfEmpty(Hub h) {
        if (hub == h && h.empty()) {
            hub = null;
            h.shutdown();
        }
    }

    private void remove(Hub h, Listener l) {
        lock.lock();
        try {
            h.remove(l);
            releaseIfEmpty(h);
        } finally {
            lock.unlock();
        }
    }

    private static final class Listener {
        final Runnable action;
        volatile boolean valid = true;

        Listener(Runnable action) {
            this.action = action;
        }
    }

    private final class Hub {
        private final WatchService service;
        private final Map<WatchKey, List<Listener>> listeners = new ConcurrentHashMap<>();
        private final Thread dispatcher;

        Hub(WatchService service) {
            this.service = service;
            this.dispatcher = new Thread(this::dispatch, "drishti-shared-watch");
            dispatcher.setDaemon(true);
            dispatcher.start();
        }

        /** Under the facility lock. */
        Registration add(Path dir, Runnable action) throws IOException {
            WatchKey key = dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            Listener l = new Listener(action);
            listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(l);
            Hub self = this;
            AtomicBoolean closed = new AtomicBoolean();
            return new Registration() {
                @Override
                public boolean valid() {
                    return l.valid && key.isValid();
                }

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        l.valid = false;
                        SharedWatchService.this.remove(self, l);
                    }
                }
            };
        }

        /** Under the facility lock: drops the listener, and cancels the key with its last listener. */
        void remove(Listener l) {
            for (Map.Entry<WatchKey, List<Listener>> e : listeners.entrySet()) {
                if (e.getValue().remove(l) && e.getValue().isEmpty()) {
                    listeners.remove(e.getKey());
                    e.getKey().cancel();
                }
            }
        }

        boolean empty() {
            return listeners.isEmpty();
        }

        void shutdown() {
            try {
                service.close();
            } catch (IOException e) {
                LOG.debug("closing the shared watch service", e);
            }
            dispatcher.interrupt();
        }

        private void dispatch() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = service.take();
                    key.pollEvents();                          // contents do not matter, OVERFLOW included: listeners look again
                    boolean alive = key.reset();
                    List<Listener> ls = listeners.get(key);
                    if (ls != null) {
                        for (Listener l : ls) {
                            if (!alive) {
                                l.valid = false;               // the folder was deleted: its key will never fire again
                            }
                            try {
                                l.action.run();
                            } catch (RuntimeException e) {
                                LOG.warn("a file watch listener failed; watching goes on", e);
                            }
                        }
                        if (!alive) {
                            listeners.remove(key);
                        }
                    }
                }
            } catch (InterruptedException | ClosedWatchServiceException e) {
                // closed with the last registration
            }
        }
    }
}
