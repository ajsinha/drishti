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
package com.ash.drishti.plugin.file;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The files of a connector's folder at one rescan, by kind, and each kind's table identity (DRISHTI_NET.md section 10):
 * {@code file:<folder>#<fingerprint>}, the fingerprint a SHA-256 of the kind's files (each one's path under the folder, size and
 * modification time, in path order). Two servers that read the same files (one folder, or copies at the same path on another
 * machine that kept their sizes and times) say the same identity and Drishti*net takes them for replicas; files that differ in any
 * of these make them shards, never wrongly replicas. A file written that one of them has not rescanned yet
 * makes them shards until it has (both are asked: a count is never short). A kind's files are {@code <kind>.jsonl},
 * {@code <date>/<kind>.jsonl}, {@code <kind>/<id>.json|csv} and {@code <date>/<kind>/<id>.json|csv}; anything else is not a kind's.
 */
final class FileSet {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Path root;
    private final Map<String, TreeMap<String, String>> byKind = new TreeMap<>();
    private Instant newest;

    FileSet(Path root) {
        this.root = root;
    }

    /** Adds one regular file found under the folder. */
    void add(Path file, long size, long modifiedMillis) {
        Instant t = Instant.ofEpochMilli(modifiedMillis);
        if (newest == null || t.isAfter(newest)) {
            newest = t;
        }
        String kind = kindOf(root.relativize(file));
        if (kind != null) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            byKind.computeIfAbsent(kind, k -> new TreeMap<>()).put(rel, size + ":" + modifiedMillis);
        }
    }

    /** The newest modification time of any file, or null when there is none. */
    Instant newest() {
        return newest;
    }

    /** The kind a file under the folder holds, or null when it is no kind's. */
    static String kindOf(Path rel) {
        int n = rel.getNameCount();
        String last = rel.getFileName() == null ? "" : rel.getFileName().toString();
        if (n == 1) {
            return last.endsWith(".jsonl") ? last.substring(0, last.length() - 6) : null;
        }
        String first = rel.getName(0).toString();
        boolean dated = DATE.matcher(first).matches();
        if (n == 2) {
            if (dated) {
                return last.endsWith(".jsonl") ? last.substring(0, last.length() - 6) : null;
            }
            return feed(last) ? first : null;
        }
        if (n == 3 && dated && feed(last)) {
            return rel.getName(1).toString();
        }
        return null;
    }

    private static boolean feed(String name) {
        return name.endsWith(".json") || name.endsWith(".csv");
    }

    /** Each kind with files: its identity. */
    Map<String, String> identities() {
        Map<String, String> out = new TreeMap<>();
        byKind.forEach((kind, files) -> out.put(kind, "file:" + root.toString().replace('\\', '/') + "#" + fingerprint(files)));
        return Map.copyOf(out);
    }

    private static String fingerprint(TreeMap<String, String> files) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        files.forEach((path, stamp) -> {
            sha.update(path.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(stamp.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) '\n');
        });
        return HexFormat.of().formatHex(sha.digest(), 0, 12);
    }
}
