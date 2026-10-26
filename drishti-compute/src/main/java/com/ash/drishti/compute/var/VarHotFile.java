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
package com.ash.drishti.compute.var;

import com.ash.drishti.compute.vector.GroupedVectors;
import com.ash.drishti.compute.vector.MappedMatrix;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.compute.vector.Vectors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.CRC32C;

/**
 * The hot file of one business date (VAR.md section 7.3, format 1, as built in 2.8.0): a local, write-once, little-endian file under the
 * data folder that holds everything the engine needs to answer a date without the lake: the trades' and positions' columns and ids, the
 * positions' runs, the books and nodes, the node aggregates in float64, and the vectors as a <b>float32</b> matrix of <b>positions</b> in
 * hierarchy order (plus the trades of positions of more than one trade). It is memory-mapped: the heap keeps the aggregates, columns and
 * links; the vectors stay in the file's pages, resident only while used. A history date is opened by mapping its file, without reading
 * the lake again.
 *
 * <pre>
 * offset  bytes  field
 * 0       8      magic "DRSHVAR1"
 * 8       4      format version (1)
 * 12      4      flags: bit 0 float32 matrices, bit 1 trade matrix present
 * 16      4      business date as yyyymmdd
 * 20      4      N, the vector width
 * 24      4      P, positions (rows of PMAT)
 * 28      4      T, trades
 * 32      4      M, aggregates (books and nodes)
 * 36      4      S, sections
 * 40      32     SCENARIO_SET_ID (UTF-8, zero padded)
 * 72      64     inputs digest: SHA-256 (hex) of the inputs the date was built from
 * 136     32     BATCH_ID (UTF-8, zero padded)
 * 168     8      written at (epoch milliseconds)
 * 176     8      failed rows (no vector)
 * 184     4      Q, rows of TMAT
 * 188     68     reserved (zero)
 * 256     S x 32 section table: {id int32 (four ASCII letters), reserved int32, offset int64, length int64, crc32c int32, pad int32}
 * </pre>
 *
 * Sections: {@code META} (the server's JSON: intake, versions, rules), {@code SCEN} (scenario days), {@code TIDS}, {@code TCOL},
 * {@code PIDS}, {@code PRUN}, {@code PCOL}, {@code BOOK}, {@code NODE}, {@code AKEY}, {@code AGGS} (float64 M x N), {@code TMAP} (trade to
 * TMAT row), {@code PMAT} (float32 P x N, 64-byte aligned), {@code TMAT} (float32 Q x N, 64-byte aligned). Every section carries a CRC32C;
 * the small ones are checked on open, the matrices by {@link #verify}.
 */
public final class VarHotFile {

    public static final int VERSION = 1;
    public static final String SUFFIX = ".vhot";
    static final byte[] MAGIC = "DRSHVAR1".getBytes(StandardCharsets.US_ASCII);
    static final int HEADER = 256;
    private static final int ENTRY = 32;
    private static final int BATCH = 1024;

    /** What the header says, read without opening the date (to check a file is the one for the inputs now). */
    public record Header(int version, LocalDate businessDate, int width, int positions, int trades, int aggregates, int tradeRows,
            String scenarioSet, String digest, String batchId, long writtenAt, long failed) {}

    /** The date's content for {@link #write}: everything the book holds, in hierarchy order. */
    record Content(LocalDate businessDate, String scenarioSet, List<LocalDate> scenarioDates, String[] tradeIds, Columns trades,
            String[] positionIds, int[] posFrom, int[] posTo, Columns positions, Map<String, int[]> bookRange, Map<String, VarBook.Node> nodes,
            Map<String, double[]> aggregates, int failed, String batchId, String digest, String meta) {}

    private VarHotFile() {}

    // ------------------------------------------------------------------------------------------------------------------- writing

