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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file's business date when neither the load nor its name gives one (DATA_LAKE_GUIDE.md section 5.2): the rows' own date when they agree
 * on one, never the business today by guess; a replace never takes out a date the file's rows do not belong to; a day split into parts
 * named {@code <table>.<date>.part-0001.jsonl} is one date.
 */
class RowDatesTest {

    @TempDir
    Path tmp;

    private int n;

    private Path file(String name, List<String> dates, int rows) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("in" + (++n)));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            String d = dates.get(i % dates.size());
            lines.add("{\"POSITION_ID\":\"P" + i + "\"" + (d == null ? "" : ",\"BUSINESS_DATE\":\"" + d + "\"") + ",\"PV\":" + i + "}");
        }
        Files.write(dir.resolve(name), lines);
        return dir;
    }

    private JsonNode load(Path dir, Path lake) throws Exception {
        String yaml = "source:\n  files: '" + dir + "/*.jsonl'\n  id: POSITION_ID\n  date: BUSINESS_DATE\n"
                + "target:\n  format: delta\n  lake: '" + lake + "'\n  domain: risk\n  kind: scenario-pv\n  mode: replace\n";
        return LakeLoadTest.load(tmp, yaml);
    }

    private static Map<String, Integer> byDate(Path lake) throws Exception {
        Map<String, Integer> out = new TreeMap<>();
        TableDump.delta(lake.resolve("risk/scenario-pv").toString(), Map.of()).values()
                .forEach(r -> out.merge(String.valueOf(r.get("business_date")), 1, Integer::sum));
        return out;
    }

    @Test
    void aFileWithoutADateInItsNameTakesItsRowsDateAndNeverReplacesAnotherDate() throws Exception {
        Path lake = tmp.resolve("lake");
        JsonNode a = load(file("scenario-pv.2026-11-03.jsonl", java.util.Arrays.asList("2026-11-03"), 30), lake);
        assertThat(a.path("exit").asInt()).as(a.path("said").asText()).isZero();

        // no date in the name, every row 2026-11-04: loaded under 2026-11-04, 2026-11-03 untouched
        JsonNode b = load(file("scenario-pv.jsonl", java.util.Arrays.asList("2026-11-04"), 12), lake);
        assertThat(b.path("exit").asInt()).as(b.path("said").asText()).isZero();
        assertThat(b.path("fileDates").get(0).path("source").asText()).isEqualTo("rows");
        assertThat(byDate(lake)).containsExactly(Map.entry("2026-11-03", 30), Map.entry("2026-11-04", 12));

        // no date anywhere: refused, nothing written (once it replaced the business today with these rows)
        JsonNode c = load(file("scenario-pv.jsonl", java.util.Arrays.asList((String) null), 7), lake);
        assertThat(c.path("exit").asInt()).isEqualTo(1);
        assertThat(c.path("error").asText()).contains("no business date").contains("nothing was written");

        // rows of two dates: ambiguous, refused
        JsonNode d = load(file("scenario-pv.jsonl", java.util.Arrays.asList("2026-11-03", "2026-11-04"), 8), lake);
        assertThat(d.path("exit").asInt()).isEqualTo(1);
        assertThat(d.path("error").asText()).contains("ambiguous");

        // named 2026-11-03, every row another date: refused (a replace would put another day's rows under 2026-11-03)
        JsonNode e = load(file("scenario-pv.2026-11-03.jsonl", java.util.Arrays.asList("2026-11-02"), 5), lake);
        assertThat(e.path("exit").asInt()).isEqualTo(1);
        assertThat(e.path("error").asText()).contains("none of its rows carries that date");
        assertThat(byDate(lake)).as("nothing of the refused loads").containsExactly(Map.entry("2026-11-03", 30), Map.entry("2026-11-04", 12));
    }

    @Test
    void aDaySplitIntoNamedPartsIsOneDateInOneCommit() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("parts"));
        for (int p = 1; p <= 3; p++) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                lines.add("{\"POSITION_ID\":\"P" + p + "-" + i + "\",\"BUSINESS_DATE\":\"2026-11-03\",\"PV\":1}");
            }
            Files.write(dir.resolve("scenario-pv.2026-11-03.part-000" + p + ".jsonl"), lines);
        }
        Path lake = tmp.resolve("lake-parts");
        JsonNode s = load(dir, lake);
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        assertThat(s.path("commit").path("commits").asInt()).isEqualTo(1);
        assertThat(byDate(lake)).containsExactly(Map.entry("2026-11-03", 30));
    }
}
