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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shard's skeleton (VAR_SHARDING.md section 5): its hot file without the matrices, which an owner sends to the members that put the date
 * together but do not hold the shard. The same format as the hot file ({@link VarHotFile}: the header, the section table, each section with
 * its CRC32C), flagged {@link VarHotFile#SKELETON}, holding only what the assembly reads: {@code META}, {@code SCEN}, {@code TIDS},
 * {@code TCOL}, {@code BOOK}, {@code AKEY}, {@code AGGS}, and {@code CTOT}, {@code CELL}, {@code RANG} when present. About 0.8 KB a trade
 * against 2 KB of float32 rows at 509 scenarios.
 */
public final class ShardSkeleton {

    /** The sections a skeleton keeps, in this order. */
    static final List<String> KEEP = List.of("META", "SCEN", "TIDS", "TCOL", "BOOK", "AKEY", "AGGS", "CTOT", "CELL", "RANG");

    private ShardSkeleton() {}

    /** Writes the skeleton of the shard hot file {@code file} to {@code out}; returns the bytes written. */
    public static long write(Path file, OutputStream out) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer head = VarHotFile.read(ch, 0, VarHotFile.HEADER);
            Map<String, long[]> table = VarHotFile.table(ch);
            List<String> kept = KEEP.stream().filter(table::containsKey).toList();
            long at = VarHotFile.HEADER + (long) kept.size() * VarHotFile.ENTRY;
            List<long[]> layout = new ArrayList<>();                          // {id, new offset, length, crc, old offset}
            for (String name : kept) {
                long[] e = table.get(name);
                at = VarHotFile.align(at, 8);
                layout.add(new long[] {VarHotFile.id(name), at, e[1], e[2], e[0]});
                at += e[1];
            }
            ByteBuffer h = ByteBuffer.allocate(VarHotFile.HEADER).order(ByteOrder.LITTLE_ENDIAN);
            h.put(head.duplicate().order(ByteOrder.LITTLE_ENDIAN));
            h.putInt(12, h.getInt(12) | VarHotFile.SKELETON);
            h.putInt(24, 0);                                                 // P: no position rows here
            h.putInt(36, kept.size());
            h.putInt(184, 0);                                                // Q: no trade rows here
            WritableByteChannel to = Channels.newChannel(out);
            long written = write(to, h.flip());
            ByteBuffer tb = ByteBuffer.allocate(kept.size() * VarHotFile.ENTRY).order(ByteOrder.LITTLE_ENDIAN);
            for (long[] e : layout) {
                tb.putInt((int) e[0]).putInt(0).putLong(e[1]).putLong(e[2]).putInt((int) e[3]).putInt(0);
            }
            written += write(to, tb.flip());
            long pos = written;
            for (long[] e : layout) {
                if (e[1] > pos) {
                    written += write(to, ByteBuffer.allocate((int) (e[1] - pos)));   // the 8-byte alignment
                    pos = e[1];
                }
                long done = 0;
                while (done < e[2]) {
                    long n = ch.transferTo(e[4] + done, e[2] - done, to);
                    if (n <= 0) {
                        throw new IOException("the hot file " + file + " ends early in a section");
                    }
                    done += n;
                }
                written += e[2];
                pos += e[2];
            }
            out.flush();
            return written;
        }
    }

    private static long write(WritableByteChannel to, ByteBuffer b) throws IOException {
        long n = 0;
        while (b.hasRemaining()) {
            n += to.write(b);
        }
        return n;
    }

    /**
     * Reads a shard's skeleton (or a whole shard hot file) as a part of its date's assembly, without its rows. Every section's checksum is
     * checked; a damaged one is refused.
     *
     * @param shard the shard's index in the date's map
     */
    public static ShardAssembly.Part read(Path file, int shard) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            VarHotFile.Header h = VarHotFile.header(VarHotFile.read(ch, 0, VarHotFile.HEADER));
            Map<String, long[]> table = VarHotFile.table(ch);
            String meta = new String(VarHotFile.section(ch, table, "META").bytes(), StandardCharsets.UTF_8);
            VarHotFile.In s = VarHotFile.section(ch, table, "SCEN");
            List<LocalDate> scen = new ArrayList<>();
            for (int i = s.getInt(); i > 0; i--) {
                scen.add(LocalDate.ofEpochDay(s.getInt()));
            }
            String[] tids = VarHotFile.section(ch, table, "TIDS").strings();
            Columns tcols = VarHotFile.section(ch, table, "TCOL").columns();
            Map<String, int[]> units = new LinkedHashMap<>();
            VarHotFile.In b = VarHotFile.section(ch, table, "BOOK");
            for (int i = b.getInt(); i > 0; i--) {
                units.put(b.getString(), new int[] {b.getInt(), b.getInt()});
            }
            String[] keys = VarHotFile.section(ch, table, "AKEY").strings();
            VarHotFile.In a = VarHotFile.section(ch, table, "AGGS");
            Map<String, double[]> agg = new LinkedHashMap<>();
            for (String k : keys) {
                double[] v = new double[h.width()];
                for (int j = 0; j < v.length; j++) {
                    v[j] = a.getDouble();
                }
                agg.put(k, v);
            }
            CurrencyTotals totals = table.containsKey("CTOT") ? CurrencyTotals.read(VarHotFile.section(ch, table, "CTOT"), h.width()) : null;
            List<String> cells = new ArrayList<>();
            int split = 0;
            if (table.containsKey("CELL")) {
                VarHotFile.In c = VarHotFile.section(ch, table, "CELL");
                split = c.getInt();
                for (int i = c.getInt(); i > 0; i--) {
                    cells.add(c.getString());
                }
            }
            String range = table.containsKey("RANG") ? new String(VarHotFile.section(ch, table, "RANG").bytes(), StandardCharsets.UTF_8) : null;
            return new ShardAssembly.Part(shard, h.digest(), meta, range, h.scenarioSet(), scen, tids, tcols, units, agg, (int) h.failed(), split,
                    cells, totals, null);
        }
    }
}
