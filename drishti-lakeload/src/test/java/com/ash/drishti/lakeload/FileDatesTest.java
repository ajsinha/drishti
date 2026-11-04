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
import java.util.ArrayList;
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
 * Feeds (docs/architecture/DATA_LOAD_RULES.md; DATA_GUIDE.md section 2.8): the shared cases ({@code tools/lake/testdata/file-dates/names.json},
 * which the Python {@code test_filedate.py} reads too, so the two rules cannot drift), then the loader end to end on a small lake: plain
 * files dated by their names, by {@code as-of} and by the business today; a row's own date only counted; an empty file ignored; a tar of
 * two tables and two dates (and a .tar.zip); a refused tar.
 */
class FileDatesTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path CASES = PythonPathEqualityTest.REPO.resolve("tools/lake/testdata/file-dates/names.json");

    @TempDir
    Path tmp;

    private static LocalDate d(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : LocalDate.parse(n.asText());
    }

    @Test
    void theSharedCases() throws IOException {
        JsonNode c = JSON.readTree(CASES.toFile());
        LocalDate today = LocalDate.parse(c.path("today").asText());
        for (JsonNode n : c.path("names")) {
            FileDates.Name got = FileDates.parse(n.path("name").asText(), today);
            String as = n.toString() + " -> " + got;
            assertThat(got.tar()).as(as).isEqualTo(n.path("tar").asBoolean(false));
            if (n.hasNonNull("error")) {
                assertThat(got.error()).as(as).isNotNull().contains(n.path("error").asText());
            } else {
                assertThat(got.error()).as(as).isNull();
                assertThat(got.table()).as(as).isEqualTo(n.path("table").asText(null));
                assertThat(got.date()).as(as).isEqualTo(d(n.get("date")));
            }
        }
        for (JsonNode n : c.path("decide")) {
            FileDates.Decided r = FileDates.decide(d(n.get("given")), d(n.get("name")), n.path("dateFrom").asText(), today, "f.jsonl");
            if (n.hasNonNull("error")) {
                assertThat(r.error()).as(n.toString()).contains(n.path("error").asText());
            } else {
                assertThat(List.of(String.valueOf(r.date()), r.source(), FileDates.words(r, "the command line"))).as(n.toString())
                        .containsExactly(n.path("date").asText(), n.path("source").asText(), n.path("words").asText());
            }
        }
        for (JsonNode n : c.path("businessToday")) {
            assertThat(FileDates.businessToday(d(n.get("today")))).isEqualTo(d(n.get("business")));
        }
        for (JsonNode t : c.path("tars")) {
            List<String[]> entries = new ArrayList<>();
            t.path("entries").forEach(e -> entries.add(new String[] {e.get(0).asText(), e.get(1).asText()}));
            List<String> known = null;
            if (t.path("known").isArray()) {
                known = new ArrayList<>();
                for (JsonNode k : t.path("known")) {
                    known.add(k.asText());
                }
            }
            FileDates.Layout lay = FileDates.tarLayout(entries, today, known);
            Map<String, Map<String, List<String>>> tables = new LinkedHashMap<>();
            lay.tables().forEach((tb, ds) -> ds.forEach((day, fs) -> tables.computeIfAbsent(tb, k -> new LinkedHashMap<>()).put(day.toString(), fs)));
            assertThat((JsonNode) JSON.valueToTree(tables)).as(t.path("case").asText()).isEqualTo(t.path("tables"));
            assertThat((JsonNode) JSON.valueToTree(lay.errors())).as(t.path("case").asText()).isEqualTo(t.path("errors"));
            assertThat((JsonNode) JSON.valueToTree(lay.ignored())).as(t.path("case").asText()).isEqualTo(t.path("ignored"));
        }
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

    /** A ustar archive of {@code members} (name → bytes). */
    static byte[] tar(Map<String, byte[]> members) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> m : members.entrySet()) {
            byte[] data = m.getValue();
            byte[] h = new byte[512];
            put(h, 0, m.getKey());
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
            out.write(h);
            out.write(data);
            out.write(new byte[(512 - data.length % 512) % 512]);
        }
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
    void eachFileIsOneDateFromItsNameOrTodayAndRowDatesAreOnlyCounted() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        Files.write(drop.resolve("deal.2026-11-03.jsonl"), lines(0, 10, i -> i >= 3 ? "2026-11-03" : "2026-11-02"));
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(drop.resolve("deal.2026-11-02.jsonl.gz")))) {
            o.write(lines(10, 15, i -> "2026-10-01"));
        }
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(drop.resolve("deal.2026-10-30.zip")))) {
            z.putNextEntry(new ZipEntry("a.jsonl"));
            z.write(lines(20, 23, i -> null));
            z.closeEntry();
        }
        Files.write(drop.resolve("deal.jsonl"), lines(30, 32, i -> "2026-10-29"));
        Files.write(drop.resolve("deal.2026-11-01.jsonl"), new byte[0]);
        JsonNode s = load(drop, "lake");
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        String today = FileDates.businessToday().toString();
        assertThat(rows(s)).isEqualTo(new TreeMap<>(Map.of("2026-10-30", 3L, "2026-11-02", 5L, "2026-11-03", 10L, today, 2L)));
        Map<String, String> byFile = new LinkedHashMap<>();
        s.path("fileDates").forEach(f -> byFile.put(f.path("file").asText(), f.path("date").asText() + "/" + f.path("source").asText() + "/"
                + f.path("otherDateRows").asLong()));
        assertThat(byFile).containsEntry("deal.2026-11-03.jsonl", "2026-11-03/name/3").containsEntry("deal.2026-11-02.jsonl.gz", "2026-11-02/name/5")
                .containsEntry("deal.2026-10-30.zip!/a.jsonl", "2026-10-30/name/0").containsEntry("deal.jsonl", today + "/today/2");
        assertThat(s.path("emptyFiles").toString()).contains("deal.2026-11-01.jsonl");
        assertThat(s.path("dateNotes").toString()).contains("3 rows of deal.2026-11-03.jsonl carried another date in TRADE_DATE; loaded under 2026-11-03");
        assertThat(s.path("said").asText()).isNotNull();

        JsonNode asOf = load(drop, "lake-asof", "as-of: 2026-10-01");
        assertThat(rows(asOf)).isEqualTo(Map.of("2026-10-01", 20L));
        JsonNode named = load(drop, "lake-name", "date-from: name");
        assertThat(named.path("exit").asInt()).isEqualTo(1);
        assertThat(named.path("said").asText() + named.path("error").asText()).contains("deal.jsonl: date-from name, but the name carries no date");
    }

    @Test
    void aTarLoadsItsTableWithEachFolderItsDateAndABadOneIsRefusedWhole() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("tar"));
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("deal/2026-11-02/a.jsonl", lines(0, 3, i -> "2026-11-02"));
        m.put("deal/2026-11-03/a.jsonl", lines(3, 7, i -> "2026-11-01"));
        m.put("__MACOSX/deal/._a.jsonl", "x".getBytes(StandardCharsets.UTF_8));
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(drop.resolve("feed.tar.gz")))) {
            o.write(tar(m));
        }
        JsonNode s = load(drop, "lake-tar");
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        assertThat(rows(s)).isEqualTo(Map.of("2026-11-02", 3L, "2026-11-03", 4L));
        assertThat(s.path("otherDateRows").asLong()).isEqualTo(4);
        assertThat(s.path("fileDates").get(0).path("source").asText()).isEqualTo("folder");

        Path zipped = Files.createDirectories(tmp.resolve("tarzip"));
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zipped.resolve("feed.tar.zip")))) {
            z.putNextEntry(new ZipEntry("feed.tar"));
            z.write(tar(m));
            z.closeEntry();
        }
        JsonNode sz = load(zipped, "lake-tarzip");
        assertThat(sz.path("exit").asInt()).as(sz.path("said").asText() + sz.path("error").asText()).isZero();
        assertThat(rows(sz)).isEqualTo(Map.of("2026-11-02", 3L, "2026-11-03", 4L));

        JsonNode given = load(drop, "lake-tar2", "as-of: 2026-11-01");
        assertThat(given.path("exit").asInt()).isNotZero();
        assertThat(given.path("said").asText()).contains("a .tar archive names its own tables and dates");

        Path bad = Files.createDirectories(tmp.resolve("bad"));
        Map<String, byte[]> b = new LinkedHashMap<>();
        b.put("deal/latest/a.jsonl", lines(0, 1, i -> null));
        b.put("deal/2026-11-03/a.jsonl", lines(1, 2, i -> null));
        b.put("top.jsonl", lines(2, 3, i -> null));
        Files.write(bad.resolve("feed.tar"), tar(b));
        JsonNode r = load(bad, "lake-bad");
        assertThat(r.path("exit").asInt()).isEqualTo(1);
        assertThat(r.path("said").asText()).contains("feed.tar: refused, nothing was loaded (2 problems)")
                .contains("deal/latest/a.jsonl: the folder latest is not a date").contains("top.jsonl: at the top");
        assertThat(Files.exists(tmp.resolve("lake-bad/trading/deal"))).isFalse();
    }
}
