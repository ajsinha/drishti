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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one JSON line into one row record ({@link RowPlan}) in a single streaming pass (Jackson's parser, no tree): the document is
 * written out again as {@code lake build} keeps it ({@link PyJson}: compact, Python's number text), the fields the plan wants are caught
 * on the way, and a vector field's numbers go straight into a {@code double[]}, never boxed. The rules are {@code tools/lake/stage.py}'s,
 * with its reject reasons: no id, an id that is not a single value, no business date or one that is not a date, a vector that is not a
 * list of numbers, a line that is not JSON or not an object. Used by one thread; a parser thread keeps one.
 */
final class RowParser {

    /** A field's value as caught. */
    private static final class Cap {
        static final byte MISSING = 0;
        static final byte NULLV = 1;
        static final byte STRING = 2;
        static final byte INT = 3;
        static final byte FLOAT = 4;
        static final byte BOOL = 5;
        static final byte CONTAINER = 6;
        byte kind;
        String text;
        double num;
        int start;
        int end;
    }

    /** A step of the wanted paths. */
    private static final class Node {
        Map<String, Node> fields;
        Map<Integer, Node> items;
        int slot = -1;
        int vector = -1;
        boolean top;
    }

    private static final Pattern YMD = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern STEP = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");
    private static final JsonFactory JSON = JsonFactory.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    private final RowPlan plan;
    private final Node root = new Node();
    private final Cap[] caps;
    private final int keySlot;
    private final int dateSlot;
    private final int[] promotedSlots;
    private final int[] partitionSlots;
    private final int[] flattenSlots;
    private final String[] flattenNames;
    private final Set<String> topNames;
    private final DoubleList[] vectors;
    private final boolean[] vectorPresent;
    private String badVector;

    final Bytes doc = new Bytes(1 << 16);
    final Bytes record = new Bytes(1 << 16);
    private final Bytes key = new Bytes(256);
    /** After an accepted line: its business date (days since the epoch), its id and the id's hash. */
    int epochDay;
    String id;
    long idHash;
    /** Per promoted column: a value that is not a number was seen (or it is an id field); a value was seen at all. */
    final boolean[] notNumeric;
    final boolean[] seen;

    RowParser(RowPlan plan) {
        this.plan = plan;
        Map<String, Integer> slots = new HashMap<>();
        keySlot = slot(slots, plan.key, false);
        dateSlot = plan.date == null || plan.asOf != null ? -1 : slot(slots, plan.date, false);
        promotedSlots = new int[plan.promoted.size()];
        for (int i = 0; i < promotedSlots.length; i++) {
            promotedSlots[i] = slot(slots, plan.promoted.get(i).path(), false);
        }
        partitionSlots = new int[plan.partition.size()];
        for (int i = 0; i < partitionSlots.length; i++) {
            partitionSlots[i] = slot(slots, plan.partition.get(i), false);
        }
        flattenNames = plan.flatten.keySet().toArray(new String[0]);
        flattenSlots = new int[flattenNames.length];
        for (int i = 0; i < flattenNames.length; i++) {
            flattenSlots[i] = slot(slots, plan.flatten.get(flattenNames[i]), true);
        }
        vectors = new DoubleList[plan.vectors.size()];
        vectorPresent = new boolean[vectors.length];
        for (int i = 0; i < vectors.length; i++) {
            RowPlan.Vector v = plan.vectors.get(i);
            Node n = node(v.path(), false);
            n.vector = i;
            n.top = v.top();
            vectors[i] = new DoubleList();
        }
        caps = new Cap[slots.size()];
        for (int i = 0; i < caps.length; i++) {
            caps[i] = new Cap();
        }
        topNames = flattenNames.length > 0 ? new HashSet<>() : null;
        notNumeric = new boolean[plan.promoted.size()];
        seen = new boolean[plan.promoted.size()];
        for (int i = 0; i < notNumeric.length; i++) {
            notNumeric[i] = plan.promoted.get(i).idField();
        }
    }

    private int slot(Map<String, Integer> slots, String path, boolean indexed) {
        String k = (indexed ? "i:" : "d:") + path;
        Integer s = slots.get(k);
        if (s == null) {
            s = slots.size();
            slots.put(k, s);
            node(path, indexed).slot = s;
        }
        return s;
    }

    /** The trie node of a path: dotted names ({@code stage.get}), or with list indexes too ({@code LEGS[0].NOTIONAL}, {@code stage.get_indexed}). */
    private Node node(String path, boolean indexed) {
        Node n = root;
        if (!indexed) {
            for (String part : path.split("\\.", -1)) {
                if (n.fields == null) {
                    n.fields = new HashMap<>();
                }
                n = n.fields.computeIfAbsent(part, x -> new Node());
            }
            return n;
        }
        Matcher m = STEP.matcher(path);
        while (m.find()) {
            if (m.group(1) != null) {
                if (n.fields == null) {
                    n.fields = new HashMap<>();
                }
                n = n.fields.computeIfAbsent(m.group(1), x -> new Node());
            } else {
                if (n.items == null) {
                    n.items = new HashMap<>();
                }
                n = n.items.computeIfAbsent(Integer.parseInt(m.group(2)), x -> new Node());
            }
        }
        return n;
    }

    /**
     * Parses the line {@code a[off, off+len)}; null when it is a row (then {@link #record}, {@link #epochDay}, {@link #id} hold it),
     * else the reason it is rejected.
     */
    String parse(byte[] a, int off, int len) {
        for (Cap c : caps) {
            c.kind = Cap.MISSING;
        }
        for (int i = 0; i < vectors.length; i++) {
            vectors[i].n = 0;
            vectorPresent[i] = false;
        }
        badVector = null;
        doc.clear();
        if (topNames != null) {
            topNames.clear();
        }
        try (JsonParser p = JSON.createParser(a, off, len)) {
            JsonToken t = p.nextToken();
            if (t != JsonToken.START_OBJECT) {
                p.skipChildren();
                if (p.nextToken() != null) {
                    return "not JSON (Extra data)";
                }
                return "not a JSON object";
            }
            object(p, root, 0);
            if (p.nextToken() != null) {
                return "not JSON (Extra data)";
            }
        } catch (JsonProcessingException e) {
            String m = e.getOriginalMessage();
            int nl = m == null ? -1 : m.indexOf('\n');
            return "not JSON (" + (m == null ? e.getClass().getSimpleName() : nl < 0 ? m : m.substring(0, nl)) + ")";
        } catch (IOException e) {
            return "not JSON (" + e.getMessage() + ")";
        }
        return row();
    }

    // ------------------------------------------------------------------------------------------------ the document, written again

    private void object(JsonParser p, Node node, int depth) throws IOException {
        int start = doc.n;
        doc.put('{');
        boolean first = true;
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            if (!first) {
                doc.put(',');
            }
            first = false;
            PyJson.string(doc, p.getTextCharacters(), p.getTextOffset(), p.getTextLength());
            doc.put(':');
            String name = p.currentName();
            Node child = node == null || node.fields == null ? null : node.fields.get(name);
            JsonToken v = p.nextToken();
            if (depth == 0) {
                if (topNames != null) {
                    topNames.add(name);
                }
                if (child != null && child.vector >= 0 && child.top) {
                    topVector(p, v, child);
                    continue;
                }
                if (plan.keyTop && (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT) && name.equals(plan.key)) {
                    Cap c = caps[keySlot];
                    number(p, v, c);
                    PyJson.string(doc, c.kind == Cap.INT ? c.text : PyJson.idOfFloat(c.num));   // the id as text, as lake build keeps it
                    continue;
                }
            }
            value(p, v, child, depth + 1);
        }
        if (t != JsonToken.END_OBJECT) {
            throw new IOException("an object ends early");
        }
        if (depth == 0 && flattenNames.length > 0) {
            for (int i = 0; i < flattenNames.length; i++) {
                Cap c = caps[flattenSlots[i]];
                if (c.kind != Cap.MISSING && !topNames.contains(flattenNames[i])) {
                    if (!first) {
                        doc.put(',');
                    }
                    first = false;
                    PyJson.string(doc, flattenNames[i]);
                    doc.put(':');
                    text(c, doc);
                }
            }
        }
        doc.put('}');
        if (node != null && node.slot >= 0) {
            Cap c = caps[node.slot];
            c.kind = Cap.CONTAINER;
            c.start = start;
            c.end = doc.n;
        }
    }

    private void value(JsonParser p, JsonToken t, Node node, int depth) throws IOException {
        if (node != null && node.vector >= 0 && t != JsonToken.START_ARRAY && t != JsonToken.VALUE_NULL && badVector == null) {
            badVector = plan.vectors.get(node.vector).path();
        }
        switch (t) {
            case START_OBJECT -> object(p, node, depth);
            case START_ARRAY -> array(p, node, depth);
            case VALUE_STRING -> {
                PyJson.string(doc, p.getTextCharacters(), p.getTextOffset(), p.getTextLength());
                if (node != null && node.slot >= 0) {
                    Cap c = caps[node.slot];
                    c.kind = Cap.STRING;
                    c.text = p.getText();
                }
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                Cap c = node != null && node.slot >= 0 ? caps[node.slot] : scratch;
                number(p, t, c);
                doc.ascii(c.text);
            }
            case VALUE_TRUE, VALUE_FALSE -> {
                boolean b = t == JsonToken.VALUE_TRUE;
                doc.ascii(b ? "true" : "false");
                if (node != null && node.slot >= 0) {
                    Cap c = caps[node.slot];
                    c.kind = Cap.BOOL;
                    c.text = b ? "true" : "false";
                }
            }
            case VALUE_NULL -> {
                doc.ascii("null");
                if (node != null && node.slot >= 0) {
                    caps[node.slot].kind = Cap.NULLV;
                }
            }
            default -> throw new IOException("an unexpected token " + t);
        }
    }

    private final Cap scratch = new Cap();

    private void number(JsonParser p, JsonToken t, Cap c) throws IOException {
        if (t == JsonToken.VALUE_NUMBER_INT) {
            c.kind = Cap.INT;
            c.text = PyJson.intText(p.getText());
            c.num = p.getDoubleValue();
        } else {
            c.kind = Cap.FLOAT;
            c.num = p.getDoubleValue();
            c.text = PyJson.floatRepr(c.num);
        }
    }

    private void array(JsonParser p, Node node, int depth) throws IOException {
        int start = doc.n;
        doc.put('[');
        DoubleList vec = node != null && node.vector >= 0 ? vectors[node.vector] : null;
        if (vec != null) {
            vectorPresent[node.vector] = true;
        }
        int i = 0;
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
            if (t == null) {
                throw new IOException("an array ends early");
            }
            if (i > 0) {
                doc.put(',');
            }
            Node child = node == null || node.items == null ? null : node.items.get(i);
            if (vec != null) {
                if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                    vec.add(p.getDoubleValue());
                } else if (t == JsonToken.VALUE_NULL) {
                    vec.add(Double.NaN);
                } else if (badVector == null) {
                    badVector = plan.vectors.get(node.vector).path();
                }
            }
            value(p, t, child, depth + 1);
            i++;
        }
        doc.put(']');
        if (node != null && node.slot >= 0) {
            Cap c = caps[node.slot];
            c.kind = Cap.CONTAINER;
            c.start = start;
            c.end = doc.n;
        }
    }

    /** A top-level vector field: its numbers into the vector, the document keeping {@code {"$vector": "<column>"}} in its place. */
    private void topVector(JsonParser p, JsonToken t, Node node) throws IOException {
        RowPlan.Vector v = plan.vectors.get(node.vector);
        if (t == JsonToken.VALUE_NULL) {
            doc.ascii("null");
            return;
        }
        if (t != JsonToken.START_ARRAY) {
            p.skipChildren();
            doc.ascii("null");
            if (badVector == null) {
                badVector = v.path();
            }
            return;
        }
        DoubleList vec = vectors[node.vector];
        vectorPresent[node.vector] = true;
        int i = 0;
        JsonToken e;
        while ((e = p.nextToken()) != JsonToken.END_ARRAY) {
            if (e == null) {
                throw new IOException("an array ends early");
            }
            Node child = node.items == null ? null : node.items.get(i);
            if (e == JsonToken.VALUE_NUMBER_INT || e == JsonToken.VALUE_NUMBER_FLOAT) {
                double d = p.getDoubleValue();
                vec.add(d);
                if (child != null && child.slot >= 0) {
                    number(p, e, caps[child.slot]);
                }
            } else if (e == JsonToken.VALUE_NULL) {
                vec.add(Double.NaN);
                if (child != null && child.slot >= 0) {
                    caps[child.slot].kind = Cap.NULLV;
                }
            } else {
                p.skipChildren();
                if (badVector == null) {
                    badVector = v.path();
                }
            }
            i++;
        }
        doc.ascii("{\"$vector\":");
        PyJson.string(doc, v.column());
        doc.put('}');
    }

    /** A caught value as JSON text (a flattened field appended to the document). */
    private void text(Cap c, Bytes out) {
        switch (c.kind) {
            case Cap.STRING -> PyJson.string(out, c.text);
            case Cap.INT, Cap.FLOAT, Cap.BOOL -> out.ascii(c.text);
            case Cap.CONTAINER -> out.put(doc.a, c.start, c.end - c.start);
            default -> out.ascii("null");
        }
    }

    // ------------------------------------------------------------------------------------------------ the row

    private String row() {
        Cap k = caps[keySlot];
        if (k.kind == Cap.MISSING || k.kind == Cap.NULLV || k.kind == Cap.STRING && k.text.isEmpty()) {
            return "no id (field '" + plan.key + "' is missing or empty)";
        }
        if (k.kind == Cap.CONTAINER) {
            return "the id field '" + plan.key + "' is not a single value";
        }
        id = switch (k.kind) {
            case Cap.FLOAT -> PyJson.idOfFloat(k.num);
            default -> k.text;
        };
        if (plan.asOf != null) {
            epochDay = (int) plan.asOf.toEpochDay();
        } else {
            Cap d = caps[dateSlot];
            LocalDate day = day(d);
            if (day == null) {
                boolean missing = d.kind == Cap.MISSING || d.kind == Cap.NULLV || d.kind == Cap.STRING && d.text.isEmpty();
                return "no business date (field '" + plan.date + "' is " + (missing ? "missing or empty)" : "not a date)");
            }
            epochDay = (int) day.toEpochDay();
        }
        if (badVector != null) {
            return "the vector field '" + badVector + "' is not a list of numbers";
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        idHash = hash(idBytes);
        // the sort key: the partition values, the sort fields, the id
        key.clear();
        for (int slot : partitionSlots) {
            String v = scalarText(caps[slot]);
            if (v == null) {
                RowPlan.keyNull(key);
            } else {
                byte[] b = v.getBytes(StandardCharsets.UTF_8);
                RowPlan.keyText(key, b, 0, b.length);
            }
        }
        for (RowPlan.SortRef r : plan.sort) {
            switch (r.kind()) {
                case 'i' -> RowPlan.keyText(key, idBytes, 0, idBytes.length);
                case 'p' -> {
                    String v = scalarText(caps[partitionSlots[r.index()]]);
                    if (v == null) {
                        RowPlan.keyNull(key);
                    } else {
                        byte[] b = v.getBytes(StandardCharsets.UTF_8);
                        RowPlan.keyText(key, b, 0, b.length);
                    }
                }
                default -> sortComponent(promotedValue(r.index()), r.index());
            }
        }
        Bytes rec = record;
        rec.clear();
        rec.putInt(key.n);
        rec.put(key.a, 0, key.n);
        rec.putInt(idBytes.length);
        rec.put(idBytes);
        rec.putInt(doc.n);
        rec.put(doc.a, 0, doc.n);
        for (int i = 0; i < promotedSlots.length; i++) {
            Cap c = promotedValue(i);
            RowPlan.Promoted pr = plan.promoted.get(i);
            if (c == null || c.kind == Cap.MISSING || c.kind == Cap.NULLV) {
                rec.put(RowPlan.NULL);
                continue;
            }
            seen[i] = true;
            if (pr.idField() && (c.kind == Cap.INT || c.kind == Cap.FLOAT)) {
                putText(rec, RowPlan.STR, c.kind == Cap.INT ? c.text : PyJson.idOfFloat(c.num));     // an id is text: 1000008, never 1000008.0
                continue;
            }
            switch (c.kind) {
                case Cap.INT, Cap.FLOAT -> {
                    rec.put(RowPlan.NUM);
                    rec.putDouble(c.num);
                    rec.putInt(c.text.length());
                    rec.ascii(c.text);
                }
                case Cap.STRING -> {
                    notNumeric[i] = true;
                    putText(rec, RowPlan.STR, c.text);
                }
                case Cap.BOOL -> {
                    notNumeric[i] = true;
                    putText(rec, RowPlan.JSON, c.text);
                }
                default -> {
                    notNumeric[i] = true;
                    rec.put(RowPlan.JSON);
                    rec.putInt(c.end - c.start);
                    rec.put(doc.a, c.start, c.end - c.start);
                }
            }
        }
        for (int slot : partitionSlots) {
            String v = scalarText(caps[slot]);
            if (v == null) {
                rec.put(RowPlan.NULL);
            } else {
                putText(rec, RowPlan.STR, v);
            }
        }
        for (int i = 0; i < vectors.length; i++) {
            if (!vectorPresent[i]) {
                rec.putInt(-1);
                continue;
            }
            DoubleList v = vectors[i];
            rec.putInt(v.n);
            rec.ensure(v.n * 8);
            for (int j = 0; j < v.n; j++) {
                Bytes.DOUBLE.set(rec.a, rec.n, v.a[j]);
                rec.n += 8;
            }
        }
        return null;
    }

    /** The value of promoted field {@code i}: the document's, else a flattened field of that name added to the document. */
    private Cap promotedValue(int i) {
        Cap c = caps[promotedSlots[i]];
        if (c.kind != Cap.MISSING) {
            return c;
        }
        String path = plan.promoted.get(i).path();
        for (int f = 0; f < flattenNames.length; f++) {
            if (flattenNames[f].equals(path)) {
                return caps[flattenSlots[f]];
            }
        }
        return c;
    }

    private void sortComponent(Cap c, int promotedIndex) {
        if (c == null || c.kind == Cap.MISSING || c.kind == Cap.NULLV) {
            RowPlan.keyNull(key);
        } else if ((c.kind == Cap.INT || c.kind == Cap.FLOAT) && !plan.promoted.get(promotedIndex).idField()) {
            RowPlan.keyNumber(key, c.num);
        } else {
            String s = c.kind == Cap.CONTAINER ? new String(doc.a, c.start, c.end - c.start, StandardCharsets.UTF_8)
                    : c.kind == Cap.FLOAT ? PyJson.idOfFloat(c.num) : c.text;
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            RowPlan.keyText(key, b, 0, b.length);
        }
    }

    private static void putText(Bytes rec, byte tag, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        rec.put(tag);
        rec.putInt(b.length);
        rec.put(b);
    }

    /** A partition value as text ({@code stage.scalar_text}): null for none, a number as Python writes it. */
    private String scalarText(Cap c) {
        return switch (c.kind) {
            case Cap.STRING, Cap.INT, Cap.FLOAT, Cap.BOOL -> c.text;
            case Cap.CONTAINER -> new String(doc.a, c.start, c.end - c.start, StandardCharsets.UTF_8);
            default -> null;
        };
    }

    /** A business date ({@code stage.parse_day}): an ISO date or date-time, yyyymmdd as text or a number; null when it is none. */
    static LocalDate day(Cap c) {
        String s;
        if (c.kind == Cap.STRING) {
            s = c.text.strip();
        } else if (c.kind == Cap.INT) {
            s = c.text;
        } else {
            return null;
        }
        try {
            Matcher m = YMD.matcher(s);
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null;
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    /** A 64-bit hash of an id (FNV-1a, then mixed), to count ids seen twice in a date. */
    static long hash(byte[] b) {
        long h = 0xcbf29ce484222325L;
        for (byte x : b) {
            h ^= x & 0xff;
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        return h ^ h >>> 33;
    }

    /** A growable list of doubles (one vector's values, reused row after row). */
    static final class DoubleList {
        double[] a = new double[1024];
        int n;

        void add(double d) {
            if (n == a.length) {
                a = java.util.Arrays.copyOf(a, n * 2);
            }
            a[n++] = d;
        }
    }

    /** The promoted columns' names, for the report. */
    List<String> promotedColumns() {
        return plan.promoted.stream().map(RowPlan.Promoted::column).toList();
    }
}
