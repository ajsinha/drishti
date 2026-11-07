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

import com.ash.drishti.lakeconfig.Lakes;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loader writing through a lake file (docs/guides/LAKE_GUIDE.md): the table's place and layout from {@code config/lakes/<lake>.yaml},
 * a load key that contradicts it refused with both values named, a kind the lake has no entry for refused, and {@code load-mode: append}
 * adding rows with each batch committed once (a repeated batch skipped, the earlier files kept); the entry's mode the table's default.
 */
class LakeFileLoadTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void lakes() {
        System.setProperty(Lakes.PROPERTY, tmp.resolve("lakes").toString());
    }

    @AfterEach
    void reset() {
        System.clearProperty(Lakes.PROPERTY);
    }

    private void lakeFile(String mode, String partition) throws Exception {
        Files.createDirectories(tmp.resolve("lakes"));
        Files.writeString(tmp.resolve("lakes/risk.yaml"), "lake: risk\nformat: delta\nroot: '" + tmp.resolve("lake") + "'\ntables:\n"
                + "  risk.scenario-pv:\n    id: POSITION_ID\n    date: BUSINESS_DATE\n    load-mode: " + mode + "\n"
                + (partition == null ? "" : "    partition-by: [" + partition + "]\n")
                + "    maintenance: {checkpoint-interval: 20}\n"
                + "    promote: [LEGAL_ENTITY, DESK, TT_BOOK, TRADE_ID, QUANTITY, BASE_PV, CCY, POSITION_ID]\n    vectors: [SCENARIO_PV]\n");
    }

    private static String config(Path in, String... extra) {
        StringBuilder y = new StringBuilder("source:\n  files: '" + in + "'\n");
        for (String e : extra) {
            y.append(e).append('\n');
        }
        return y.append("target:\n  lake: risk\n  kind: scenario-pv\n").toString();
    }

    private long commits(Path table) throws Exception {
        try (Stream<Path> s = Files.list(table.resolve("_delta_log"))) {
            return s.filter(p -> p.getFileName().toString().matches("\\d{20}\\.json")).count();
        }
    }

    @Test
    void theTableIsWhereAndAsTheLakeFileSays() throws Exception {
        lakeFile("replace", "DESK");
        Path in = PvData.write(tmp.resolve("in/pv.jsonl"), PvData.lines(200, 20, 2, 1));
        JsonNode s = LakeLoadTest.load(tmp, config(in));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        Path table = tmp.resolve("lake/risk/scenario-pv");
        assertThat(table.resolve("_delta_log")).isDirectory();
        String log = Files.readString(table.resolve("_delta_log/00000000000000000000.json"));
        assertThat(log).contains("\"partitionColumns\":[\"business_date\",\"DESK\"]");
        assertThat(log).contains("delta.checkpointInterval");                       // the lake file's maintenance, as a table property
    }

    @Test
    void aLoadKeyThatContradictsTheLakeFileIsRefusedNamingBoth() throws Exception {
        lakeFile("replace", "DESK");
        Path in = PvData.write(tmp.resolve("in/pv.jsonl"), PvData.lines(20, 5, 1, 1));
        JsonNode s = LakeLoadTest.load(tmp, config(in, "  partition-by: [LEGAL_ENTITY]"));
        assertThat(s.path("exit").asInt()).isNotZero();
        assertThat(s.path("said").asText() + s.path("error").asText()).contains("LEGAL_ENTITY").contains("DESK").contains("risk.scenario-pv");
    }

    @Test
    void aKindTheLakeFileLacksIsRefused() throws Exception {
        lakeFile("replace", null);
        Path in = PvData.write(tmp.resolve("in/pv.jsonl"), PvData.lines(20, 5, 1, 1));
        String yaml = config(in).replace("kind: scenario-pv", "kind: pnl-vector");
        JsonNode s = LakeLoadTest.load(tmp, yaml);
        assertThat(s.path("exit").asInt()).isNotZero();
        assertThat(s.path("said").asText() + s.path("error").asText()).contains("has no table").contains("pnl-vector");
    }

    @Test
    void anAppendCommitsEachBatchOnceAndKeepsTheEarlierRows() throws Exception {
        lakeFile("append", null);
        Path table = tmp.resolve("lake/risk/scenario-pv");
        Path in = PvData.byDate(tmp.resolve("in"), PvData.lines(100, 10, 2, 1));      // one file a business date (the load rules)
        JsonNode first = LakeLoadTest.load(tmp, config(in), "target.batch-id=b1");
        assertThat(first.path("exit").asInt()).as(first.path("said").asText() + first.path("error").asText()).isZero();
        long after1 = commits(table);
        assertThat(after1).isEqualTo(2);

        JsonNode again = LakeLoadTest.load(tmp, config(in), "target.batch-id=b1");          // the same file again: never twice
        assertThat(again.path("exit").asInt()).as(again.path("error").asText()).isZero();
        assertThat(commits(table)).isEqualTo(after1);

        Path in2 = PvData.byDate(tmp.resolve("in2"), PvData.lines(100, 10, 2, 7));
        JsonNode second = LakeLoadTest.load(tmp, config(in2), "target.batch-id=b2");      // another batch: added, nothing removed
        assertThat(second.path("exit").asInt()).as(second.path("error").asText()).isZero();
        assertThat(commits(table)).isEqualTo(after1 + 2);
        String last = Files.readString(table.resolve(String.format("_delta_log/%020d.json", after1 + 1)));
        assertThat(last).doesNotContain("\"remove\"").contains("drishti-batch:b2:");
    }

    /**
     * The mode (DATA_ARCHITECTURE.md section 10.3): the lake file's entry is the table's default, below an explicit target.mode and above
     * target.default-mode (the inbox's); without a batch id an append is not guarded, each run's rows carry their load id.
     */
    @Test
    void theEntrysModeIsTheTablesDefaultAndAnExplicitModeWins() throws Exception {
        lakeFile("append", null);
        Path in = PvData.byDate(tmp.resolve("in"), PvData.lines(20, 5, 1, 1));
        JsonNode s = LakeLoadTest.load(tmp, config(in), "target.default-mode=replace-date");
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        assertThat(List.of(s.path("mode").asText(), s.path("modeFrom").asText())).containsExactly("append", "the lake file's entry");
        JsonNode r = LakeLoadTest.load(tmp, config(in), "target.mode=replace-date");
        assertThat(r.path("exit").asInt()).as(r.path("said").asText() + r.path("error").asText()).isZero();
        assertThat(List.of(r.path("mode").asText(), r.path("modeFrom").asText())).containsExactly("overwrite-dates",
                "given (--mode or the .ready file)");
    }
}
