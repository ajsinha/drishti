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

import com.ash.drishti.api.ColumnSet;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One kind's JSON-lines file for one business day ({@code <root>/2026-09-30/trade.jsonl}), indexed in one pass so that
 * a million lines are served without holding a single document: every id with the byte offset and length of its
 * line (sorted, so a read is a binary search and one positioned read), and the kind's promoted fields as columns.
 *
 * <p>A line is either the loaders' row, {@code {"kind", "id", "date", "doc", "columns": {path: value}}} ({@code doc} a
 * JSON string or object; {@code columns} the promoted fields, read without parsing the document), or a plain document,
 * whose id is its {@code id-field} and whose promoted fields are read from it.
 */
final class JsonlDay {

    private static final JsonFactory JSON = new JsonFactory();

    private final Path file;
    private final long size;
    private final long modified;
    private final String[] ids;                         // sorted
    private final long[] offsets;
    private final int[] lengths;
    private final ColumnSet columns;

    private JsonlDay(Path file, long size, long modified, String[] ids, long[] offsets, int[] lengths, ColumnSet columns) {
        this.file = file;
        this.size = size;
        this.modified = modified;
        this.ids = ids;
        this.offsets = offsets;
        this.lengths = lengths;
        this.columns = columns;
    }

    /** True when the file is still the one indexed (same size and modification time). */
    boolean current() {
        try {
            return Files.size(file) == size && Files.getLastModifiedTime(file).toMillis() == modified;
        } catch (IOException e) {
            return false;
        }
    }

    int size() {
        return ids.length;
    }

    String[] ids() {
        return ids;
    }

    ColumnSet columns() {
        return columns;
    }

    /** Bytes this index holds, for the cache's weigher. */
    long weight() {
        long w = 128L + 28L * ids.length;
        for (String id : ids) {
            w += 2L * id.length();
        }
        return w + 8L * ids.length * (columns.numbers().size() + columns.texts().size());   // texts share their values
    }

