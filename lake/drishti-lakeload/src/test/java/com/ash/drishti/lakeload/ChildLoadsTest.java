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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Child kinds in the parallel loader (docs/guides/DATA_LAKE_GUIDE.md section 5.7): the pack's {@code ingest:} block declares {@code LEGS} of
 * {@code deal} a kind of its own; the loader splits it by the rules of tools/packgen/childkinds.py. The documents and the rows they must give are
 * {@code src/test/resources/childkinds}, which tools/test_child_parity.py checks against the Python split too, so the two cannot drift.
 */
class ChildLoadsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of("src/test/resources/childkinds");

    @TempDir
    Path tmp;

    private static List<JsonNode> lines(Path f) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String l : Files.readAllLines(f)) {
            if (!l.isBlank()) {
                out.add(JSON.readTree(l));
            }
        }
        return out;
    }

    private static List<JsonNode> explode(String docs) throws IOException {
        ChildKinds c = ChildKinds.of(FIXTURE.resolve("pack.yaml"), "deal").get(0);
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode d : lines(FIXTURE.resolve(docs))) {
            out.addAll(c.explode(d));
        }
        return out;
    }

    @Test
    void theRulesAreThePacksIngestBlock() throws Exception {
        List<ChildKinds> kids = ChildKinds.of(FIXTURE.resolve("pack.yaml"), "deal");
        assertThat(kids).hasSize(1);
        ChildKinds c = kids.get(0);
        assertThat(List.of(c.parent(), c.field(), c.kind(), c.key(), c.link(), c.parentKey(), c.natural()))
                .containsExactly("deal", "LEGS", "deal-leg", "LEG_KEY", "TRADE_ID", "TRADE_ID", "LEG_ID");
        assertThat(ChildKinds.of(FIXTURE.resolve("pack.yaml"), "deal-leg")).isEmpty();
        assertThat(ChildKinds.linkOf("deal", "id")).isEqualTo("DEAL_ID");
        assertThat(ChildKinds.linkOf("deal", "trade.TRADE_ID")).isEqualTo("TRADE_ID");
    }

    @Test
    void theSplitGivesTheRowsThePythonSplitGives() throws Exception {
        assertThat(explode("deal.2026-11-03.jsonl")).isEqualTo(lines(FIXTURE.resolve("expected-deal-leg.jsonl")));
        assertThat(explode("edge.jsonl")).isEqualTo(lines(FIXTURE.resolve("expected-edge.jsonl")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"delta", "iceberg"})
    void theChildKindIsLoadedBesideItsParentInEitherFormat(String format) throws Exception {
        Path pack = tmp.resolve("pack");
        Files.createDirectories(pack);
        Files.copy(FIXTURE.resolve("pack.yaml"), pack.resolve("pack.yaml"));
        Path in = tmp.resolve("in/deal.2026-11-03.jsonl");
        Files.createDirectories(in.getParent());
        Files.copy(FIXTURE.resolve("deal.2026-11-03.jsonl"), in);
        Path lake = tmp.resolve("lake");
        String yaml = "source:\n  files: '" + in + "'\n  pack: '" + pack + "'\n  id: TRADE_ID\n"
                + "target:\n  format: " + format + "\n  lake: '" + lake + "'\n  domain: deals\n  kind: deal\n";
        JsonNode s = LakeLoadTest.load(tmp, yaml);
        assertThat(s.path("exit").asInt()).as(s.toString()).isZero();
        Map<String, Map<String, Object>> parents = rows(format, lake, "deal");
        Map<String, Map<String, Object>> legs = rows(format, lake, "deal-leg");
        assertThat(parents.keySet()).containsExactly("2026-11-03|T1", "2026-11-03|T2", "2026-11-03|T3", "2026-11-03|T4", "2026-11-03|T5");
        assertThat(legs.keySet()).containsExactly("2026-11-03|T1:1", "2026-11-03|T1:2", "2026-11-03|T2:0", "2026-11-03|T2:B", "2026-11-03|T5:3");
        Map<String, Object> leg = legs.get("2026-11-03|T1:1");
        assertThat(List.of(leg.get("TRADE_ID"), String.valueOf(leg.get("LEG_ID")), leg.get("CURRENCY"))).containsExactly("T1", "1", "USD");
        assertThat(((Number) leg.get("NOTIONAL")).doubleValue()).isEqualTo(1000.5);
        assertThat(legs.get("2026-11-03|T2:0").get("LEG_ID")).isNull();
        assertThat(legs.get("2026-11-03|T2:B").get("TRADE_ID")).isEqualTo("T2");
        assertThat((String) parents.get("2026-11-03|T1").get("doc")).contains("\"LEGS\":[");           // the parent keeps its array
    }

    private static Map<String, Map<String, Object>> rows(String format, Path lake, String kind) throws Exception {
        return format.equals("delta") ? TableDump.delta(lake.resolve("deals/" + kind).toString(), Map.of())
                : TableDump.iceberg(lake.toString(), "deals", kind, Map.of());
    }
}
