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
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Heap dumps kept within a count (RESOURCES_AND_SCALE.md section 7.4, 2.9.7): a dump is about the live heap (up to 36 GB on a shard owner),
 * and before 2.9.7 every out-of-memory error left one for ever. At start the oldest beyond {@code drishti.disk.max-dumps} (2) are deleted
 * from the JVM's {@code -XX:HeapDumpPath} folder (or {@code drishti.disk.dumps-dir}).
 */
public final class Dumps {

    private Dumps() {}

    /** The dump folder: the JVM's {@code -XX:HeapDumpPath} (its folder when it names a file), else {@code fallback}. */
    public static Path folder(String fallback) {
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-XX:HeapDumpPath=")) {
                Path p = Path.of(a.substring("-XX:HeapDumpPath=".length()));
                return p.getFileName() != null && p.getFileName().toString().endsWith(".hprof") ? p.getParent() : p;
            }
        }
        return Path.of(fallback);
    }

    /** The dumps in {@code dir}, newest first. */
    public static List<Path> list(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".hprof") && Files.isRegularFile(p)).toList());
            out.sort(Comparator.comparingLong(Dumps::modified).reversed());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Deletes the dumps in {@code dir} beyond the newest {@code keep}; returns how many went. */
    public static int prune(Path dir, int keep) {
        List<Path> all = list(dir);
        int gone = 0;
        for (Path p : all.subList(Math.min(keep, all.size()), all.size())) {
            try {
                Files.deleteIfExists(p);
                gone++;
            } catch (IOException e) {
                // being written: the next start
            }
        }
        return gone;
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
