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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The annotated example in RACHANA_REFERENCE.md is real: it is taken from the document as written, previewed against
 * the trading pack's MX-20000001, and every panel must render with data.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading"})
@AutoConfigureMockMvc
class RachanaReferenceExampleTest {

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    /** The YAML block under "A complete example, annotated". */
    static String example() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("../docs/RACHANA_REFERENCE.md"));
        int section = lines.indexOf("## A complete example, annotated");
        int start = -1;
        List<String> out = new ArrayList<>();
        for (int i = section; i < lines.size(); i++) {
            if (start < 0 && lines.get(i).equals("```yaml")) {
                start = i;
            } else if (start >= 0 && lines.get(i).equals("```")) {
                return String.join("\n", out) + "\n";
            } else if (start >= 0) {
                out.add(lines.get(i));
            }
        }
        throw new IllegalStateException("no annotated example found in RACHANA_REFERENCE.md");
    }

    @Test
    void theAnnotatedExampleRendersEveryPanel() throws Exception {
        String body = json.writeValueAsString(Map.of("yaml", example(), "kind", "trade", "id", "MX-20000001"));
        String out = mvc.perform(post("/api/v1/studio/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode v = json.readTree(out);
        assertThat(v.path("provenance").path("layout").asText()).startsWith("Sutra swap-annotated v1");
        assertThat(v.path("title").path("pill").asText()).isEqualTo("Rates · Interest rate swap (fixed/float)");
        assertThat(v.path("strip")).hasSize(8);
        assertThat(v.path("strip").get(0).path("label").asText()).isEqualTo("Notional");      // label from the field name
        List<String> ids = new ArrayList<>();
        for (JsonNode p : v.path("panels")) {
            ids.add(p.path("id").asText());
            assertThat(p.path("empty").asBoolean()).as(p.path("id").asText() + " " + p.path("error").asText()).isFalse();
            assertThat(p.path("error").isMissingNode() || p.path("error").isNull()).as(p.path("id") + ": " + p.path("error")).isTrue();
        }
        assertThat(ids).containsExactly("terms", "legs", "cashflows", "schedule", "operations", "lifecycle", "notes", "built", "pnl", "curve",
                "dv01", "dv01use", "refs");
        assertThat(v.path("keys").toString()).contains("F7", "F8", "F9");
    }
}
