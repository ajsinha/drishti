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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The gleif pack loads beside us-equities and draws its screens from the committed GLEIF data, read as the user's cluster reads it: the
 * demo connector off, the documents in the Delta lake built from data/testdata/gleif ({@link PublicPacksOnLakes}). Every example it
 * advertises opens a view built by its Sutra with every panel filled and no missing link; a click on a child opens the child's key
 * facts beside the children table; a group's tree holds its members level under level; a us-equities company and its LEI record link
 * both ways; an LEI opens, and the type-ahead and the search form find an entity by its LEI and its legal name.
 */
class GleifPackTest extends PublicPacksOnLakes {

    static final Path PACK = Path.of("../config/packs/gleif");
    static final String JPM = "8I5DZWZKVSZI1NUHU748";
    static final String APPLE = "HWUPKR0MPOU8FGXBT394";

    @Autowired ViewPipeline pipeline;
    @Autowired com.ash.drishti.engine.command.CommandParser commands;

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
                    .flatMap(p -> ((PanelData.Links) p.data()).links().stream())
                    // world-economy's graph field JURISDICTION links every pack's JURISDICTION to its own jurisdiction kind, which
                    // holds countries, not GLEIF's subdivisions (US-DE): the gleif pack's own links are the ones checked here
                    .filter(l -> l.link() == null || !"jurisdiction".equals(l.link().kind())).toList())
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

    @Test
    void anLeiOpensAndTheTypeAheadFindsItByLeiAndLegalName() throws Exception {
        assertThat(commands.require("LEI " + JPM).kind()).isEqualTo("lei-entity");
        assertThat(view("lei-entity", JPM).path("provenance").path("sutra").asText()).isEqualTo("lei-entity");
        assertThat(view("lei-group", "ALL").path("panels").size()).isPositive();             // LEIG ALL
        assertThat(suggested("LEI " + JPM.substring(0, 8))).contains(JPM);
        assertThat(suggested("LEI JPMORGAN CHASE & CO")).contains(JPM);                  // by legal name: the lake's type-ahead columns
        assertThat(suggested("LEIG Ford Motor")).isNotEmpty();
    }

    @Test
    void theSearchFormFindsAnEntityByItsLegalName() throws Exception {
        assertThat(formSearch("gleif", "lei-entity", ("{'path':'LEI','op':'in','values':['" + APPLE + "']}").replace('\'', '"'))).containsExactly(APPLE);
        assertThat(formSearch("gleif", "lei-entity", "{'path':'NAME','op':'contains','value':'Apple Inc'}".replace('\'', '"'))).contains(APPLE);
    }
}
