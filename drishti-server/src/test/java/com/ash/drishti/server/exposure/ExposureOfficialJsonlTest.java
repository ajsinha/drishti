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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.content.FileContentStore;
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

/** 2.9.4 (docs/guides/DATA_FORMATS.md): official exposure as JSON Lines beside its Delta tables; the trade contributions as JSONL. */
class ExposureOfficialJsonlTest {

    @TempDir
    Path dir;

    @Test
    void eachPublishedCommitIsAlsoAJsonLinesFile() throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("HIERARCHY", "CPTY");
        r.put("NODE_ID", "CP-1");
        r.put("COUNTERPARTY", "CP-1");
        r.put("RESULT_VERSION", 1L);
        r.put("EPE", 125.0);
        LocalDate d = LocalDate.of(2026, 9, 30);
        ExposurePublisher p = new ExposurePublisher(dir, 7);
        long v = p.publish(ExposurePublisher.CPTY, d, List.of(r));
        Path f = p.jsonlFile(ExposurePublisher.CPTY, d, v);
        assertThat(f).isEqualTo(dir.resolve("risk-results/jsonl/exposure-official-cpty/2026-09-30/exposure-official-cpty-v" + v + ".jsonl"));
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        assertThat(new ObjectMapper().readTree(lines.get(0)).get("EPE").asDouble()).isEqualTo(125.0);
    }

    @Test
    void tradeContributionsAreLinesAndOldRecordsAreStillRead() {
        ExposureOfficialStore store = new ExposureOfficialStore(new FileContentStore(dir.resolve("content")));
        LocalDate d = LocalDate.of(2026, 9, 30);
        Map<String, Object> trades = new LinkedHashMap<>();
        trades.put("businessDate", "2026-09-30");
        trades.put("runId", "XR-1");
        trades.put("tenors", List.of("1W", "1Y"));
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("T1", Map.of("nettingSet", "NS-1", "ee", List.of(10, 20)));
        rows.put("T2", Map.of("nettingSet", "NS-1", "ee", List.of(5, 7)));
        trades.put("trades", rows);
        store.putTrades(d, 2, trades);
        String key = ExposureOfficialStore.tradesKey(d, 2);
        assertThat(key).isEqualTo("exposure/official/2026-09-30-trades-v2.jsonl");
        String text = store.docs().store().get(key).orElseThrow().text();
        assertThat(text.split("\n")).hasSize(3);                                       // a header, then a trade a line
        assertThat(text).startsWith("{\"row\":\"header\"");
        assertThat(store.trades(key).orElseThrow()).isEqualTo(trades);
        store.docs().putLatest("exposure/official/2026-09-29-trades-v1.json", trades);    // a record written before 2.9.4
        assertThat(store.trades("exposure/official/2026-09-29-trades-v1.json").orElseThrow()).isEqualTo(trades);
    }
}
