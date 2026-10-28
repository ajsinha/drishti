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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A hierarchy built from the trades' columns over the API (2.9.5, VAR_GUIDE.md section 16), on {@link VarHierarchyFixture}: the limit
 * letters {@code LL} ({@code LIMIT_LETTER > DESK}, configured by {@code drishti.var.hierarchies}) beside MGMT and LEGAL from the rows. Its
 * tree and titles; a letter holding part of a book is exactly the sum of its trades ({@code LL:LL-A} is {@code TRD:T1,T3,T5}); a node
 * by its path and by {@code NODE:}; its limits from {@code var-limit} (none: blank); a position split between letters, said; a row with an
 * empty letter refused (DRS-5211) and a limit naming no node said (DRS-5212); the official records of every hierarchy; the word in the
 * search hints and in a saved basket; a portfolio defined on a letter.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=var-limit",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,LIMIT_LETTER,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.var-result=var-engine",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.hierarchies.LL.title=Limit letters", "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK",
        "drishti.var.hierarchies.LL.word=LL", "drishti.var.hierarchies.LL.limits=var-limit",
        "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarHierarchyApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-hier-lake-" + System.nanoTime())).toAbsolutePath();
        VarHierarchyFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarOfficial official;
    final ObjectMapper json = new ObjectMapper();

    private String rita() {
        return "Bearer " + tokens.mint("rita", List.of("risk"), 300);
    }

    private JsonNode get(String url, String... params) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization", rita());
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode scope(String scope) throws Exception {
        return get("/api/v1/var/scope", "scope", scope);
    }

    @Test
    void theLetterTreeItsTitleAndWords() throws Exception {
        JsonNode t = get("/api/v1/var/tree", "hierarchy", "LL");
        assertThat(t.get("hierarchy").asText()).isEqualTo("LL");
        assertThat(t.get("title").asText()).isEqualTo("Limit letters");
        List<String> ids = new ArrayList<>();
        t.get("nodes").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).containsExactly("LL~BANK", "LL~LL-A", "LL~LL-A/DESK-X", "LL~LL-A/DESK-Y", "LL~LL-B", "LL~LL-B/DESK-X");
        List<String> hs = new ArrayList<>();
        t.get("hierarchies").forEach(h -> hs.add(h.asText()));
        assertThat(hs).containsExactly("LL", "LEGAL", "MGMT");         // the configured first, then the data's
        assertThat(t.at("/hierarchyInfo/0/title").asText()).isEqualTo("Limit letters");
        assertThat(t.at("/hierarchyInfo/0/word").asText()).isEqualTo("LL");
        // limits: from var-limit; a node without a row has none (null, never zero)
        for (JsonNode n : t.get("nodes")) {
            switch (n.get("id").asText()) {
                case "LL~LL-A" -> assertThat(n.get("limit").asLong()).isEqualTo(700);
                case "LL~LL-A/DESK-Y" -> assertThat(n.get("limit").asLong()).isEqualTo(300);
                case "LL~LL-B" -> assertThat(n.get("limit").isNull()).isTrue();
                default -> { }
            }
        }
        // the MGMT tree is unchanged
        assertThat(get("/api/v1/var/tree").get("hierarchy").asText()).isEqualTo("MGMT");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/var/tree").param("hierarchy", "NOPE")
                .header("Authorization", rita())).andExpect(status().isNotFound());
    }

    @Test
    void aLetterIsExactlyItsTradesAndHoldsPartOfABook() throws Exception {
        JsonNode a = scope("LL:LL-A");
        assertThat(a.at("/provenance/scope").asText()).isEqualTo("LL:LL-A");
        assertThat(a.get("var").asDouble()).isEqualTo(scope("TRD:T1,T3,T5").get("var").asDouble());
        assertThat(a.get("es").asDouble()).isEqualTo(scope("TRD:T1,T3,T5").get("es").asDouble());
        assertThat(a.get("limit").asLong()).isEqualTo(700);
        JsonNode b = scope("LL:LL-B");
        assertThat(b.get("var").asDouble()).isEqualTo(360);           // -60 x U: the worst scenario is U's 6 (a loss of 360)
        assertThat(b.get("var").asDouble()).isEqualTo(scope("TRD:T2,T4").get("var").asDouble());
        assertThat(b.get("limit").isNull()).isTrue();
        assertThat(scope("LL:LL-A/DESK-Y").get("var").asDouble()).isEqualTo(scope("TRD:T5").get("var").asDouble());
        assertThat(scope("NODE:LL~LL-A/DESK-X").get("var").asDouble()).isEqualTo(scope("TRD:T1,T3").get("var").asDouble());
        // the letters split BOOK-1: together they are the book
        assertThat(scope("LL:LL-A + LL:LL-B").get("var").asDouble()).isEqualTo(scope("BANK").get("var").asDouble());
        assertThat(scope("BOOK:BOOK-1 & LL:LL-B").get("var").asDouble()).isEqualTo(scope("TRD:T2").get("var").asDouble());
        // a portfolio on a letter's node
        assertThat(scope("PORT:PF-LL-A").get("var").asDouble()).isEqualTo(a.get("var").asDouble());
    }

    @Test
    void aSplitPositionAnEmptyLetterAndALimitOfNoNodeAreSaid() throws Exception {
        JsonNode p1 = scope("POS:" + VarHierarchyFixture.POS_A + "@LL-A");
        assertThat(p1.get("var").asDouble()).isEqualTo(scope("TRD:T1").get("var").asDouble());
        assertThat(scope("POS:" + VarHierarchyFixture.POS_A + "@LL-B").get("var").asDouble()).isEqualTo(scope("TRD:T2").get("var").asDouble());
        JsonNode bank = scope("BANK");
        assertThat(bank.at("/provenance/warnings").toString()).contains("1 position had trades under two nodes").contains("DRS-5212")
                .contains("LL~LL-NOPE");
        assertThat(bank.at("/provenance/rejections").toString()).contains("DRS-5211").contains("T10").contains("LIMIT_LETTER is empty");
        assertThat(bank.at("/provenance/warnings").toString()).doesNotContain("MGMT~DESK-X");     // MGMT reads no limits kind
    }

    @Test
    void theOfficialRecordsHoldEveryHierarchysNodesAndTheWordIsSearchable() throws Exception {
        JsonNode cap = json.readTree(mvc.perform(post("/api/v1/var/official").param("asOf", VarHierarchyFixture.DAY.toString()).header("Authorization", "Bearer " + tokens.mint("ada", List.of("admin"), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("nodes").asInt()).as(cap.toString()).isEqualTo(9 + 6);
        JsonNode record = get("/api/v1/var/official");
        assertThat(record.path("nodes").path("LL~LL-A").path("var").asDouble()).isEqualTo(scope("LL:LL-A").get("var").asDouble());
        String letterCapture = record.path("nodes").path("LL~LL-A").path("captureId").asText();
        assertThat(scope("CAPTURE:" + letterCapture).get("var").asDouble()).isEqualTo(scope("LL:LL-A").get("var").asDouble());
        JsonNode hints = get("/api/v1/var/hints", "q", "LL-A");
        assertThat(hints.toString()).contains("LL:LL-A");
        mvc.perform(post("/api/v1/var/baskets").header("Authorization", rita()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"letters\",\"scope\":\"LL:LL-A - TRD:T5\"}")).andExpect(status().isOk());
        assertThat(scope("BASKET:rita/letters").get("var").asDouble()).isEqualTo(scope("TRD:T1,T3").get("var").asDouble());
    }
}
