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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.VectorReads;
import com.ash.drishti.api.VectorSink;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A day of several Iceberg data files read with its files decoded at once ({@link VectorReads#parallel}, VAR_GUIDE.md section 27a.6)
 * gives exactly what one thread gives: the same rows in the same order, every value to the bit, the same texts, the rows without a vector.
 */
class IcebergParallelVectorsTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    @TempDir
    Path tmp;

    private static List<String> read(IcebergSourcePlugin p, VectorReads.Options o) throws Exception {
        List<String> out = new ArrayList<>();
        Thread me = Thread.currentThread();
        VectorSink sink = (id, values, len, texts) -> {
            assertThat(Thread.currentThread()).isSameAs(me);
            StringBuilder b = new StringBuilder(id).append('|').append(len).append('|').append(String.join(";", texts));
            for (int i = 0; i < len; i++) {
                b.append(',').append(Long.toHexString(Double.doubleToRawLongBits(values[i])));
            }
            out.add(b.toString());
        };
        assertThat(p.vectors(VectorLake.KIND, "PNL", List.of("TT_BOOK", "QUANTITY"), AsOf.of(DAY),
                o == null ? sink : VectorReads.parallel(sink, o))).isTrue();
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "library"})
    void severalFilesAtOnceGiveWhatOneThreadGives(String engine) throws Exception {
        Path root = tmp.resolve("lake-" + engine);
        VectorLake.loadIceberg(tmp, root, engine, Map.of(DAY, VectorLake.positions(60, 3)));   // seven rows a file: nine files
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(DatedSourceContract.context(VectorLake.settings(root, engine, tmp.resolve("cache-" + engine))));
        try {
            List<String> serial = read(p, null);
            assertThat(serial).hasSize(60).anyMatch(r -> r.contains("|-1|"));
            for (int threads : new int[] {2, 4, 8}) {
                assertThat(read(p, new VectorReads.Options(threads, 1L << 20))).as(threads + " threads").isEqualTo(serial);
            }
        } finally {
            p.close();
        }
    }
}
