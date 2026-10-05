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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * About this page is never weaker than the view (docs/architecture/CONTEXT_HELP.md, Masking and security): the answer is
 * derived from the view rebuilt for the caller, so a masked field's value is in no part of it, the fields hidden for the
 * caller are listed by name, a panel they may not open is named only as the page names it, and a Sutra {@code where} that
 * reads a masked field is not answered.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=trader,mtm,dv01,productType,counterparty",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.security.roles.nocurve.kinds[0]=trade", "drishti.security.roles.nocurve.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:target/explainmask-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class ExplainMaskTest {

    private static final String URL = "/api/v1/views/trade/IRS-48213/explain";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode explain(String role) throws Exception {
        String auth = "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
        return json.readTree(mvc.perform(get(URL).header("Authorization", auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void maskedFieldsAreListedByNameAndNoValueOfOneAppearsAnywhere() throws Exception {
        JsonNode e = explain("masked");
        List<String> labels = new ArrayList<>(e.path("layout").path("masked").findValuesAsText("label"));
        assertThat(labels).contains("MTM (USD)", "DV01 (USD)");
        for (JsonNode m : e.path("layout").path("masked")) {
            assertThat(m.path("panels")).isNotEmpty();
        }
        String text = e.toString();
        assertThat(text).doesNotContain("412580", "412,580", "22310", "22,310", "A. Shah");
        assertThat(text).doesNotContain("\"IRS\"");                    // the masked productType is not echoed either
    }

    @Test
    void aWhereOverAMaskedFieldIsNotAnsweredButTheRealChoiceIsStillExplained() throws Exception {
        JsonNode masked = explain("masked").path("layout");
        JsonNode full = explain("full").path("layout");
        assertThat(masked.path("sutra").path("name").asText()).isEqualTo(full.path("sutra").path("name").asText());   // the view's own choice
        boolean sawMasked = false;
        for (JsonNode c : masked.path("candidates")) {
            if (c.path("where").asText().contains("productType") && !c.path("chosen").asBoolean()) {
                assertThat(c.path("result").asText()).isIn("masked", "false");   // never a revealing "true" for a candidate not chosen
                sawMasked |= "masked".equals(c.path("result").asText());
            }
        }
        for (JsonNode c : full.path("candidates")) {
            assertThat(c.path("result").asText()).isNotEqualTo("masked");
        }
        assertThat(sawMasked || masked.path("candidates").size() > 0).isTrue();
        assertThat(full.has("masked")).isFalse();                     // a role with raw has nothing hidden
    }

    @Test
    void aPanelTheCallerMayNotOpenIsNamedByTitleAndKindOnly() throws Exception {
        JsonNode e = explain("nocurve");
        JsonNode denied = e.path("layout").path("noAccess");
        assertThat(denied).hasSize(1);
        assertThat(denied.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("title", "kind");
        assertThat(denied.get(0).path("kind").asText()).isEqualTo("curve");
        assertThat(denied.get(0).path("title").asText()).isEqualTo("USD-SOFR curve");   // what the page itself shows
        assertThat(e.path("next").path("panelKinds").toString()).doesNotContain("line");   // no badge for a panel they cannot open
        assertThat(e.path("layout").path("noData").findValuesAsText("id")).doesNotContain("curve");
        assertThat(e.path("layout").path("errors")).isEmpty();
        assertThat(explain("full").path("layout").has("noAccess")).isFalse();
    }
}
