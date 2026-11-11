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
package com.ash.drishti.engine.shape;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaSamplerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["tradeId","notional"],
             "properties":{
               "tradeId":{"type":"string","examples":["IRS-1","IRS-2"]},
               "side":{"enum":["Pay","Receive"]},
               "notional":{"type":"integer","minimum":1000,"maximum":5000},
               "rate":{"type":"number","minimum":0.01,"maximum":0.09},
               "live":{"type":"boolean"},
               "tradeDate":{"type":"string","format":"date"},
               "at":{"type":"string","format":"date-time"},
               "mail":{"type":"string","format":"email"},
               "code":{"type":"string","minLength":6,"maxLength":8},
               "legs":{"type":"array","minItems":2,"maxItems":3,"items":{"$ref":"#/$defs/leg"}},
               "x-drishti":{"type":"string"}
             },
             "$defs":{"leg":{"type":"object","properties":{"ccy":{"enum":["USD","EUR"]},"amount":{"type":"number","minimum":0,"maximum":10}}}}}
            """;

    private static List<JsonNode> docs(String schema, int n) throws Exception {
        return new SchemaSampler(JSON.readTree(schema)).documents(n);
    }

    @Test
    void patternOneOfPatternPropertiesPrefixItemsAndRecursionAreHonoured() throws Exception {
        for (JsonNode d : docs("{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^T-[0-9]{4}$\"},"
                + "\"ccy\":{\"type\":\"string\",\"pattern\":\"^(USD|EUR|[A-Z]{3}-\\\\d+)$\"}}}", 12)) {
            assertThat(d.path("id").asText()).matches("T-[0-9]{4}");
            assertThat(d.path("ccy").asText()).matches("USD|EUR|[A-Z]{3}-\\d+");
        }
        for (JsonNode d : docs("{\"type\":\"object\",\"required\":[\"v\"],\"properties\":{\"v\":{\"oneOf\":[{\"type\":\"integer\",\"minimum\":10},"
                + "{\"type\":\"string\",\"pattern\":\"^x+$\"}]}}}", 12)) {
            JsonNode v = d.path("v");
            assertThat(v.isIntegralNumber() && v.asInt() >= 10 || v.isTextual() && v.asText().matches("x+")).as(d.toString()).isTrue();
        }
        for (JsonNode d : docs("{\"type\":\"object\",\"patternProperties\":{\"^px_[A-Z]{3}$\":{\"type\":\"number\",\"minimum\":0}},"
                + "\"additionalProperties\":false,\"minProperties\":2}", 6)) {
            assertThat(d.size()).isGreaterThanOrEqualTo(2);
            d.fieldNames().forEachRemaining(k -> assertThat(k).matches("px_[A-Z]{3}"));
        }
        for (JsonNode d : docs("{\"type\":\"object\",\"required\":[\"pt\"],\"properties\":{\"pt\":{\"type\":\"array\",\"prefixItems\":[{\"type\":\"number\"},"
                + "{\"type\":\"string\"}],\"items\":false,\"minItems\":2},\"no\":false}}", 6)) {
            assertThat(d.path("pt")).hasSize(2);
            assertThat(d.path("pt").get(0).isNumber()).isTrue();
            assertThat(d.path("pt").get(1).isTextual()).isTrue();
            assertThat(d.has("no")).isFalse();
        }
        for (JsonNode d : docs("{\"$defs\":{\"node\":{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"children\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/node\"}}}}},\"$ref\":\"#/$defs/node\"}", 3)) {
            Deque<JsonNode> todo = new ArrayDeque<>(List.of(d));
            while (!todo.isEmpty()) {
                JsonNode node = todo.pop();
                assertThat(node.has("name")).as("every level has the required name").isTrue();
                node.path("children").forEach(todo::push);
            }
        }
    }

    @Test
    void aDanglingOrRemoteRefIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> docs("{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/missing\"}}}", 1))
                .hasMessageContaining("points at nothing");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> docs("{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"http://example.invalid/x.json\"}}}", 1))
                .hasMessageContaining("not inside the schema");
    }

    @Test
    void syntheticDocumentsValidateAgainstTheirSchemaAndVary() throws Exception {
        JsonNode schema = JSON.readTree(SCHEMA);
        MiniSchemaValidator validator = new MiniSchemaValidator(schema);
        List<JsonNode> docs = new SchemaSampler(schema).documents(8);
        assertThat(docs).hasSize(8);
        for (JsonNode d : docs) {
            assertThat(validator.validate(d)).as(d.toString()).isEmpty();
            assertThat(d.path("tradeDate").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(d.path("mail").asText()).endsWith("@example.com");
            assertThat(d.path("code").asText().length()).isBetween(6, 8);
            assertThat(d.path("legs").size()).isBetween(2, 3);
        }
        assertThat(docs.stream().map(d -> d.path("tradeId").asText()).distinct().count()).isEqualTo(2);       // from the examples
        assertThat(docs.stream().map(d -> d.path("side").asText()).distinct().count()).isEqualTo(2);          // from the enum
        assertThat(docs.stream().map(d -> d.path("notional").asInt()).distinct().count()).isGreaterThan(3);   // within min/max
        assertThat(new SchemaSampler(schema).documents(8)).isEqualTo(docs);                                    // deterministic
    }

    @Test
    void schemaValuesBeyondTheLimitsAreClampedWithAProblemAndNeverAllocated() throws Exception {
        JsonNode schema = JSON.readTree("""
                {"type":"object","properties":{
                  "rows":{"type":"array","minItems":2000000000,"items":{"type":"string"}},
                  "name":{"type":"string","minLength":2000000000},
                  "nest":{"type":"array","minItems":50,"items":{"type":"array","minItems":50,"items":{"type":"array","minItems":50,"items":{"type":"string","minLength":900}}}}}}
                """);
        SchemaSampler.Limits limits = new SchemaSampler.Limits(20, 100, 8, 64 * 1024);
        SchemaSampler sampler = new SchemaSampler(schema, limits);
        List<JsonNode> docs = sampler.documents(2);
        assertThat(docs.get(0).path("rows")).hasSize(20);
        assertThat(docs.get(0).path("name").asText()).hasSize(100);
        assertThat(docs.get(0).toString().length()).isLessThan(64 * 1024 + 4096);
        assertThat(sampler.problems()).anyMatch(p -> p.contains("2000000000 items")).anyMatch(p -> p.contains("2000000000 characters"))
                .anyMatch(p -> p.contains("sample-max-kb"));
    }
}
