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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a row is, resolved once from the configuration exactly as {@code tools/lake/stage.py} resolves {@code lake build}'s options: the
 * promoted fields (with the flattened names added, the partition fields taken out), the partition fields, the vector fields (a field that
 * is also promoted or a partition field is not a vector), and the sort key: the partition values, the {@code sort-by} fields, the id.
 *
 * <p>A row travels between the stages as one record (no object per value): {@code [int keyLen][key][int idLen][id][int docLen][doc]},
 * then each promoted value ({@code tag}: 0 null, 1 a number {@code [double][int len][its Python text]}, 2 text {@code [int len][utf8]},
 * 3 other JSON {@code [int len][utf8]}: true, false, a list, an object), each partition value (0 or 2), each vector
 * ({@code [int n][n doubles]}, n = -1 for none; a null element is NaN).
 */
final class RowPlan {

    static final byte NULL = 0;
    static final byte NUM = 1;
    static final byte STR = 2;
    static final byte JSON = 3;

    /** A promoted field: its document path, its column name ({@code counterparty.id} is {@code counterparty__id}), whether it is an id field. */
    record Promoted(String path, String column, boolean idField) {}

    /** A vector field: path and column; {@code top}: a top-level field, which leaves the document for a {@code {"$vector": ...}} marker. */
    record Vector(String path, String column, boolean top) {}

    /** One component of the sort key: the id, or a promoted or partition value by index. */
    record SortRef(char kind, int index, String name) {}

    private static final Pattern ID_FIELD = Pattern.compile("(?i)(^|_)(id|ids|ref|refs|key)$");

    final String key;
    final boolean keyTop;
    final String date;
    final java.time.LocalDate asOf;
    /** The timestamp field that decides which duplicate is kept ({@link Latest}); null: the last read. */
    final String latestBy;
    final List<Promoted> promoted = new ArrayList<>();
    final List<String> partition = new ArrayList<>();
    final List<Vector> vectors = new ArrayList<>();
    final Map<String, String> flatten;
    final List<SortRef> sort = new ArrayList<>();
    /** Key components that make a shard group: the partition values and the sort fields before the id. */
    final int groupComponents;
    final List<String> sortNames = new ArrayList<>();

    RowPlan(LoadConfig.Source s, List<String> sortBy) {
        this.key = s.id();
        this.keyTop = !key.contains(".");
        this.date = s.date();
        this.asOf = s.asOf();
        this.latestBy = s.latestBy() == null || s.latestBy().isBlank() ? null : s.latestBy();
        this.flatten = new LinkedHashMap<>(s.flatten());
        List<String> promote = new ArrayList<>(s.promote());
        flatten.keySet().stream().filter(n -> !promote.contains(n)).forEach(promote::add);
        partition.addAll(s.partitionBy());
        for (String p : promote) {
            if (!partition.contains(p)) {
                promoted.add(new Promoted(p, column(p), isIdField(p) || p.equals(key)));
            }
        }
        for (String v : s.vectors()) {
            if (!partition.contains(v) && promote.stream().noneMatch(v::equals)) {
                vectors.add(new Vector(v, column(v), !v.contains(".")));
            }
        }
        List<String> wanted = sortBy.isEmpty() ? defaultSort() : sortBy;
        int group = partition.size();
        boolean idSeen = false;
        for (String name : wanted) {
            SortRef r = ref(name);
            if (r == null) {
                throw new IllegalArgumentException("target.layout.sort-by " + name + " is not id, the id field, a promoted field or a partition field");
            }
            if (r.kind() == 'i') {
                idSeen = true;
            } else if (!idSeen) {
                group++;
            }
            sort.add(r);
            sortNames.add(r.name());
        }
        if (!idSeen) {
            sort.add(new SortRef('i', 0, "id"));
            sortNames.add("id");
        }
        this.groupComponents = group;
    }

