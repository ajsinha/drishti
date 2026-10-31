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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.VectorReads;
import com.ash.drishti.api.VectorSink;
import com.ash.drishti.deltalake.write.LakeCli;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A date of several vector files read with its files decoded at once ({@link VectorReads#parallel}, VAR_GUIDE.md section 27a.6) gives
 * exactly what one thread reading them gives: the same rows in the same order, every value to the bit, the same texts, the rows without
 * a vector; and a damaged file fails the read as it does on one thread.
 */
class DeltaParallelVectorsTest {

    static final int N = 509;
    static final int PARTS = 6;
    static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    @TempDir
    Path tmp;

    /** A pnl-vector table of {@code PARTS} files on one date, {@code rows} trades each, a few without a vector. */
    private void write(Path table, int rows) throws Exception {
        write(table, rows, PARTS);
    }

    private void write(Path table, int rows, int files) throws Exception {
        Random r = new Random(11);
        List<String> parts = new ArrayList<>();
        for (int p = 0; p < files; p++) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                String id = "MX-" + (20000000 + p * 10_000 + i);
                StringBuilder a = new StringBuilder("[");
                for (int j = 0; j < N; j++) {
                    a.append(j == 0 ? "" : ",").append(Math.round(r.nextGaussian() * 50_000) + (j % 7 == 0 ? 0.25 : 0) + r.nextDouble());
                }
                String pnl = i % 41 == 5 ? "null" : a.append(']').toString();
                String doc = "{\\\"VECTOR_ID\\\":\\\"PV-" + id + "\\\",\\\"PNL\\\":{\\\"$vector\\\":\\\"PNL\\\"}}";
                lines.add("[\"" + id + "\",\"" + doc + "\",{\"DESK\":\"DESK-" + (i % 4) + "\",\"TT_BOOK\":" + (i % 9 == 0 ? "null" : "\"BOOK-" + (i % 13) + "\"")
                        + ",\"PNL\":" + pnl + "}]");
            }
            Path rowsFile = tmp.resolve("v" + p + ".rows");
            Files.write(rowsFile, lines, StandardCharsets.UTF_8);
            parts.add("{\"file\":\"" + rowsFile + "\",\"values\":{\"business_date\":\"" + DAY + "\"}}");
        }
        String m = "{\"table\":\"" + table + "\",\"partitionColumns\":[\"business_date\"],\"columns\":["
                + "{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"doc\",\"type\":\"string\"},{\"name\":\"business_date\",\"type\":\"date\"},"
                + "{\"name\":\"DESK\",\"type\":\"string\"},{\"name\":\"TT_BOOK\",\"type\":\"string\"},{\"name\":\"PNL\",\"type\":\"vector\"}],"
                + "\"promoted\":[\"DESK\",\"TT_BOOK\",\"PNL\"],\"mode\":\"overwrite-dates\",\"batchRows\":50,\"statsColumns\":[\"id\",\"business_date\"],"
                + "\"parts\":[" + String.join(",", parts) + "]}";
        Path mf = tmp.resolve("manifest.json");
        Files.writeString(mf, m);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
    }

    private DeltaSourcePlugin plugin(Path root) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "risk", "source-name", "risk-store", "engine", "native",
                "layout.pnl-vector.columns", "DESK,TT_BOOK", "layout.pnl-vector.vectors", "PNL"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    /** One line per row, in order: id, length, texts, every value's bits. */
    private static List<String> read(DeltaSourcePlugin p, VectorReads.Options o) throws Exception {
        List<String> out = new ArrayList<>();
        Thread me = Thread.currentThread();
        VectorSink sink = (id, values, len, texts) -> {
            assertThat(Thread.currentThread()).isSameAs(me);
            StringBuilder b = new StringBuilder(id).append('|').append(len).append('|').append(texts[0]).append('|').append(texts[1]);
            for (int i = 0; i < len; i++) {
                b.append(',').append(Long.toHexString(Double.doubleToRawLongBits(values[i])));
            }
            out.add(b.toString());
        };
        assertThat(p.vectors("pnl-vector", "PNL", List.of("DESK", "TT_BOOK"), AsOf.of(DAY), o == null ? sink : VectorReads.parallel(sink, o)))
                .isTrue();
        return out;
    }

    private List<Path> files(Path table) throws Exception {
        try (Stream<Path> s = Files.walk(table)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".parquet")).sorted().toList();
        }
    }

    @Test
    void severalFilesAtOnceGiveWhatOneThreadGives() throws Exception {
        Path root = tmp.resolve("lake");
        write(root.resolve("risk/pnl-vector"), 300);
        assertThat(files(root.resolve("risk/pnl-vector"))).hasSizeGreaterThan(1);
        DeltaSourcePlugin p = plugin(root);
        try {
            List<String> serial = read(p, null);
            assertThat(serial).hasSize(PARTS * 300);
            assertThat(serial).anyMatch(r -> r.contains("|-1|"));                          // rows without a vector are kept
            for (VectorReads.Options o : List.of(new VectorReads.Options(2, 1L << 20), new VectorReads.Options(4, 1L << 20),
                    new VectorReads.Options(8, 64L << 20))) {
                assertThat(read(p, o)).as(o.toString()).isEqualTo(serial);
            }
        } finally {
            p.close();
        }
    }

    /** One large file: its row groups are read in runs on several threads and put back in order. */
    @Test
    void theRowGroupsOfOneFileAtOnceGiveWhatOneThreadGives() throws Exception {
        Path root = tmp.resolve("lake1");
        write(root.resolve("risk/pnl-vector"), 1200, 1);
        List<Path> fs = files(root.resolve("risk/pnl-vector"));
        assertThat(fs).hasSize(1);
        try (com.ash.drishti.deltalake.NativeEngine ne = com.ash.drishti.deltalake.NativeEngine.create()) {
            assertThat(ne.rowGroups(io.delta.kernel.utils.FileStatus.of(fs.get(0).toString(), Files.size(fs.get(0)), 0)).length)
                    .as("row groups of the file").isGreaterThan(4);
        }
        DeltaSourcePlugin p = plugin(root);
        try {
            List<String> serial = read(p, null);
            assertThat(serial).hasSize(1200);
            for (int threads : new int[] {2, 3, 8}) {
                assertThat(read(p, new VectorReads.Options(threads, 1L << 20))).as(threads + " threads").isEqualTo(serial);
            }
        } finally {
            p.close();
        }
    }

    @Test
    void aDamagedFileFailsTheReadAsOnOneThread() throws Exception {
        Path root = tmp.resolve("lake");
        write(root.resolve("risk/pnl-vector"), 400);
        List<Path> fs = files(root.resolve("risk/pnl-vector"));
        DeltaSourcePlugin p = plugin(root);
        try {
            read(p, new VectorReads.Options(4, 1L << 20));                          // the footers known before the damage
            Files.write(fs.get(fs.size() / 2), "not a parquet file".getBytes(StandardCharsets.UTF_8));
            Class<? extends Throwable> serial = catchType(() -> read(p, null));
            assertThat(serial).isNotNull();
            assertThatThrownBy(() -> read(p, new VectorReads.Options(4, 1L << 20))).isInstanceOf(serial);
            assertThatThrownBy(() -> read(p, new VectorReads.Options(2, 1L << 20))).isInstanceOf(serial);
        } finally {
            p.close();
        }
    }

    private static Class<? extends Throwable> catchType(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        try {
            c.call();
            return null;
        } catch (Throwable t) {
            return t.getClass();
        }
    }
}