    /**
     * Writes a date's hot file to {@code file} (a new, empty file): the small sections, then the float32 rows of every position (each the
     * float64 sum of its trades' rows of {@code sorted}, converted once), a batch at a time on the pool, then the trade rows of positions
     * of more than one trade. Returns each trade's row in TMAT (-1: its row is its position's).
     */
    static int[] write(Path file, Content c, Vectors sorted, ParallelSums sums) throws IOException {
        int n = sorted.width();
        int p = c.positionIds().length;
        int t = c.tradeIds().length;
        int[] memberRow = new int[t];
        int q = 0;
        for (int k = 0; k < p; k++) {
            boolean several = c.posTo()[k] - c.posFrom()[k] > 1;
            for (int o = c.posFrom()[k]; o < c.posTo()[k]; o++) {
                memberRow[o] = several ? q++ : -1;
            }
        }
        List<String> keys = new ArrayList<>(c.aggregates().keySet());
        Map<String, byte[]> small = new LinkedHashMap<>();
        small.put("META", c.meta() == null ? new byte[0] : c.meta().getBytes(StandardCharsets.UTF_8));
        small.put("SCEN", scen(c.scenarioDates()));
        small.put("TIDS", strings(c.tradeIds()));
        small.put("TCOL", columns(c.trades()));
        small.put("PIDS", strings(c.positionIds()));
        small.put("PRUN", runs(c.posFrom(), c.posTo()));
        small.put("PCOL", columns(c.positions()));
        small.put("BOOK", books(c.bookRange()));
        small.put("NODE", nodes(c.nodes()));
        small.put("AKEY", strings(keys.toArray(new String[0])));
        small.put("AGGS", aggregates(keys, c.aggregates(), n));
        small.put("TMAP", ints(memberRow));
        int sections = small.size() + 1 + (q > 0 ? 1 : 0);
        long at = HEADER + (long) sections * ENTRY;
        List<long[]> table = new ArrayList<>();                         // {id, offset, length, crc}
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Map.Entry<String, byte[]> e : small.entrySet()) {
                at = align(at, 8);
                byte[] b = e.getValue();
                writeAt(ch, ByteBuffer.wrap(b), at);
                table.add(new long[] {id(e.getKey()), at, b.length, crc(b)});
                at += b.length;
            }
            long pmat = align(at, 64);
            long rowBytes = (long) n * Float.BYTES;
            CRC32C pc = new CRC32C();
            for (int from = 0; from < p; from += BATCH) {
                int f0 = from;
                int to = Math.min(p, from + BATCH);
                ByteBuffer out = positionRows(c, sorted, sums, f0, to, n);
                pc.update(out.duplicate());
                writeAt(ch, out, pmat + f0 * rowBytes);
            }
            table.add(new long[] {id("PMAT"), pmat, p * rowBytes, pc.getValue()});
            long end = pmat + p * rowBytes;
            if (q > 0) {
                long tmat = align(end, 64);
                CRC32C tc = new CRC32C();
                ByteBuffer out = ByteBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 8, BATCH * rowBytes)).order(ByteOrder.LITTLE_ENDIAN);
                double[] row = new double[n];
                long pos = tmat;
                for (int o = 0; o < t; o++) {
                    if (memberRow[o] < 0) {
                        continue;
                    }
                    if (out.remaining() < rowBytes) {
                        out.flip();
                        tc.update(out.duplicate());
                        pos += writeAt(ch, out, pos);
                        out.clear();
                    }
                    sorted.copy(o, row);
                    for (int j = 0; j < n; j++) {
                        out.putFloat((float) row[j]);
                    }
                }
                out.flip();
                tc.update(out.duplicate());
                writeAt(ch, out, pos);
                table.add(new long[] {id("TMAT"), tmat, q * rowBytes, tc.getValue()});
            }
            writeAt(ch, header(c, n, p, t, keys.size(), q, sections), 0);
            ByteBuffer tb = ByteBuffer.allocate(sections * ENTRY).order(ByteOrder.LITTLE_ENDIAN);
            for (long[] e : table) {
                tb.putInt((int) e[0]).putInt(0).putLong(e[1]).putLong(e[2]).putInt((int) e[3]).putInt(0);
            }
            tb.flip();
            writeAt(ch, tb, HEADER);
            ch.force(true);
        }
        return memberRow;
    }

    /** Positions {@code from..to}: each the float64 sum of its trades' rows, converted to float32, summed on the pool in slices. */
    private static ByteBuffer positionRows(Content c, Vectors sorted, ParallelSums sums, int from, int to, int n) {
        int count = to - from;
        float[] rows = new float[count * n];
        int slices = Math.max(1, Math.min(sums.threads(), count / 64));
        int step = (count + slices - 1) / slices;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int s = 0; s < count; s += step) {
            int a = s;
            int b = Math.min(count, s + step);
            tasks.add(() -> {
                double[] acc = new double[n];
                for (int k = a; k < b; k++) {
                    java.util.Arrays.fill(acc, 0.0);
                    sorted.addRangeInto(acc, c.posFrom()[from + k], c.posTo()[from + k]);
                    int off = k * n;
                    for (int j = 0; j < n; j++) {
                        rows[off + j] = (float) acc[j];
                    }
                }
                return true;
            });
        }
        sums.all(tasks);
        ByteBuffer out = ByteBuffer.allocate(rows.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.asFloatBuffer().put(rows);
        return out;
    }

    private static ByteBuffer header(Content c, int n, int p, int t, int m, int q, int sections) {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put(MAGIC).putInt(VERSION).putInt(1 | (q > 0 ? 2 : 0));
        LocalDate d = c.businessDate();
        h.putInt(d.getYear() * 10_000 + d.getMonthValue() * 100 + d.getDayOfMonth());
        h.putInt(n).putInt(p).putInt(t).putInt(m).putInt(sections);
        fixed(h, c.scenarioSet(), 32);
        fixed(h, c.digest(), 64);
        fixed(h, c.batchId(), 32);
        h.putLong(System.currentTimeMillis()).putLong(c.failed()).putInt(q);
        h.position(HEADER);
        h.flip();
        return h;
    }

    private static void fixed(ByteBuffer h, String s, int width) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        int len = Math.min(width, b.length);
        h.put(b, 0, len);
        for (int i = len; i < width; i++) {
            h.put((byte) 0);
        }
    }

    private static long writeAt(FileChannel ch, ByteBuffer b, long pos) throws IOException {
        long written = 0;
        while (b.hasRemaining()) {
            written += ch.write(b, pos + written);
        }
        return written;
    }

    static long align(long at, int to) {
        return (at + to - 1) / to * to;
    }

    static int id(String name) {
        byte[] b = name.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    static String name(int id) {
        return new String(new byte[] {(byte) id, (byte) (id >> 8), (byte) (id >> 16), (byte) (id >> 24)}, StandardCharsets.US_ASCII);
    }

    private static long crc(byte[] b) {
        CRC32C c = new CRC32C();
        c.update(b);
        return c.getValue();
    }

    // --------------------------------------------------------------------------------------------------------------- the sections

    private static byte[] scen(List<LocalDate> dates) {
        Out o = new Out();
        o.putInt(dates.size());
        dates.forEach(d -> o.putInt((int) d.toEpochDay()));
        return o.bytes();
    }

    private static byte[] strings(String[] values) {
        Out o = new Out();
        o.putInt(values.length);
        for (String v : values) {
            o.putString(v);
        }
        return o.bytes();
    }

    private static byte[] ints(int[] values) {
        Out o = new Out();
        o.putInt(values.length);
        for (int v : values) {
            o.putInt(v);
        }
        return o.bytes();
    }

    private static byte[] runs(int[] from, int[] to) {
        Out o = new Out();
        o.putInt(from.length);
        for (int i = 0; i < from.length; i++) {
            o.putInt(from[i]).putInt(to[i]);
        }
        return o.bytes();
    }

    private static byte[] columns(Columns c) {
        Out o = new Out();
        o.putInt(c.rows());
        List<String> texts = c.textColumns();
        o.putInt(texts.size());
        for (String col : texts) {
            o.putString(col);
            String[] dict = c.dictionary(col);
            o.putInt(dict.length);
            for (String v : dict) {
                o.putString(v);
            }
            for (int r = 0; r < c.rows(); r++) {
                o.putInt(c.code(col, r));
            }
        }
        List<String> nums = c.numberColumns();
        o.putInt(nums.size());
        for (String col : nums) {
            o.putString(col);
            for (int r = 0; r < c.rows(); r++) {
                o.putDouble(c.number(col, r));
            }
        }
        return o.bytes();
    }

    private static byte[] books(Map<String, int[]> bookRange) {
        Out o = new Out();
        o.putInt(bookRange.size());
        bookRange.forEach((b, r) -> o.putString(b).putInt(r[0]).putInt(r[1]));
        return o.bytes();
    }

    private static byte[] nodes(Map<String, VarBook.Node> nodes) {
        Out o = new Out();
        o.putInt(nodes.size());
        for (VarBook.Node n : nodes.values()) {
            o.putString(n.id()).putString(n.hierarchy()).putString(n.level()).putString(n.name()).putString(n.parent()).putDouble(n.limit());
            o.putInt(n.books().size());
            n.books().forEach(o::putString);
        }
        return o.bytes();
    }

    private static byte[] aggregates(List<String> keys, Map<String, double[]> agg, int n) {
        Out o = new Out();
        for (String k : keys) {
            double[] v = agg.get(k);
            for (int j = 0; j < n; j++) {
                o.putDouble(v[j]);
            }
        }
        return o.bytes();
    }

    // ------------------------------------------------------------------------------------------------------------------- reading

    /** The header of a hot file, or null when the file is not one this version can read. */
    public static Header header(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return header(read(ch, 0, HEADER));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Header header(ByteBuffer h) {
        byte[] magic = new byte[8];
        h.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("not a VaR hot file");
        }
        int version = h.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("hot file format " + version + ", this server reads " + VERSION);
        }
        h.getInt();
        int ymd = h.getInt();
        int n = h.getInt();
        int p = h.getInt();
        int t = h.getInt();
        int m = h.getInt();
        h.getInt();
        String set = text(h, 32);
        String digest = text(h, 64);
        String batch = text(h, 32);
        long written = h.getLong();
        long failed = h.getLong();
        int q = h.getInt();
        return new Header(version, LocalDate.of(ymd / 10_000, ymd / 100 % 100, ymd % 100), n, p, t, m, q, set, digest, batch, written, failed);
    }

    private static String text(ByteBuffer h, int width) {
        byte[] b = new byte[width];
        h.get(b);
        int len = 0;
        while (len < width && b[len] != 0) {
            len++;
        }
        return new String(b, 0, len, StandardCharsets.UTF_8);
    }

    private static ByteBuffer read(FileChannel ch, long pos, long length) throws IOException {
        if (length > Integer.MAX_VALUE - 8) {
            throw new IOException("a section of " + length + " bytes is too large to read whole");
        }
        ByteBuffer b = ByteBuffer.allocate((int) length).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            if (ch.read(b, pos + b.position()) < 0) {
                throw new IOException("the hot file ends early");
            }
        }
        b.flip();
        return b;
    }

    private static Map<String, long[]> table(FileChannel ch) throws IOException {
        ByteBuffer h = read(ch, 0, HEADER);
        h.position(36);
        int sections = h.getInt();
        ByteBuffer tb = read(ch, HEADER, (long) sections * ENTRY);
        Map<String, long[]> out = new LinkedHashMap<>();
        for (int i = 0; i < sections; i++) {
            int id = tb.getInt();
            tb.getInt();
            long off = tb.getLong();
            long len = tb.getLong();
            long crc = tb.getInt() & 0xffffffffL;
            tb.getInt();
            out.put(name(id), new long[] {off, len, crc});
        }
        return out;
    }

    private static In section(FileChannel ch, Map<String, long[]> table, String name) throws IOException {
        long[] e = table.get(name);
        if (e == null) {
            throw new IOException("the hot file has no " + name + " section");
        }
        ByteBuffer b = read(ch, e[0], e[1]);
        CRC32C c = new CRC32C();
        c.update(b.duplicate());
        if (c.getValue() != e[2]) {
            throw new IOException("the hot file's " + name + " section is damaged (checksum)");
        }
        return new In(b);
    }

    /**
     * Opens a hot file: the small sections onto the heap (checked), the matrices mapped. The book answers like one loaded from the lake,
     * from the float32 position rows and the float64 aggregates.
     *
     * @param loadMillis what the load took (the file's opening, or the build that wrote it)
     */
    public static VarBook open(Path file, long loadMillis) throws IOException {
        Header h;
        Map<String, long[]> table;
        String meta;
        List<LocalDate> scen = new ArrayList<>();
        String[] tids;
        Columns tcols;
        String[] pids;
        int[] from;
        int[] to;
        Columns pcols;
        Map<String, int[]> bookRange = new LinkedHashMap<>();
        Map<String, VarBook.Node> nodes;
        Map<String, double[]> agg = new java.util.concurrent.ConcurrentHashMap<>();
        int[] memberRow;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            h = header(read(ch, 0, HEADER));
            table = table(ch);
            meta = new String(section(ch, table, "META").bytes(), StandardCharsets.UTF_8);
            In s = section(ch, table, "SCEN");
            for (int i = s.getInt(); i > 0; i--) {
                scen.add(LocalDate.ofEpochDay(s.getInt()));
            }
            tids = section(ch, table, "TIDS").strings();
            tcols = section(ch, table, "TCOL").columns();
            pids = section(ch, table, "PIDS").strings();
            In r = section(ch, table, "PRUN");
            int p = r.getInt();
            from = new int[p];
            to = new int[p];
            for (int i = 0; i < p; i++) {
                from[i] = r.getInt();
                to[i] = r.getInt();
            }
            pcols = section(ch, table, "PCOL").columns();
            In b = section(ch, table, "BOOK");
            for (int i = b.getInt(); i > 0; i--) {
                bookRange.put(b.getString(), new int[] {b.getInt(), b.getInt()});
            }
            nodes = readNodes(section(ch, table, "NODE"), bookRange);
            String[] keys = section(ch, table, "AKEY").strings();
            In a = section(ch, table, "AGGS");
            for (String k : keys) {
                double[] v = new double[h.width()];
                for (int j = 0; j < v.length; j++) {
                    v[j] = a.getDouble();
                }
                agg.put(k, v);
            }
            In tm = section(ch, table, "TMAP");
            memberRow = new int[tm.getInt()];
            for (int i = 0; i < memberRow.length; i++) {
                memberRow[i] = tm.getInt();
            }
        }
        int[] posOf = new int[tids.length];
        for (int k = 0; k < pids.length; k++) {
            java.util.Arrays.fill(posOf, from[k], to[k], k);
        }
        long[] pm = table.get("PMAT");
        MappedMatrix positions = MappedMatrix.open(file, pm[0], h.positions(), h.width(), MappedMatrix.Type.FLOAT32, 0);
        MappedMatrix trades = null;
        if (h.tradeRows() > 0) {
            long[] tm = table.get("TMAT");
            try {
                trades = MappedMatrix.open(file, tm[0], h.tradeRows(), h.width(), MappedMatrix.Type.FLOAT32, 0);
            } catch (IOException | RuntimeException e) {
                positions.close();
                throw e;
            }
        }
        Vectors store = new GroupedVectors(positions, trades, posOf, from, to, memberRow);
        VarBook.Hot hot = new VarBook.Hot(file, positions, trades, h.digest(), meta, h.writtenAt());
        return new VarBook(h.businessDate(), h.scenarioSet(), scen, store, tids, tcols, posOf, pids, from, to, pcols, nodes, bookRange, agg,
                (int) h.failed(), loadMillis, hot);
    }

    private static Map<String, VarBook.Node> readNodes(In in, Map<String, int[]> bookRange) {
        List<String[]> rows = new ArrayList<>();
        Map<String, List<String>> books = new LinkedHashMap<>();
        Map<String, Double> limits = new LinkedHashMap<>();
        for (int i = in.getInt(); i > 0; i--) {
            String[] r = {in.getString(), in.getString(), in.getString(), in.getString(), in.getString()};
            limits.put(r[0], in.getDouble());
            List<String> bs = new ArrayList<>();
            for (int k = in.getInt(); k > 0; k--) {
                bs.add(in.getString());
            }
            rows.add(r);
            books.put(r[0], bs);
        }
        return VarBook.nodes(rows, books, limits, bookRange);
    }

    /** Checks every section's checksum, the matrices included (a test, or an administrator's check of a file kept on disk). */
    public static void verify(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            Map<String, long[]> table = table(ch);
            for (Map.Entry<String, long[]> e : table.entrySet()) {
                CRC32C c = new CRC32C();
                ByteBuffer b = ByteBuffer.allocate(1 << 20);
                long pos = e.getValue()[0];
                long end = pos + e.getValue()[1];
                while (pos < end) {
                    b.clear();
                    b.limit((int) Math.min(b.capacity(), end - pos));
                    int got = ch.read(b, pos);
                    if (got < 0) {
                        throw new IOException("the hot file ends early in " + e.getKey());
                    }
                    b.flip();
                    c.update(b);
                    pos += got;
                }
                if (c.getValue() != e.getValue()[2]) {
                    throw new IOException("the hot file's " + e.getKey() + " section is damaged (checksum)");
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------- little-endian buffers

    /** A growing little-endian buffer for the small sections. */
    static final class Out {
        private ByteBuffer b = ByteBuffer.allocate(1 << 12).order(ByteOrder.LITTLE_ENDIAN);

        private void room(int n) {
            if (b.remaining() < n) {
                long want = Math.max((long) b.capacity() * 2, (long) b.position() + n);
                if (want > Integer.MAX_VALUE - 8) {
                    throw new IllegalStateException("a hot file section larger than 2 GB");
                }
                ByteBuffer g = ByteBuffer.allocate((int) want).order(ByteOrder.LITTLE_ENDIAN);
                b.flip();
                g.put(b);
                b = g;
            }
        }

        Out putInt(int v) {
            room(4);
            b.putInt(v);
            return this;
        }

        Out putDouble(double v) {
            room(8);
            b.putDouble(v);
            return this;
        }

        /** A text as its UTF-8 length and bytes; null as length -1. */
        Out putString(String s) {
            if (s == null) {
                return putInt(-1);
            }
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            putInt(u.length);
            room(u.length);
            b.put(u);
            return this;
        }

        byte[] bytes() {
            return java.util.Arrays.copyOf(b.array(), b.position());
        }
    }

    /** Reads what {@link Out} wrote. */
    static final class In {
        private final ByteBuffer b;

        In(ByteBuffer b) {
            this.b = b;
        }

        int getInt() {
            return b.getInt();
        }

        double getDouble() {
            return b.getDouble();
        }

        String getString() {
            int len = b.getInt();
            if (len < 0) {
                return null;
            }
            byte[] u = new byte[len];
            b.get(u);
            return new String(u, StandardCharsets.UTF_8);
        }

        byte[] bytes() {
            byte[] u = new byte[b.remaining()];
            b.get(u);
            return u;
        }

        String[] strings() {
            String[] out = new String[getInt()];
            for (int i = 0; i < out.length; i++) {
                out[i] = getString();
            }
            return out;
        }

        Columns columns() {
            int rows = getInt();
            Map<String, String[]> dicts = new LinkedHashMap<>();
            Map<String, int[]> codes = new LinkedHashMap<>();
            for (int c = getInt(); c > 0; c--) {
                String col = getString();
                String[] dict = new String[getInt()];
                for (int i = 0; i < dict.length; i++) {
                    dict[i] = getString();
                }
                int[] code = new int[rows];
                for (int r = 0; r < rows; r++) {
                    code[r] = getInt();
                }
                dicts.put(col, dict);
                codes.put(col, code);
            }
            Map<String, double[]> numbers = new LinkedHashMap<>();
            for (int c = getInt(); c > 0; c--) {
                String col = getString();
                double[] v = new double[rows];
                for (int r = 0; r < rows; r++) {
                    v[r] = getDouble();
                }
                numbers.put(col, v);
            }
            return Columns.of(rows, dicts, codes, numbers);
        }
    }
}
