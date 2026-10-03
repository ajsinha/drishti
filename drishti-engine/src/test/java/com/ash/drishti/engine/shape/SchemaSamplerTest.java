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
}
