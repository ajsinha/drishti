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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 2.9.4 (docs/guides/DATA_FORMATS.md): the official VaR results as JSON Lines beside the Delta table, and the membership record as JSONL. */
class VarOfficialJsonlTest {

    @TempDir
    Path lake;

    static List<Map<String, Object>> rows(long version) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("HIERARCHY", "MGMT");
        r.put("NODE_ID", "MGMT~DESK-RATES");
        r.put("RESULT_VERSION", version);
        r.put("VAR_99_1D", 1250000.0);
        return List.of(r);
    }

    @Test
    void eachPublishedCommitIsAlsoAJsonLinesFileAndNothingIsOverwritten() throws Exception {
        LocalDate d = LocalDate.of(2026, 9, 30);
        VarPublisher p = new VarPublisher(lake, 7);
        long v1 = p.publish(d, rows(1));
        long v2 = p.publish(d, rows(2));                                          // a restatement: a second file
        Path f1 = p.jsonlFile(d, v1);
        Path f2 = p.jsonlFile(d, v2);
        assertThat(f1).isEqualTo(lake.resolve("risk-results/jsonl/var-official/2026-09-30/var-official-v" + v1 + ".jsonl"));
        List<String> lines = Files.readAllLines(f2, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        JsonNode row = new ObjectMapper().readTree(lines.get(0));
        assertThat(row.get("NODE_ID").asText()).isEqualTo("MGMT~DESK-RATES");
        assertThat(row.get("RESULT_VERSION").asLong()).isEqualTo(2);
        assertThat(row.get("BUSINESS_DATE").asText()).isEqualTo("2026-09-30");
        assertThat(Files.readString(f1)).contains("\"RESULT_VERSION\":1");
        VarPublisher off = new VarPublisher(lake.resolve("other"), 7, false);   // drishti.var.official.jsonl: false
        long v = off.publish(d, rows(1));
        assertThat(off.jsonlFile(d, v)).doesNotExist();
    }

    @Test
    void theMembershipRecordRoundTripsThroughItsLines() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessDate", "2026-09-30");
        m.put("resultVersion", 2);
        m.put("positionRules", Map.of("securities", "SECURITY_ID"));
        m.put("trades", 3);
        m.put("positions", 2);
        Map<String, Object> books = new LinkedHashMap<>();
        books.put("BOOK-1", new LinkedHashMap<>(Map.of("POS-1", List.of("T1", "T2"))));
        books.put("BOOK-2", new LinkedHashMap<>(Map.of("POS-2", List.of("T3"))));
        m.put("books", books);
        m.put("nodes", new LinkedHashMap<>(Map.of("MGMT~DESK-RATES", List.of("BOOK-1", "BOOK-2"))));
        String text = new String(MembershipLines.write(m), StandardCharsets.UTF_8);
        assertThat(text.split("\n")).hasSize(1 + 2 + 1);                         // a header, a position a line, a node a line
        assertThat(MembershipLines.read(text)).isEqualTo(m);
    }
}
