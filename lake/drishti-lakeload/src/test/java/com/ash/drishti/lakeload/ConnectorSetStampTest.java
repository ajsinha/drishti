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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A connector's {@code set:} is stored into the rows a load writes through it (DATA_LAKE_GUIDE.md, "Several source systems"), in Delta and
 * Iceberg: a row that lacks the field gets the constant, a row that has it keeps its own value; the document and the promoted column agree.
 */
class ConnectorSetStampTest {

    @TempDir
    Path tmp;

    @Test
    void theConnectorsConstantsAreStoredInTheRowsThatLackThem() throws Exception {
        Path pack = Files.createDirectories(tmp.resolve("config/packs/d/trading"));
        Files.createDirectories(tmp.resolve("modules"));
        Files.writeString(pack.resolve("pack.yaml"), "pack: trading\nconnectors:\n  trade-swapone:\n    plugin: delta\n    kinds: [trade]\n    settings:\n"
                + "      domain: risk\n      table.trade: scenario-pv\n      layout.trade.columns: TRADE_ID,SOURCE_SYSTEM\n      set:\n        SOURCE_SYSTEM: SWAPONE\n");
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        Files.write(drop.resolve("trade.2026-11-03.jsonl"), List.of("{\"TRADE_ID\":\"A\",\"N\":1}", "{\"TRADE_ID\":\"B\",\"SOURCE_SYSTEM\":\"OTHER\"}"));
        for (String format : List.of("delta", "iceberg")) {
            Path lake = tmp.resolve("lake-" + format);
            String yaml = "source:\n  files: '" + drop + "/*.jsonl'\n  id: TRADE_ID\n  pack: '" + pack + "'\n  connector: trade-swapone\n"
                    + "target:\n  format: " + format + "\n  lake: '" + lake + "'\n  kind: trade\n";
            JsonNode s = LakeLoadTest.load(tmp, yaml);
            assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
            Map<String, Map<String, Object>> rows = LakeLoadTest.dump(format, lake);
            assertThat(rows).hasSize(2);
            String text = rows.toString();
            assertThat(text.split("SWAPONE", -1).length - 1).as(format + " stamps the row that lacks the field, in the column and the document: " + text).isEqualTo(2);
            assertThat(text).as(format + " keeps a value the row has").contains("OTHER");
        }
    }
}
