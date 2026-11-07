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
package com.ash.drishti.common;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The input of a store loader (PostgreSQL, DuckDB, MongoDB, Redis, Cassandra, Aerospike, Iceberg, the File connector's): loader rows
 * ({@code {"domain", "kind", "id", "date", "doc", "columns"}}), one JSON object a line, filed under the business date the load rules
 * give (docs/architecture/DATA_ARCHITECTURE.md section 10; {@link FileDates}), never a row's own {@code date}:
 *
 * <ul>
 *   <li>a <b>tar</b> ({@code .tar}, {@code .tgz}, {@code .tar.gz}) of {@code <table>/<YYYY-MM-DD>/<file>.jsonl[.gz]}: each file's rows
 *       under its folder's date, every row of the folder's table (the kind); {@code as-of} is refused for it;</li>
 *   <li>a <b>plain file</b> ({@code .jsonl}, {@code .jsonl.gz}) or standard input ({@code -}): one table and one date, {@code as-of},
 *       else its {@code .ready} file's {@code business-date}, else the date in its name ({@code trade.2026-09-30.jsonl}), else the
 *       business today; rows of a second table refuse it ("send a tar");</li>
 *   <li>a <b>folder</b>: its plain files and tars, in name order.</li>
 * </ul>
 *
 * <p>A row's own date is kept as data and only counted when it differs ({@link #notes}). Read a line with {@link #next}, then file the
 * parsed row with {@link #place}, which checks its table and returns the date to file it under.
 */
public final class RowFeed implements AutoCloseable {

    private final List<Path> files = new ArrayList<>();
    private final LocalDate asOf;
    private final boolean stdin;
    private int fileIndex = -1;
    private BufferedReader reader;
    private TarStream tar;
    private String label;
    private LocalDate date;
    private String table;                 // the table of the current plain file (first row's), or the tar folder's
    private boolean plain;
    private final Map<String, String> dates = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    /** Each tar's folders: the archive, then its dates (one note line per archive, not per folder). */
    private final Map<String, java.util.TreeSet<LocalDate>> tarDates = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, java.util.concurrent.atomic.AtomicLong> other = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, LocalDate> otherDay = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> tables = java.util.Collections.synchronizedMap(new LinkedHashMap<>());   // label -> its table

    /** Where a line belongs: its file (or tar member), the business date it is filed under, its folder's table (null: a plain file). */
    public record Tag(String label, LocalDate date, String folder) {}

    private java.io.Reader stream;              // a stream given by the caller (stream()): one table, one date

    private RowFeed(String input, LocalDate asOf) throws IOException {
        this.asOf = asOf;
        this.stdin = "-".equals(input);
        if (!stdin && input != null) {
            Path p = Path.of(input);
            if (Files.isDirectory(p)) {
                try (var s = Files.list(p)) {
                    s.filter(f -> Files.isRegularFile(f) && isData(f.getFileName().toString())).sorted().forEach(files::add);
                }
            } else {
                files.add(p);
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (FileDates.isTar(name) && asOf != null) {
                    throw new IOException(name + ": a tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ folders): "
                            + "--as-of is refused for it");
                }
                if (FileDates.isTar(name) && !name.toLowerCase(Locale.ROOT).endsWith(".tar.zip")) {
                    checkLayout(f, name);
                }
            }
        }
    }

    /** A tar's whole layout checked before any of its rows is read (as lake build does): one refused member refuses the archive. */
    private static void checkLayout(Path f, String name) throws IOException {
        List<String[]> entries = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(f);
             TarStream t = new TarStream(name.toLowerCase(Locale.ROOT).endsWith(".tar") ? raw : new GZIPInputStream(raw, 1 << 16), name)) {
            for (String[] e = t.skip(); e != null; e = t.skip()) {
                entries.add(e);
            }
        }
        FileDates.Layout lay = FileDates.tarLayout(entries, FileDates.today(), null);
        if (!lay.errors().isEmpty()) {
            throw new IOException(name + ": refused (" + String.join("; ", lay.errors()) + ")");
        }
    }

    /** The feed of {@code input}: a file, a folder of them, or {@code -} for standard input; {@code asOf}: the date given (or null). */
    public static RowFeed open(String input, LocalDate asOf) throws IOException {
        return new RowFeed(input, asOf);
    }

    /**
     * A stream of rows of one table and one date (a pipe, a socket): {@code date} the business date it is filed under (null: the business
     * today), as {@code --as-of} gives standard input's.
     */
    public static RowFeed stream(java.io.Reader in, LocalDate date) throws IOException {
        RowFeed f = new RowFeed(null, date);
        f.stream = in;
        return f;
    }

    /** {@code --as-of YYYY-MM-DD} among a loader's arguments, null without it. */
    public static LocalDate asOf(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--as-of")) {
                return LocalDate.parse(args[i + 1]);
            }
        }
        return null;
    }

    private static boolean isData(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return FileDates.isTar(n) || n.endsWith(".jsonl") || n.endsWith(".jsonl.gz") || n.endsWith(".ndjson") || n.endsWith(".ndjson.gz");
    }

    /** The next line (blank ones included), null at the end; {@link #date} and {@link #label} say where it belongs. */
    public String next() throws IOException {
        while (true) {
            if (reader != null) {
                String line = reader.readLine();
                if (line != null) {
                    return line;
                }
                reader = null;
            }
            if (tar != null) {
                TarStream.Member m = tar.next();
                if (m != null) {
                    openMember(m);
                    continue;
                }
                tar.close();
                tar = null;
            }
            if (stdin || stream != null) {
                if (fileIndex >= 0) {
                    return null;
                }
                fileIndex = 0;
                if (stream != null) {
                    openPlain("the stream", null, null);
                    reader = new BufferedReader(stream, 1 << 16);
                } else {
                    // standard input: a tar stream of <table>/<YYYY-MM-DD>/ folders (rows of several dates, as lake build's Iceberg
                    // writer sends them), else one table on one date
                    java.io.BufferedInputStream in = new java.io.BufferedInputStream(System.in, 1 << 16);
                    in.mark(512);
                    byte[] head = in.readNBytes(512);
                    in.reset();
                    if (head.length == 512 && new String(head, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) {
                        if (asOf != null) {
                            throw new IOException("standard input: a tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ "
                                    + "folders): --as-of is refused for it");
                        }
                        tar = new TarStream(in, "standard input");
                        plain = false;
                    } else {
                        openPlain("standard input", null, in);
                    }
                }
                continue;
            }
            if (++fileIndex >= files.size()) {
                return null;
            }
            Path f = files.get(fileIndex);
            String name = f.getFileName().toString();
            if (FileDates.isTar(name)) {
                InputStream in = Files.newInputStream(f);
                String low = name.toLowerCase(Locale.ROOT);
                if (low.endsWith(".tar.zip")) {
                    in.close();
                    throw new IOException(name + ": a .tar.zip is read by lake build; give the store loaders a .tar, .tgz or .tar.gz");
                }
                tar = new TarStream(low.endsWith(".tar") ? in : new GZIPInputStream(in, 1 << 16), name);
                plain = false;
            } else {
                openPlain(name, f, Files.newInputStream(f));
            }
        }
    }

    private void openPlain(String name, Path path, InputStream raw) throws IOException {
        LocalDate ready = asOf == null && path != null ? FileDates.readyDate(path.toString()) : null;
        LocalDate given = asOf != null ? asOf : ready;
        FileDates.Decided d = FileDates.decide(given, path == null ? null : FileDates.of(name), "auto", FileDates.today(), name);
        if (d.error() != null) {
            throw new IOException(d.error());
        }
        if (ready != null) {
            d = new FileDates.Decided(d.date(), "ready", null);
        }
        label = name;
        date = d.date();
        table = null;
        plain = true;
        dates.put(name, FileDates.words(d, "the command line"));
        if (raw == null) {
            return;                                                   // a stream given: its reader is set by the caller
        }
        InputStream in = name.toLowerCase(Locale.ROOT).endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw;
        reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20);
    }

    private void openMember(TarStream.Member m) throws IOException {
        FileDates.Layout lay = FileDates.tarLayout(List.<String[]>of(new String[] {m.name(), m.type()}), FileDates.today(), null);
        if (!lay.errors().isEmpty()) {
            throw new IOException(tar.name + ": refused (" + String.join("; ", lay.errors()) + ")");
        }
        if (lay.tables().isEmpty()) {
            return;                                                   // a folder entry, or litter: nothing to read
        }
        var e = lay.tables().entrySet().iterator().next();
        table = e.getKey();
        date = e.getValue().keySet().iterator().next();
        label = tar.name + "!/" + m.name();
        plain = false;
        tarDates.computeIfAbsent(tar.name, k -> new java.util.TreeSet<>()).add(date);
        InputStream in = new ByteArrayInputStream(m.data());
        reader = new BufferedReader(new InputStreamReader(m.name().toLowerCase(Locale.ROOT).endsWith(".gz") ? new GZIPInputStream(in) : in,
                StandardCharsets.UTF_8));
    }

    /** The business date of the current line. */
    public LocalDate date() {
        return date;
    }

    /** The file (or tar member, {@code <archive>!/<member>}) of the current line. */
    public String label() {
        return label;
    }

    /** The current line's {@link Tag}: kept with a line that is parsed later (on another thread), for {@link #place(Tag, String, LocalDate)}. */
    public Tag tag() {
        return new Tag(label, date, plain ? null : table);
    }

    /**
     * Files a parsed row of the current line: its {@code table} (the kind; a {@code domain/} before it is ignored) must be the file's one
     * table (a tar folder's, or the first row's of a plain file); its own date ({@code own}, null: none) is counted when it differs.
     * Returns the business date to file it under.
     */
    public LocalDate place(String rowTable, LocalDate own) throws IOException {
        return place(tag(), rowTable, own);
    }

    /** {@link #place(String, LocalDate)} for a line read earlier, by its {@link Tag} (call it in the lines' order). */
    public LocalDate place(Tag t, String rowTable, LocalDate own) throws IOException {
        String kind = rowTable.contains("/") ? rowTable.substring(rowTable.indexOf('/') + 1) : rowTable;   // a domain's is the row's
        if (t.folder() == null) {
            String first = tables.putIfAbsent(t.label(), kind);
            if (first != null && !first.equalsIgnoreCase(kind)) {
                throw new IOException(t.label() + ": rows of " + first + " and " + kind + ": a file is one table and one business date "
                        + "(docs/architecture/DATA_ARCHITECTURE.md section 10): send a tar of <table>/<YYYY-MM-DD>/ folders");
            }
        } else {
            if (!t.folder().equalsIgnoreCase(kind)) {
                throw new IOException(t.label() + ": a row of " + kind + " in folder " + t.folder() + "/ (each folder is a table, and its rows name it)");
            }
        }
        if (own != null && !own.equals(t.date())) {
            other.computeIfAbsent(t.label(), k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            otherDay.put(t.label(), t.date());
        }
        return t.date();
    }

    /** What the load says of its dates: each file's business date and where it came from, then the rows that said another date. */
    public List<String> notes() {
        List<String> out = new ArrayList<>();
        synchronized (dates) {
            dates.forEach((f, w) -> out.add(f + ": business date " + w));
        }
        synchronized (tarDates) {
            tarDates.forEach((a, ds) -> out.add(a + (ds.size() == 1 ? ": business date " + ds.first() : ": business dates " + ds.first() + " to " + ds.last() + " ("
                    + ds.size() + " dates)") + " from the archive's folders"));
        }
        synchronized (other) {
            other.forEach((f, n) -> out.add(FileDates.otherDateNote(n.get(), f, "date", otherDay.get(f))));
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
        if (tar != null) {
            tar.close();
        }
    }

    /** A plain (ustar) tar read in order, one member at a time (each member's bytes held while it is read: one day's file). */
    static final class TarStream implements AutoCloseable {
        record Member(String name, String type, byte[] data) {}

        private final InputStream in;
        final String name;

        TarStream(InputStream in, String name) {
            this.in = in;
            this.name = name;
        }

        /** The next member's name and type, its bytes skipped; null at the end. */
        String[] skip() throws IOException {
            Member m = read(false);
            return m == null ? null : new String[] {m.name(), m.type()};
        }

        Member next() throws IOException {
            return read(true);
        }

        private Member read(boolean keep) throws IOException {
            byte[] h = in.readNBytes(512);
            if (h.length < 512 || h[0] == 0) {
                return null;
            }
            String n = text(h, 0, 100);
            String prefix = text(h, 345, 155);
            if (!prefix.isEmpty()) {
                n = prefix + "/" + n;
            }
            String sz = text(h, 124, 12).trim();
            long size = sz.isEmpty() ? 0 : Long.parseLong(sz, 8);
            byte type = h[156];
            byte[] data = keep ? in.readNBytes((int) size) : null;
            long pad = (512 - size % 512) % 512;
            in.skipNBytes(keep ? pad : size + pad);
            String t = type == '0' || type == 0 ? "file" : type == '5' ? "dir" : type == '2' || type == '1' ? "link" : "other";
            return new Member(n, t, data);
        }

        private static String text(byte[] b, int from, int len) {
            int end = from;
            while (end < from + len && b[end] != 0) {
                end++;
            }
            return new String(b, from, end - from, StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
