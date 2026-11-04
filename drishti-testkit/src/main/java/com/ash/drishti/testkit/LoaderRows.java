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

import com.ash.drishti.common.RowFeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loader rows ({@code {"domain", "kind", "id", "date", "doc", "columns"}}) written as a store loader takes them by the load rules
 * (docs/architecture/DATA_LOAD_RULES.md; {@link RowFeed}), as the generators write them: a tar of {@code <kind>/<YYYY-MM-DD>/rows.jsonl}
 * folders, each row in its own date's folder; rows of one kind and one date as one dated file {@code <kind>.<YYYY-MM-DD>.jsonl}; lines
 * that are not rows (a test of a bad line) as one plain file {@code rows.jsonl}, under the business today.
 */
public final class LoaderRows {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoaderRows() {
    }

    /** The rows written into a new temporary folder (module doc); returns the file to load. */
    public static Path write(List<String> lines) {
        try {
            return write(Files.createTempDirectory("loader-rows"), lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The rows written into {@code dir} (module doc); returns the file to load. */
    public static Path write(Path dir, List<String> lines) {
        try {
            Files.createDirectories(dir);
            Map<String, Map<String, StringBuilder>> byKind = new TreeMap<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n;
                try {
                    n = JSON.readTree(line);
                } catch (IOException e) {
                    n = null;
                }
                if (n == null || !n.isObject() || !n.path("kind").isTextual() || !n.path("date").isTextual()
                        || !n.path("date").asText().matches("\\d{4}-\\d{2}-\\d{2}.*")) {      // a bad row as it is: the loader names it
                    Path f = dir.resolve("rows.jsonl");
                    Files.writeString(f, String.join("\n", lines) + "\n");
                    return f;
                }
                byKind.computeIfAbsent(n.path("kind").asText(), k -> new TreeMap<>())
                        .computeIfAbsent(n.path("date").asText().substring(0, 10), d -> new StringBuilder()).append(line).append('\n');
            }
            if (byKind.isEmpty()) {
                Path f = dir.resolve("rows.jsonl");                       // no rows: an empty plain file
                Files.writeString(f, "");
                return f;
            }
            if (byKind.size() == 1 && byKind.values().iterator().next().size() == 1) {
                var k = byKind.entrySet().iterator().next();
                var d = k.getValue().entrySet().iterator().next();
                Path f = dir.resolve(k.getKey() + "." + d.getKey() + ".jsonl");
                Files.writeString(f, d.getValue());
                return f;
            }
            Path f = dir.resolve("rows.tar");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (var k : byKind.entrySet()) {
                for (var d : k.getValue().entrySet()) {
                    entry(out, k.getKey() + "/" + d.getKey() + "/rows.jsonl", d.getValue().toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            out.write(new byte[1024]);                                   // the two end blocks
            Files.write(f, out.toByteArray());
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code file}'s lines written again by the load rules (module doc) into the folder {@code <file>.rows/}; returns the file to load. */
    public static Path rewrite(Path file) {
        try {
            return write(file.resolveSibling(file.getFileName() + ".rows"), Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A feed of the rows (written as {@link #write(List)} writes them). */
    public static RowFeed feed(List<String> lines) {
        try {
            return RowFeed.open(write(lines).toString(), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A feed of {@code text}'s lines. */
    public static RowFeed feed(String text) {
        return feed(List.of(text.split("\n")));
    }

    private static void entry(ByteArrayOutputStream out, String name, byte[] data) throws IOException {
        byte[] h = new byte[512];
        put(h, 0, name, 100);
        put(h, 100, "0000644", 8);
        put(h, 108, "0000000", 8);
        put(h, 116, "0000000", 8);
        put(h, 124, String.format("%011o", data.length), 12);
        put(h, 136, "00000000000", 12);
        h[156] = '0';
        put(h, 257, "ustar", 6);
        put(h, 263, "00", 2);
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o", sum), 7);
        out.write(h);
        out.write(data);
        out.write(new byte[(int) ((512 - data.length % 512) % 512)]);
    }

    private static void put(byte[] h, int at, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, at, Math.min(b.length, len));
    }
}
