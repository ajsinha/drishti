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

import com.ash.drishti.common.FileDates;
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
                    // the Java loader reads feeds only (DATA_GUIDE.md section 2.8): a tar must be <table>/<YYYY-MM-DD>/, a zip one table
                    // with its files loose; the shared rule's other layouts (load_rows.py's, Python only) are refused here
                    String file = c.path("file").asText();
                    JsonNode want0 = c.get("expect");
                    boolean folders = want0 != null && want0.get("datasets").size() > 0 && !want0.get("datasets").has(
                            c.hasNonNull("dataset") ? c.get("dataset").asText() : Archives.stem(file));
                    if (!c.has("refused") && (FileDates.isTar(file) || folders)) {
                        assertThatThrownBy(() -> plan(io, c)).isInstanceOf(Archives.ArchiveException.class);
                        return;
                    }
                    if (c.has("refused")) {
                        assertThatThrownBy(() -> rows(io, plan(io, c), limits(c))).isInstanceOf(Archives.ArchiveException.class)
                                .satisfies(e -> assertThat(e.getMessage()).containsAnyOf(c.get("refused").asText(), "refused, nothing was loaded",
                                        "a .zip is one table and one business date"));
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

    /** A feed tar: {@code <table>/<YYYY-MM-DD>/<file>} members. */
    private Path feed(String name, Map<String, String> members) throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        members.forEach((k, v) -> m.put(k, v.getBytes(StandardCharsets.UTF_8)));
        Path p = tmp.resolve(name);
        Files.write(p, FileDatesTest.tar(m));
        return p;
    }

    @Test
    void aTarsFoldersAreItsTablesEachDateFolderItsDate() throws Exception {
        Path lake = tmp.resolve("lake");
        Path tar = feed("bundle.tar", Map.of("trade/2026-10-01/t1.jsonl", "{\"id\":\"T1\"}\n{\"id\":\"T2\"}\n",
                "trade/2026-10-02/t2.jsonl", "{\"id\":\"T3\"}\n{\"id\":\"T4\"}\n", "position/2026-10-01/p.jsonl", "{\"id\":\"P1\"}\n{\"id\":\"P2\"}\n{\"id\":\"P3\"}\n"));
        JsonNode s = LakeLoadTest.load(tmp, config(lake, tar.toString(), "datasets: [trade, position]\n"));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText()).isZero();
        assertThat(DatasetLoads.rows(s)).isEqualTo(new LinkedHashMap<>(Map.of("position", 3L, "trade", 4L)));
        assertThat(rowsOf(lake.resolve("fixture/trade"))).isEqualTo(4);
        assertThat(rowsOf(lake.resolve("fixture/position"))).isEqualTo(3);
        assertThat(s.path("datasets").path("trade").path("rows").toString()).contains("2026-10-01").contains("2026-10-02");
        assertThat(s.path("datasets").path("trade").path("files")).hasSize(2);
        JsonNode one = LakeLoadTest.load(tmp, config(tmp.resolve("lake-one"), tar.toString(), "  kind: trade\n"), "source.dataset=trade");
        assertThat(one.path("exit").asInt()).as(one.path("said").asText()).isZero();
        assertThat(one.path("accepted").asLong()).as("the one table this run is for").isEqualTo(4);
    }

    @Test
    void aFolderlessArchiveIsTheOneTable() throws Exception {
        Path lake = tmp.resolve("lake");
        for (String f : List.of("trade.zip")) {
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
        Files.copy(FIX.resolve("trade.zip"), in.resolve("a.zip"));
        Files.copy(FIX.resolve("trade.zip"), in.resolve("b.zip"));
        Path lake = tmp.resolve("lake");
        JsonNode s = LakeLoadTest.load(tmp, config(lake, in + "/*", "  kind: trade\n"));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText()).isZero();
        assertThat(s.path("documents").asLong()).isEqualTo(12);
        assertThat(s.path("duplicates").toString()).contains(FileDates.businessToday().toString());   // the same ids twice: the last read kept
    }

    @Test
    void refusalsCommitNothing() throws Exception {
        Path lake = tmp.resolve("lake");
        JsonNode unknown = LakeLoadTest.load(tmp, config(lake, FIX.resolve("unknown.tar.gz").toString(), "datasets: [trade, position]\n"));
        assertThat(unknown.path("exit").asInt()).isEqualTo(LakeLoad.FAILED);
        assertThat(unknown.path("said").asText()).contains("refused, nothing was loaded");
        JsonNode single = LakeLoadTest.load(tmp, config(lake, FIX.resolve("bundle.zip").toString(), "  kind: trade\n"));
        assertThat(single.path("said").asText()).contains("a .zip is one table and one business date");
        JsonNode mixed = LakeLoadTest.load(tmp, config(lake, FIX.resolve("mixed.tar.gz").toString(), "  kind: trade\n"));
        assertThat(mixed.path("said").asText()).contains("at the top; every file is <table>/<YYYY-MM-DD>/<file>.jsonl");
        Path other = feed("other.tar", Map.of("mystery/2026-10-01/m.jsonl", "{\"id\":\"M\"}\n"));
        JsonNode unconfigured = LakeLoadTest.load(tmp, config(lake, other.toString(), "datasets: [trade, position]\n"));
        assertThat(unconfigured.path("said").asText()).contains("folder mystery matches no configured dataset");
        java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(z)) {
            gz.write(new byte[16 << 20]);                                   // 16 MB of zeros in a few kB: a gzip bomb in a feed tar
        }
        Path bombTar = tmp.resolve("gzbomb.tar");
        Files.write(bombTar, FileDatesTest.tar(Map.of("trade/2026-10-01/big.jsonl.gz", z.toByteArray())));
        JsonNode bomb = LakeLoadTest.load(tmp, config(lake, bombTar.toString(), "  kind: trade\n"),
                "source.archive.max-ratio=2");
        assertThat(bomb.path("exit").asInt()).isEqualTo(LakeLoad.FAILED);
        assertThat(bomb.path("error").asText()).contains("zip bomb");
        assertThat(lake.resolve("fixture")).doesNotExist();                     // no table: at most the run log of the failed runs
    }

    @Test
    void everyDatasetIsCheckedBeforeTheFirstCommit() throws Exception {
        Path bad = feed("bad.tar", new LinkedHashMap<>(Map.of("alpha/2026-10-01/a.jsonl", "{\"id\":\"A-1\"}\n",
                "beta/2026-10-01/b.jsonl", "{\"id\":\"B-1\"}\nnot json\n")));
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
