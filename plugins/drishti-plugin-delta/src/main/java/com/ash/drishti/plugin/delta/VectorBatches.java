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
package com.ash.drishti.plugin.delta;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reads of one file's vector columns that arrive together are made one read. A record's vectors (a scenario PV's 509 P&Ls) live in
 * its file's vector columns, and reading them decodes the file's whole vector columns whatever the record: a page of 100 records read
 * one by one decoded the same file 100 times (1.6 s for the SPV list, 2.4 s for XPATH). The first read of a file waits a moment
 * ({@link #WINDOW_NANOS}) for the others asked at the same time (a page's records are fetched concurrently), then reads every id
 * asked in one pass and hands each its vectors. A record asked alone waits the window once.
 */
final class VectorBatches {

    /** How long the first read of a file waits for the others of the same page. */
    static final long WINDOW_NANOS = 3_000_000L;

    private static final class Batch {
        final Set<String> ids = new HashSet<>();
        final CompletableFuture<Map<String, Map<String, double[]>>> done = new CompletableFuture<>();
        boolean closed;
    }

    private final ConcurrentHashMap<String, Batch> open = new ConcurrentHashMap<>();

    /**
     * The vectors of {@code id} from the file {@code key} names (its kind, date, file and columns), read by {@code read} with every id
     * asked of the same file at the same time.
     */
    Map<String, double[]> get(String key, String id, Function<Set<String>, Map<String, Map<String, double[]>>> read) {
        while (true) {
            boolean[] leader = {false};
            Batch b = open.computeIfAbsent(key, k -> {
                leader[0] = true;
                return new Batch();
            });
            synchronized (b) {
                if (b.closed) {
                    continue;                                   // read already started: join the next batch
                }
                b.ids.add(id);
            }
            if (leader[0]) {
                java.util.concurrent.locks.LockSupport.parkNanos(WINDOW_NANOS);
                Set<String> ids;
                synchronized (b) {
                    b.closed = true;
                    open.remove(key, b);
                    ids = Set.copyOf(b.ids);
                }
                try {
                    b.done.complete(read.apply(ids));
                } catch (RuntimeException e) {
                    b.done.completeExceptionally(e);
                    throw e;
                }
            }
            try {
                return b.done.join().getOrDefault(id, Map.of());
            } catch (java.util.concurrent.CompletionException e) {
                throw e.getCause() instanceof RuntimeException r ? r : e;
            }
        }
    }
}
