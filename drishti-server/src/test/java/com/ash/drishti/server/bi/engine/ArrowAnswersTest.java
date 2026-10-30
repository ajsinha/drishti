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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 2.9.7 "Arrow where it pays": an answer sent as Arrow is the JSON answer, cell for cell (nulls, text with dates, decimals, integers past
 * 32 bits, a key left out of some records), and an answer that cannot be written as Arrow goes as JSON.
 */
class ArrowAnswersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The answer rebuilt from the stream as the browser's helper rebuilds it (drishti-table.js), as a JSON tree. */
    static JsonNode rebuilt(byte[] bytes) throws Exception {
        Map<String, String> meta = ArrowTestReader.metadata(bytes);
        ObjectNode answer = (ObjectNode) JSON.readTree(meta.get(ArrowAnswers.ANSWER_KEY));
        JsonNode table = JSON.readTree(meta.get(ArrowAnswers.TABLE_KEY));
        ArrowTestReader.Table t = ArrowTestReader.read(bytes);
        List<String> names = new ArrayList<>(t.columns().keySet());
        switch (table.get("shape").asText()) {
            case "rows" -> {
                ArrayNode rows = answer.putArray(table.get("at").asText());
                for (int r = 0; r < t.rows(); r++) {
                    ArrayNode row = rows.addArray();
                    for (String n : names) {
                        row.add(JSON.valueToTree(t.columns().get(n).get(r)));
                    }
                }
            }
            case "records" -> {
                List<String> absent = new ArrayList<>();
                table.get("absent").forEach(a -> absent.add(a.asText()));
                ArrayNode rows = answer.putArray(table.get("at").asText());
                for (int r = 0; r < t.rows(); r++) {
                    ObjectNode o = rows.addObject();
                    for (String n : names) {
                        Object v = t.columns().get(n).get(r);
                        if (v != null || !absent.contains(n)) {
                            o.set(n, JSON.valueToTree(v));
                        }
                    }
                }
            }
            default -> {
                for (String n : names) {
                    answer.set(n, JSON.valueToTree(t.columns().get(n)));
                }
            }
        }
        return answer;
    }

    /** Equal as numbers, not as Java types: JSON has one number type, as the browser does. */
    static void assertSame(JsonNode arrow, Object answer) {
        JsonNode json = JSON.valueToTree(answer);
        assertThat(normal(arrow)).isEqualTo(normal(json));
    }

    private static JsonNode normal(JsonNode n) {
        if (n.isNumber()) {
            return JSON.getNodeFactory().numberNode(n.decimalValue().stripTrailingZeros());
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(normal(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            new java.util.TreeMap<>(JSON.convertValue(n, Map.class)).keySet().forEach(k -> o.set(String.valueOf(k), normal(n.get(String.valueOf(k)))));
            return o;
        }
        return n;
    }

    @Test
    void aBiAnswerRoundTripsCellForCell() throws Exception {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("columns", List.of(Map.of("name", "DESK", "type", "string", "masked", false), Map.of("name", "TRADE_DATE", "type", "string", "masked", false),
                Map.of("name", "MTM", "type", "decimal", "masked", false), Map.of("name", "trades", "type", "integer", "masked", false)));
        answer.put("rows", List.of(Arrays.asList("DESK-FX", "2026-10-08", 1234567.89, 9_007_199_254_740_000L),
                Arrays.asList(null, "2026-10-07", null, 3L), Arrays.asList("•••", "", -0.01, null), Arrays.asList("Zürich ✓", "2026-10-08T09:30:00Z", 1e-9, -5L)));
        answer.put("rowCount", 4);
        answer.put("meta", Map.of("layer", "base", "masked", List.of()));
        answer.put("totals", List.of(Map.of("by", List.of(), "rows", List.of(List.of(1.5)))));
        byte[] bytes = ArrowAnswers.encode(answer, "rows", "rows").orElseThrow();
        assertThat(ArrowTestReader.read(bytes).rows()).isEqualTo(4);
        assertSame(rebuilt(bytes), answer);
    }

    @Test
    void repeatedTextGoesAsADictionaryAndReadsBackTheSame(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(Arrays.asList("T-" + i, i % 7 == 3 ? null : List.of("DESK-FX", "DESK-FI", "Zürich ✓").get(i % 3), "2026-10-08", i * 1.25, (long) i,
                    i % 5 == 4 ? null : i % 2 == 0));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("columns", List.of(Map.of("name", "id"), Map.of("name", "desk"), Map.of("name", "day"), Map.of("name", "mtm"), Map.of("name", "n"),
                Map.of("name", "partial")));
        answer.put("rows", rows);
        assertThat(ArrowAnswers.repeats(new Object[] {"a", "b"})).isFalse();
        byte[] bytes = ArrowAnswers.encode(answer, "rows", "rows").orElseThrow();
        assertSame(rebuilt(bytes), answer);
        java.nio.file.Files.write(dir.resolve("sample.arrow"), bytes);
        java.nio.file.Path target = java.nio.file.Path.of("target");
        if (java.nio.file.Files.isDirectory(target)) {                       // the stream for pyarrow and Arrow JS to read back (DATA_FORMATS.md section 6)
            java.nio.file.Files.write(target.resolve("arrow-answers-sample.arrow"), bytes);
        }
    }

    @Test
    void recordsKeepAKeyLeftOutApartFromANull() throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("rank", 1);
        r1.put("date", "2020-03-16");
        r1.put("pnl", -1250000.5);
        r1.put("from", "Rates");
        r1.put("marginalPer1m", null);
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("rank", 2);
        r2.put("date", "2020-03-12");
        r2.put("pnl", -980000.0);
        r2.put("marginalPer1m", 12.5);
        r1.put("partial", true);
        r2.put("partial", false);
        a.put("var", 1.2e6);
        a.put("rows", List.of(r1, r2));
        byte[] bytes = ArrowAnswers.encode(a, "records", "rows").orElseThrow();
        JsonNode back = rebuilt(bytes);
        assertThat(back.get("rows").get(1).has("from")).isFalse();
        assertThat(back.get("rows").get(0).get("marginalPer1m").isNull()).isTrue();
        assertSame(back, a);
    }

    @Test
    void parallelArraysOfAPathRoundTrip() throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("var", 10.0);
        a.put("series", new long[] {-5, 7, 0, 4_000_000_000L});
        a.put("scenarioDates", List.of("2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05"));
        a.put("rows", List.of(Map.of("rank", 1)));
        byte[] bytes = ArrowAnswers.encode(a, "arrays", "series,scenarioDates").orElseThrow();
        assertSame(rebuilt(bytes), a);
    }

    @Test
    void whatArrowIsNotWrittenWithGoesAsJson() {
        assertThat(ArrowAnswers.encode(Map.of("columns", List.of(Map.of("name", "ok")), "rows", List.of(List.of(true), List.of("yes"))), "rows", "rows")).isEmpty();
        assertThat(ArrowAnswers.encode(Map.of("columns", List.of(Map.of("name", "o")), "rows", List.of(List.of(Map.of("a", 1L)))), "rows", "rows")).isEmpty();
        assertThat(ArrowAnswers.encode(Map.of("columns", List.of(Map.of("name", "x")), "rows", List.of(List.of("a"), List.of(1L))), "rows", "rows")).isEmpty();
        assertThat(ArrowAnswers.encode(Map.of("columns", List.of(Map.of("name", "x")), "rows", List.of(List.of(Double.NaN))), "rows", "rows")).isEmpty();
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);
        assertThat(ArrowAnswers.encode(Map.of("rows", List.of(withNull, Map.of("b", 1L))), "records", "rows")).isEmpty();
        assertThat(ArrowAnswers.encode(Map.of("series", List.of(1L), "scenarioDates", List.of("a", "b")), "arrays", "series,scenarioDates")).isEmpty();
    }

    @Test
    void negotiationAnswersArrowOnlyWhenAsked() {
        Map<String, Object> a = Map.of("columns", List.of(Map.of("name", "x")), "rows", List.of(List.of(1L)));
        assertThat(ArrowAnswers.negotiate("application/json", a, "rows", "rows").getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(ArrowAnswers.negotiate(null, a, "rows", "rows").getBody()).isSameAs(a);
        var arrow = ArrowAnswers.negotiate("application/vnd.apache.arrow.stream, application/json;q=0.9", a, "rows", "rows");
        assertThat(arrow.getHeaders().getContentType().toString()).isEqualTo(ArrowAnswers.MEDIA);
        assertThat(arrow.getBody()).isInstanceOf(byte[].class);
        var refused = ArrowAnswers.negotiate(ArrowAnswers.MEDIA, Map.of("columns", List.of(Map.of("name", "x")), "rows", List.of(List.of(List.of(1L)))), "rows", "rows");
        assertThat(refused.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }
}
