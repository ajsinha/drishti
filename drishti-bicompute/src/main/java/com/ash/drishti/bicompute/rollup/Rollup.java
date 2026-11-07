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
package com.ash.drishti.bicompute.rollup;

import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.Partials;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * One rollup on one date (ARCHITECTURE.md section 23.9.6): per partition, the parts absorbed and the partial states per grain cell key (members
 * and one state per function, in the definition's order). The partitions are merged in partition order, then cell-key order, so the merged
 * rows are the same whatever order the loads came in. Encoded to bytes with each function's own codec (what is kept on disk).
 */
public final class Rollup {

    /** The partition of a source that is not parted. */
    public static final String WHOLE = "*";

    /** One partition: the parts absorbed and its rows. */
    public static final class Partition {
        final TreeSet<String> parts = new TreeSet<>();
        final TreeMap<CellKey, Partials.Entry> rows = new TreeMap<>();

        public java.util.Set<String> parts() {
            return java.util.Collections.unmodifiableSet(parts);
        }

        public int size() {
            return rows.size();
        }
    }

    final RollupDef def;
    final String date;
    final List<FunctionCall> functions;
    final TreeMap<String, Partition> partitions = new TreeMap<>();
    String version;
    String refreshedAt;
    String lastRefresh = "";
    private volatile TreeMap<CellKey, Partials.Entry> merged;

    Rollup(RollupDef def, String date, List<FunctionCall> functions) {
        this.def = def;
        this.date = date;
        this.functions = List.copyOf(functions);
    }

    public RollupDef def() {
        return def;
    }

    public String date() {
        return date;
    }

    public String version() {
        return version;
    }

    public List<FunctionCall> functions() {
        return functions;
    }

    /** The function index of a call id, or -1. */
    int index(String functionId) {
        for (int i = 0; i < functions.size(); i++) {
            if (functions.get(i).id().equals(functionId)) {
                return i;
            }
        }
        return -1;
    }

    /** The grain rows of every partition merged, in partition order then key order (computed once per refresh). */
    TreeMap<CellKey, Partials.Entry> merged() {
        TreeMap<CellKey, Partials.Entry> m = merged;
        if (m == null) {
            m = new TreeMap<>();
            for (Partition p : partitions.values()) {
                for (Map.Entry<CellKey, Partials.Entry> e : p.rows.entrySet()) {
                    Partials.Entry have = m.get(e.getKey());
                    m.put(e.getKey(), have == null ? e.getValue() : merge(have, e.getValue()));
                }
            }
            merged = m;
        }
        return m;
    }

    void changed() {
        merged = null;
    }

    /** Two entries of the same key as one (members added, states merged by each function). */
    Partials.Entry merge(Partials.Entry a, Partials.Entry b) {
        Object[] s = new Object[functions.size()];
        for (int f = 0; f < s.length; f++) {
            s[f] = functions.get(f).fn().merge(a.states()[f], b.states()[f]);
        }
        return new Partials.Entry(a.members() + b.members(), s);
    }

    /** A partition's rows with {@code more} merged in. */
    void absorb(Partition p, Map<CellKey, Partials.Entry> more) {
        for (Map.Entry<CellKey, Partials.Entry> e : more.entrySet()) {
            Partials.Entry have = p.rows.get(e.getKey());
            p.rows.put(e.getKey(), have == null ? e.getValue() : merge(have, e.getValue()));
        }
        changed();
    }

    /** The grain rows kept (every partition). */
    public long rows() {
        long n = 0;
        for (Partition p : partitions.values()) {
            n += p.rows.size();
        }
        return n;
    }

    /** The bytes the states hold, roughly. */
    public long bytes() {
        long b = 0;
        for (Partition p : partitions.values()) {
            for (Map.Entry<CellKey, Partials.Entry> e : p.rows.entrySet()) {
                b += 64 + 2L * e.getKey().canonical().length();
                for (int f = 0; f < functions.size(); f++) {
                    b += functions.get(f).fn().bytes(e.getValue().states()[f]);
                }
            }
        }
        return b;
    }

    public Map<String, Partition> partitions() {
        return java.util.Collections.unmodifiableMap(partitions);
    }

    // ---- codec ---------------------------------------------------------------------------------------------------------------------------

    static final int MAGIC = 0x44525231;   // DRR1

    /** The rollup as bytes: its version, the functions' ids, the partitions with their parts and rows, states by each function's codec. */
    byte[] encode() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(MAGIC);
            o.writeUTF(def.id());
            o.writeUTF(date == null ? "" : date);
            o.writeUTF(version == null ? "" : version);
            o.writeUTF(refreshedAt == null ? "" : refreshedAt);
            o.writeInt(functions.size());
            for (FunctionCall f : functions) {
                o.writeUTF(f.id());
            }
            o.writeInt(partitions.size());
            for (Map.Entry<String, Partition> p : partitions.entrySet()) {
                o.writeUTF(p.getKey());
                o.writeInt(p.getValue().parts.size());
                for (String part : p.getValue().parts) {
                    writeLong(o, part);
                }
                o.writeInt(p.getValue().rows.size());
                for (Map.Entry<CellKey, Partials.Entry> r : p.getValue().rows.entrySet()) {
                    o.writeInt(r.getKey().coords().size());
                    for (Map.Entry<String, String> c : r.getKey().coords().entrySet()) {
                        writeLong(o, c.getKey());
                        writeLong(o, c.getValue());
                    }
                    o.writeLong(r.getValue().members());
                    for (int f = 0; f < functions.size(); f++) {
                        byte[] b = functions.get(f).fn().encode(r.getValue().states()[f]);
                        o.writeInt(b.length);
                        o.write(b);
                    }
                }
            }
            o.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A rollup from its bytes; the functions resolved by id ({@code fns}); null when the bytes keep other functions than the definition. */
    static Rollup decode(RollupDef def, byte[] bytes, Function<String, FunctionCall> fns) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) {
                return null;
            }
            String id = in.readUTF();
            if (!id.equals(def.id())) {
                return null;
            }
            String date = in.readUTF();
            String version = in.readUTF();
            String at = in.readUTF();
            int nf = in.readInt();
            List<FunctionCall> calls = new ArrayList<>();
            for (int i = 0; i < nf; i++) {
                calls.add(fns.apply(in.readUTF()));
            }
            Rollup r = new Rollup(def, date.isEmpty() ? null : date, calls);
            r.version = version;
            r.refreshedAt = at;
            int np = in.readInt();
            for (int i = 0; i < np; i++) {
                Partition p = new Partition();
                String name = in.readUTF();
                int parts = in.readInt();
                for (int j = 0; j < parts; j++) {
                    p.parts.add(readLong(in));
                }
                int rows = in.readInt();
                for (int j = 0; j < rows; j++) {
                    int nc = in.readInt();
                    TreeMap<String, String> coords = new TreeMap<>();
                    for (int c = 0; c < nc; c++) {
                        coords.put(readLong(in), readLong(in));
                    }
                    long members = in.readLong();
                    Object[] st = new Object[nf];
                    for (int f = 0; f < nf; f++) {
                        byte[] b = new byte[in.readInt()];
                        in.readFully(b);
                        st[f] = calls.get(f).fn().decode(b);
                    }
                    p.rows.put(new CellKey(coords), new Partials.Entry(members, st));
                }
                r.partitions.put(name, p);
            }
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeLong(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        o.writeInt(b.length);
        o.write(b);
    }

    private static String readLong(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }
}
