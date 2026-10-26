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
package com.ash.drishti.compute.ccr;

import com.ash.drishti.compute.vector.MappedMatrix;
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
import java.util.zip.CRC32C;

/**
 * The hot exposure file of one business date (EXPOSURE_GUIDE.md section 26.2, format 1), written once after the date's first load and then
 * memory-mapped: little-endian, a 256-byte header (magic {@code DRSHEXP1}, version, business date, P, G, the primary count, the sets,
 * the run id, the digest of the inputs it was built from, the batch id), a section table with a CRC-32C per section, and the sections:
 *
 * <ul>
 *   <li>{@code META}: the grid, the sets with their CSA terms and flags, the trades' index, the run's record and the load's intake, as
 *       JSON (small: kilobytes per thousand sets);</li>
 *   <li>{@code VMAT}: every set's value, float32, one row of P paths per grid date ({@code set * G + k}), 64-byte aligned (what-ifs, other
 *       collateral models, partial scopes);</li>
 *   <li>{@code EMAT} and {@code NMAT}: every set's exposure and negative exposure under the default collateral model at the primary dates
 *       ({@code set * G_primary + i}).</li>
 * </ul>
 *
 * Only the pages a scope touches become resident; the heap keeps the index and the statistics. No trade paths are kept here: they stay
 * in the lake (EXPOSURE_GUIDE.md open question 5, decided by default).
 */
public final class ExposureHotFile {

    public static final int VERSION = 1;
    public static final String SUFFIX = ".xhot";
    static final byte[] MAGIC = "DRSHEXP1".getBytes(StandardCharsets.US_ASCII);
    static final int HEADER = 256;
    private static final int ENTRY = 32;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** What the header says. */
    public record Header(int version, LocalDate businessDate, int paths, int grid, int primaries, int sets, String runId, String digest,
            String batchId) {}

    /** An opened file: its header, its META section as JSON, its mapped matrices. */
    public record Opened(Header header, Map<String, Object> meta, MappedMatrix v, MappedMatrix e, MappedMatrix n, Path file) {}

    private ExposureHotFile() {}