    /** The document of {@code id}, read from its line alone; empty when the day does not hold it. */
    Optional<byte[]> document(String id) throws IOException {
        int i = Arrays.binarySearch(ids, id);
        if (i < 0) {
            return Optional.empty();
        }
        byte[] line = new byte[lengths[i]];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offsets[i]);
            raf.readFully(line);
        }
        return Optional.of(documentOf(line));
    }

    /** Ids whose line mentions {@code target} as a JSON string, reading at most {@code maxLines} lines. */
    List<String> mentioning(String target, int maxLines) throws IOException {
        byte[] needle = ("\"" + target + "\"").getBytes(StandardCharsets.UTF_8);
        byte[] escaped = ("\\\"" + target + "\\\"").getBytes(StandardCharsets.UTF_8);   // inside a doc kept as a string
        List<String> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            for (int i = 0; i < Math.min(ids.length, maxLines); i++) {
                byte[] line = new byte[lengths[i]];
                raf.seek(offsets[i]);
                raf.readFully(line);
                if ((contains(line, needle) || contains(line, escaped)) && !ids[i].equals(target)) {
                    out.add(ids[i]);
                }
            }
        }
        return out;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** The document in a line: its {@code doc} (a string holding JSON, or an object), or the whole line. */
    static byte[] documentOf(byte[] line) throws IOException {
        try (JsonParser p = JSON.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("not a JSON object");
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken t = p.nextToken();
                if ("doc".equals(name)) {
                    if (t == JsonToken.VALUE_STRING) {
                        return p.getText().getBytes(StandardCharsets.UTF_8);
                    }
                    if (t == JsonToken.START_OBJECT) {
                        long from = p.currentTokenLocation().getByteOffset();
                        p.skipChildren();
                        long to = p.currentLocation().getByteOffset();
                        return Arrays.copyOfRange(line, (int) from, (int) to);
                    }
                }
                p.skipChildren();
            }
        }
        return line;                                    // a plain document per line
    }

    /**
     * Reads the whole file once: each line's id, offset and length, and the promoted {@code paths} (from the line's
     * {@code columns}, else from the document). Lines without an id are skipped.
     */
    static JsonlDay index(Path file, LocalDate day, List<String> paths, String idField) throws IOException {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        Map<String, Integer> column = new HashMap<>();
        for (int c = 0; c < paths.size(); c++) {
            column.put(paths.get(c), c);
        }
        // a large file is cut into segments at line ends and indexed on several threads; a small one in one go
        int segments = (int) Math.max(1, Math.min(SEGMENTS, size / segmentBytes));
        List<Part> parts = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            long[] cuts = new long[segments + 1];
            cuts[segments] = size;
            for (int k = 1; k < segments; k++) {
                cuts[k] = lineStartAfter(ch, size * k / segments, size);
            }
            List<java.util.concurrent.Future<Part>> running = new ArrayList<>();
            for (int k = 0; k < segments; k++) {
                long from = cuts[k];
                long to = Math.max(from, cuts[k + 1]);
                running.add(pool.submit(() -> scan(ch, from, to, paths.size(), idField, column)));
            }
            for (var f : running) {
                parts.add(f.get());
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        int n = parts.stream().mapToInt(x -> x.n).sum();
        String[] ids = new String[n];
        long[] offsets = new long[n];
        int[] lengths = new int[n];
        Object[][] values = new Object[paths.size()][n];
        int at = 0;
        for (Part x : parts) {
            System.arraycopy(x.ids, 0, ids, at, x.n);
            System.arraycopy(x.offsets, 0, offsets, at, x.n);
            System.arraycopy(x.lengths, 0, lengths, at, x.n);
            for (int c = 0; c < values.length; c++) {
                System.arraycopy(x.values[c], 0, values[c], at, x.n);
            }
            at += x.n;
        }
        return sorted(file, size, modified, day, paths, n, ids, offsets, lengths, values);
    }

    private static final int SEGMENTS = 8;
    /** The smallest segment worth a thread of its own (smaller in tests, to exercise the cuts). */
    static long segmentBytes = 64L << 20;
    private static final int BLOCK = 4 << 20;

    /** The lines of one segment: growable columns. */
    private static final class Part {
        String[] ids = new String[1024];
        long[] offsets = new long[1024];
        int[] lengths = new int[1024];
        Object[][] values;
        int n;

        Part(int columns) {
            values = new Object[columns][1024];
        }

        void add(String id, long offset, int length, Object[] row) {
            if (n == ids.length) {
                int grown = n * 2;
                ids = Arrays.copyOf(ids, grown);
                offsets = Arrays.copyOf(offsets, grown);
                lengths = Arrays.copyOf(lengths, grown);
                for (int c = 0; c < values.length; c++) {
                    values[c] = Arrays.copyOf(values[c], grown);
                }
            }
            ids[n] = id;
            offsets[n] = offset;
            lengths[n] = length;
            for (int c = 0; c < row.length; c++) {
                values[c][n] = row[c];
            }
            n++;
        }
    }

    /** The offset just after the first line end at or after {@code at} (the file's size when there is none). */
    private static long lineStartAfter(FileChannel ch, long at, long size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(64 << 10);
        long pos = at;
        while (pos < size) {
            b.clear();
            int got = ch.read(b, pos);
            if (got <= 0) {
                break;
            }
            for (int i = 0; i < got; i++) {
                if (b.get(i) == '\n') {
                    return pos + i + 1;
                }
            }
            pos += got;
        }
        return size;
    }

    /** Indexes the lines in {@code [from, to)}, read in blocks of 4 MB. */
    private static Part scan(FileChannel ch, long from, long to, int columns, String idField, Map<String, Integer> column) throws IOException {
        Part part = new Part(columns);
        ByteBuffer block = ByteBuffer.allocate(BLOCK);
        ByteArrayOutputStream carry = new ByteArrayOutputStream(16_384);   // a line that runs past the end of a block
        long pos = from;
        long lineStart = from;
        while (pos < to) {
            block.clear();
            block.limit((int) Math.min(BLOCK, to - pos));
            int got = ch.read(block, pos);
            if (got <= 0) {
                break;
            }
            byte[] a = block.array();
            int begin = 0;
            for (int i = 0; i < got; i++) {
                if (a[i] == '\n') {
                    byte[] line;
                    if (carry.size() > 0) {
                        carry.write(a, begin, i - begin);
                        line = carry.toByteArray();
                        carry.reset();
                    } else {
                        line = Arrays.copyOfRange(a, begin, i);
                    }
                    take(part, line, lineStart, idField, column, columns);
                    begin = i + 1;
                    lineStart = pos + i + 1;
                }
            }
            carry.write(a, begin, got - begin);
            pos += got;
        }
        if (carry.size() > 0) {
            take(part, carry.toByteArray(), lineStart, idField, column, columns);   // the last line, without a line end
        }
        return part;
    }

    private static void take(Part part, byte[] line, long offset, String idField, Map<String, Integer> column, int columns) throws IOException {
        int length = line.length;
        while (length > 0 && (line[length - 1] == '\r' || line[length - 1] == ' ')) {
            length--;
        }
        if (length == 0) {
            return;
        }
        Object[] row = new Object[columns];
        String id = parse(length == line.length ? line : Arrays.copyOf(line, length), idField, column, row);
        if (id != null) {
            part.add(id, offset, length, row);
        }
    }

    /** The line's id; its promoted values go into {@code row}. */
    private static String parse(byte[] line, String idField, Map<String, Integer> column, Object[] row) throws IOException {
        String id = null;
        String plainId = null;
        boolean envelope = false;
        byte[] embedded = null;
        boolean hasColumns = false;
        try (JsonParser p = JSON.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken t = p.nextToken();
                if ("id".equals(name) && t.isScalarValue()) {
                    id = p.getText();
                }
                if (idField.equals(name) && t.isScalarValue()) {
                    plainId = p.getText();
                }
                if ("doc".equals(name)) {
                    envelope = true;
                    if (t == JsonToken.VALUE_STRING && !column.isEmpty()) {
                        embedded = p.getText().getBytes(StandardCharsets.UTF_8);
                    }
                    p.skipChildren();
                } else if ("columns".equals(name) && t == JsonToken.START_OBJECT) {
                    hasColumns = true;
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        Integer c = column.get(p.currentName());
                        JsonToken v = p.nextToken();
                        if (c != null) {
                            row[c] = value(p, v);
                        } else {
                            p.skipChildren();
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        if (!column.isEmpty() && !hasColumns) {                 // promoted values from the document itself
            fill(envelope ? embedded : line, column, row);
        }
        return envelope ? id : plainId;
    }

    private static Object value(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getDoubleValue();
        }
        if (v == JsonToken.VALUE_NULL) {
            return null;
        }
        if (v.isScalarValue()) {
            return p.getText();
        }
        p.skipChildren();
        return null;
    }

    /** Promoted values read from a document by their dotted paths. */
    private static void fill(byte[] doc, Map<String, Integer> column, Object[] row) throws IOException {
        if (doc == null) {
            return;
        }
        try (JsonParser p = JSON.createParser(doc)) {
            if (p.nextToken() == JsonToken.START_OBJECT) {
                walk(p, "", column, row);
            }
        }
    }

    private static void walk(JsonParser p, String prefix, Map<String, Integer> column, Object[] row) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String path = prefix + p.currentName();
            JsonToken t = p.nextToken();
            if (t == JsonToken.START_OBJECT && column.keySet().stream().anyMatch(c -> c.startsWith(path + "."))) {
                walk(p, path + ".", column, row);
            } else {
                Integer c = column.get(path);
                if (c != null) {
                    row[c] = value(p, t);
                } else {
                    p.skipChildren();
                }
            }
        }
    }

    /** Sorts the lines by id (the file may be in any order) and turns the promoted values into columns. */
    private static JsonlDay sorted(Path file, long size, long modified, LocalDate day, List<String> paths, int n, String[] ids, long[] offsets,
            int[] lengths, Object[][] values) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        String[] idsIn = ids;
        Arrays.sort(order, (a, b) -> idsIn[a].compareTo(idsIn[b]));
        String[] sortedIds = new String[n];
        long[] sortedOffsets = new long[n];
        int[] sortedLengths = new int[n];
        for (int i = 0; i < n; i++) {
            sortedIds[i] = ids[order[i]];
            sortedOffsets[i] = offsets[order[i]];
            sortedLengths[i] = lengths[order[i]];
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (int c = 0; c < paths.size(); c++) {
            Object[] col = values[c];
            boolean numeric = false;
            boolean other = false;
            for (int i = 0; i < n; i++) {
                if (col[i] instanceof Double) {
                    numeric = true;
                } else if (col[i] != null) {
                    other = true;
                }
            }
            if (numeric && !other) {
                double[] v = new double[n];
                for (int i = 0; i < n; i++) {
                    Object x = col[order[i]];
                    v[i] = x == null ? Double.NaN : (Double) x;
                }
                nums.put(paths.get(c), v);
            } else {
                String[] v = new String[n];
                Map<String, String> shared = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    Object x = col[order[i]];
                    String s = x == null ? null : x instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(x);
                    v[i] = s == null || shared.size() > 200_000 ? s : shared.computeIfAbsent(s, k -> k);   // books, desks: one copy each
                }
                texts.put(paths.get(c), v);
            }
        }
        return new JsonlDay(file, size, modified, sortedIds, sortedOffsets, sortedLengths, new ColumnSet(sortedIds, nums, texts, day));
    }
}
