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
package com.ash.drishti.memlake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.common.RowRetention;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.ash.drishti.lakeload.LakeLoad;
import com.ash.drishti.lakeload.LakeRetention;
import com.ash.drishti.lakeload.TableDump;
import com.ash.drishti.memlake.store.MemoryStores;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage parity of the row retention (docs/guides/DATA_LAKE_GUIDE.md, "Row retention"): the same ten rows and the same
 * {@code retention:} in the memory lake, a Delta table, an Iceberg table and a files store leave the same rows, and delta-rs
 * (the {@code deltalake} package) reads the Delta result.
 */
class RetentionParityTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 13);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 14);

    @TempDir
    Path dir;
    private MemoryLake memory;

    @AfterEach
    void stop() {
        if (memory != null) {
            memory.close();
        }
        MemoryStores.remove("rp");
    }

    /** Ten trades, T0 the newest and T9 nine hours old, alternating between two business dates. */
    private static List<String[]> trades() {
        Instant now = Instant.now();
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            out.add(new String[] {"T" + i, (i % 2 == 0 ? D1 : D2).toString(), now.minusSeconds(3_600L * i).toString()});
        }
        return out;
    }

    private static String doc(String[] t) {
        return "{\"TRADE_ID\":\"" + t[0] + "\",\"BOOK\":\"B\",\"UPDATED_AT\":\"" + t[2] + "\"}";
    }

    private static TreeSet<String> ids(Map<String, Map<String, Object>> dump) {
        TreeSet<String> out = new TreeSet<>();
        dump.keySet().forEach(k -> out.add(k.substring(k.indexOf('|') + 1)));
        return out;
    }

    private TreeSet<String> memory(RowRetention r) throws Exception {
        Files.writeString(dir.resolve("mem.jsonl"), String.join("\n", trades().stream().map(RetentionParityTest::doc).toList()) + "\n");
        MemoryLakeSettings.TableSpec t = new MemoryLakeSettings.TableSpec("trades", "rp", null,
                new MemoryLakeSettings.Source(null, null, dir.resolve("mem.jsonl").toString(), null, null, null, null), "merge",
                List.of("TRADE_ID"), null, "2026-11-14", List.of("BOOK"), null, null, false, null, false, 2, null, null, r);
        MemoryLakeSettings p = new MemoryLakeSettings(Map.of("rp", new MemoryLakeSettings.Store(64, 64, "RP", true, null, null)), List.of(t),
                new MemoryLakeSettings.Compaction(false, false, null, 0, 1, null, 3, null), null, 1);
        memory = new MemoryLake(p, new ReplenishSource(null), null, () -> true, null);
        assertThat(memory.runNow("trades", "test").outcome()).isEqualTo("ok");
        assertThat(memory.compactor().rewrite(memory.table("trades"), "retention")).isTrue();
        TreeSet<String> out = new TreeSet<>();
        TableUpkeep.rows(LakeWriter.engineFor("mem://rp/trades", Map.of()), "mem://rp/trades", pv -> true).forEach(s -> out.add(s.id()));
        return out;
    }

    /** The trades loaded into a lake of {@code format} (a file a business date, as the loader reads them), then the retention applied. */
    private TreeSet<String> lake(String format, RowRetention r) throws Exception {
        Path in = dir.resolve("in-" + format);
        Files.createDirectories(in);
        for (LocalDate d : List.of(D1, D2)) {
            Files.write(in.resolve("trades." + d + ".jsonl"), trades().stream().filter(t -> t[1].equals(d.toString())).map(RetentionParityTest::doc).toList(),
                    StandardCharsets.UTF_8);
        }
        Path lake = dir.resolve("lake-" + format);
        String yaml = "source:\n  files: '" + in + "'\n  id: TRADE_ID\n  promote: [BOOK]\ntarget:\n  format: " + format + "\n  lake: '" + lake
                + "'\n  domain: risk\n  kind: trades\n";
        Path cfg = dir.resolve("load-" + format + ".yaml");
        Files.writeString(cfg, yaml);
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(said, true, StandardCharsets.UTF_8)))
                .as(said.toString(StandardCharsets.UTF_8)).isZero();
        Map<String, Object> done = LakeRetention.apply(lake.toString(), format, "risk/trades", r, Instant.now(), false);
        assertThat(done.get("dropped")).isNotEqualTo(0L);
        return ids(format.equals("delta") ? TableDump.delta(lake.resolve("risk/trades").toString(), Map.of())
                : TableDump.iceberg(lake.toString(), "risk", "trades", Map.of()));
    }

    /** A files store ({@code <root>/<domain>/<date>/<kind>.jsonl}, the loaders' rows), then the retention applied. */
    private TreeSet<String> files(RowRetention r) throws Exception {
        Path root = dir.resolve("files");
        for (LocalDate d : List.of(D1, D2)) {
            Path day = Files.createDirectories(root.resolve("risk").resolve(d.toString()));
            List<String> lines = new ArrayList<>();
            for (String[] t : trades()) {
                if (t[1].equals(d.toString())) {
                    lines.add("{\"kind\":\"trades\",\"id\":\"" + t[0] + "\",\"date\":\"" + d + "\",\"doc\":" + quote(doc(t)) + ",\"columns\":{\"BOOK\":\"B\"}}");
                }
            }
            Files.write(day.resolve("trades.jsonl"), lines, StandardCharsets.UTF_8);
        }
        LakeRetention.apply(root.toString(), "files", "risk/trades", r, Instant.now(), false);
        TreeSet<String> out = new TreeSet<>();
        for (LocalDate d : List.of(D1, D2)) {
            Path f = root.resolve("risk").resolve(d.toString()).resolve("trades.jsonl");
            if (Files.exists(f)) {
                Files.readAllLines(f).forEach(l -> out.add(l.replaceAll("^\\{\"kind\":\"trades\",\"id\":\"([^\"]+)\".*", "$1")));
            }
        }
        return out;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void parity(RowRetention r, String... expected) throws Exception {
        TreeSet<String> mem = memory(r);
        assertThat(mem).as("memory").containsExactly(expected);
        assertThat(lake("delta", r)).as("delta").isEqualTo(mem);
        assertThat(lake("iceberg", r)).as("iceberg").isEqualTo(mem);
        assertThat(files(r)).as("files").isEqualTo(mem);
        deltaRsReads(dir.resolve("lake-delta/risk/trades"), expected);
    }

    @Test
    void ttlByAFieldLeavesTheSameRowsInEveryStore() throws Exception {
        parity(new RowRetention("330m", "UPDATED_AT", null), "T0", "T1", "T2", "T3", "T4", "T5");
    }

    @Test
    void ttlAndTheRowCapLeaveTheSameRowsInEveryStore() throws Exception {
        parity(new RowRetention("8h", "UPDATED_AT", 5), "T0", "T1", "T2", "T3", "T4");
    }

    /** delta-rs reads what the retention committed: the same rows (skipped when no Python with the deltalake package is at hand). */
    private static void deltaRsReads(Path table, String... expected) throws Exception {
        Path py = null;
        for (Path d = Path.of("").toAbsolutePath(); d != null && py == null; d = d.getParent()) {
            Path c = d.resolve("drishti-console/.venv/bin/python");
            py = Files.isExecutable(c) ? c : null;
        }
        assumeTrue(py != null, "no Python environment with delta-rs (drishti-console/.venv)");
        Path out = Files.createTempFile(table.getParent(), "deltars", ".out");
        Process p = new ProcessBuilder(py.toString(), "-c",
                "import os, sys\nfrom deltalake import DeltaTable\nt = DeltaTable(sys.argv[1]).to_pyarrow_table(columns=['id'])\n"
                        + "print(','.join(sorted(t.column('id').to_pylist())))\nsys.stdout.flush()\nos._exit(0)", table.toString())
                .redirectErrorStream(true).redirectOutput(out.toFile()).start();      // os._exit: delta-rs's runtime can hold the interpreter open at exit
        if (!p.waitFor(90, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        String text = Files.readString(out, StandardCharsets.UTF_8).trim();
        assertThat(text.lines().reduce((x, y) -> y).orElse("")).as("delta-rs read: " + text).isEqualTo(String.join(",", expected));
    }
}