    /**
     * Writes a date's file: {@code meta} as JSON, then each set's rows of the three matrices (float32), set by set.
     *
     * @param rows set by set: {@code {V (G x P), E (Gp x P), N (Gp x P)}} in double, written as float32
     */
    public static void write(Path file, Header h, Map<String, Object> meta, List<double[][]> rows) throws IOException {
        byte[] m = JSON.writeValueAsBytes(meta);
        int p = h.paths();
        long rowBytes = (long) p * Float.BYTES;
        long vRows = (long) h.sets() * h.grid();
        long ePrimary = (long) h.sets() * h.primaries();
        int sections = 4;
        long at = HEADER + (long) sections * ENTRY;
        List<long[]> table = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            at = align(at, 8);
            writeAt(ch, ByteBuffer.wrap(m), at);
            table.add(new long[] {id("META"), at, m.length, crc(m)});
            at += m.length;
            long vAt = align(at, 64);
            long eAt = align(vAt + vRows * rowBytes, 64);
            long nAt = align(eAt + ePrimary * rowBytes, 64);
            CRC32C cv = new CRC32C();
            CRC32C ce = new CRC32C();
            CRC32C cn = new CRC32C();
            for (int s = 0; s < rows.size(); s++) {
                double[][] r = rows.get(s);
                cv.update(floats(r[0]).duplicate());
                writeAt(ch, floats(r[0]), vAt + (long) s * h.grid() * rowBytes);
                ByteBuffer eb = floats(r[1]);
                ce.update(eb.duplicate());
                writeAt(ch, eb, eAt + (long) s * h.primaries() * rowBytes);
                ByteBuffer nb = floats(r[2]);
                cn.update(nb.duplicate());
                writeAt(ch, nb, nAt + (long) s * h.primaries() * rowBytes);
            }
            table.add(new long[] {id("VMAT"), vAt, vRows * rowBytes, cv.getValue()});
            table.add(new long[] {id("EMAT"), eAt, ePrimary * rowBytes, ce.getValue()});
            table.add(new long[] {id("NMAT"), nAt, ePrimary * rowBytes, cn.getValue()});
            writeAt(ch, header(h, sections), 0);
            ByteBuffer tb = ByteBuffer.allocate(sections * ENTRY).order(ByteOrder.LITTLE_ENDIAN);
            for (long[] e : table) {
                tb.putInt((int) e[0]).putInt(0).putLong(e[1]).putLong(e[2]).putInt((int) e[3]).putInt(0);
            }
            tb.flip();
            writeAt(ch, tb, HEADER);
            ch.force(true);
        }
    }

    private static ByteBuffer floats(double[] d) {
        ByteBuffer b = ByteBuffer.allocate(d.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double x : d) {
            b.putFloat((float) x);
        }
        b.flip();
        return b;
    }

    private static ByteBuffer header(Header h, int sections) {
        ByteBuffer b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        b.put(MAGIC).putInt(VERSION);
        LocalDate d = h.businessDate();
        b.putInt(d.getYear() * 10_000 + d.getMonthValue() * 100 + d.getDayOfMonth());
        b.putInt(h.paths()).putInt(h.grid()).putInt(h.primaries()).putInt(h.sets()).putInt(sections);
        fixed(b, h.runId(), 64);
        fixed(b, h.digest(), 64);
        fixed(b, h.batchId(), 64);
        b.position(HEADER);
        b.flip();
        return b;
    }

    /** The header of a file, or null when it is not a whole exposure hot file of this format. */
    public static Header header(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return header(read(ch, 0, HEADER));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Header header(ByteBuffer b) {
        byte[] magic = new byte[8];
        b.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("not an exposure hot file");
        }
        int version = b.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("exposure hot file format " + version + ", this server reads " + VERSION);
        }
        int ymd = b.getInt();
        int p = b.getInt();
        int g = b.getInt();
        int gp = b.getInt();
        int sets = b.getInt();
        b.getInt();
        String run = text(b, 64);
        String digest = text(b, 64);
        String batch = text(b, 64);
        return new Header(version, LocalDate.of(ymd / 10_000, ymd / 100 % 100, ymd % 100), p, g, gp, sets, run, digest, batch);
    }

    /** Opens a file: its META checked and read, its matrices mapped (float32). */
    @SuppressWarnings("unchecked")
    public static Opened open(Path file) throws IOException {
        Header h;
        Map<String, long[]> table = new LinkedHashMap<>();
        Map<String, Object> meta;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer hb = read(ch, 0, HEADER);
            h = header(hb.duplicate().order(ByteOrder.LITTLE_ENDIAN));
            hb.position(32);
            int sections = hb.getInt();
            ByteBuffer tb = read(ch, HEADER, (long) sections * ENTRY);
            for (int i = 0; i < sections; i++) {
                int id = tb.getInt();
                tb.getInt();
                long off = tb.getLong();
                long len = tb.getLong();
                long crc = tb.getInt() & 0xffffffffL;
                tb.getInt();
                table.put(name(id), new long[] {off, len, crc});
            }
            long[] me = table.get("META");
            if (me == null || table.get("VMAT") == null || table.get("EMAT") == null || table.get("NMAT") == null) {
                throw new IOException("the exposure hot file " + file.getFileName() + " lacks a section");
            }
            ByteBuffer mb = read(ch, me[0], me[1]);
            CRC32C c = new CRC32C();
            c.update(mb.duplicate());
            if (c.getValue() != me[2]) {
                throw new IOException("the exposure hot file's META section is damaged (checksum)");
            }
            byte[] bytes = new byte[mb.remaining()];
            mb.get(bytes);
            meta = JSON.readValue(bytes, Map.class);
        }
        MappedMatrix v = null;
        MappedMatrix e = null;
        try {
            v = MappedMatrix.open(file, table.get("VMAT")[0], h.sets() * h.grid(), h.paths(), MappedMatrix.Type.FLOAT32, 0);
            e = MappedMatrix.open(file, table.get("EMAT")[0], h.sets() * h.primaries(), h.paths(), MappedMatrix.Type.FLOAT32, 0);
            MappedMatrix n = MappedMatrix.open(file, table.get("NMAT")[0], h.sets() * h.primaries(), h.paths(), MappedMatrix.Type.FLOAT32, 0);
            return new Opened(h, meta, v, e, n, file);
        } catch (IOException | RuntimeException ex) {
            if (v != null) {
                v.close();
            }
            if (e != null) {
                e.close();
            }
            throw ex;
        }
    }

    /** Checks every section's checksum (a test, or an administrator's verify). */
    public static void verify(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer hb = read(ch, 0, HEADER);
            header(hb.duplicate().order(ByteOrder.LITTLE_ENDIAN));
            hb.position(32);
            int sections = hb.getInt();
            ByteBuffer tb = read(ch, HEADER, (long) sections * ENTRY);
            for (int i = 0; i < sections; i++) {
                String name = name(tb.getInt());
                tb.getInt();
                long off = tb.getLong();
                long len = tb.getLong();
                long crc = tb.getInt() & 0xffffffffL;
                tb.getInt();
                CRC32C c = new CRC32C();
                long done = 0;
                while (done < len) {
                    ByteBuffer part = read(ch, off + done, Math.min(len - done, 1 << 24));
                    done += part.remaining();
                    c.update(part);
                }
                if (c.getValue() != crc) {
                    throw new IOException("the exposure hot file's " + name + " section is damaged (checksum)");
                }
            }
        }
    }

    private static void fixed(ByteBuffer h, String s, int width) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        int len = Math.min(width, b.length);
        h.put(b, 0, len);
        for (int i = len; i < width; i++) {
            h.put((byte) 0);
        }
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

    private static long writeAt(FileChannel ch, ByteBuffer b, long pos) throws IOException {
        long written = 0;
        while (b.hasRemaining()) {
            written += ch.write(b, pos + written);
        }
        return written;
    }

    private static ByteBuffer read(FileChannel ch, long pos, long length) throws IOException {
        if (length > Integer.MAX_VALUE - 8) {
            throw new IOException("a section of " + length + " bytes is too large to read whole");
        }
        ByteBuffer b = ByteBuffer.allocate((int) length).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            if (ch.read(b, pos + b.position()) < 0) {
                throw new IOException("the exposure hot file ends early");
            }
        }
        b.flip();
        return b;
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
}
