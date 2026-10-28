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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.lakeio.LocalStorage;
import com.ash.drishti.lakeio.ParquetInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loader at the local limit (10,000 positions, 510 scenarios each, in eight gzip files: about 60 MB of JSON Lines): the measured
 * throughput of each stage, printed for docs/architecture/PARALLEL_LOADER.md; the vector column written BYTE_STREAM_SPLIT and ZSTD; the
 * load manifest's key ranges; and the same load under a 24 MB memory budget, which spills, gives the identical table.
 */
class ThroughputTest {

    static final int POSITIONS = 10_000;
    static final int SCENARIOS = 510;

    @TempDir
    Path tmp;

    @Test
    void tenThousandPositionsMeasuredAndATinyBudgetGivesTheSameTable() throws Exception {
        List<String> lines = PvData.lines(POSITIONS, SCENARIOS, 1, 42);
        int per = POSITIONS / 8;
        for (int i = 0; i < 8; i++) {
            PvData.gzip(tmp.resolve("in/pv-" + i + ".jsonl.gz"), lines.subList(i * per, (i + 1) * per), 1);
        }
        long plainBytes = lines.stream().mapToLong(l -> l.length() + 1).sum();
        String files = tmp.resolve("in").toString() + "/*.jsonl.gz";
        // three loads in one JVM: the first is cold (the JIT compiles as it goes), the last is what a long load runs at
        Path manifest = tmp.resolve("out/manifest.json");
        Path lake = null;
        JsonNode s = null;
        for (int run = 0; run < 3; run++) {
            lake = tmp.resolve("lake-" + run);
            s = LakeLoadTest.load(tmp, PvData.config(files, lake.toString(), "delta"), "target.layout.file-size-mb=8", "report.manifest=" + manifest);
            assertThat(s.path("exit").asInt()).as(s.path("error").asText()).isZero();
            assertThat(s.path("accepted").asLong()).isEqualTo(POSITIONS);
            double seconds = s.path("timings").path("totalSeconds").asDouble();
            System.out.printf("lakeload throughput (run %d, %s): %,d positions x %d scenarios, %,.1f MB JSON (%,.1f MB gzip) in %.3f s: %,.0f rows/s, "
                    + "%,.1f MB/s of JSON; %d cores%n", run + 1, run == 0 ? "cold" : "warm", POSITIONS, SCENARIOS, plainBytes / 1048576.0,
                    s.path("files").findValues("bytes").stream().mapToLong(JsonNode::asLong).sum() / 1048576.0, seconds, POSITIONS / seconds,
                    plainBytes / 1048576.0 / seconds, s.path("throughput").path("cores").asInt());
        }
        System.out.println("lakeload stages (warm): " + s.path("throughput").toPrettyString());
        System.out.println("lakeload memory: " + s.path("memory"));
        JsonNode ice = LakeLoadTest.load(tmp, PvData.config(files, tmp.resolve("lake-iceberg").toString(), "iceberg"), "target.layout.file-size-mb=8");
        assertThat(ice.path("exit").asInt()).as(ice.path("error").asText()).isZero();
        System.out.printf("lakeload throughput (iceberg, warm): %.3f s, %,.0f rows/s%n", ice.path("timings").path("totalSeconds").asDouble(),
                POSITIONS / ice.path("timings").path("totalSeconds").asDouble());

        // the vector column: BYTE_STREAM_SPLIT, ZSTD
        Path any;
        try (Stream<Path> f = Files.walk(lake.resolve("risk/scenario-pv"))) {
            any = f.filter(p -> p.toString().endsWith(".parquet")).findFirst().orElseThrow();
        }
        try (ParquetFileReader r = ParquetFileReader.open(ParquetInput.of(new LocalStorage(), any.toString(), -1))) {
            ColumnChunkMetaData vec = r.getFooter().getBlocks().get(0).getColumns().stream()
                    .filter(c -> c.getPath().toDotString().startsWith("SCENARIO_PV")).findFirst().orElseThrow();
            assertThat(vec.getEncodings()).contains(Encoding.BYTE_STREAM_SPLIT);
            assertThat(vec.getCodec().name()).isEqualTo("ZSTD");
        }

        // the load manifest: every file with its rows and its first and last legal entity and desk, in key order
        JsonNode m = LakeLoadTest.JSON.readTree(manifest.toFile());
        assertThat(m.path("groupBy")).hasToString("[\"LEGAL_ENTITY\",\"DESK\"]");
        JsonNode fs = m.path("dates").path("2026-10-27").path("files");
        assertThat(fs.size()).isGreaterThan(1);
        long rows = 0;
        String prev = "";
        for (JsonNode f : fs) {
            rows += f.path("rows").asLong();
            String first = f.path("first").path("LEGAL_ENTITY").asText() + "|" + f.path("first").path("DESK").asText() + "|"
                    + f.path("first").path("id").asText();
            assertThat(first.compareTo(prev)).isGreaterThan(0);
            prev = f.path("last").path("LEGAL_ENTITY").asText() + "|" + f.path("last").path("DESK").asText() + "|" + f.path("last").path("id").asText();
        }
        assertThat(rows).isEqualTo(POSITIONS);

        // a 24 MB budget: the rows spill as sorted runs and are merged back; the table is the same
        Path small = tmp.resolve("lake-small");
        JsonNode t = LakeLoadTest.load(tmp, PvData.config(files, small.toString(), "delta"), "target.layout.file-size-mb=8", "memory.budget-mb=24");
        assertThat(t.path("exit").asInt()).as(t.path("error").asText()).isZero();
        assertThat(t.path("memory").path("spilledMb").asLong()).isPositive();
        System.out.println("lakeload 24 MB budget: " + t.path("memory") + " in " + t.path("timings").path("totalSeconds").asDouble() + " s");
        Map<String, Map<String, Object>> a = TableDump.delta(lake.resolve("risk/scenario-pv").toString(), Map.of());
        Map<String, Map<String, Object>> b = TableDump.delta(small.resolve("risk/scenario-pv").toString(), Map.of());
        assertThat(b).hasSize(POSITIONS).isEqualTo(a);

        // and in a JVM of 192 MB heap with a 32 MB budget: memory is bounded by the budget, not by the input
        Path summary = tmp.resolve("bounded.json");
        Path cfg = tmp.resolve("bounded.yaml");
        Files.writeString(cfg, PvData.config(files, tmp.resolve("lake-bounded").toString(), "delta"));
        Files.createDirectories(tmp.resolve("jvmtmp"));
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m",
                "-Djava.io.tmpdir=" + tmp.resolve("jvmtmp"), "-cp", System.getProperty("java.class.path"), LakeLoad.class.getName(), "--config",
                cfg.toString(), "--set", "memory.budget-mb=32", "--set", "target.layout.file-size-mb=8", "--summary", summary.toString(), "-q")
                .redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(said).isZero();
        JsonNode bounded = LakeLoadTest.JSON.readTree(summary.toFile());
        System.out.println("lakeload -Xmx192m, 32 MB budget: " + bounded.path("memory") + " in " + bounded.path("timings").path("totalSeconds") + " s");
        assertThat(TableDump.delta(tmp.resolve("lake-bounded/risk/scenario-pv").toString(), Map.of())).isEqualTo(a);
    }
}
