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
package com.ash.drishti.dataload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-process folder and the folder lock (DATA_LAKE_GUIDE.md and 4.4). A run's files are renamed into {@code inprocess/<run>/} (one
 * file system: a rename is atomic, so a file is either in the inbox or claimed, never both). {@code inbox.lock} is created atomically and
 * holds the run, the node, the process and a heartbeat the holder refreshes while it runs; a lock whose heartbeat is older than three intervals,
 * or whose process on this same node has ended, is taken over (and the takeover logged), so two servers on one shared folder never both run.
 */
public final class Claims {

    private static final Logger LOG = LoggerFactory.getLogger(Claims.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The lock's file, in the inbox's folder (beside inbox/, never in it). */
    public static final String LOCK = "inbox.lock";

    private final Path root;
    private final Clock clock;

    public Claims(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    public Path inprocess() {
        return root.resolve("inprocess");
    }

    public Path run(String run) {
        return inprocess().resolve(run);
    }

    /** The lock's holder as its file says (node, pid, heartbeat), or null when nobody holds it. */
    public JsonNode holder() {
        Path f = root.resolve(LOCK);
        try {
            return JSON.readTree(Files.readString(f));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            return JSON.createObjectNode().put("node", "?").put("error", "unreadable: " + e.getMessage());
        }
    }

    /**
     * Takes the lock for {@code node}, or answers null (the holder is alive: this cycle does not run). {@code stale}: a heartbeat older
     * than this is a holder gone. The returned lock refreshes its heartbeat every {@code beat} until closed.
     */
    public Lock lock(String node, Duration stale, Duration beat) throws IOException {
        return lock("", node, stale, beat);
    }

    /** {@link #lock(String, Duration, Duration)} for the run {@code run} (named in the lock, so a cycle due meanwhile can say which run loads). */
    public Lock lock(String run, String node, Duration stale, Duration beat) throws IOException {
        Files.createDirectories(root);
        Path f = root.resolve(LOCK);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Files.writeString(f, body(run, node, clock.instant(), clock.instant()).toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new Lock(f, run, node, beat);
            } catch (FileAlreadyExistsException e) {
                JsonNode h = holder();
                if (h == null) {
                    continue;                                                  // released between the two looks
                }
                String why = gone(h, node, stale);
                if (why == null) {
                    return null;
                }
                // take it over: one rename wins, whoever else tries finds the file gone and the new one in place
                Path aside = root.resolve("inbox-lock-taken-" + clock.millis() + "-" + ProcessHandle.current().pid());
                try {
                    Files.move(f, aside, StandardCopyOption.ATOMIC_MOVE);
                } catch (NoSuchFileException | FileAlreadyExistsException x) {
                    continue;
                }
                Files.deleteIfExists(aside);
                LOG.warn("data inbox: the folder lock of {} (process {}) was taken over by {}: {}", h.path("node").asText("?"),
                        h.path("pid").asText("?"), node, why);
            }
        }
        return null;
    }

    /** Why the holder {@code h} counts as gone, or null while it is alive. */
    private String gone(JsonNode h, String node, Duration stale) {
        Instant beat;
        try {
            beat = Instant.parse(h.path("heartbeat").asText());
        } catch (RuntimeException e) {
            return "its file could not be read";
        }
        Duration age = Duration.between(beat, clock.instant());
        if (age.compareTo(stale) > 0) {
            return "its heartbeat is " + age.toSeconds() + " s old (over " + stale.toSeconds() + " s)";
        }
        long pid = h.path("pid").asLong(-1);
        if (node.equals(h.path("node").asText()) && pid > 0 && pid != ProcessHandle.current().pid() && ProcessHandle.of(pid).isEmpty()) {
            return "its process " + pid + " on this node has ended";
        }
        return null;
    }

    private static ObjectNode body(String run, String node, Instant since, Instant beat) {
        return JSON.createObjectNode().put("run", run).put("node", node).put("pid", ProcessHandle.current().pid()).put("since", since.toString())
                .put("heartbeat", beat.toString());
    }

    /**
     * Claims a file and its marker into {@code inprocess/<run>/} (the marker first renamed beside it, so a crash between the two leaves the
     * file in the inbox without its marker and never loaded half-claimed). Returns the claimed file and marker (null when it had none).
     */
    public Path[] claim(Inbox.Ready r, String run) throws IOException {
        Path to = run(run);
        Files.createDirectories(to);
        Path marker = null;
        if (r.marker() != null) {
            marker = move(r.marker(), to.resolve(r.file().getFileName() + Inbox.READY));     // a short marker takes its file's full name
        }
        Path file = move(r.file(), to.resolve(r.file().getFileName()));
        return new Path[] {file, marker};
    }

    /** A rename within the folder's file system: atomic where the system can, else a plain move. */
    static Path move(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try {
            return Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            return Files.move(from, to);
        }
    }

    /** The folder lock while held: a heartbeat on its own thread; closing removes the file (if it is still ours). */
    public final class Lock implements AutoCloseable {

        private final Path file;
        private final String run;
        private final String node;
        private final Instant since;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final Thread beater;
        private volatile boolean closed;

        Lock(Path file, String run, String node, Duration beat) {
            this.file = file;
            this.run = run;
            this.node = node;
            this.since = clock.instant();
            this.beater = Thread.ofVirtual().name("dataload-lock-heartbeat").start(() -> {
                while (!closed) {
                    try {
                        Thread.sleep(Math.max(100, beat.toMillis()));
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!closed) {
                        beat();
                    }
                }
            });
        }

        /** Refreshes the heartbeat; when the file is no longer ours (taken over), the lock is lost and the cycle stops. */
        public void beat() {
            JsonNode h = holder();
            if (h == null || !node.equals(h.path("node").asText()) || h.path("pid").asLong() != ProcessHandle.current().pid()) {
                if (lost.compareAndSet(false, true)) {
                    LOG.warn("data inbox: the folder lock is no longer this node's ({}): the cycle stops after the current file",
                            h == null ? "removed" : "held by " + h.path("node").asText());
                }
                return;
            }
            try {
                Path tmp = file.resolveSibling(LOCK + "-" + ProcessHandle.current().pid() + ".new");
                Files.writeString(tmp, body(run, node, since, clock.instant()).toString());
                move(tmp, file);
            } catch (IOException e) {
                LOG.warn("data inbox: the folder lock's heartbeat could not be written: {}", e.getMessage());
            }
        }

        /** True while the lock is still this node's. */
        public boolean held() {
            return !lost.get() && !closed;
        }

        @Override
        public void close() {
            closed = true;
            beater.interrupt();
            if (lost.get()) {
                return;
            }
            JsonNode h = holder();
            if (h != null && node.equals(h.path("node").asText()) && h.path("pid").asLong() == ProcessHandle.current().pid()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    LOG.warn("data inbox: the folder lock could not be removed: {}", e.getMessage());
                }
            }
        }
    }
}
