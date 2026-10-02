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
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One kind's JSON-lines file for one business day ({@code <root>/2026-09-30/trade.jsonl}), indexed in one pass so that
 * a million lines are served without holding a single document: every id with the byte offset and length of its
 * line (sorted, so a read is a binary search and one positioned read), and the kind's promoted fields as columns.
 *
 * <p>A line is either the loaders' row, {@code {"kind", "id", "date", "doc", "columns": {path: value}}} ({@code doc} a
 * JSON string or object; {@code columns} the promoted fields, read without parsing the document), or a plain document,
 * whose id is its {@code id-field} and whose promoted fields are read from it.
 *
 * <p>A file is not all or nothing: a line that cannot be read (truncated, not an object, no id, longer than
 * {@code max-document-mb}, beyond the {@link JsonlFormat}'s limits) is skipped and counted with its line number and
 * why, and the rest of the day is served. An id on several lines keeps its last line, as a reload of the file would;
 * the ids repeated are counted. A file that cannot be opened at all is an empty day that says why. Either way the
 * index is kept until the file changes, so a bad file is read once, not on every request.
 *
 * <p>The index holds the file it read open, so a read always uses offsets into the file they came from: a file
 * replaced by a rename (as {@link JsonlLoader} does) stays the old file to this index until a newer index replaces
 * it, and {@link #close()} releases it. A file rewritten in place is caught by checking the id of the line read.
 */
final class JsonlDay implements AutoCloseable {

    /** How many lines and ids a report names. */
    private static final int SAMPLE = 5;

    /**
     * What was wrong with a file: lines skipped (a few of them by number, with why), ids on several lines, or the whole
     * file unreadable ({@code failure}).
     */
    record Report(int unreadable, List<String> unreadableLines, int duplicates, List<String> duplicateIds, String failure) {

        static final Report CLEAN = new Report(0, List.of(), 0, List.of(), null);

        boolean clean() {
            return unreadable == 0 && duplicates == 0 && failure == null;
        }

        /** True when the day may be missing entities: lines skipped, or the file unreadable. */
        boolean incomplete() {
            return unreadable > 0 || failure != null;
        }

        /** In words, for health, logs and a partial answer, naming the file as {@code where}. */
        String describe(String where) {
            List<String> parts = new ArrayList<>();
            if (failure != null) {
                parts.add("unreadable file " + where + ": " + failure);
            }
            if (unreadable > 0) {
                parts.add(unreadable + " unreadable line" + (unreadable == 1 ? "" : "s") + " in " + where + " (" + String.join("; ", unreadableLines)
                        + (unreadable > unreadableLines.size() ? "; ..." : "") + ")");
            }
            if (duplicates > 0) {
                parts.add(duplicates + " duplicate id" + (duplicates == 1 ? "" : "s") + " in " + where + ", the last line of each kept ("
                        + String.join(", ", duplicateIds) + (duplicates > duplicateIds.size() ? ", ..." : "") + ")");
            }
            return String.join("; ", parts);
        }
    }

    /** The file changed under this index after it was read: build a new index and read again. */
    static final class StaleIndexException extends IOException {
        private static final long serialVersionUID = 1L;

        StaleIndexException(String message) {
            super(message);
        }
    }

    private final Path file;
    private final Object fileKey;
    private final long size;
    private final long modified;
    private final FileChannel channel;                  // null when the file could not be opened
    private final String idField;
    private final JsonlFormat format;
    private final String[] ids;                         // sorted, unique
    private final long[] offsets;
    private final int[] lengths;
    private final ColumnSet columns;
    private final Report report;

    private JsonlDay(Path file, BasicFileAttributes at, FileChannel channel, String idField, JsonlFormat format, String[] ids, long[] offsets,
            int[] lengths, ColumnSet columns, Report report) {
        this.file = file;
        this.fileKey = at.fileKey();
        this.size = at.size();
        this.modified = at.lastModifiedTime().toMillis();
        this.channel = channel;
        this.idField = idField;
        this.format = format;
        this.ids = ids;
        this.offsets = offsets;
        this.lengths = lengths;
        this.columns = columns;
        this.report = report;
    }

    /** True when the file is still the one indexed (the same file, size and modification time). */
    boolean current() {
        try {
            BasicFileAttributes at = Files.readAttributes(file, BasicFileAttributes.class);
            return at.size() == size && at.lastModifiedTime().toMillis() == modified && Objects.equals(at.fileKey(), fileKey);
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

    Report report() {
        return report;
    }

    /** The indexed file's modification time in milliseconds: the generation of what is read from it. */
    long modified() {
        return modified;
    }

    /** False when the file could not be read at all ({@link Report#failure()}). */
    boolean readable() {
        return channel != null;
    }

    /** Bytes this index holds, for the cache's weigher; at least 1 MB, as each day also holds its file open. */
    long weight() {
        long w = 128L + 28L * ids.length;
        for (String id : ids) {
            w += 2L * id.length();
        }
        w += 8L * ids.length * (columns.numbers().size() + columns.texts().size() + columns.mixed().size());   // texts share their values
        return Math.max(1L << 20, w);
    }

    @Override
    public void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing left to release
            }
        }
    }

    /**
     * The document of {@code id}, read from its line alone; empty when the day does not hold it.
     *
     * @throws StaleIndexException when the file changed under the index (closed, shorter, or the line there is no
     *     longer this id's): the caller builds a new index and asks again
     */
    Optional<byte[]> document(String id) throws IOException {
        int i = Arrays.binarySearch(ids, id);
        if (i < 0 || channel == null) {
            return Optional.empty();
        }
        Line line;
        try {
            line = line(read(i), idField, format);
        } catch (JsonProcessingException e) {
            throw new StaleIndexException(file + " changed under its index: the line at " + offsets[i] + " is no longer JSON");
        }
        if (!id.equals(line.id())) {
            throw new StaleIndexException(file + " changed under its index: the line at " + offsets[i] + " is not " + id + "'s");
        }
        return Optional.of(line.doc());
    }

    private byte[] read(int i) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(lengths[i]);
        long pos = offsets[i];
        try {
            while (b.hasRemaining()) {
                if (channel.read(b, pos + b.position()) < 0) {
                    throw new StaleIndexException(file + " is shorter than when it was indexed");
                }
            }
        } catch (ClosedChannelException e) {
            throw new StaleIndexException(file + "'s index was replaced by a newer one");
        }
        return b.array();
    }

    /** Ids whose line mentions {@code target} as a JSON string, reading at most {@code maxLines} lines. */
    List<String> mentioning(String target, int maxLines) throws IOException {
        return mentioning(target, maxLines, null);
    }

    /**
     * Ids whose line mentions {@code target} as a JSON string, among those not in {@code skip}, reading at most
     * {@code maxLines} lines. Every id whose line is read is added to {@code skip} (when not null): an entity kept in
     * {@code effective} mode is looked up in its newest line only, the caller reading its days newest first.
     */
    List<String> mentioning(String target, int maxLines, java.util.Set<String> skip) throws IOException {
        byte[] needle = ("\"" + target + "\"").getBytes(StandardCharsets.UTF_8);
        byte[] escaped = ("\\\"" + target + "\\\"").getBytes(StandardCharsets.UTF_8);   // inside a doc kept as a string
        List<String> out = new ArrayList<>();
        if (channel == null) {
            return out;
        }
        int read = 0;
        for (int i = 0; i < ids.length && read < maxLines; i++) {
            if (skip != null && !skip.add(ids[i])) {
                continue;                                     // a newer line of this entity was read already
            }
            read++;
            byte[] line = read(i);
            if ((contains(line, needle) || contains(line, escaped)) && !ids[i].equals(target)) {
                out.add(ids[i]);
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

    /** A line's id and its document. */
    record Line(String id, byte[] doc) {}

    /** The id of a line and its document: its {@code doc} (a string holding JSON, or an object), or the whole line. */
    static Line line(byte[] line, String idField, JsonlFormat format) throws IOException {
        String id = null;
        String plainId = null;
        byte[] doc = null;
        try (JsonParser p = format.parser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new JsonParseException(p, "not a JSON object");
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
                if ("doc".equals(name) && doc == null) {
                    if (t == JsonToken.VALUE_STRING) {
                        doc = p.getText().getBytes(StandardCharsets.UTF_8);
                    } else if (t == JsonToken.START_OBJECT) {
                        long from = p.currentTokenLocation().getByteOffset();
                        p.skipChildren();
                        doc = Arrays.copyOfRange(line, (int) from, (int) p.currentLocation().getByteOffset());
                    }
                }
                p.skipChildren();
            }
        }
        return doc != null ? new Line(id, doc) : new Line(plainId, line);   // else a plain document per line
    }

    /** {@link #index(Path, LocalDate, List, String, JsonlFormat)} with the default format. */
    static JsonlDay index(Path file, LocalDate day, List<String> paths, String idField) throws IOException {
        return index(file, day, paths, idField, JsonlFormat.DEFAULT);
    }

    /**
     * Reads the whole file once: each line's id, offset and length, and the promoted {@code paths} (from the line's
     * {@code columns}, else from the document). Lines that cannot be read are skipped and counted.
     *
     * @throws NoSuchFileException when the file is gone (the next rescan forgets it); any other failure to read it is an
     *     empty day whose {@link #report()} says why
     */
    static JsonlDay index(Path file, LocalDate day, List<String> paths, String idField, JsonlFormat format) throws IOException {
        for (int attempt = 0; ; attempt++) {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
            FileChannel ch;
            try {
                ch = FileChannel.open(file, StandardOpenOption.READ);
            } catch (NoSuchFileException e) {
                throw e;
            } catch (IOException e) {
                return failed(file, before, day, idField, format, e);
            }
            BasicFileAttributes after;
            try {
                after = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (IOException e) {
                ch.close();
                throw e;
            }
            if (attempt < 3 && !Objects.equals(before.fileKey(), after.fileKey())) {
                ch.close();                                       // replaced while it was opened: open the new one
                continue;
            }
            try {
                return index(file, after, ch, day, paths, idField, format);
            } catch (IOException | RuntimeException e) {
                ch.close();
                return failed(file, after, day, idField, format, e);
            }
        }
    }

    private static JsonlDay failed(Path file, BasicFileAttributes at, LocalDate day, String idField, JsonlFormat format, Exception e) {
        String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return new JsonlDay(file, at, null, idField, format, new String[0], new long[0], new int[0],
                new ColumnSet(new String[0], Map.of(), Map.of(), day), new Report(0, List.of(), 0, List.of(), why));
    }

    private static JsonlDay index(Path file, BasicFileAttributes at, FileChannel ch, LocalDate day, List<String> paths, String idField,
            JsonlFormat format) throws IOException {
        long size = ch.size();
        Map<String, Integer> column = new HashMap<>();
        for (int c = 0; c < paths.size(); c++) {
            column.put(paths.get(c), c);
        }
        // a large file is cut into segments at line ends and indexed on several threads; a small one in one go
        int segments = (int) Math.max(1, Math.min(SEGMENTS, size / segmentBytes));
        List<Part> parts = new ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            long[] cuts = new long[segments + 1];
            cuts[segments] = size;
            for (int k = 1; k < segments; k++) {
                cuts[k] = lineStartAfter(ch, size * k / segments, size);
            }
            List<java.util.concurrent.Future<Part>> running = new ArrayList<>();
            for (int k = 0; k < segments; k++) {
                long from = cuts[k];
                long to = Math.max(from, cuts[k + 1]);
                running.add(pool.submit(() -> new Part(paths.size(), idField, column, format).scan(ch, from, to)));
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
        int filled = 0;
        int unreadable = 0;
        List<String> unreadableLines = new ArrayList<>();
        long linesBefore = 0;                                     // segments start at line starts: numbers add up
        for (Part x : parts) {
            System.arraycopy(x.ids, 0, ids, filled, x.n);
            System.arraycopy(x.offsets, 0, offsets, filled, x.n);
            System.arraycopy(x.lengths, 0, lengths, filled, x.n);
            for (int c = 0; c < values.length; c++) {
                System.arraycopy(x.values[c], 0, values[c], filled, x.n);
            }
            filled += x.n;
            unreadable += x.skipped;
            for (int k = 0; k < x.skippedLines.size() && unreadableLines.size() < SAMPLE; k++) {
                unreadableLines.add("line " + (linesBefore + x.skippedLines.get(k)) + ": " + x.skippedWhy.get(k));
            }
            linesBefore += x.lines;
        }
        return sorted(file, at, ch, idField, format, day, paths, n, ids, offsets, lengths, values, unreadable, List.copyOf(unreadableLines));
    }

    private static final int SEGMENTS = 8;
    /** The smallest segment worth a thread of its own (smaller in tests, to exercise the cuts). */
    static long segmentBytes = 64L << 20;
    private static final int BLOCK = 4 << 20;

    /** The lines of one segment: growable columns, and the lines skipped. */
    private static final class Part {
        private final String idField;
        private final Map<String, Integer> column;
        private final JsonlFormat format;
        String[] ids = new String[1024];
        long[] offsets = new long[1024];
        int[] lengths = new int[1024];
        Object[][] values;
        int n;
        /** Lines seen (blank ones too), lines skipped, and the first few skipped: their numbers in the segment and why. */
        long lines;
        int skipped;
        final List<Long> skippedLines = new ArrayList<>();
        final List<String> skippedWhy = new ArrayList<>();

        Part(int columns, String idField, Map<String, Integer> column, JsonlFormat format) {
            this.values = new Object[columns][1024];
            this.idField = idField;
            this.column = column;
            this.format = format;
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

        /** The current line ({@link #lines}) is skipped. */
        void skip(String why) {
            skipped++;
            if (skippedLines.size() < SAMPLE) {
                skippedLines.add(lines);
                skippedWhy.add(why);
            }
        }

        /** Indexes the lines in {@code [from, to)}, read in blocks of 4 MB; a line longer than the format allows is skipped unread. */
        Part scan(FileChannel ch, long from, long to) throws IOException {
            ByteBuffer block = ByteBuffer.allocate(BLOCK);
            ByteArrayOutputStream carry = new ByteArrayOutputStream(16_384);   // a line that runs past the end of a block
            boolean tooLong = false;                                           // the current line is past the limit: not kept
            int max = format.maxLineBytes();
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
                        lines++;
                        if (tooLong || (long) carry.size() + (i - begin) > max) {
                            skip(format.tooLong());
                            carry.reset();
                        } else {
                            byte[] line;
                            if (carry.size() > 0) {
                                carry.write(a, begin, i - begin);
                                line = carry.toByteArray();
                                carry.reset();
                            } else {
                                line = Arrays.copyOfRange(a, begin, i);
                            }
                            take(line, lineStart);
                        }
                        tooLong = false;
                        begin = i + 1;
                        lineStart = pos + i + 1;
                    }
                }
                if (!tooLong && (long) carry.size() + (got - begin) > max) {
                    tooLong = true;
                    carry.reset();
                } else if (!tooLong) {
                    carry.write(a, begin, got - begin);
                }
                pos += got;
            }
            if (tooLong || carry.size() > 0) {                                 // the last line, without a line end
                lines++;
                if (tooLong) {
                    skip(format.tooLong());
                } else {
                    take(carry.toByteArray(), lineStart);
                }
            }
            return this;
        }

        private void take(byte[] line, long offset) {
            int length = line.length;
            while (length > 0 && (line[length - 1] == '\r' || line[length - 1] == ' ' || line[length - 1] == '\t')) {
                length--;
            }
            int start = 0;
            while (start < length && (line[start] == ' ' || line[start] == '\t')) {
                start++;
            }
            if (start == length) {
                return;                                                        // a blank line
            }
            Object[] row = new Object[values.length];
            String id;
            try {
                id = parse(length == line.length ? line : Arrays.copyOf(line, length), idField, column, row, format);
            } catch (JsonProcessingException e) {
                skip(String.valueOf(e.getOriginalMessage()).lines().findFirst().orElse("not JSON"));
                return;
            } catch (IOException | RuntimeException e) {
                skip(String.valueOf(e.getMessage()));
                return;
            }
            if (id == null) {
                skip("no id (\"id\" in a row, or the id-field \"" + idField + "\" in a plain document)");
                return;
            }
            add(id, offset, length, row);
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

    /** The line's id; its promoted values go into {@code row}. */
    private static String parse(byte[] line, String idField, Map<String, Integer> column, Object[] row, JsonlFormat format) throws IOException {
        String id = null;
        String plainId = null;
        boolean envelope = false;
        byte[] embedded = null;                                   // the document, when promoted values are read from it
        int embeddedFrom = 0;
        int embeddedTo = 0;
        boolean hasColumns = false;
        try (JsonParser p = format.parser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new JsonParseException(p, "not a JSON object");
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
                if ("doc".equals(name) && !envelope) {
                    envelope = true;
                    if (t == JsonToken.VALUE_STRING && !column.isEmpty()) {
                        embedded = p.getText().getBytes(StandardCharsets.UTF_8);
                        embeddedTo = embedded.length;
                    } else if (t == JsonToken.START_OBJECT && !column.isEmpty()) {   // the same document, not quoted
                        embedded = line;
                        embeddedFrom = (int) p.currentTokenLocation().getByteOffset();
                        p.skipChildren();
                        embeddedTo = (int) p.currentLocation().getByteOffset();
                    }
                    p.skipChildren();
                } else if ("columns".equals(name) && t == JsonToken.START_OBJECT) {
                    hasColumns = true;
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        Integer c = column.get(p.currentName());
                        JsonToken v = p.nextToken();
                        if (c != null) {
                            row[c] = JsonlFormat.value(p, v);
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
            if (envelope) {
                fill(embedded, embeddedFrom, embeddedTo - embeddedFrom, column, row, format);
            } else {
                fill(line, 0, line.length, column, row, format);
            }
        }
        return envelope ? id : plainId;
    }

    /** Promoted values read from a document by their dotted paths. */
    private static void fill(byte[] doc, int offset, int length, Map<String, Integer> column, Object[] row, JsonlFormat format) throws IOException {
        if (doc == null) {
            return;
        }
        try (JsonParser p = format.parser(doc, offset, length)) {
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
                    row[c] = JsonlFormat.value(p, t);
                } else {
                    p.skipChildren();
                }
            }
        }
    }

    /**
     * Sorts the lines by id (the file may be in any order), keeps each id's last line (as a reload of the file would
     * leave it), and turns the promoted values into columns.
     */
    private static JsonlDay sorted(Path file, BasicFileAttributes at, FileChannel ch, String idField, JsonlFormat format, LocalDate day,
            List<String> paths, int n, String[] ids, long[] offsets, int[] lengths, Object[][] values, int unreadable, List<String> unreadableLines) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        String[] idsIn = ids;
        Arrays.sort(order, (a, b) -> {
            int c = idsIn[a].compareTo(idsIn[b]);
            return c != 0 ? c : Integer.compare(a, b);             // lines are in file order: an id's last line sorts last
        });
        int[] keep = new int[n];                                   // the line kept for each id, in id order
        int m = 0;
        int duplicates = 0;
        boolean repeated = false;
        List<String> duplicateIds = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int i = order[k];
            if (m > 0 && ids[keep[m - 1]].equals(ids[i])) {
                if (!repeated) {
                    repeated = true;
                    duplicates++;
                    if (duplicateIds.size() < SAMPLE) {
                        duplicateIds.add(ids[i]);
                    }
                }
                keep[m - 1] = i;                                   // a later line replaces an earlier one
            } else {
                keep[m++] = i;
                repeated = false;
            }
        }
        String[] sortedIds = new String[m];
        long[] sortedOffsets = new long[m];
        int[] sortedLengths = new int[m];
        for (int i = 0; i < m; i++) {
            sortedIds[i] = ids[keep[i]];
            sortedOffsets[i] = offsets[keep[i]];
            sortedLengths[i] = lengths[keep[i]];
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        Map<String, Object[]> mixed = new LinkedHashMap<>();
        for (int c = 0; c < paths.size(); c++) {
            Object[] col = values[c];
            boolean numeric = false;
            boolean other = false;
            for (int i = 0; i < m; i++) {
                Object x = col[keep[i]];
                if (x instanceof Double) {
                    numeric = true;
                } else if (x != null) {
                    other = true;
                }
            }
            if (numeric && !other) {
                double[] v = new double[m];
                for (int i = 0; i < m; i++) {
                    Object x = col[keep[i]];
                    v[i] = x == null ? Double.NaN : (Double) x;
                }
                nums.put(paths.get(c), v);
            } else if (numeric) {
                // numbers on some lines and text on others ("N/A"): each value as the document holds it, so a search from
                // columns orders and compares it as one from documents does, and a number beyond a long keeps its value
                Object[] v = new Object[m];
                Map<String, String> shared = new HashMap<>();
                for (int i = 0; i < m; i++) {
                    Object x = col[keep[i]];
                    v[i] = x instanceof String s && shared.size() <= 200_000 ? shared.computeIfAbsent(s, k -> k) : x;
                }
                mixed.put(paths.get(c), v);
            } else {
                String[] v = new String[m];
                Map<String, String> shared = new HashMap<>();
                for (int i = 0; i < m; i++) {
                    String s = (String) col[keep[i]];
                    v[i] = s == null || shared.size() > 200_000 ? s : shared.computeIfAbsent(s, k -> k);   // books, desks: one copy each
                }
                texts.put(paths.get(c), v);
            }
        }
        Report report = unreadable == 0 && duplicates == 0 ? Report.CLEAN
                : new Report(unreadable, unreadableLines, duplicates, List.copyOf(duplicateIds), null);
        return new JsonlDay(file, at, ch, idField, format, sortedIds, sortedOffsets, sortedLengths, new ColumnSet(sortedIds, nums, texts, day, null, mixed),
                report);
    }
}
