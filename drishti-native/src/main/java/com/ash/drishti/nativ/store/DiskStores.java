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
package com.ash.drishti.nativ.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens disk stores (DRISHTI_NATIVE.md sections 2 and 4): RocksDB when it loads, else H2's MVStore, with a loud warning once and the
 * status DEGRADED; the server always starts. {@code drishti.native.store.mode} decides: {@code auto} (the default: RocksDB, else Java),
 * {@code java} (MVStore, chosen: no warning), {@code native} (RocksDB or an error: for a test bench). The server sets the mode from its
 * settings at start ({@link #configure}); plain Java tools read the system property of the same name.
 */
public final class DiskStores {

    private static final Logger LOG = LoggerFactory.getLogger(DiskStores.class);

    /** The setting's name, also read as a system property. */
    public static final String MODE_PROPERTY = "drishti.native.store.mode";

    /** How the implementation is chosen. */
    public enum Mode {
        /** RocksDB when it loads, else MVStore with a warning. */
        AUTO,
        /** MVStore, chosen. */
        JAVA,
        /** RocksDB, else an error. */
        NATIVE;

        /** {@code auto}, {@code java} or {@code native} (any case); anything else reads as auto. */
        public static Mode of(String text) {
            if (text == null) {
                return AUTO;
            }
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "java", "off" -> JAVA;
                case "native", "on" -> NATIVE;
                default -> AUTO;
            };
        }
    }

    /**
     * What serves the disk stores of this process.
     *
     * @param impl {@code rocksdb} or {@code mvstore}
     * @param mode the mode read
     * @param degraded auto mode fell back to Java
     * @param reason why Java serves, or null
     * @param version RocksDB's version when it serves
     */
    public record Status(String impl, Mode mode, boolean degraded, String reason, String version) {

        /** For Health and Admin. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", degraded ? "DEGRADED" : "OK");
            m.put("impl", impl);
            m.put("mode", mode.name().toLowerCase(Locale.ROOT));
            m.put("reason", reason);
            m.put("version", version);
            return m;
        }
    }

    private static volatile Mode mode = Mode.of(System.getProperty(MODE_PROPERTY));
    private static volatile boolean warned;

    private DiskStores() {
    }

    /** Sets the mode (the server, from {@code drishti.native.store.mode}); stores opened from now on follow it. */
    public static void configure(String text) {
        mode = Mode.of(text);
    }

    public static Mode mode() {
        return mode;
    }

    /**
     * Opens the store in {@code dir} with the implementation the mode and the platform allow.
     *
     * @return the store, or null when {@code how.create()} is false and the folder holds none that opens
     * @throws IOException when it cannot be opened (or, in {@code native} mode, when RocksDB cannot load)
     */
    public static DiskStore open(Path dir, DiskStore.Options how) throws IOException {
        Mode m = mode;
        if (m == Mode.JAVA) {
            return MvStore.open(dir, how, System::currentTimeMillis);
        }
        String why = RocksDbStore.load();
        if (why == null) {
            return RocksDbStore.open(dir, how);
        }
        if (m == Mode.NATIVE) {
            throw new IOException("RocksDB cannot load and " + MODE_PROPERTY + " is native: " + why);
        }
        warn(why);
        return MvStore.open(dir, how, System::currentTimeMillis);
    }

    /** This process's disk store implementation (loads RocksDB once to say so, unless Java is chosen). */
    public static Status status() {
        Mode m = mode;
        if (m == Mode.JAVA) {
            return new Status("mvstore", m, false, "chosen (" + MODE_PROPERTY + ": java)", null);
        }
        String why = RocksDbStore.load();
        return why == null ? new Status("rocksdb", m, false, null, RocksDbStore.version()) : new Status("mvstore", m, m == Mode.AUTO, why, null);
    }

    private static void warn(String why) {
        if (warned) {
            return;
        }
        warned = true;
        LOG.warn("************************************************************************************************");
        LOG.warn("DISK STORE ON JAVA: RocksDB cannot load here ({}).", why);
        LOG.warn("The disk caches and the message-state stores run on H2's MVStore (pure Java): same answers, slower writes;");
        LOG.warn("a store RocksDB wrote earlier is not read (each keeps its own files), so those caches start empty.");
        LOG.warn("Fix: a supported platform (Linux or Windows x86-64, macOS); or set {}: java to choose Java and silence this.", MODE_PROPERTY);
        LOG.warn("************************************************************************************************");
    }

    /**
     * Whether another process has the store in {@code dir} open: RocksDB holds a lock on its {@code LOCK} file while open, MVStore on its
     * file (locks {@link FileChannel#tryLock} sees on Linux and macOS). A folder without one, or whose lock is free, is not in use.
     */
    public static boolean inUse(Path dir) {
        return locked(dir.resolve("LOCK")) || locked(dir.resolve(MvStore.FILE));
    }

    private static boolean locked(Path lock) {
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) {
                return true;
            }
            l.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
