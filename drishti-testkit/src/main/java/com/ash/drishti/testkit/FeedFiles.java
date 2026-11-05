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
package com.ash.drishti.testkit;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The committed test data's files as the tests read them (docs/architecture/DATA_LOAD_RULES.md section 10; the Python half is
 * {@code tools/lake/feedfiles.py}): a table's folder holds one file per business date, {@code <table>.<YYYY-MM-DD>.jsonl.gz}, or plain
 * tar archives of {@code <table>/<YYYY-MM-DD>/<file>.jsonl.gz} folders. {@link #parts} lists every file of every date (a tar's members
 * read in place), the dates in order and each date's files in name order; {@link #lines} reads their JSON Lines.
 */
public final class FeedFiles {

    private static final Pattern DATED = Pattern.compile("^[A-Za-z0-9_-]+\\.(\\d{4}-\\d{2}-\\d{2})\\.jsonl(\\.gz)?$");
    private static final Pattern MEMBER = Pattern.compile("^[A-Za-z0-9_-]+/(\\d{4}-\\d{2}-\\d{2})/([^/]+\\.jsonl(\\.gz)?)$");

    private FeedFiles() {
    }

    /** One file of a date: a dated file, or a tar's member ({@code member} its file name in the date's folder, {@code bytes} its content). */
    public record Part(String date, Path path, String member, byte[] bytes) {

        /** The file's own name: a dated file's, or the member's within its date folder (as {@code stress-cob.part-001.jsonl.gz}). */
        public String name() {
            return member != null ? member : path.getFileName().toString();
        }

        /** The part's non-blank lines. */
        public List<String> lines() throws IOException {
            String n = name();
            InputStream raw = bytes != null ? new ByteArrayInputStream(bytes) : Files.newInputStream(path);
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(n.endsWith(".gz") ? new GZIPInputStream(raw) : raw,
                    StandardCharsets.UTF_8))) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    if (!line.isBlank()) {
                        out.add(line);
                    }
                }
            }
            return out;
        }
    }

    /** Every part of the folder's dated files and tars, by date, then file and member name. */
    public static List<Part> parts(Path folder) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<Part> out = new ArrayList<>();
        for (Path f : files) {
            String n = f.getFileName().toString();
            if (n.endsWith(".tar")) {
                out.addAll(tar(f));
                continue;
            }
            Matcher m = DATED.matcher(n);
            if (m.matches()) {
                out.add(new Part(m.group(1), f, null, null));
            }
        }
        out.sort(Comparator.comparing(Part::date).thenComparing(p -> p.path().getFileName().toString()).thenComparing(Part::name));
        return out;
    }

    /** The parts of one date. */
    public static List<Part> parts(Path folder, String date) throws IOException {
        return parts(folder).stream().filter(p -> p.date().equals(date)).toList();
    }

    /** Every non-blank line of the parts {@code which} keeps, in {@link #parts} order. */
    public static List<String> lines(Path folder, Predicate<Part> which) throws IOException {
        List<String> out = new ArrayList<>();
        for (Part p : parts(folder)) {
            if (which.test(p)) {
                out.addAll(p.lines());
            }
        }
        return out;
    }

    /** The members of a plain (uncompressed) tar: 512-byte headers (ustar), each file's bytes after its header. */
    static List<Part> tar(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        List<Part> out = new ArrayList<>();
        int at = 0;
        while (at + 512 <= all.length) {
            if (all[at] == 0) {
                break;                                                  // the end blocks
            }
            String name = text(all, at, 100);
            String prefix = text(all, at + 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            long size = Long.parseLong(text(all, at + 124, 12).trim().isEmpty() ? "0" : text(all, at + 124, 12).trim(), 8);
            byte type = all[at + 156];
            int start = at + 512;
            if (type == '0' || type == 0) {
                Matcher m = MEMBER.matcher(name);
                if (m.matches()) {
                    byte[] b = new byte[(int) size];
                    System.arraycopy(all, start, b, 0, (int) size);
                    out.add(new Part(m.group(1), file, m.group(2), b));
                }
            }
            at = start + (int) ((size + 511) / 512 * 512);
        }
        return out;
    }

    private static String text(byte[] b, int from, int len) {
        int end = from;
        while (end < from + len && b[end] != 0) {
            end++;
        }
        return new String(b, from, end - from, StandardCharsets.US_ASCII);
    }
}
