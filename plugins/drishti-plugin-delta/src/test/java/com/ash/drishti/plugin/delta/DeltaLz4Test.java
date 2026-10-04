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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.parquet.format.CompressionCodec;
import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.Util;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * LZ4 Parquet pages as delta-rs and Arrow write them, read by both engines and compared value by value with the same
 * table written uncompressed. {@code src/test/resources/lake-codecs} holds the fixtures (two dates of 40 trades each):
 * {@code lz4} and {@code lz4_raw} (delta-rs asked for each codec), {@code arrow_lz4} (the same table whose files pyarrow
 * rewrote with {@code compression="lz4"}: Arrow's raw blocks under the LZ4 codec) and {@code uncompressed}.
 */
class DeltaLz4Test {

    private static final Path CODECS = Path.of("src/test/resources/lake-codecs");
    private static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);
    private static final List<String> COLS = List.of("mtm", "book", "nettingSet", "counterparty.id");

    private static DeltaSourcePlugin plugin(String fixture, String engine) throws Exception {
        return plugin(CODECS.resolve(fixture), engine);
    }

    private static DeltaSourcePlugin plugin(Path root, String engine) throws Exception {
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toAbsolutePath().toString(), "domain", "desk",
                "source-name", "lake", "engine", engine, "layout.trade.columns", "mtm,book,nettingSet,counterparty.id")));
        return p;
    }

    /** The codecs the fixture's column chunks declare, read from its footers (so the test knows what it is testing). */
    private static Set<CompressionCodec> codecsOf(String fixture) throws Exception {
        return codecsIn(CODECS.resolve(fixture));
    }

    private static Set<CompressionCodec> codecsIn(Path dir) throws Exception {
        Set<CompressionCodec> found = new HashSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".parquet")).toList()) {
                byte[] all = Files.readAllBytes(f);
                int len = ByteBuffer.wrap(all, all.length - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                FileMetaData md = Util.readFileMetaData(new ByteArrayInputStream(all, all.length - 8 - len, len));
                md.getRow_groups().forEach(rg -> rg.getColumns().forEach(c -> found.add(c.getMeta_data().getCodec())));
            }
        }
        return found;
    }

    @ParameterizedTest
    @ValueSource(strings = {"lz4", "lz4_raw", "arrow_lz4"})
    void fixturesAreRealLz4Pages(String fixture) throws Exception {
        System.out.println("codec footer of " + fixture + ": " + codecsOf(fixture));
        assertThat(codecsOf(fixture)).hasSize(1).containsAnyOf(CompressionCodec.LZ4, CompressionCodec.LZ4_RAW);
        assertThat(codecsOf("uncompressed")).containsExactly(CompressionCodec.UNCOMPRESSED);
    }

    /** What older Arrow and parquet-cpp wrote: a raw LZ4 block under the deprecated LZ4 codec (footers say LZ4, pages unchanged). */
    private static Path rawBlocksUnderLz4() throws Exception {
        Path root = Files.createTempDirectory("drishti-lz4-raw-under-lz4");
        Path src = CODECS.resolve("arrow_lz4");
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                    if (to.toString().endsWith(".parquet")) {
                        DeltaUnreadableTest.recodec(to, CompressionCodec.LZ4);
                    }
                }
            }
        }
        assertThat(codecsIn(root)).containsExactly(CompressionCodec.LZ4);
        return root;
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "hadoop"})
    void rawLz4BlocksUnderTheDeprecatedLz4CodecReadToo(String engine) throws Exception {
        Path root = rawBlocksUnderLz4();
        DeltaSourcePlugin p = plugin(root, engine);
        DeltaSourcePlugin plain = plugin("uncompressed", engine);
        assertThat(p.health()).isEqualTo("UP (engine: " + engine + ")");
        for (int i = 1; i <= 40; i++) {
            EntityRef ref = EntityRef.of("trade", String.format("T-%03d", i));
            assertThat(p.fetch(ref, AsOf.of(D30)).orElseThrow().data()).isEqualTo(plain.fetch(ref, AsOf.of(D30)).orElseThrow().data());
        }
        assertThat(p.columns("trade", COLS, AsOf.of(D29)).orElseThrow().ids()).hasSize(40);
        p.close();
        plain.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "hadoop"})
    void everyValueOfLz4AndLz4RawTablesEqualsTheUncompressedCopy(String engine) throws Exception {
        DeltaSourcePlugin plain = plugin("uncompressed", engine);
        for (String fixture : new String[] {"lz4", "lz4_raw", "arrow_lz4"}) {
            DeltaSourcePlugin p = plugin(fixture, engine);
            assertThat(p.health()).as(fixture + " health").isEqualTo("UP (engine: " + engine + ")");
            assertThat(p.search("trade", "", 100)).as(fixture + " lists every id").hasSize(40);
            assertThat(p.listingProblem("trade")).isEmpty();
            for (LocalDate d : new LocalDate[] {D29, D30}) {
                for (int i = 1; i <= 40; i++) {
                    EntityRef ref = EntityRef.of("trade", String.format("T-%03d", i));
                    assertThat(p.fetch(ref, AsOf.of(d)).orElseThrow().data()).as(fixture + " " + ref + " " + d)
                            .isEqualTo(plain.fetch(ref, AsOf.of(d)).orElseThrow().data());
                }
                ColumnSet got = p.columns("trade", COLS, AsOf.of(d)).orElseThrow();
                ColumnSet want = plain.columns("trade", COLS, AsOf.of(d)).orElseThrow();
                assertThat(got.ids()).hasSize(40).isEqualTo(want.ids());
                want.numbers().forEach((k, v) -> assertThat(got.numbers().get(k)).as(fixture + " " + k).isEqualTo(v));
                want.texts().forEach((k, v) -> assertThat(got.texts().get(k)).as(fixture + " " + k).isEqualTo(v));
            }
            p.close();
        }
        plain.close();
    }
}
