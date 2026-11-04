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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The business date in a file's name (DATA_GUIDE.md section 2.8): the shared cases ({@code tools/lake/testdata/file-dates/names.json},
 * which the Python {@code test_filedate.py} reads too, so the two rules cannot drift), then the loader end to end on a small lake with the
 * same files and the same expected dates and counts as the Python test: plain, .gz, .zip (a member's own date first) and .tar.gz; the rows
 * that carried another date counted and named; {@code as-of} over the name; {@code date-from: field} ignoring it.
 */
class FileDatesTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path CASES = PythonPathEqualityTest.REPO.resolve("tools/lake/testdata/file-dates/names.json");

    @TempDir
    Path tmp;

    private static String iso(LocalDate d) {
        return d == null ? null : d.toString();
    }

    @Test
    void theSharedNames() throws IOException {
        JsonNode cases = JSON.readTree(CASES.toFile());
        assertThat(cases.path("names").size()).isGreaterThan(20);
        for (JsonNode c : cases.path("names")) {
            assertThat(iso(FileDates.of(c.path("name").asText()))).as(c.path("name").asText()).isEqualTo(c.path("date").asText(null));
        }
        for (JsonNode c : cases.path("entries")) {
            assertThat(iso(FileDates.of(c.path("archive").asText(), c.path("entry").asText()))).as(c.toString()).isEqualTo(c.path("date").asText(null));
        }
    }

    @Test
    void whyANameCarriesNoDate() {
        assertThat(FileDates.find("abc 20261340.jsonl").why()).contains("is not a calendar date");
        assertThat(FileDates.find("abc 20261103 v2.jsonl").why()).contains("not at the end");
        assertThat(FileDates.find("abc.jsonl").why()).contains("no YYYYMMDD");
        assertThat(FileDates.find("abc 20261103.jsonl").why()).isNull();
        assertThat(FileDates.otherDateNote(1, "a 20261103.jsonl", "TRADE_DATE", LocalDate.parse("2026-11-03")))
                .isEqualTo("1 row of a 20261103.jsonl carried another date in TRADE_DATE; loaded under 2026-11-03 from the file name");
    }

    // ------------------------------------------------------------------------------------------------ the loader end to end

    private static String deal(int i, String day) {
        return "{\"TRADE_ID\":" + (1000 + i) + (day == null ? "" : ",\"TRADE_DATE\":\"" + day + "\"") + ",\"DESK\":\"" + (i % 2 == 0 ? "RATES" : "FX")
                + "\",\"NOTIONAL\":" + (10.0 * i) + "}\n";
    }

    private static byte[] lines(int from, int to, java.util.function.IntFunction<String> day) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) {
            b.append(deal(i, day.apply(i)));
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The same drop folder as test_filedate.py's LakeBuildTest.files. */
    private Path drop() throws IOException {
        Path d = Files.createDirectories(tmp.resolve("drop"));
        Files.write(d.resolve("deals 20261103.jsonl"), lines(0, 10, i -> i >= 3 ? "2026-11-03" : "2026-11-02"));
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(d.resolve("deals_2026-11-04.jsonl.gz")))) {
            o.write(lines(10, 15, i -> "2026-11-04"));
        }
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(d.resolve("deals 20261105.zip")))) {
            z.putNextEntry(new ZipEntry("day 20261106.jsonl"));
            z.write(lines(20, 24, i -> "2026-11-01"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("rest.jsonl"));
            z.write(lines(30, 33, i -> "2026-11-05"));
            z.closeEntry();
        }
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(d.resolve("deals.20261107.tar.gz")))) {
            o.write(tar("part.jsonl", lines(40, 42, i -> "2026-11-07")));
        }
        Files.write(d.resolve("deals.jsonl"), lines(50, 52, i -> "2026-10-30"));
        return d;
    }

    /** A one-member ustar archive. */
    static byte[] tar(String name, byte[] data) throws IOException {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, "0000644\0");
        put(h, 108, "0000000\0");
        put(h, 116, "0000000\0");
        put(h, 124, String.format("%011o\0", data.length));
        put(h, 136, String.format("%011o\0", 1_700_000_000L));
        put(h, 148, "        ");
        h[156] = '0';
        put(h, 257, "ustar\0");
        put(h, 263, "00");
        int sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o\0 ", sum));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(h);
        out.write(data);
        out.write(new byte[(512 - data.length % 512) % 512]);
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] h, int at, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, at, b.length);
    }

    private JsonNode load(Path drop, String lake, String... extra) throws Exception {
        StringBuilder y = new StringBuilder("source:\n  files: '" + drop + "'\n  id: TRADE_ID\n  date: TRADE_DATE\n");
        for (String e : extra) {
            y.append("  ").append(e).append('\n');
        }
        y.append("target:\n  format: delta\n  lake: '").append(tmp.resolve(lake)).append("'\n  domain: trading\n  kind: deal\n");
        return LakeLoadTest.load(tmp, y.toString());
    }

    private static Map<String, Long> rows(JsonNode s) {
        Map<String, Long> out = new TreeMap<>();
        s.path("rows").fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asLong()));
        return out;
    }

    @Test
    void theNamesDateIsEveryRowsDateAndOtherDatesAreCounted() throws Exception {
        Path drop = drop();
        JsonNode s = load(drop, "lake");
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        assertThat(s.path("dateFrom").asText()).isEqualTo("auto");
        assertThat(rows(s)).isEqualTo(Map.of("2026-10-30", 2L, "2026-11-03", 10L, "2026-11-04", 5L, "2026-11-05", 3L, "2026-11-06", 4L, "2026-11-07", 2L));
        Map<String, String> byFile = new LinkedHashMap<>();
        s.path("fileDates").forEach(f -> byFile.put(f.path("file").asText(), f.path("date").asText() + "/" + f.path("otherDateRows").asLong()));
        assertThat(byFile).isEqualTo(Map.of("deals 20261103.jsonl", "2026-11-03/3", "deals_2026-11-04.jsonl.gz", "2026-11-04/0",
                "deals 20261105.zip!/day 20261106.jsonl", "2026-11-06/4", "deals 20261105.zip!/rest.jsonl", "2026-11-05/0",
                "deals.20261107.tar.gz!/part.jsonl", "2026-11-07/0"));
        assertThat(s.path("otherDateRows").asLong()).isEqualTo(7);
        assertThat(s.path("dateNotes").toString())
                .contains("3 rows of deals 20261103.jsonl carried another date in TRADE_DATE; loaded under 2026-11-03 from the file name")
                .contains("4 rows of deals 20261105.zip!/day 20261106.jsonl carried another date in TRADE_DATE; loaded under 2026-11-06 from the file name");
        Map<String, Map<String, Object>> table = TableDump.delta(tmp.resolve("lake/trading/deal").toString(), Map.of());
        assertThat(table).hasSize(26);
        assertThat(table.keySet()).anyMatch(k -> k.startsWith("2026-11-03") && k.contains("1000"));
    }

    @Test
    void theCommandLineSaysWhereEachDateCameFrom() throws Exception {
        Path drop = drop();
        Path cfg = tmp.resolve("load.yaml");
        Files.writeString(cfg, "source:\n  files: '" + drop + "'\n  id: TRADE_ID\n  date: TRADE_DATE\ntarget:\n  format: delta\n  lake: '"
                + tmp.resolve("lake-cli") + "'\n  domain: trading\n  kind: deal\n");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int code = LakeLoad.run(new String[] {"--config", cfg.toString()}, new java.io.PrintStream(said, true, StandardCharsets.UTF_8));
        assertThat(code).isZero();
        assertThat(said.toString(StandardCharsets.UTF_8)).contains("business date 2026-11-03 from the name of deals 20261103.jsonl")
                .contains("3 rows of deals 20261103.jsonl carried another date in TRADE_DATE; loaded under 2026-11-03 from the file name");
    }

    @Test
    void asOfWinsOverTheNameAndDateFromFieldIgnoresIt() throws Exception {
        Path drop = drop();
        JsonNode asOf = load(drop, "lake-asof", "as-of: 2026-12-01");
        assertThat(asOf.path("exit").asInt()).as(asOf.path("error").asText()).isZero();
        assertThat(rows(asOf)).isEqualTo(Map.of("2026-12-01", 26L));
        assertThat(asOf.path("fileDates")).isEmpty();
        assertThat(asOf.path("otherDateRows").asLong()).isZero();

        JsonNode field = load(drop, "lake-field", "date-from: field");
        assertThat(field.path("exit").asInt()).as(field.path("error").asText()).isZero();
        assertThat(rows(field)).isEqualTo(Map.of("2026-10-30", 2L, "2026-11-01", 4L, "2026-11-02", 3L, "2026-11-03", 7L, "2026-11-04", 5L,
                "2026-11-05", 3L, "2026-11-07", 2L));
        assertThat(field.path("fileDates")).isEmpty();
        assertThat(field.path("dateFrom").asText()).isEqualTo("field");
    }

    @Test
    void withoutADateFieldOnlyNamedFilesLoad() throws Exception {
        Path d = Files.createDirectories(tmp.resolve("nodate"));
        Files.write(d.resolve("deals 20261103.jsonl"), lines(0, 3, i -> null));
        Files.write(d.resolve("deals.jsonl"), lines(3, 5, i -> null));
        String y = "source:\n  files: '" + d + "'\n  id: TRADE_ID\n%starget:\n  format: delta\n  lake: '" + tmp.resolve("lake-nodate")
                + "'\n  domain: trading\n  kind: deal\n";
        JsonNode s = LakeLoadTest.load(tmp, y.formatted(""));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText()).isEqualTo(2);
        assertThat(rows(s)).isEqualTo(Map.of("2026-11-03", 3L));
        assertThat(s.path("rejects").path("byReason").path("no business date (no date field, and the file name carries no date)").asLong()).isEqualTo(2);
        JsonNode refused = LakeLoadTest.load(tmp, y.formatted("  date-from: field\n"));
        assertThat(refused.path("said").asText()).contains("source.date: name the business-date field");
        JsonNode bad = LakeLoadTest.load(tmp, y.formatted("  date-from: name\n"));
        assertThat(bad.path("said").asText()).contains("source.date-from name: auto");
        assertThat(List.of(refused.path("exit").asInt(), bad.path("exit").asInt())).doesNotContain(0);
    }
}
