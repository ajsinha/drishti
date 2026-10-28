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

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.RangeReader;
import com.ash.drishti.lakeio.Storage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The input of a load: the files that {@code source.files} names (a file, a folder, or a glob in the last segments: {@code /data/pv/*.jsonl.gz},
 * {@code C:\pv\cob-*.jsonl}, {@code s3://bucket/pv/*.jsonl.zst}), each cut into {@link Split}s that readers take in parallel. A compressed
 * file (gzip or zstd, known by its first bytes) is one split, decompressed as a stream by one reader; an uncompressed file larger than
 * {@code split-mb} is cut into byte ranges, each read on its own (ranged GETs on S3), a line belonging to the range it starts in.
 */
final class Inputs {

    /** How a file is compressed (read from its first bytes, as lake build does). */
    enum Compression { NONE, GZIP, ZSTD }

    /** One input file. */
    record InputFile(String path, String name, long size, Compression compression) {}

    /** A part of a file one reader reads: bytes {@code [start, end)} of it ({@code index} counts from 0 within the file). */
    record Split(int id, InputFile file, long start, long end, int index, int of) {}

    /** The suffixes a folder is read for (lake build's, and .zst). */
    static final List<String> SUFFIXES = List.of(".jsonl", ".ndjson", ".json", ".gz", ".zst");

    private Inputs() {}

    /** The files {@code patterns} name, in a stable order (by path). */
    static List<InputFile> resolve(List<String> patterns, boolean recursive, FileAccess io) throws IOException {
        List<InputFile> out = new ArrayList<>();
        for (String pattern : patterns) {
            List<FileEntry> found = LocalPaths.isLocal(pattern) || pattern.startsWith("file:") ? local(pattern, recursive) : s3(pattern, io);
            if (found.isEmpty()) {
                throw new FileNotFoundException("source.files " + pattern + ": no input file matches");
            }
            for (FileEntry e : found) {
                out.add(new InputFile(e.path(), name(e.path()), e.size(), compression(e.path(), io)));
            }
        }
        out.sort(Comparator.comparing(InputFile::path));
        return out;
    }

    /** Each file's splits: a compressed or small file whole, a large plain file in ranges of about {@code splitBytes}. */
    static List<Split> splits(List<InputFile> files, long splitBytes) {
        List<Split> out = new ArrayList<>();
        for (InputFile f : files) {
            int parts = f.compression() != Compression.NONE || f.size() <= splitBytes ? 1 : (int) Math.min(Integer.MAX_VALUE, (f.size() + splitBytes - 1) / splitBytes);
            long step = parts == 1 ? f.size() : (f.size() + parts - 1) / parts;
            for (int i = 0; i < parts; i++) {
                long start = i * step;
                long end = i == parts - 1 ? f.size() : Math.min(f.size(), start + step);
                out.add(new Split(out.size(), f, start, end, i, parts));
            }
        }
        return out;
    }

    static String name(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static boolean wanted(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return SUFFIXES.stream().anyMatch(n::endsWith);
    }

    private static boolean hasGlob(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
    }

    /** A local file, folder or glob ({@code *}, {@code ?}, {@code **} for any depth). */
    private static List<FileEntry> local(String pattern, boolean recursive) throws IOException {
        String p = pattern.startsWith("file:") ? LocalPaths.toLocal(pattern, LocalPaths.WINDOWS) : pattern;
        String slashed = p.replace('\\', '/');
        int firstGlob = -1;
        for (int i = 0; i < slashed.length(); i++) {
            if ("*?[".indexOf(slashed.charAt(i)) >= 0) {
                firstGlob = i;
                break;
            }
        }
        List<FileEntry> out = new ArrayList<>();
        if (firstGlob < 0) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) {
                out.add(new FileEntry(path.toAbsolutePath().toString(), Files.size(path), 0));
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> s = recursive ? Files.walk(path) : Files.list(path)) {
                    for (Path f : s.filter(Files::isRegularFile).filter(f -> wanted(f.getFileName().toString())).sorted().toList()) {
                        out.add(new FileEntry(f.toAbsolutePath().toString(), Files.size(f), 0));
                    }
                }
            } else {
                throw new FileNotFoundException("source.files " + pattern + ": no such file or folder");
            }
            return out;
        }
        int base = slashed.lastIndexOf('/', firstGlob);
        Path dir = Path.of(base < 0 ? "." : base == 0 ? "/" : p.substring(0, base));
        String rest = slashed.substring(base + 1);
        Pattern glob = Pattern.compile(globRegex(rest));
        boolean deep = recursive || rest.contains("/") || rest.contains("**");
        if (!Files.isDirectory(dir)) {
            throw new FileNotFoundException("source.files " + pattern + ": no such folder " + dir);
        }
        try (Stream<Path> s = deep ? Files.walk(dir) : Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (glob.matcher(rel).matches()) {
                    out.add(new FileEntry(f.toAbsolutePath().toString(), Files.size(f), 0));
                }
            }
        }
        return out;
    }

    /** S3: an object, a prefix (its files), or a glob in the last segment ({@code s3://bucket/pv/cob-*.jsonl.gz}). */
    private static List<FileEntry> s3(String pattern, FileAccess io) throws IOException {
        Storage st = io.storage(pattern);
        String norm = pattern;
        int slash = norm.lastIndexOf('/');
        String last = norm.substring(slash + 1);
        List<FileEntry> out = new ArrayList<>();
        if (hasGlob(norm.substring(0, slash))) {
            throw new IllegalArgumentException("source.files " + pattern + ": on S3 a glob goes in the last segment only (s3://bucket/pv/*.jsonl.gz)");
        }
        if (!hasGlob(last)) {
            if (!last.isEmpty()) {
                try {
                    FileEntry e = st.status(norm);
                    return List.of(new FileEntry(norm, e.size(), e.modified()));
                } catch (FileNotFoundException notAnObject) {
                    // a prefix: its files
                }
            }
            String dir = norm.endsWith("/") ? norm : norm + "/";
            for (FileEntry e : st.listFrom(dir + "\u0000")) {
                if (wanted(name(e.path()))) {
                    out.add(e);
                }
            }
            return out;
        }
        int lit = 0;
        while (lit < last.length() && "*?[".indexOf(last.charAt(lit)) < 0) {
            lit++;
        }
        String dir = norm.substring(0, slash + 1);
        Pattern glob = Pattern.compile(globRegex(last));
        for (FileEntry e : st.listFrom(dir + (lit == 0 ? "\u0000" : last.substring(0, lit)))) {
            if (glob.matcher(name(e.path())).matches()) {
                out.add(e);
            }
        }
        return out;
    }

    /** A glob as a regular expression: {@code *} any characters but '/', {@code **} any, {@code ?} one, {@code [..]} a class. */
    static String globRegex(String glob) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    r.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;                                           // **/ matches no folder too
                        r.append("/?");
                    }
                } else {
                    r.append("[^/]*");
                }
            } else if (c == '?') {
                r.append("[^/]");
            } else if (c == '[') {
                int end = glob.indexOf(']', i);
                if (end < 0) {
                    r.append("\\[");
                } else {
                    r.append('[').append(glob, i + 1, end).append(']');
                    i = end;
                }
            } else {
                r.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return r.toString();
    }

    /** gzip ({@code 1f 8b}) or zstd ({@code 28 b5 2f fd}) by the first bytes, else plain. */
    private static Compression compression(String path, FileAccess io) throws IOException {
        String p = io.resolve(LocalPaths.isLocal(path) ? LocalPaths.toUri(path, LocalPaths.WINDOWS) : path);
        try (RangeReader r = io.storage(p).open(p, -1)) {
            if (r.length() < 4) {
                return Compression.NONE;
            }
            ByteBuffer b = ByteBuffer.allocate(4);
            r.readFully(0, b);
            byte[] h = b.array();
            if ((h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x8b) {
                return Compression.GZIP;
            }
            if ((h[0] & 0xff) == 0x28 && (h[1] & 0xff) == 0xb5 && (h[2] & 0xff) == 0x2f && (h[3] & 0xff) == 0xfd) {
                return Compression.ZSTD;
            }
            return Compression.NONE;
        }
    }

    /** The URI form a file is opened by ({@code file:} for a local one). */
    static String uri(String path) {
        return LocalPaths.isLocal(path) ? LocalPaths.toUri(path, LocalPaths.WINDOWS) : path;
    }
}
