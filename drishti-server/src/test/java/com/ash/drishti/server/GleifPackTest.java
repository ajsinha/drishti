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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The gleif pack loads beside us-equities and draws its screens from the committed GLEIF data: every example it advertises opens a
 * view built by its Sutra with every panel filled and no missing link; a click on a child opens the child's key facts beside the
 * children table; a group's tree holds its members level under level; a us-equities company and its LEI record link both ways.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=core,us-equities,gleif",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class GleifPackTest {

    static final Path PACK = Path.of("../config/packs/gleif");
    static final String JPM = "8I5DZWZKVSZI1NUHU748";
    static final String APPLE = "HWUPKR0MPOU8FGXBT394";

    @Autowired ViewPipeline pipeline;
    @Autowired com.ash.drishti.engine.command.CommandParser commands;
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private String auth() {
        return "Bearer " + tokens.mint("u-analyst", List.of("full"), 300);
    }

    private JsonNode view(String kind, String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/views/" + kind + "/" + id).header("Authorization", auth())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode click(String kind, String id, String panel, int row) throws Exception {
        String body = json.writeValueAsString(Map.of("panel", panel, "event", "open", "row", row, "selections", List.of()));
        return json.readTree(mvc.perform(post("/api/v1/views/" + kind + "/" + id + "/events").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode panel(JsonNode panels, String id) {
        for (JsonNode p : panels) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id + " in " + panels);
    }

    private static JsonNode field(JsonNode kv, String label) {
        for (JsonNode f : kv.path("data").path("fields")) {
            if (label.equals(f.path("label").asText())) {
                return f;
            }
        }
        throw new AssertionError("no field " + label + " in " + kv);
    }

    @Test
    void everyAdvertisedExampleOpensAFullViewByItsSutra() throws Exception {
        JsonNode manifest = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(PACK.resolve("pack.yaml")));
        assertThat(manifest.path("console").path("examples")).isNotEmpty();
        for (JsonNode ex : manifest.path("console").path("examples")) {
            EntityRef ref = commands.require(ex.get(0).asText());
            ViewModel v = pipeline.view(ref);
            assertThat(v.provenance().layout()).as(ref.toString()).startsWith("Sutra ");
            assertThat(v.panels()).as(ref.toString()).allSatisfy(p -> assertThat(p.empty()).as(ref + "/" + p.id()).isFalse());
            assertThat(v.panels().stream().filter(p -> p.data() instanceof PanelData.Links)
                    .flatMap(p -> ((PanelData.Links) p.data()).links().stream()).toList())
                    .as(ref + " links").allSatisfy(l -> assertThat(l.status()).as(ref + " -> " + l.text()).isNotEqualTo("missing"));
        }
    }

    @Test
    void anEntityPageHasItsFactsRenewalChildrenAndIdentifiers() throws Exception {
        JsonNode v = view("lei-entity", JPM);
        assertThat(v.path("provenance").path("sutra").asText()).isEqualTo("lei-entity");
        assertThat(field(panel(v.path("panels"), "card"), "LEI").path("text").asText()).isEqualTo(JPM);
        assertThat(field(panel(v.path("panels"), "card"), "Ownership group").path("link").path("kind").asText()).isEqualTo("lei-group");
        assertThat(panel(v.path("panels"), "children").path("data").path("rows").size()).isGreaterThanOrEqualTo(5);
        assertThat(field(panel(v.path("panels"), "identifiers"), "BICs").path("text").asText()).isNotBlank();
        assertThat(v.toString()).doesNotContain("\"child\"");                         // a child opens on a click, not before
    }

    @Test
    void aClickOnAChildOpensItsKeyFactsBeside() throws Exception {
        JsonNode v = view("lei-entity", JPM);
        String lei = panel(v.path("panels"), "children").path("data").path("rows").get(0).path("cells").get(3).path("text").asText();
        JsonNode r = click("lei-entity", JPM, "children", 0);
        assertThat(r.path("selection").path("payload").path("id").asText()).isEqualTo(lei).matches("[0-9A-Z]{20}");
        JsonNode kids = r.path("children").get(0);
        assertThat(kids.path("place").asText()).isEqualTo("detail-right");
        JsonNode card = kids.path("panels").get(0);
        assertThat(card.path("id").asText()).isEqualTo("child");
        assertThat(field(card, "LEI").path("text").asText()).isEqualTo(lei);
        assertThat(field(card, "Direct parent").path("text").asText()).isEqualTo(JPM);
    }

    @Test
    void aGroupHasItsTreeItsMembersAndTheCap() throws Exception {
        JsonNode v = view("lei-group", JPM);
        assertThat(v.path("provenance").path("sutra").asText()).isEqualTo("lei-group");
        JsonNode tree = panel(v.path("panels"), "tree").path("data").path("rows");
        assertThat(tree.size()).isEqualTo(1);                                            // the ultimate parent
        assertThat(tree.get(0).path("cells").get(2).path("text").asText()).isEqualTo(JPM);
        assertThat(tree.get(0).path("children").size()).isPositive();                   // its children, and theirs below them
        JsonNode members = panel(v.path("panels"), "members").path("data").path("rows");
        assertThat(members.size()).isBetween(10, 50);
        assertThat(field(panel(v.path("panels"), "facts"), "Cap").path("text").asText()).contains("GLEIF");
        JsonNode r = click("lei-group", JPM, "members", 1);
        assertThat(r.path("children").get(0).path("panels").get(0).path("id").asText()).isEqualTo("member");
        assertThat(view("lei-group", "ALL").path("provenance").path("sutra").asText()).isEqualTo("lei-browse");
        assertThat(view("lei-country", "ALL").path("provenance").path("sutra").asText()).isEqualTo("lei-countries");
        assertThat(view("lei-country", "US").path("provenance").path("sutra").asText()).isEqualTo("lei-country");
    }

    @Test
    void aUsEquitiesCompanyAndItsLeiRecordLinkBothWays() throws Exception {
        JsonNode company = view("us-company", "AAPL");
        JsonNode lei = field(panel(company.path("panels"), "card"), "LEI");
        assertThat(lei.path("text").asText()).isEqualTo(APPLE);
        assertThat(lei.path("link").path("kind").asText()).isEqualTo("lei-entity");
        assertThat(company.path("keys").toString()).contains("lei-entity");
        JsonNode entity = view("lei-entity", APPLE);
        JsonNode listed = field(panel(entity.path("panels"), "listed"), "Company");
        assertThat(listed.path("link").path("kind").asText()).isEqualTo("us-company");
        assertThat(listed.path("link").path("id").asText()).isEqualTo("AAPL");
        assertThat(view("lei-relationship", "LEIREL-" + panel(view("lei-entity", JPM).path("panels"), "children").path("data").path("rows")
                .get(0).path("cells").get(3).path("text").asText() + "-DIRECT").path("provenance").path("sutra").asText()).isEqualTo("lei-relationship");
    }
}
