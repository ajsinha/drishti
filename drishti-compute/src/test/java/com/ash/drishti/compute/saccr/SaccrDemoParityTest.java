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
package com.ash.drishti.compute.saccr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The engine against the reference implementation (tools/packgen/ccr/saccrmath.py) on every netting set of the demo's business date: the
 * committed {@code saccr-trade} rows (data/testdata/counterparty-risk/saccr-trade) with each set's header as the generator read it
 * (drishti-compute/src/test/resources/saccr/reference.json), EAD margined, unmargined and after the cap, to the cent.
 */
class SaccrDemoParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path testdata() {
        for (Path p : List.of(Path.of("../data/testdata"), Path.of("data/testdata"))) {
            if (Files.isDirectory(p.resolve("counterparty-risk/saccr-trade"))) {
                return p;
            }
        }
        return null;
    }

    @Test
    void everyDemoNettingSetEqualsTheReferenceToTheCent() throws Exception {
        Path td = testdata();
        Assumptions.assumeTrue(td != null, "no committed test data beside this module");
        JsonNode ref;
        try (InputStream in = SaccrDemoParityTest.class.getResourceAsStream("/saccr/reference.json")) {
            ref = JSON.readTree(in);
        }
        LocalDate cob = LocalDate.parse(ref.get("businessDate").asText());
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        Path file = td.resolve("counterparty-risk/saccr-trade/cob-" + cob + ".jsonl.gz");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            for (String ln = r.readLine(); ln != null; ln = r.readLine()) {
                if (!ln.isBlank()) {
                    Map<String, Object> m = JSON.readValue(ln, new TypeReference<Map<String, Object>>() {});
                    assertThat(SaccrRows.computedField(m)).as("a delivered row carries no computed figure").isNull();
                    rows.computeIfAbsent(String.valueOf(m.get("NETTING_SET")), k -> new ArrayList<>()).add(m);
                }
            }
        }
        SaccrParams p = SaccrParams.bcbs();
        int checked = 0;
        var it = ref.get("sets").fields();
        while (it.hasNext()) {
            var e = it.next();
            JsonNode x = e.getValue();
            List<Map<String, Object>> set = rows.getOrDefault(e.getKey(), List.of());
            assertThat(set).as(e.getKey()).hasSize(x.get("rows").asInt());
            if (set.isEmpty()) {
                assertThat(x.get("EAD").asDouble()).isZero();
                continue;
            }
            JsonNode h = x.get("header");
            SaccrInput.NettingSet ns = new SaccrInput.NettingSet(e.getKey(), null, null, h.get("V").asDouble(), h.get("C").asDouble(), h.get("NICA").asDouble(),
                    h.get("TH").asDouble(), h.get("MTA").asDouble(), h.get("MARGINED").asBoolean(), h.get("CLEARED").asBoolean(), h.get("MPOR_DAYS").asInt(),
                    h.get("MARGIN_FREQUENCY_DAYS").asInt(), h.get("DISPUTES").asInt(), h.get("ILLIQUID").asBoolean(), h.get("TRADES_IN_SET").asInt());
            List<SaccrInput.Trade> trades = set.stream().map(m -> SaccrRows.trade(m, cob, Double.NaN)).toList();
            Saccr.Result r = Saccr.run(new SaccrInput(ns, trades), p);
            assertThat(r.refusals()).as(e.getKey()).isEmpty();
            assertThat(r.unmargined().ead()).as(e.getKey() + " unmargined").isCloseTo(x.get("EAD_U").asDouble(), within(0.005));
            if (r.margined() != null) {
                assertThat(r.margined().ead()).as(e.getKey() + " margined").isCloseTo(x.get("EAD_M").asDouble(), within(0.005));
            }
            assertThat(r.ead()).as(e.getKey()).isCloseTo(x.get("EAD").asDouble(), within(0.005));
            assertThat(r.capped()).as(e.getKey()).isEqualTo(x.get("CAPPED").asBoolean());
            checked++;
        }
        assertThat(checked).isGreaterThan(30);
    }
}
