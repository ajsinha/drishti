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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Layer 2 of About this page (docs/architecture/CONTEXT_HELP.md): what each number on the page means, for exactly the fields the
 * page shows. A hidden field's definition is given and the meaning of its values is not; a derived kind explains its own
 * fields from its formula; {@code ?panel=} narrows the answer to one panel.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk,genomics,trading",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=var99,significance",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:target/explainglossary-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class ExplainGlossaryTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired com.ash.drishti.engine.source.SourceRouter router;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode explain(String role, String path) throws Exception {
        String auth = "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
        return json.readTree(mvc.perform(get("/api/v1/views/" + path + "/explain").header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode term(JsonNode e, String key) {
        for (JsonNode t : e.path("glossary")) {
            if (key.equals(t.path("key").asText())) {
                return t;
            }
        }
        return null;
    }

    @Test
    void theKeyFiguresOfAVarPageAreExplainedFromThePacksVocabulary() throws Exception {
        JsonNode e = explain("full", "var/VAR-COMM");
        JsonNode v = term(e, "var99");
        assertThat(v).isNotNull();
        assertThat(v.path("term").asText()).isEqualTo("Value at risk, 99%, 1 day");
        assertThat(v.path("unit").asText()).isEqualTo("USD");
        assertThat(v.path("sign").asText()).isEqualTo("A loss, written as a positive number.");
        assertThat(v.path("origin").asText()).isEqualTo("market-risk:vocabulary.var99");
        assertThat(v.path("shownIn").toString()).contains("strip");
        assertThat(v.path("label").asText()).isNotBlank();
        assertThat(term(e, "es975").path("term").asText()).isEqualTo("Expected shortfall, 97.5%");
        assertThat(term(e, "exceptions").path("note").asText()).startsWith("0-4 is the Basel green zone");
    }

    @Test
    void aFieldTheDocumentHasButNoPanelShowsGetsNoEntry() throws Exception {
        JsonNode e = explain("full", "var/VAR-COMM");
        for (JsonNode t : e.path("glossary")) {                        // every entry names where the page shows it
            assertThat(t.path("shownIn")).isNotEmpty();
        }
        assertThat(term(e, "scenarioPnl")).isNull();                   // authored, but not a cell of this page
    }

    @Test
    void aTableColumnsEntryIsKeyedByItsFieldPathWithoutArraySteps() throws Exception {
        JsonNode e = explain("full", "variant/VRNT-APOE-E4");
        JsonNode stars = term(e, "evidence.stars");
        assertThat(stars.path("label").asText()).isEqualTo("Review stars");
        assertThat(stars.path("shownIn").toString()).isEqualTo("[\"evidence\"]");
        assertThat(stars.path("unit").asText()).isEqualTo("stars");
        assertThat(term(e, "evidence.date").path("origin").asText()).isEqualTo("core:vocabulary.date");   // no pack word: the core vocabulary
    }

    @Test
    void aHiddenFieldKeepsItsDefinitionButLosesTheMeaningOfItsValues() throws Exception {
        JsonNode full = explain("full", "variant/VRNT-APOE-E4");
        JsonNode sf = term(full, "significance");
        assertThat(sf).isNotNull();
        assertThat(sf.has("masked")).isFalse();
        assertThat(sf.path("values").path("Risk factor").asText()).startsWith("Raises the risk");     // the meaning of the value on the page

        JsonNode masked = explain("masked", "variant/VRNT-APOE-E4");
        JsonNode sm = term(masked, "significance");
        assertThat(sm).isNotNull();
        assertThat(sm.path("masked").asBoolean()).isTrue();
        assertThat(sm.path("term").asText()).isEqualTo("Clinical significance");                    // the definition is not a value
        assertThat(sm.has("values")).isFalse();
        assertThat(masked.toString()).doesNotContain("Raises the risk without causing disease alone");
        assertThat(masked.path("glossary").toString()).doesNotContain("Risk factor");
    }

    @Test
    void aMaskedKeyFigureIsFlaggedAndItsValueIsNowhereInTheGlossary() throws Exception {
        JsonNode e = explain("masked", "var/VAR-COMM");
        JsonNode v = term(e, "var99");
        assertThat(v.path("masked").asBoolean()).isTrue();
        assertThat(e.path("glossary").toString()).doesNotContain("11.0m", "10959317");
    }

    @Test
    void aDerivedKindExplainsItsOwnFieldsFromTheirFormula() throws Exception {
        JsonNode e = explain("full", "desk-pnl/DESK-RATES");
        JsonNode mtm = term(e, "mtm");
        assertThat(mtm).isNotNull();
        assertThat(mtm.path("formula").asText()).startsWith("sum $.mtm");
        assertThat(mtm.path("means").asText()).contains("Sum of mtm over the trade records");
        assertThat(mtm.path("origin").asText()).isEqualTo("derived:desk-pnl.fields.mtm");
        assertThat(term(e, "tradeCount").path("formula").asText()).startsWith("count");
    }

    @Test
    void theRouterDescribesADerivedFieldWithoutKnowingTheSourceSoPackLintSeesIt() {
        // pack lint asks by kind alone (no provenance): the derived source's note is found among the sources that serve the kind
        assertThat(router.describeField("desk-pnl", "mtm")).get().satisfies(n -> assertThat(n.formula()).startsWith("sum $.mtm"));
        assertThat(router.describeField("desk-pnl", "nothing")).isEmpty();
        assertThat(router.describeField("var", "var99")).isEmpty();
    }

    @Test
    void panelNarrowsTheGlossaryToTheFieldsThatPanelShowsAndAnUnknownPanelIs4006() throws Exception {
        String auth = "Bearer " + tokens.mint("u-full", List.of("full"), 300);
        String url = "/api/v1/views/variant/VRNT-APOE-E4/explain";
        JsonNode narrowed = json.readTree(mvc.perform(get(url + "?panel=evidence").header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(narrowed.path("glossary").findValuesAsText("key")).containsExactlyInAnyOrder("evidence.source", "evidence.assertion", "evidence.stars", "evidence.date");
        assertThat(explain("full", "variant/VRNT-APOE-E4").path("glossary").size()).isGreaterThan(narrowed.path("glossary").size());
        mvc.perform(get(url + "?panel=nope").header("Authorization", auth)).andExpect(status().isNotFound());
    }
}
