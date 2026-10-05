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

import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Layer 1 of About this page (docs/architecture/CONTEXT_HELP.md): the packs' authored text for a kind, rendered over the
 * document the caller may see, so a field hidden for a role reads as the mask in the text and nothing derived from it leaks.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk,genomics",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=var99,limit,significance",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:target/explainabout-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class ExplainAboutTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AboutCatalog catalog;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode explain(String role, String path) throws Exception {
        String auth = "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
        return json.readTree(mvc.perform(get("/api/v1/views/" + path + "/explain").header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void theShippedAboutFilesLoadWithNoProblem() {
        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.forKind("var")).isPresent();
        assertThat(catalog.forKind("variant")).isPresent();
    }

    @Test
    void aVarPageIsDescribedInTheDesksTermsWithThePacksNameAndTheSutrasPack() throws Exception {
        JsonNode e = explain("full", "var/VAR-COMM");
        JsonNode about = e.path("about");
        assertThat(about.path("pack").path("name").asText()).isEqualTo("market-risk");
        assertThat(about.path("kindTitle").asText()).isEqualTo("Value-at-risk result");
        assertThat(about.path("text").asText()).startsWith("VAR-COMM is a 1-day 99% historical VaR for DESK-COMM: 11.0m USD, 58% of its 18.9m limit, with 1 exception(s)");
        assertThat(e.path("layout").path("sutra").path("pack").asText()).isEqualTo("market-risk");
        assertThat(about.path("panels").findValuesAsText("id")).contains("scenarios");
        assertThat(about.path("panels").findValuesAsText("description").toString()).contains("The markers are -VaR (11.0m)");
    }

    @Test
    void aVariantPageIsDescribedInTheGenesTerms() throws Exception {
        String text = explain("full", "variant/VRNT-APOE-E4").path("about").path("text").asText();
        assertThat(text).isEqualTo("VRNT-APOE-E4 is a missense change in APOE (p.Cys130Arg, c.388T>C); it is classified \"Risk factor\" for Late-onset Alzheimer disease.");
    }

    @Test
    void maskedFieldsReadAsTheMaskInTheTextAndNoValueOrMagnitudeOfOneAppearsAnywhere() throws Exception {
        JsonNode e = explain("masked", "var/VAR-COMM");
        String text = e.path("about").path("text").asText();
        assertThat(text).startsWith("VAR-COMM is a 1-day 99% historical VaR for DESK-COMM: ••• USD");
        assertThat(text).doesNotContain("11.0m", "58%", "18.9m", "10959317", "18940000");
        assertThat(e.toString()).doesNotContain("11.0m", "10959317", "18940000", "18.9m");
        assertThat(e.path("about").path("panels").toString()).doesNotContain("11.0m");   // the panel's text reads the mask too
        String variant = explain("masked", "variant/VRNT-APOE-E4").path("about").path("text").asText();
        assertThat(variant).contains("classified \"•••\"").doesNotContain("Risk factor");
    }
}
