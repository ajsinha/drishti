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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.io.Relocation;
import com.ash.drishti.lakeio.S3Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The archive rule (DATA_GUIDE.md section 2.7), Java side: every case of the shared fixtures ({@code tools/lake/testdata/archives},
 * the same cases {@code tools/lake/test_archives.py} runs), and the loader end to end: an archive's folders loaded as datasets into
 * their tables (each checked before the first commit), a folderless archive into the one table, and the refusals committing nothing.
 */
class ArchivesTest {

    static final Path FIX = PythonPathEqualityTest.REPO.resolve("tools/lake/testdata/archives");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    static FileAccess io() {
        return new FileAccess(S3Settings.from(Map.of()), Relocation.none(), null);
    }

    static Archives.Limits limits(JsonNode c) {
        Archives.Limits d = Archives.Limits.defaults(k -> null);
        JsonNode l = c.path("limits");
        return new Archives.Limits(l.has("max_mb") ? (long) (l.get("max_mb").asDouble() * 1048576) : d.maxBytes(),
                l.has("max_ratio") ? l.get("max_ratio").asDouble() : d.maxRatio(), l.has("max_members") ? l.get("max_members").asInt() : d.maxMembers());
    }

    /** The plan of a case, its datasets' rows counted as the loader reads them (non-blank lines). */
    static Map<String, Long> rows(FileAccess io, Archives.Plan p, Archives.Limits lim) throws Exception {
        Map<String, Long> out = new TreeMap<>();
        p.datasets().keySet().forEach(d -> out.put(d, 0L));
        Archives.read(io, p, p.members(), lim, new Meter("t", 1), 1 << 16, (m, in) -> {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            long n = 0;
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                n += line.isBlank() ? 0 : 1;
            }
            out.merge(m.dataset(), n, Long::sum);
        });
        return out;
    }

    static Archives.Plan plan(FileAccess io, JsonNode c) throws Exception {
        Path f = FIX.resolve(c.path("file").asText());
        Archives.Plan p = Archives.plan(io, f.toString(), Files.size(f), c.path("dataset").asText(null), limits(c));
        if (c.has("configured")) {
            List<String> conf = new ArrayList<>();
            c.get("configured").forEach(x -> conf.add(x.asText()));
            p.require(conf);
        }
        return p;
    }

    @TestFactory
    Stream<DynamicTest> theSharedCases() throws Exception {
        JsonNode doc = JSON.readTree(FIX.resolve("expected.json").toFile());
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : doc.get("cases")) {
            out.add(DynamicTest.dynamicTest(c.path("case").asText(), () -> {
                try (FileAccess io = io()) {
                    if (c.has("refused")) {
                        assertThatThrownBy(() -> rows(io, plan(io, c), limits(c))).isInstanceOf(Archives.ArchiveException.class)
                                .hasMessageContaining(c.get("refused").asText());
                        return;
                    }
                    Archives.Plan p = plan(io, c);
                    JsonNode want = c.get("expect");
                    Map<String, Long> wantRows = new TreeMap<>();
                    want.get("datasets").fields().forEachRemaining(e -> {
                        wantRows.put(e.getKey(), e.getValue().get("rows").asLong());
                        List<String> members = new ArrayList<>();
                        e.getValue().get("members").forEach(m -> members.add(m.asText()));
                        assertThat(p.datasets().get(e.getKey())).extracting(Archives.Member::name).containsExactlyElementsOf(members);
                    });
                    assertThat(p.datasets().keySet()).containsExactlyInAnyOrderElementsOf(wantRows.keySet());
                    assertThat(rows(io, p, limits(c))).isEqualTo(wantRows);
                    assertThat(p.notes()).as(p.notes().toString()).hasSize(want.get("notes").asInt());
                }
            }));
        }
        return out.stream();
    }

    @Test
    void theDefaultsAreTheSharedOnes() throws Exception {
        JsonNode d = JSON.readTree(FIX.resolve("expected.json").toFile()).get("defaults");
        assertThat(Archives.DEFAULT_MAX_MB).isEqualTo(d.get("max_mb").asLong());
        assertThat(Archives.DEFAULT_MAX_RATIO).isEqualTo(d.get("max_ratio").asDouble());
        assertThat(Archives.DEFAULT_MAX_MEMBERS).isEqualTo(d.get("max_members").asInt());
        assertThat(Archives.FLOOR).isEqualTo(d.get("floor_bytes").asLong());
        Archives.Limits env = Archives.Limits.defaults(Map.of("DRISHTI_ARCHIVE_MAX_MB", "5", "DRISHTI_ARCHIVE_MAX_RATIO", "7",
                "DRISHTI_ARCHIVE_MAX_MEMBERS", "9")::get);
        assertThat(env).isEqualTo(new Archives.Limits(5L << 20, 7, 9));
        assertThat(Inputs.SUFFIXES).contains(".zip", ".tgz", ".tar", ".gz", ".zst");
    }

    private String config(Path lake, String files, String extra) {
        return "source:\n  files: '" + files + "'\n  id: id\n  date: date\n" + "target:\n  format: delta\n  lake: '" + lake + "'\n  domain: fixture\n"
                + extra + "parallel: {readers: 2, parsers: 2, writers: 2}\nmemory: {budget-mb: 64}\n";
    }

    private static long rowsOf(Path table) throws Exception {
        return TableDump.delta(table.toString(), Map.of()).size();
    }

    @Test
    void foldersAreDatasetsEachItsOwnTable() throws Exception {
        Path lake = tmp.resolve("lake");
        JsonNode s = LakeLoadTest.load(tmp, config(lake, FIX.resolve("bundle.tar.gz").toString(), "datasets: [trade, position]\n"));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText()).isZero();
        assertThat(DatasetLoads.rows(s)).isEqualTo(new LinkedHashMap<>(Map.of("position", 3L, "trade", 4L)));
        assertThat(rowsOf(lake.resolve("fixture/trade"))).isEqualTo(4);
        assertThat(rowsOf(lake.resolve("fixture/position"))).isEqualTo(3);
        assertThat(s.path("said").asText()).contains("datasets: 2").containsPattern("trade +4 rows {2}2 files {2}versions 0 to 1")
                .containsPattern("position +3 rows {2}1 file {2}version 0");
        assertThat(s.path("datasets").path("trade").path("files")).hasSize(2);
    }

    @Test
    void aFolderlessArchiveIsTheOneTable() throws Exception {
        Path lake = tmp.resolve("lake");
        for (String f : List.of("trade.zip", "trade.tgz", "trade.tar")) {
            JsonNode s = LakeLoadTest.load(tmp, config(lake, FIX.resolve(f).toString(), "  kind: trade\n"));
            assertThat(s.path("exit").asInt()).as(f + s.path("said").asText()).isZero();
            assertThat(s.path("accepted").asLong()).isEqualTo(6);
            assertThat(s.path("files").get(0).path("file").asText()).endsWith(f + "!/a.jsonl");
        }
        assertThat(rowsOf(lake.resolve("fixture/trade"))).isEqualTo(6);
        // the archive's name is no table name: the configured table is loaded whatever the archive is called
        JsonNode s = LakeLoadTest.load(tmp, config(lake, FIX.resolve("book.zip").toString(), "  kind: trade\n"));
        assertThat(s.path("exit").asInt()).isZero();
        // with datasets:, its name (or --dataset) says which
        JsonNode named = LakeLoadTest.load(tmp, config(tmp.resolve("lake2"), FIX.resolve("book.zip").toString(), "datasets: [trade]\n"),
                "source.dataset=trade");
        assertThat(named.path("exit").asInt()).as(named.path("said").asText()).isZero();
        JsonNode unnamed = LakeLoadTest.load(tmp, config(tmp.resolve("lake3"), FIX.resolve("book.zip").toString(), "datasets: [trade]\n"));
        assertThat(unnamed.path("exit").asInt()).isEqualTo(LakeLoad.FAILED);
        assertThat(unnamed.path("said").asText()).contains("dataset book matches no configured dataset (trade)");
        assertThat(tmp.resolve("lake3")).doesNotExist();
    }

    @Test
    void aGlobOfArchivesAndTheirFoldersInOneTable() throws Exception {
        Path in = tmp.resolve("in");
        Files.createDirectories(in);
        Files.copy(FIX.resolve("trade.tgz"), in.resolve("a.tgz"));
        Files.copy(FIX.resolve("trade.zip"), in.resolve("b.zip"));
        Path lake = tmp.resolve("lake");
        JsonNode s = LakeLoadTest.load(tmp, config(lake, in + "/*", "  kind: trade\n"));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText()).isZero();
        assertThat(s.path("documents").asLong()).isEqualTo(12);
        assertThat(s.path("duplicates").toString()).contains("2026-10-01");      // the same ids twice: the last read kept, as always
    }

    @Test
    void refusalsCommitNothing() throws Exception {
        Path lake = tmp.resolve("lake");
        JsonNode unknown = LakeLoadTest.load(tmp, config(lake, FIX.resolve("unknown.tar.gz").toString(), "datasets: [trade, position]\n"));
        assertThat(unknown.path("exit").asInt()).isEqualTo(LakeLoad.FAILED);
        assertThat(unknown.path("said").asText()).contains("folders mystery, other match no configured dataset (position, trade)");
        JsonNode single = LakeLoadTest.load(tmp, config(lake, FIX.resolve("bundle.zip").toString(), "  kind: trade\n"));
        assertThat(single.path("said").asText()).contains("folder position matches no configured dataset (trade)");
        JsonNode mixed = LakeLoadTest.load(tmp, config(lake, FIX.resolve("mixed.tar.gz").toString(), "  kind: trade\n"));
        assertThat(mixed.path("said").asText()).contains("mixed archive is refused");
        JsonNode bomb = LakeLoadTest.load(tmp, config(lake, FIX.resolve("gzbomb.tar").toString(), "  kind: trade\n"),
                "source.archive.max-ratio=2");
        assertThat(bomb.path("exit").asInt()).isEqualTo(LakeLoad.FAILED);
        assertThat(bomb.path("error").asText()).contains("zip bomb");
        assertThat(lake.resolve("fixture")).doesNotExist();                     // no table: at most the run log of the failed runs
    }

    @Test
    void everyDatasetIsCheckedBeforeTheFirstCommit() throws Exception {
        Path bad = tmp.resolve("bad.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(bad))) {
            z.putNextEntry(new java.util.zip.ZipEntry("alpha/a.jsonl"));
            z.write("{\"id\":\"A-1\",\"date\":\"2026-10-01\"}\n".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new java.util.zip.ZipEntry("beta/b.jsonl"));
            z.write("{\"id\":\"B-1\",\"date\":\"2026-10-01\"}\nnot json\n".getBytes(StandardCharsets.UTF_8));
        }
        Path lake = tmp.resolve("lake");
        String cfg = config(lake, bad.toString(), "datasets: [alpha, beta]\nrejects: {max-pct: 0}\n");
        JsonNode s = LakeLoadTest.load(tmp, cfg);
        assertThat(s.path("exit").asInt()).isEqualTo(LakeLoad.FAILED);
        assertThat(s.path("said").asText()).contains("dataset beta failed its check").contains("no dataset was loaded");
        assertThat(lake.resolve("fixture/alpha")).doesNotExist();                // alpha comes first, and still was not committed
        JsonNode structure = LakeLoadTest.load(tmp, cfg, "source.archive.validate=structure");
        assertThat(structure.path("exit").asInt()).isEqualTo(LakeLoad.FAILED);
        assertThat(lake.resolve("fixture/alpha/_delta_log")).exists();          // only the structure checked: alpha loaded before beta failed
    }
}
