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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The basket sheet's API (VAR_GUIDE.md section 3.5) over the tiny hand-computed lake of {@link VarLakeFixture}: every cell checked by the
 * server with the reason in words, the minus rule (a leading "-" or the Op column; a desk taken away takes its trades whichever part added
 * them; a minus that takes nothing away flagged), Start from the whole bank, 10,000 ids in a column checked, saved as lists and read back
 * into the sheet, entitlements (outside the view, a masked counterparty, aggregate only), an administrator's edit (saved under its owner,
 * audited, the owner told), a portfolio opened as a copy, and the workbook.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-result=var-engine",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=COUNTERPARTY",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.desk-y.kinds[0]=*", "drishti.security.roles.desk-y.raw=true", "drishti.var.access.desk-y[MGMT~DESK-Y]=positions",
        "drishti.security.roles.division-head.kinds[0]=*", "drishti.security.roles.division-head.raw=true",
        "drishti.var.access.division-head[MGMT~DESK-X]=aggregate",
        "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarBasketSheetApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-sheet-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired com.ash.drishti.identity.AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode check(Object sheet, String auth) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/risk/var/basket/sheet").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(sheet))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private double var(String scope) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/risk/var/scope").param("scope", scope).header("Authorization", as("rita", "risk")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("var").asDouble();
    }

    private static Map<String, Object> sheet(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static JsonNode cell(JsonNode answer, String column, int row) {
        for (JsonNode c : answer.get("cells")) {
            if (c.get("column").asText().equals(column) && c.get("row").asInt() == row) {
                return c;
            }
        }
        return null;
    }

    @Test
    void everyCellIsCheckedAndTheFiguresAreTheEnginesOwn() throws Exception {
        JsonNode a = check(sheet("op", List.of("+", "+", "+", "+"), "TRD", List.of("T1", "T2", "T6", "10000000"), "DESK", List.of("", "", "DESK-Y", "")),
                as("rita", "risk"));
        assertThat(a.get("found").asInt()).isEqualTo(3);
        assertThat(a.get("notFound").asInt()).isEqualTo(2);
        assertThat(cell(a, "TRD", 2).get("reason").asText()).startsWith("TRD:T6 is not live on 2026-09-30").contains("no P&L vector");
        assertThat(cell(a, "TRD", 3).get("reason").asText()).contains("TRD:10000000 is not a live trade on 2026-09-30");
        assertThat(cell(a, "TRD", 3).get("status").asText()).isEqualTo("not-found");
        assertThat(cell(a, "TRD", 0)).isNull();                                   // found: no word needed
        double v = var("TRD:T1,T2 + DESK:DESK-Y");
        assertThat(a.get("var").asDouble()).isEqualTo(v);
        assertThat(a.get("trades").asInt()).isEqualTo(3);
        assertThat(a.get("sumOfParts").asDouble()).isEqualTo(1000 + 240 + 450);  // each part alone
        assertThat(a.get("diversification").asDouble()).isEqualTo(1690 - v);
        assertThat(a.get("scope").asText()).isEqualTo("TRD:T1,T2,T6,10000000 + DESK:DESK-Y");   // every id kept; the figures leave the not found out
        assertThat(a.get("columns").findValuesAsText("key")).containsExactly("TRD", "POS", "PORT", "BOOK", "DESK", "CPTY");
        // a cell that is not one id says so; an unknown column is refused in words
        JsonNode bad = check(sheet("TRD", List.of("T1 T2")), as("rita", "risk"));
        assertThat(cell(bad, "TRD", 0).get("reason").asText()).contains("one id a cell");
        mvc.perform(post("/api/v1/risk/var/basket/sheet").header("Authorization", as("rita", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"TEAM\":[\"X\"]}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("no column TEAM")));
        // an empty sheet: no figures, said in words
        JsonNode empty = check(sheet(), as("rita", "risk"));
        assertThat(empty.get("empty").asBoolean()).isTrue();
        assertThat(empty.get("notice").asText()).contains("the basket is empty");
    }

    @Test
    void theMinusRule() throws Exception {
        // the Op column, and a leading "-" on an id: the same basket, the scope grammar's A - B
        JsonNode op = check(sheet("op", List.of("+", "-"), "DESK", List.of("DESK-X", ""), "TRD", List.of("", "T1")), as("rita", "risk"));
        JsonNode lead = check(sheet("DESK", List.of("DESK-X"), "TRD", List.of("-T1")), as("rita", "risk"));
        double v = var("DESK:DESK-X - TRD:T1");
        assertThat(op.get("var").asDouble()).isEqualTo(v);
        assertThat(lead.get("var").asDouble()).isEqualTo(v);
        assertThat(lead.get("scope").asText()).isEqualTo("DESK:DESK-X - TRD:T1");
        assertThat(lead.get("takenAway").asInt()).isEqualTo(1);
        // a minus on a desk takes away all its trades, whichever part added them
        JsonNode desk = check(sheet("TRD", List.of("T1", "T5"), "DESK", List.of("-DESK-X", "")), as("rita", "risk"));
        assertThat(desk.get("var").asDouble()).isEqualTo(450);
        assertThat(desk.get("trades").asInt()).isEqualTo(1);
        // a minus that takes nothing away is flagged in words, and changes nothing
        JsonNode none = check(sheet("DESK", List.of("DESK-X"), "TRD", List.of("−T5")), as("rita", "risk"));
        assertThat(cell(none, "TRD", 0).get("status").asText()).isEqualTo("takes-nothing");
        assertThat(cell(none, "TRD", 0).get("reason").asText()).isEqualTo("TRD:T5 takes nothing away: none of its 1 trade is in a part added");
        assertThat(none.get("var").asDouble()).isEqualTo(390);
        assertThat(none.get("takesNothing").asInt()).isEqualTo(1);
    }

    @Test
    void startFromTheWholeBank() throws Exception {
        JsonNode a = check(sheet("wholeBank", true, "DESK", List.of("-DESK-Y"), "TRD", List.of("T1")), as("rita", "risk"));
        assertThat(a.get("scope").asText()).isEqualTo("BANK - DESK:DESK-Y");
        assertThat(a.get("var").asDouble()).isEqualTo(390);
        assertThat(cell(a, "TRD", 0).get("status").asText()).isEqualTo("adds-nothing");
        assertThat(cell(a, "TRD", 0).get("reason").asText()).contains("Start from the whole bank already holds");
        assertThat(a.get("sumOfParts").isNull()).isTrue();                      // the bank has no parts to add up
        // the whole bank alone is the bank
        assertThat(check(sheet("wholeBank", true), as("rita", "risk")).get("var").asDouble()).isEqualTo(540);
    }

    @Test
    void tenThousandIdsInAColumnAreCheckedSavedAndReadBackIntoTheSheet() throws Exception {
        List<String> ids = new ArrayList<>();
        ids.add("T1");
        ids.add("T2");
        for (int i = 0; ids.size() < 10_000; i++) {
            ids.add(String.format("MX-%08d", i));
        }
        String rita = as("rita", "risk");
        long t0 = System.nanoTime();
        JsonNode a = check(sheet("TRD", ids), rita);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(a.get("found").asInt()).isEqualTo(2);
        assertThat(a.get("notFound").asInt()).isEqualTo(9_998);
        assertThat(a.get("var").asDouble()).isEqualTo(600);
        assertThat(ms).as("10,000 ids checked in %d ms", ms).isLessThan(30_000);
        // saved as lists, not one long scope text; the basket computes; it opens back into the sheet column by column
        JsonNode saved = json.readTree(mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "big", "sheet", sheet("TRD", ids))))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(saved.get("parts").asInt()).isEqualTo(10_000);
        assertThat(saved.at("/sheet/TRD").size()).isEqualTo(10_000);
        assertThat(var("BASKET:rita/big")).isEqualTo(600);
        JsonNode open = json.readTree(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "BASKET:rita/big").header("Authorization", rita))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(open.at("/sheet/TRD").size()).isEqualTo(10_000);
        assertThat(open.at("/sheet/TRD/9999").asText()).isEqualTo(ids.get(9_999));
        assertThat(open.at("/basket/editable").asBoolean()).isTrue();
        assertThat(open.at("/basket/fromSheet").asBoolean()).isTrue();
        // the list of baskets carries no sheet (opened one at a time)
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/risk/var/baskets").header("Authorization", rita)).andReturn().getResponse().getContentAsString());
        for (JsonNode b : list) {
            if (b.get("id").asText().equals("rita/big")) {
                assertThat(b.has("sheet")).isFalse();
                assertThat(b.get("fromSheet").asBoolean()).isTrue();
            }
        }
        // a column longer than a sheet holds is refused
        List<String> more = new ArrayList<>(ids);
        while (more.size() <= VarBasketSheet.MAX_ROWS) {
            more.add("X" + more.size());
        }
        mvc.perform(post("/api/v1/risk/var/basket/sheet").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(sheet("TRD", more)))).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void entitlementsDecideWhatACellFinds() throws Exception {
        // positions level on DESK-Y only: a trade of DESK-X is outside the view, said so; the rest computes
        JsonNode yan = check(sheet("TRD", List.of("T5", "T1")), as("yan", "desk-y"));
        assertThat(cell(yan, "TRD", 1).get("reason").asText()).isEqualTo("TRD:T1 is outside your view: your role does not see its trades");
        assertThat(yan.get("var").asDouble()).isEqualTo(450);
        // a masked counterparty may not select trades
        JsonNode mo = check(sheet("CPTY", List.of("CP-1")), as("mo", "masked"));
        assertThat(cell(mo, "CPTY", 0).get("reason").asText()).contains("masked for your role");
        // aggregate only on DESK-X: the desk computes, a trade of it may not be named one by one
        assertThat(check(sheet("DESK", List.of("DESK-X")), as("dora", "division-head")).get("var").asDouble()).isEqualTo(390);
        mvc.perform(post("/api/v1/risk/var/basket/sheet").header("Authorization", as("dora", "division-head")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"TRD\":[\"T1\"]}")).andExpect(status().isForbidden());
        // a role that may not open the vectors
        mvc.perform(post("/api/v1/risk/var/basket/sheet").header("Authorization", as("cara", "curves")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"DESK\":[\"DESK-X\"]}")).andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorEditsAnyonesBasketUnderItsOwnerAuditedAndTold() throws Exception {
        String rita = as("rita", "risk");
        String ola = as("ola", "risk");
        String ada = as("ada", "admin");
        mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "edit-me", "sheet", sheet("DESK", List.of("DESK-X")), "sharedWith", List.of("ola")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        // the owner edits: Save keeps the name as a new version; a stale version is refused
        mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "edit-me", "version", 1, "sheet", sheet("DESK", List.of("DESK-X"), "TRD", List.of("-T1")),
                        "sharedWith", List.of("ola"))))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "edit-me", "version", 1, "sheet", sheet("DESK", List.of("DESK-X"))))))
                .andExpect(status().isConflict());
        // someone it is shared with may not edit it (as if there were none to edit)
        mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", ola).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "edit-me", "owner", "rita", "sheet", sheet("DESK", List.of("DESK-Y"))))))
                .andExpect(status().isNotFound());
        JsonNode olaOpen = json.readTree(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "BASKET:rita/edit-me").header("Authorization", ola))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(olaOpen.at("/basket/editable").asBoolean()).isFalse();
        // an administrator edits it: saved under its owner, shared as before, audited, the owner told
        JsonNode edited = json.readTree(mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", ada).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "edit-me", "owner", "rita", "version", 2, "sheet", sheet("DESK", List.of("DESK-Y"))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(edited.get("id").asText()).isEqualTo("rita/edit-me");
        assertThat(edited.get("owner").asText()).isEqualTo("rita");
        assertThat(edited.get("version").asInt()).isEqualTo(3);
        assertThat(edited.get("editedBy").asText()).isEqualTo("ada");
        assertThat(edited.get("sharedWith").toString()).contains("ola");
        assertThat(var("BASKET:rita/edit-me")).isEqualTo(450);
        assertThat(audit.recent(50, "rita/edit-me")).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("var-basket-edit");
            assertThat(e.actor()).isEqualTo("ada");
            assertThat(e.detail()).startsWith("edited by administrator ada for rita").contains("version=3").contains("parts=1");
        });
        JsonNode inbox = json.readTree(mvc.perform(get("/api/v1/me/inbox").param("type", "notice").header("Authorization", rita))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(inbox.toString()).contains("rita/edit-me").contains("edited by administrator ada");
        // an administrator edits only a basket that exists
        mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", ada).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "nothing", "owner", "rita", "sheet", sheet("DESK", List.of("DESK-Y"))))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPortfolioOpensAsACopyAndAScopeOpensColumnByColumn() throws Exception {
        String rita = as("rita", "risk");
        JsonNode mix = json.readTree(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "PORT:PF-MIX").header("Authorization", rita))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(mix.at("/portfolio/id").asText()).isEqualTo("PF-MIX");
        assertThat(mix.at("/portfolio/note").asText()).contains("a copy you save as your own basket");
        assertThat(mix.at("/sheet/POS").toString()).contains(VarLakeFixture.POS_B);
        assertThat(mix.at("/sheet/TRD").toString()).contains("T4").contains("T5");
        // a rule portfolio has no columns of its own: the copy holds the portfolio
        JsonNode eur = json.readTree(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "PORT:PF-EUR").header("Authorization", rita))
                .andReturn().getResponse().getContentAsString());
        assertThat(eur.at("/sheet/PORT/0").asText()).isEqualTo("PF-EUR");
        // an ad-hoc scope: each kind in its column, the parts taken away below the parts added
        JsonNode adhoc = json.readTree(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "DESK:DESK-X + TRD:T5 - TRD:T1")
                .header("Authorization", rita)).andReturn().getResponse().getContentAsString());
        assertThat(adhoc.at("/sheet/op").toString()).isEqualTo("[\"+\",\"-\"]");
        assertThat(adhoc.at("/sheet/DESK/0").asText()).isEqualTo("DESK-X");
        assertThat(adhoc.at("/sheet/TRD").toString()).isEqualTo("[\"T5\",\"T1\"]");
        assertThat(check(adhoc.get("sheet"), rita).get("var").asDouble()).isEqualTo(var("DESK:DESK-X + TRD:T5 - TRD:T1"));
        JsonNode rule = json.readTree(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "DESK:DESK-X - WHERE:CURRENCY == 'EUR'")
                .header("Authorization", rita)).andReturn().getResponse().getContentAsString());
        assertThat(rule.get("unplaced").toString()).contains("WHERE:CURRENCY");
    }

    @Test
    void theSheetDownloadsAsAWorkbook() throws Exception {
        byte[] x = mvc.perform(post("/api/v1/risk/var/basket/sheet.xlsx").header("Authorization", as("rita", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "rates mix", "sheet", sheet("op", List.of("+", "-"), "DESK", List.of("DESK-X", ""),
                        "TRD", List.of("", "T1"))))))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", containsString("rates-mix.xlsx"))).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(x, 0, 2, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PK");
        String all = new String(x, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(all).contains("xl/worksheets/sheet2.xml");
    }
}
