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
package com.ash.drishti.server.bi.poc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.flatbuf.Type;

/** RUPAKA PHASE 0 PROOF OF CONCEPT (tests): reads back an Arrow IPC stream written by {@link ArrowIpcWriter}, from the format's own definitions. */
final class ArrowTestReader {

    /** Column name to values (String, Long, Double or null), in order, and the row count. */
    record Table(Map<String, List<Object>> columns, int rows) {}

    private ArrowTestReader() {}

    static Table read(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        List<String> names = new ArrayList<>();
        List<Byte> types = new ArrayList<>();
        Map<String, List<Object>> out = new LinkedHashMap<>();
        int rows = 0;
        boolean ended = false;
        while (in.remaining() >= 8) {
            if (in.getInt() != -1) {
                throw new IllegalStateException("no continuation marker");
            }
            int size = in.getInt();
            if (size == 0) {
                ended = true;
                break;
            }
            ByteBuffer meta = in.slice().order(ByteOrder.LITTLE_ENDIAN).limit(size);
            in.position(in.position() + size);
            Message m = Message.getRootAsMessage(meta);
            ByteBuffer body = in.slice().order(ByteOrder.LITTLE_ENDIAN).limit((int) m.bodyLength());
            in.position(in.position() + (int) m.bodyLength());
            if (m.headerType() == MessageHeader.Schema) {
                Schema s = (Schema) m.header(new Schema());
                for (int i = 0; i < s.fieldsLength(); i++) {
                    Field f = s.fields(i);
                    names.add(f.name());
                    types.add(f.typeType());
                }
            } else if (m.headerType() == MessageHeader.RecordBatch) {
                RecordBatch rb = (RecordBatch) m.header(new RecordBatch());
                rows = (int) rb.length();
                int b = 0;
                for (int c = 0; c < names.size(); c++) {
                    long nulls = rb.nodes(c).nullCount();
                    var validity = rb.buffers(b++);
                    List<Object> col = new ArrayList<>();
                    boolean text = types.get(c) == Type.Utf8;
                    var first = rb.buffers(b++);
                    var second = text ? rb.buffers(b++) : null;
                    for (int r = 0; r < rows; r++) {
                        boolean valid = nulls == 0 || (body.get((int) validity.offset() + (r >> 3)) >> (r & 7) & 1) == 1;
                        if (!valid) {
                            col.add(null);
                        } else if (text) {
                            int from = body.getInt((int) first.offset() + r * 4);
                            int to = body.getInt((int) first.offset() + r * 4 + 4);
                            byte[] raw = new byte[to - from];
                            body.get((int) second.offset() + from, raw);
                            col.add(new String(raw, StandardCharsets.UTF_8));
                        } else if (types.get(c) == Type.Int) {
                            col.add(body.getLong((int) first.offset() + r * 8));
                        } else {
                            col.add(body.getDouble((int) first.offset() + r * 8));
                        }
                    }
                    out.put(names.get(c), col);
                }
            }
        }
        if (!ended) {
            throw new IllegalStateException("no end-of-stream marker");
        }
        return new Table(out, rows);
    }
}