    /** The default sort: legal entity, desk, id, keeping those of them the rows have (the id alone when neither is promoted). */
    private List<String> defaultSort() {
        List<String> out = new ArrayList<>();
        for (String n : LoadConfig.DEFAULT_SORT) {
            if (n.equals("id") || ref(n) != null) {
                out.add(n);
            }
        }
        return out;
    }

    private SortRef ref(String name) {
        if (name.equals("id") || name.equals(key)) {
            return new SortRef('i', 0, "id");
        }
        for (int i = 0; i < partition.size(); i++) {
            if (partition.get(i).equals(name) || column(partition.get(i)).equals(name)) {
                return new SortRef('p', i, column(partition.get(i)));
            }
        }
        for (int i = 0; i < promoted.size(); i++) {
            if (promoted.get(i).path().equals(name) || promoted.get(i).column().equals(name)) {
                return new SortRef('c', i, promoted.get(i).column());
            }
        }
        return null;
    }

    /** True when the layout sorts by the id alone (as lake build's writers and the Iceberg loader do). */
    boolean sortedById() {
        return sort.size() == 1 && partition.isEmpty();
    }

    /** The physical column of a promoted path or partition: {@code counterparty.id} to {@code counterparty__id} (as tools/lake/stage.py). */
    static String column(String path) {
        return path.replace(".", "__");
    }

    /** A field named like an id or a key (ID, TRADE_ID, TRADE_IDS, DEAL_REF): its column is text whatever the values (stage.is_id_field). */
    static boolean isIdField(String path) {
        String p = path.replace("__", ".");
        return ID_FIELD.matcher(p.substring(p.lastIndexOf('.') + 1)).find();
    }

    // ------------------------------------------------------------------------------------------------ sort keys

    /** A null component. */
    static void keyNull(Bytes k) {
        k.put(0);
    }

    /** A number component: ordered as numbers (before every text). */
    static void keyNumber(Bytes k, double d) {
        long bits = Double.doubleToLongBits(d);
        bits = bits < 0 ? ~bits : bits ^ Long.MIN_VALUE;
        k.put(1);
        for (int s = 56; s >= 0; s -= 8) {
            k.put((int) (bits >>> s) & 0xff);
        }
    }

    /** A text component: its UTF-8 bytes (a zero byte escaped as 00 FF) and a 00 00 end, so a shorter text sorts first. */
    static void keyText(Bytes k, byte[] a, int off, int len) {
        k.put(2);
        k.ensure(len + 2);
        for (int i = off; i < off + len; i++) {
            k.put(a[i]);
            if (a[i] == 0) {
                k.put(0xff);
            }
        }
        k.put(0);
        k.put(0);
    }

    /** The length of the first {@code n} components of a key. */
    static int prefixLength(byte[] a, int off, int len, int n) {
        int i = off;
        int end = off + len;
        for (int c = 0; c < n && i < end; c++) {
            int tag = a[i++];
            if (tag == 1) {
                i += 8;
            } else if (tag == 2) {
                while (i + 1 < end && !(a[i] == 0 && a[i + 1] == 0)) {
                    i += a[i] == 0 ? 2 : 1;
                }
                i += 2;
            }
        }
        return Math.min(i, end) - off;
    }

    /** The components of a key as values (text, a number or null), for the load manifest. */
    static List<Object> components(byte[] a, int off, int len) {
        List<Object> out = new ArrayList<>();
        int i = off;
        int end = off + len;
        while (i < end) {
            int tag = a[i++];
            if (tag == 0) {
                out.add(null);
            } else if (tag == 1) {
                long bits = 0;
                for (int b = 0; b < 8; b++) {
                    bits = bits << 8 | (a[i + b] & 0xff);
                }
                bits = bits < 0 ? bits ^ Long.MIN_VALUE : ~bits;
                out.add(Double.longBitsToDouble(bits));
                i += 8;
            } else {
                Bytes t = new Bytes(32);
                while (i + 1 < end && !(a[i] == 0 && a[i + 1] == 0)) {
                    t.put(a[i]);
                    i += a[i] == 0 ? 2 : 1;
                }
                i += 2;
                out.add(new String(t.a, 0, t.n, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
