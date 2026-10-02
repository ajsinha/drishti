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
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.parquet.format.CompressionCodec;
import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.Util;
import org.junit.jupiter.api.Test;

/**
 * DATA-01, DATA-13, DATA-18: a Delta table that cannot be read says so. Parquet pages in a codec the engine does not
 * decompress (the fixture's footers are rewritten to say LZ4), a truncated Parquet file and a log missing its first
 * commit each make reads fail (not "not held"), with the codec and what to do in the error when the reader can act on
 * it; health turns DEGRADED naming the table and date, and back to UP once the table reads again; type-ahead keeps the
 * ids it listed before a failed reindex and searches are told the listing is incomplete. Native engine (the default).
 */
class DeltaUnreadableTest {

    private static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);

    /** A fresh copy of the laid-out fixture lake (40 trades on each of two dates). */
    private static Path lake() throws Exception {
        Path root = Files.createTempDirectory("drishti-unreadable");
        Path src = Path.of("src/test/resources/lake-layout");
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        return root;
    }

    private static Path date(Path root, LocalDate d) {
        return root.resolve("desk/trade/business_date=" + d);
    }

    private static List<Path> parquet(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.toString().endsWith(".parquet")).sorted().toList();
        }
    }

    /** Rewrites a Parquet file's footer to say every column chunk is compressed with {@code codec} (pages unchanged). */
    static void recodec(Path f, CompressionCodec codec) throws Exception {
        byte[] all = Files.readAllBytes(f);
        int n = all.length;
        int len = ByteBuffer.wrap(all, n - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int start = n - 8 - len;
        FileMetaData md = Util.readFileMetaData(new ByteArrayInputStream(all, start, len));
        md.getRow_groups().forEach(rg -> rg.getColumns().forEach(c -> c.getMeta_data().setCodec(codec)));
        ByteArrayOutputStream footer = new ByteArrayOutputStream();
        Util.writeFileMetaData(md, footer);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(all, 0, start);
        footer.writeTo(out);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(footer.size()).array());
        out.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        Files.write(f, out.toByteArray());
    }

    private static DeltaSourcePlugin plugin(Path root) throws Exception {
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "domain", "desk", "source-name", "lake", "engine", "native",
                "layout.trade.columns", "mtm,book,nettingSet,counterparty.id")));
        return p;
    }

    @Test
    void lz4PagesFailTheReadWithTheCodecAndWhatToDoAndHealthNamesTheDate() throws Exception {
        Path root = lake();
        for (Path f : parquet(date(root, D30))) {
            recodec(f, CompressionCodec.LZ4);
        }
        DeltaSourcePlugin p = plugin(root);
        assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T-001"), AsOf.of(D30))).isInstanceOf(UnreadableData.class)
                .hasMessageContaining("trade 2026-09-30 cannot be read").hasMessageContaining("does not decompress LZ4")
                .hasMessageContaining("relayout --force");
        assertThatThrownBy(() -> p.columns("trade", List.of("mtm"), AsOf.of(D30))).isInstanceOf(UnreadableData.class);
        assertThat(p.fetch(EntityRef.of("trade", "T-001"), AsOf.of(D29))).isPresent();      // the other date reads
        assertThat(p.health()).startsWith("DEGRADED: cannot read trade 2026-09-30: ").contains("LZ4");
        // the newest date's ids could not be listed at start: type-ahead has none, and searches are told so
        assertThat(p.search("trade", "", 100)).isEmpty();
        assertThat(p.listingProblem("trade")).hasValueSatisfying(why -> assertThat(why).contains("LZ4").contains("it lists none"));
    }

    @Test
    void aFailedReindexKeepsThePreviousIdsAndHealthRecoversWhenTheTableReadsAgain() throws Exception {
        Path root = lake();
        DeltaSourcePlugin p = plugin(root);
        assertThat(p.search("trade", "", 100)).hasSize(40);
        assertThat(p.health()).startsWith("UP");
        Path broken = parquet(date(root, D30)).get(0);
        byte[] good = Files.readAllBytes(broken);
        Files.write(broken, java.util.Arrays.copyOf(good, good.length / 2));       // truncated in place
        p.purgeCaches();
        p.reindex();
        assertThat(p.search("trade", "", 100)).as("the ids listed before stay listed").hasSize(40);
        assertThat(p.listingProblem("trade")).hasValueSatisfying(why -> assertThat(why).contains("2026-09-30").contains("it lists those of"));
        assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T-001"))).as("a failure, not 'not held'").isNotInstanceOf(UnreadableData.class);
        assertThat(p.health()).startsWith("DEGRADED: cannot read trade 2026-09-30: ");
        Files.write(broken, good);                                                  // repaired
        p.purgeCaches();
        p.reindex();
        assertThat(p.fetch(EntityRef.of("trade", "T-001"))).isPresent();
        assertThat(p.listingProblem("trade")).isEmpty();
        assertThat(p.health()).isEqualTo("UP (engine: native)");
    }

    @Test
    void aLogMissingItsFirstCommitFailsReadsAndHealthSaysSo() throws Exception {
        Path root = lake();
        Files.delete(root.resolve("desk/trade/_delta_log/00000000000000000000.json"));
        DeltaSourcePlugin p = plugin(root);
        assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T-001"))).as("a failure, not 'not held'").isInstanceOf(RuntimeException.class);
        assertThat(p.health()).startsWith("DEGRADED: cannot read trade log: ");
    }

    @Test
    void lz4FromArrowUnderTheHadoopEngineSaysWhatToDo() {
        // parquet-java's LZ4 codec throws this for the LZ4 framing delta-rs and Arrow write
        RuntimeException e = new RuntimeException("reading failed", new LZ4Exception("Malformed input at 12"));
        RuntimeException thrown = TableProblems.classify("trade", "2026-09-30", e);
        assertThat(thrown).isInstanceOf(UnreadableData.class).hasMessageContaining("hadoop engine does not decompress")
                .hasMessageContaining("relayout --force");
        assertThat(TableProblems.classify("trade", "2026-09-30", new IllegalStateException("x"))).isNotInstanceOf(UnreadableData.class);
    }

    /** Stands in for lz4-java's exception (matched by its simple name, shaded or not). */
    static final class LZ4Exception extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LZ4Exception(String message) {
            super(message);
        }
    }
}
