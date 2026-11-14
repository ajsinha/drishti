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

import com.ash.drishti.api.ListValues;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A promoted field holding a list of simple values is written as a native {@code array<string>} column (Delta and Iceberg), its values
 * as texts (a number in its plain text, a null element left out); a list of objects is not promoted as a list (its column keeps the JSON
 * text, the list stays in the document); the document keeps every list as it was (DATA_LAKE_GUIDE.md section 2.4).
 */
class ListColumnsLoadTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"delta", "iceberg"})
    void aListOfSimpleValuesIsAnArrayColumnAndReadsBackAsWritten(String format) throws Exception {
        Path in = tmp.resolve("in/trade.2026-11-13.jsonl");
        Files.createDirectories(in.getParent());
        Files.writeString(in, String.join("\n",
                "{\"TRADE_ID\":\"T-1\",\"DESK\":\"RATES\",\"TAGS\":[\"fx\",\"rates\"],\"YEARS\":[2025,2026.0],\"LEGS\":[{\"N\":1}]}",
                "{\"TRADE_ID\":\"T-2\",\"DESK\":\"FX\",\"TAGS\":[],\"YEARS\":[2030,null],\"LEGS\":[{\"N\":2}]}",
                "{\"TRADE_ID\":\"T-3\",\"DESK\":\"FX\",\"TAGS\":null,\"LEGS\":[]}",
                "{\"TRADE_ID\":\"T-4\",\"DESK\":\"FX\",\"TAGS\":[\"credit, \\\"senior\\\"\",true],\"YEARS\":[1],\"LEGS\":[{\"N\":3}]}") + "\n");
        Path lake = tmp.resolve("lake");
        String yaml = "source:\n  files: '" + in + "'\n  id: TRADE_ID\n  promote: [DESK, TAGS, YEARS, LEGS]\n"
                + "target:\n  format: " + format + "\n  lake: '" + lake + "'\n  domain: trading\n  kind: trade\n";
        JsonNode s = LakeLoadTest.load(tmp, yaml);
        assertThat(s.path("exit").asInt()).as(s.toString()).isZero();
        assertThat(s.path("columns").path("types").path("TAGS").asText()).isEqualTo("list");
        assertThat(s.path("columns").path("types").path("YEARS").asText()).isEqualTo("list");
        assertThat(s.path("columns").path("types").path("LEGS").asText()).isEqualTo("string");     // a list of objects: not a list attribute
        Map<String, Map<String, Object>> t = format.equals("delta") ? TableDump.delta(lake.resolve("trading/trade").toString(), Map.of())
                : TableDump.iceberg(lake.toString(), "trading", "trade", Map.of());
        assertThat(t.get("2026-11-13|T-1").get("TAGS")).isEqualTo(List.of("fx", "rates"));
        assertThat(t.get("2026-11-13|T-1").get("YEARS")).isEqualTo(List.of("2025", "2026"));
        assertThat(t.get("2026-11-13|T-2").get("YEARS")).isEqualTo(List.of("2030"));
        assertThat(t.get("2026-11-13|T-3").get("TAGS")).isNull();
        assertThat(t.get("2026-11-13|T-4").get("TAGS")).isEqualTo(List.of("credit, \"senior\"", "true"));
        assertThat(t.get("2026-11-13|T-1").get("LEGS")).isEqualTo("[{\"N\":1}]");
        assertThat((String) t.get("2026-11-13|T-1").get("doc")).contains("\"TAGS\":[\"fx\",\"rates\"]");
        Object empty = t.get("2026-11-13|T-2").get("TAGS");
        assertThat(empty == null ? List.of() : empty).isEqualTo(List.of());          // an empty list (Parquet may keep it as empty or none)
        assertThat(ListValues.of(ListValues.json(List.of("credit, \"senior\"", "true")))).containsExactly("credit, \"senior\"", "true");
    }
}
