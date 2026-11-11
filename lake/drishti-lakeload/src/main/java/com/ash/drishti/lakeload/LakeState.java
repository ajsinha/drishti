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
package com.ash.drishti.lakeload;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A local lake's state folder, {@code <lake>/_drishti-lake}, used as {@code drishti.py lake build} uses it: the lock (shared on the lake,
 * exclusive on {@code locks/<domain>/<table>.lock}; on Windows exclusive on the whole lake, as there), the run log
 * ({@code runs.log}), each run's summary ({@code runs/<run>.json}) and {@code last-run.json}. The lock is an operating-system lock, so a
 * run that dies releases it. On Linux and macOS lake build takes both a {@code flock} and a POSIX lock ({@code lockf}) on the same files,
 * so it and this loader exclude each other.
 */
final class LakeState {

    static final String STATE = "_drishti-lake";

    /** Another run holds the lock. */
    static final class Locked extends Exception {
        private static final long serialVersionUID = 1L;

        Locked(String message) {
            super(message);
        }
    }

    private LakeState() {}

    /** Takes the lock for {@code domain/table} of the lake at {@code lake}; closing the result releases it. */
    static AutoCloseable lock(Path lake, String domain, String table) throws IOException, Locked {
        boolean windows = com.ash.drishti.lakeio.LocalPaths.WINDOWS;
        List<AutoCloseable> held = new ArrayList<>();
        try {
            Path lakeLock = lake.resolve(STATE).resolve("lock");
            if (windows) {
                held.add(take(lakeLock, false, "the lake"));
            } else {
                held.add(take(lakeLock, true, "the lake"));
                held.add(take(lake.resolve(STATE).resolve("locks").resolve(domain).resolve(table + ".lock"), false, "table " + domain + "/" + table));
            }
        } catch (IOException | Locked | RuntimeException e) {
            release(held);
            throw e;
        }
        return () -> release(held);
    }

    private static void release(List<AutoCloseable> held) {
        for (int i = held.size() - 1; i >= 0; i--) {
            try {
                held.get(i).close();
            } catch (Exception e) {
                // released by the operating system when the process ends anyway
            }
        }
    }

    private static AutoCloseable take(Path file, boolean shared, String what) throws IOException, Locked {
        Files.createDirectories(file.getParent());
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = ch.tryLock(0, 1, shared);                     // byte 0, as lake build locks it on Windows
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            String holder;
            try {
                holder = Files.readString(file, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                holder = "";
            }
            ch.close();
            throw new Locked(what + " is locked by " + (holder.isEmpty() ? "another run" : holder));
        }
        if (!shared) {
            String who = LoadReport.JSON.createObjectNode().put("pid", ProcessHandle.current().pid()).put("host", host())
                    .put("since", LoadReport.now()).put("by", "lakeload").toString();
            ch.truncate(0);
            ch.write(ByteBuffer.wrap(who.getBytes(StandardCharsets.UTF_8)), 0);
        }
        FileLock l = lock;
        return () -> {
            try {
                if (!shared) {
                    ch.truncate(0);
                }
                l.release();
            } finally {
                ch.close();
            }
        };
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** Writes {@code runs/<run>.json}, {@code last-run.json} (whole, by a rename) and appends the run's line to {@code runs.log}. */
    static Path record(Path lake, String run, ObjectNode summary, String logLine) throws IOException {
        Path state = lake.resolve(STATE);
        Path runs = state.resolve("runs");
        Files.createDirectories(runs);
        String text = LoadReport.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        Path mine = runs.resolve(run + ".json");
        Files.writeString(mine, text);
        Path tmp = state.resolve("last-run.json." + ProcessHandle.current().pid());
        Files.writeString(tmp, text);
        Files.move(tmp, state.resolve("last-run.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(state.resolve("runs.log"), logLine + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return mine;
    }
}
