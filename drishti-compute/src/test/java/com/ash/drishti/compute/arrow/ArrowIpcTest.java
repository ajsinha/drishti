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
package com.ash.drishti.compute.arrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryEncoder;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The compute transport's codec on the official Arrow library: every column type with nulls, many batches, a file, a dictionary. */
class ArrowIpcTest {

    static ArrowTable sample(int rows) {
        long[] ids = new long[rows];
        double[] v = new double[rows];
        boolean[] b = new boolean[rows];
        String[] s = new String[rows];
        double[] vec = new double[rows * 3];
        BitSet nulls = new BitSet();
        for (int i = 0; i < rows; i++) {
            ids[i] = i;
            v[i] = i / 4.0;
            b[i] = i % 2 == 0;
            s[i] = "row " + i + " é";
            vec[i * 3] = i;
            vec[i * 3 + 1] = -i;
            vec[i * 3 + 2] = 0.5;
            if (i % 7 == 3) {
                nulls.set(i);
            }
        }
        return new ArrowTable(List.of(new ArrowTable.Column("ID", ArrowTable.Type.INT64, 1, ids, null),
                new ArrowTable.Column("V", ArrowTable.Type.FLOAT64, 1, v, nulls), new ArrowTable.Column("B", ArrowTable.Type.BOOL, 1, b, nulls),
                new ArrowTable.Column("S", ArrowTable.Type.UTF8, 1, s, nulls), new ArrowTable.Column("PATH", ArrowTable.Type.FLOAT64_VECTOR, 3, vec, null)));
    }

    /** Cell for cell, vectors by value. */
    static void same(ArrowTable a, ArrowTable b) {
        assertThat(b.rows()).isEqualTo(a.rows());
        assertThat(b.names()).isEqualTo(a.names());
        for (ArrowTable.Column c : a.columns()) {
            for (int r = 0; r < a.rows(); r++) {
                Object x = c.value(r);
                Object y = b.column(c.name()).value(r);
                if (x instanceof double[] dx) {
                    assertThat((double[]) y).as(c.name() + " row " + r).containsExactly(dx);
                } else {
                    assertThat(y).as(c.name() + " row " + r).isEqualTo(x);
                }
            }
        }
    }

    @Test
    void everyTypeAndItsNullsComeBackAsWritten() throws Exception {
        ArrowTable t = sample(50);
        ArrowTable back = ArrowIpc.read(ArrowIpc.toBytes(t));
        assertThat(back.rows()).isEqualTo(50);
        assertThat(back.names()).containsExactly("ID", "V", "B", "S", "PATH");
        same(t, back);
        assertThat(back.column("V").isNull(3)).isTrue();
        assertThat(back.vector("PATH", 9)).containsExactly(9, -9, 0.5);
        assertThat(ArrowMemory.allocated()).as("no Arrow memory held after").isZero();
    }

    @Test
    void anEmptyTableKeepsItsColumns() throws Exception {
        ArrowTable back = ArrowIpc.read(ArrowIpc.toBytes(sample(0)));
        assertThat(back.rows()).isZero();
        assertThat(back.names()).containsExactly("ID", "V", "B", "S", "PATH");
    }

    @Test
    void aLargeTableIsWrittenInSeveralBatchesAndJoinedWhenRead(@TempDir Path dir) throws Exception {
        ArrowTable t = sample(1000);
        assertThat(ArrowIpc.batchRows(t)).isGreaterThan(0);
        Path f = dir.resolve("t.arrows");
        ArrowIpc.write(t, f);
        same(t, ArrowIpc.read(f));
    }

    @Test
    void aDictionaryColumnIsReadAsText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BufferAllocator a = ArrowMemory.child("dict-test", 0)) {
            DictionaryEncoding enc = new DictionaryEncoding(0, false, new ArrowType.Int(32, true));
            try (VarCharVector values = new VarCharVector("DESK", a); VarCharVector raw = new VarCharVector("DESK", a)) {
                values.allocateNew(2);
                values.setSafe(0, "RATES".getBytes());
                values.setSafe(1, "FX".getBytes());
                values.setValueCount(2);
                raw.allocateNew(3);
                raw.setSafe(0, "FX".getBytes());
                raw.setSafe(1, "RATES".getBytes());
                raw.setSafe(2, "FX".getBytes());
                raw.setValueCount(3);
                Dictionary d = new Dictionary(values, enc);
                DictionaryProvider.MapDictionaryProvider p = new DictionaryProvider.MapDictionaryProvider(d);
                try (org.apache.arrow.vector.FieldVector idx = (org.apache.arrow.vector.FieldVector) DictionaryEncoder.encode(raw, d);
                        VectorSchemaRoot root = new VectorSchemaRoot(List.of(new Field("DESK", new FieldType(true, ArrowType.Utf8.INSTANCE, enc), null)),
                                List.of(idx), 3);
                        ArrowStreamWriter w = new ArrowStreamWriter(root, p, Channels.newChannel(out))) {
                    w.start();
                    w.writeBatch();
                    w.end();
                }
            }
        }
        ArrowTable back = ArrowIpc.read(out.toByteArray());
        assertThat(back.strings("DESK")).containsExactly("FX", "RATES", "FX");
    }

    @Test
    void bytesThatAreNotAStreamAreRefusedInWords() {
        assertThatThrownBy(() -> ArrowIpc.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8})).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void theSchemaCarriesItsMetadata() {
        Schema s = ArrowIpc.schema(sample(1), java.util.Map.of("drishti.kind", "trade"));
        assertThat(s.getCustomMetadata()).containsEntry("drishti.kind", "trade");
        assertThat(s.findField("PATH").getChildren().get(0).getType()).isInstanceOf(ArrowType.FloatingPoint.class);
    }
}
