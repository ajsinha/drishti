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
package com.ash.drishti.server.disk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the VaR and exposure tiers share about their hot files on disk (2.9.7, O5): which to delete to come under a cap in bytes (the least
 * recently used, never an open one), a date's files, and the per-date drill-down of Admin, Caches. The callers hold their own lock; these
 * only read the maps they are given. A file lives in {@code <root>/<date>[-<currency>][-shard-<i>]/<prefix><digest><suffix>}.
 */
public final class HotFiles {

    private HotFiles() {}

    /** The bytes of a file, 0 when it is gone. */
    public static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** The bytes of these files. */
    public static long bytes(Collection<Path> files) {
        long b = 0;
        for (Path p : files) {
            b += size(p);
        }
        return b;
    }

    /** The largest of these files. */
    public static long largest(Collection<Path> files) {
        long m = 0;
        for (Path p : files) {
            m = Math.max(m, size(p));
        }
        return m;
    }

    /**
     * The files to delete so the rest is at most {@code target} bytes: the least recently used first ({@code used}: file to last use), never
     * one in {@code open} nor {@code keep}.
     */
    public static List<Path> beyond(Map<Path, Long> used, Set<Path> open, long target, Path keep) {
        Map<Path, Long> sizes = new LinkedHashMap<>();
        long total = 0;
        for (Path p : used.keySet()) {
            long s = size(p);
            sizes.put(p, s);
            total += s;
        }
        List<Path> byAge = new ArrayList<>(used.keySet());
        byAge.sort((a, b) -> Long.compare(used.get(a), used.get(b)));
        List<Path> gone = new ArrayList<>();
        for (Path p : byAge) {
            if (total <= target) {
                break;
            }
            if (!open.contains(p) && !p.equals(keep)) {
                gone.add(p);
                total -= sizes.get(p);
            }
        }
        return gone;
    }

    /** The files of one business date (every currency and shard) nobody has open. */
    public static List<Path> ofDate(Collection<Path> files, Set<Path> open, LocalDate date) {
        String d = date.toString();
        List<Path> out = new ArrayList<>();
        for (Path p : files) {
            if (p.getParent().getFileName().toString().startsWith(d) && !open.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** The oldest file and when it was last used. */
    public static String oldest(Map<Path, Long> used, Path root) {
        return used.entrySet().stream().min(Map.Entry.comparingByValue())
                .map(e -> root.relativize(e.getKey()).toString().replace('\\', '/') + " (last used " + Instant.ofEpochMilli(e.getValue()) + ")")
                .orElse("");
    }

    /** Per business date, newest first: files, bytes, open or on disk, when last used, and the USD file's digest. */
    public static List<Map<String, Object>> dates(Map<Path, Long> used, Set<Path> open, String prefix, String suffix) {
        Map<String, Map<String, Object>> by = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Map.Entry<Path, Long> e : used.entrySet()) {
            String folder = e.getKey().getParent().getFileName().toString();
            if (folder.length() < 10) {
                continue;
            }
            Map<String, Object> m = by.computeIfAbsent(folder.substring(0, 10), k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("date", k);
                x.put("state", "disk");
                x.put("files", 0);
                x.put("sizeBytes", 0L);
                x.put("lastUsedAt", "");
                return x;
            });
            m.put("files", (int) m.get("files") + 1);
            m.put("sizeBytes", (long) m.get("sizeBytes") + size(e.getKey()));
            String at = Instant.ofEpochMilli(e.getValue()).toString();
            if (at.compareTo((String) m.get("lastUsedAt")) > 0) {
                m.put("lastUsedAt", at);
            }
            if (open.contains(e.getKey())) {
                m.put("state", "open");
            }
            String n = e.getKey().getFileName().toString();
            if (folder.length() == 10 && n.startsWith(prefix) && n.endsWith(suffix)) {
                m.put("digest", n.substring(prefix.length(), n.length() - suffix.length()));
            }
        }
        return new ArrayList<>(by.values());
    }
}
