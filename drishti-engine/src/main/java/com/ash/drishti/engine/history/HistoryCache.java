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
package com.ash.drishti.engine.history;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.HistoryDay;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Days read for a history, unmasked and unformatted, keyed by everything that decides them and by the kind's {@code dataVersion}
 * (HISTORY_QUERIES.md section 11). A new load changes the version, so an entry is never stale and needs no expiry; a kind whose sources
 * give no version is never cached. Bounded by an estimate of its size; the least recently used entries go first. Masks and formats are
 * applied per answer, so one entry serves every user, and nothing here ever leaves the server.
 */
public final class HistoryCache {

    private record Entry(List<HistoryDay> days, long bytes) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;
    private long hits;
    private long misses;

    public List<HistoryDay> get(String key) {
        lock.lock();
        try {
            Entry e = entries.get(key);
            if (e == null) {
                misses++;
                return null;
            }
            hits++;
            return e.days();
        } finally {
            lock.unlock();
        }
    }

    /** Keeps the days under {@code key}, dropping the least recently used entries while over {@code maxBytes} (0: nothing is kept). */
    public void put(String key, List<HistoryDay> days, long maxBytes) {
        if (maxBytes <= 0) {
            return;
        }
        long size = estimate(days);
        if (size > maxBytes / 4) {
            return;                                                           // one huge answer would push out everything else
        }
        lock.lock();
        try {
            Entry old = entries.put(key, new Entry(List.copyOf(days), size));
            bytes += size - (old == null ? 0 : old.bytes());
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            while (bytes > maxBytes && it.hasNext()) {
                Map.Entry<String, Entry> e = it.next();
                bytes -= e.getValue().bytes();
                it.remove();
            }
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            entries.clear();
            bytes = 0;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> stats() {
        lock.lock();
        try {
            return Map.of("entries", entries.size(), "bytes", bytes, "hits", hits, "misses", misses);
        } finally {
            lock.unlock();
        }
    }

    /** About 48 bytes per node of each document, plus the day's own record. */
    static long estimate(List<HistoryDay> days) {
        long n = 0;
        for (HistoryDay d : days) {
            n += 96 + 48L * nodes(d.document().data(), 0);
        }
        return n;
    }

    private static long nodes(DataNode n, int depth) {
        if (depth > 64) {
            return 1;
        }
        long c = 1;
        if (n instanceof DataNode.Obj o) {
            for (DataNode v : o.fields().values()) {
                c += nodes(v, depth + 1);
            }
        } else if (n instanceof DataNode.Arr a) {
            for (int i = 0; i < a.size(); i++) {
                c += nodes(a.get(i), depth + 1);
            }
        }
        return c;
    }
}
