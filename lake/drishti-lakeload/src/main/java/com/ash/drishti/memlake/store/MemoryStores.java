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
package com.ash.drishti.memlake.store;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The memory stores of this JVM, by name: every reader and writer that meets a {@code mem://<store>/…} path reaches the same store here
 * (the Delta connector, the lake writer, the Iceberg engine, the replenish jobs). A store asked for before the configuration defines it
 * exists with a cap of zero (it holds nothing and refuses writes), so a connector that starts first simply finds its tables empty.
 */
public final class MemoryStores {

    /** The default chunk: 1 MB. */
    public static final int DEFAULT_CHUNK_BYTES = 1 << 20;

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");
    private static final Map<String, MemoryStorage> STORES = new ConcurrentHashMap<>();

    private MemoryStores() {}

    /** Whether {@code path} is a memory path ({@code mem://…}). */
    public static boolean isMemory(String path) {
        return path != null && path.trim().regionMatches(true, 0, MemoryStorage.SCHEME + "://", 0, MemoryStorage.SCHEME.length() + 3);
    }

    /** The store's name in a memory path: {@code mem://news/news} → {@code news}. */
    public static String storeName(String path) {
        if (!isMemory(path)) {
            throw new IllegalArgumentException("not a memory path: " + path);
        }
        String rest = path.trim().substring(MemoryStorage.SCHEME.length() + 3);
        int slash = rest.indexOf('/');
        return (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    /** The store that answers {@code path}. */
    public static MemoryStorage forPath(String path) {
        return store(storeName(path));
    }

    /** The store of this name, made empty with a cap of zero when it is not defined yet. */
    public static MemoryStorage store(String name) {
        check(name);
        return STORES.computeIfAbsent(name, n -> new MemoryStorage(n, 0, DEFAULT_CHUNK_BYTES));
    }

    /**
     * Defines (or redefines) a store: its hard cap, its chunk size and its contract. A store already holding data keeps its chunk size (a
     * new one applies after a restart); a lower cap releases its free chunks at once.
     */
    public static MemoryStorage define(String name, long capBytes, int chunkBytes, String contract) {
        check(name);
        MemoryStorage s = STORES.computeIfAbsent(name, n -> new MemoryStorage(n, capBytes, chunkBytes));
        if (s.pool().chunkBytes() != chunkBytes) {
            s.rechunk(capBytes, chunkBytes);                    // the same store object: readers that met it first keep reaching it
        }
        s.pool().cap(capBytes);
        s.contract(contract);
        return s;
    }

    /** Every store, by name. */
    public static List<MemoryStorage> all() {
        return STORES.values().stream().sorted(Comparator.comparing(MemoryStorage::name)).toList();
    }

    /** The stores' caps added up: what the JVM's direct-memory limit must cover beyond the server's own 256 MB. */
    public static long totalCapBytes() {
        return STORES.values().stream().mapToLong(s -> s.pool().capBytes()).sum();
    }

    /** Drops a store and frees its memory (tests; a store removed from the configuration). */
    public static void remove(String name) {
        MemoryStorage s = STORES.remove(name);
        if (s != null) {
            s.clear();
            s.pool().cap(0);
        }
    }

    private static void check(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a memory store's name is lower-case letters, digits, '-' and '_': " + name);
        }
    }
}
