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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The system baskets (VAR_GUIDE.md section 5.7) kept in the basket store, on the VaR test lake: created, edited and retired only with the
 * permission {@code var:system-baskets} (a role that lists it, or an administrator), audited, the owner told; checked on save (an unknown
 * node, a rule that does not read, a position the date does not hold, an id that exists: DRS-5220); imported from YAML with a diff first
 * and written with {@code apply}; exported and read back unchanged; listed for every viewer of VaR; resolved as {@code PORT:<id>} (an
 * unknown id: DRS-5221), served as the {@code system-basket} kind for its page; opened as a copy in the sheet; and never mixing one person's
 * baskets with another's.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.connectors.var-system-baskets.plugin=var-system-baskets",
        "drishti.sources.connectors.var-system-baskets.kinds[0]=system-basket",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.sources.routes.system-basket=var-system-baskets",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.system-baskets.seed=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.keeper.kinds[0]=*", "drishti.security.roles.keeper.raw=true",
        "drishti.security.roles.keeper.permissions[0]=var:system-baskets",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarSystemBasketApiTest {

    static Path lake;
    static final String API = "/api/v1/risk/var/system-baskets";

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-sb-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        Path store = Files.createDirectories(lake.resolve("store"));      // a basket store of its own: the system scope starts empty
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.store.root", store::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired com.ash.drishti.identity.AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private ResultActions save(Map<String, Object> body, String auth) throws Exception {
        return mvc.perform(post(API).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions scope(String scope, String auth) throws Exception {
        return mvc.perform(get("/api/v1/risk/var/scope").param("scope", scope).header("Authorization", auth));
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @Order(1)
    void onlyTheHoldersOfThePermissionChangeThemAndEachChangeIsAuditedAndTold() throws Exception {
        Map<String, Object> eur = m("id", "PF-EUR", "name", "Euro", "owner", "olga", "type", "rule", "rule", "CURRENCY == 'EUR'",
                "rollsUpTo", m("kind", "bank", "id", "BANK"), "validFrom", "2026-01-02");
        save(eur, as("rita", "risk")).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(containsString("var:system-baskets")));
        JsonNode made = read(save(eur, as("kim", "keeper")).andExpect(status().isOk()));
        assertThat(made.get("version").asInt()).isEqualTo(1);
        assertThat(made.get("scope").asText()).isEqualTo("WHERE:CURRENCY == 'EUR'");
        assertThat(made.get("updatedBy").asText()).isEqualTo("kim");
        // an administrator holds every permission
        read(save(m("id", "PF-MIX", "name", "Mix", "owner", "kim", "type", "basket", "sheet",
                m("POS", List.of(VarLakeFixture.POS_B), "TRD", List.of("T4", "T5"))), as("ada", "admin")).andExpect(status().isOk()));
        // the same id again is refused in words; an edit names the version it opened, a stale one is 409
        save(eur, as("kim", "keeper")).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("DRS-5220"))
                .andExpect(jsonPath("$.detail").value(containsString("exists already")));
        Map<String, Object> edit = new LinkedHashMap<>(eur);
        edit.put("name", "Everything in EUR");
        edit.put("version", 1);
        assertThat(read(save(edit, as("kim", "keeper")).andExpect(status().isOk())).get("version").asInt()).isEqualTo(2);
        save(edit, as("kim", "keeper")).andExpect(status().isConflict());
        save(edit, as("rita", "risk")).andExpect(status().isForbidden());
        assertThat(audit.recent(50, "PF-EUR")).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("var-system-basket-create");
            assertThat(e.actor()).isEqualTo("kim");
        }).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("var-system-basket-edit");
            assertThat(e.detail()).contains("version=2").contains("type=rule");
        });
        // the owner (olga) is told in their inbox that kim changed it
        JsonNode inbox = json.readTree(mvc.perform(get("/api/v1/me/inbox").param("type", "notice").header("Authorization", as("olga", "risk")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(inbox.toString()).contains("PORT:PF-EUR").contains("edited by kim");
    }

    @Test
    @Order(2)
    void aSaveIsCheckedOnTheDateAndRefusedWithTheReason() throws Exception {
        String kim = as("kim", "keeper");
        save(m("id", "PF-NODE", "name", "n", "type", "hierarchy", "node", "MGMT~DESK-NOPE"), kim).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("DRS-5220")).andExpect(jsonPath("$.detail").value(containsString("MGMT~DESK-NOPE is not a node")));
        save(m("id", "PF-RULE", "name", "r", "type", "rule", "rule", "CURRENCY === ("), kim).andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(containsString("the rule does not read")));
        save(m("id", "PF-POS", "name", "p", "type", "basket", "members", List.of(m("position", "POS-NOPE~X"))), kim).andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(containsString("POS-NOPE~X is not held")));
        save(m("id", "pf lower", "name", "p", "type", "rule", "rule", "CURRENCY == 'EUR'"), kim).andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(containsString("upper-case")));
        save(m("id", "PF-T", "name", "t", "type", "portfolio"), kim).andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(containsString("type is one of")));
        // a basket from the sheet holds positions and trades only: a desk is a hierarchy basket
        save(m("id", "PF-SHEET", "name", "s", "type", "basket", "sheet", m("DESK", List.of("DESK-X"))), kim).andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(containsString("is a node (type hierarchy)")));
        // a hierarchy basket takes the one node the sheet names
        JsonNode h = read(save(m("id", "PF-DESK-X", "name", "Desk X", "type", "hierarchy", "sheet", m("DESK", List.of("DESK-X"))), kim)
                .andExpect(status().isOk()));
        assertThat(h.get("node").asText()).isEqualTo("MGMT~DESK-X");
    }

    @Test
    @Order(3)
    void portResolvesFromTheStoreForEveryViewerAndAnUnknownIdIsSaidInWords() throws Exception {
        String rita = as("rita", "risk");
        assertThat(read(scope("PORT:PF-EUR", rita).andExpect(status().isOk())).get("var").asDouble()).isEqualTo(120);
        assertThat(read(scope("PORT:PF-MIX", rita).andExpect(status().isOk())).get("var").asDouble()).isEqualTo(540);
        assertThat(read(scope("PORT:PF-DESK-X", rita).andExpect(status().isOk())).get("var").asDouble())
                .isEqualTo(read(scope("DESK:DESK-X", rita)).get("var").asDouble());
        scope("PORT:PF-NOPE", rita).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-5221"))
                .andExpect(jsonPath("$.detail").value(containsString("no system basket PF-NOPE")));
        // the list: every viewer of VaR sees the same system baskets, with whether they may change them
        JsonNode list = read(mvc.perform(get(API).header("Authorization", rita)).andExpect(status().isOk()));
        assertThat(list.get("mayManage").asBoolean()).isFalse();
        assertThat(list.get("baskets").findValuesAsText("id")).contains("PF-EUR", "PF-MIX", "PF-DESK-X");
        assertThat(read(mvc.perform(get(API).header("Authorization", as("kim", "keeper")))).get("mayManage").asBoolean()).isTrue();
        // the explorer's System baskets list (the tree's answer) is the same
        JsonNode tree = read(mvc.perform(get("/api/v1/risk/var/tree").header("Authorization", rita)).andExpect(status().isOk()));
        assertThat(tree.get("portfolios").findValuesAsText("id")).contains("PF-EUR", "PF-MIX", "PF-DESK-X");
        // PORT <id>: the system-basket kind's page, its figures the engine's
        JsonNode page = read(mvc.perform(get("/api/v1/entities/system-basket/PF-MIX/raw").header("Authorization", rita)).andExpect(status().isOk()));
        assertThat(page.toString()).contains("\"PORTFOLIO_ID\":\"PF-MIX\"").contains("\"TYPE\":\"BASKET\"");
        mvc.perform(get("/api/v1/entities/system-basket/PF-NOPE/raw").header("Authorization", rita)).andExpect(status().isNotFound());
        // Edit a copy: the sheet holds the basket's positions and trades, with whether the viewer may change the system basket itself
        JsonNode copy = read(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "PORT:PF-MIX").header("Authorization", rita))
                .andExpect(status().isOk()));
        assertThat(copy.at("/portfolio/note").asText()).contains("a copy you save as your own basket");
        assertThat(copy.at("/portfolio/mayManage").asBoolean()).isFalse();
        assertThat(copy.at("/sheet/POS").toString()).contains(VarLakeFixture.POS_B);
        assertThat(copy.at("/sheet/TRD").toString()).contains("T4").contains("T5");
        JsonNode keep = read(mvc.perform(get("/api/v1/risk/var/basket/sheet").param("scope", "PORT:PF-MIX").header("Authorization", as("kim", "keeper"))));
        assertThat(keep.at("/portfolio/mayManage").asBoolean()).isTrue();
        assertThat(keep.at("/portfolio/systemBasket/version").asInt()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void importShowsTheDiffFirstAndWritesOnlyWithApplyAndExportReadsBackUnchanged() throws Exception {
        String kim = as("kim", "keeper");
        String yaml = """
                system-baskets:
                  - id: PF-EUR
                    name: Everything in EUR
                    owner: olga
                    type: rule
                    rule: "CURRENCY == 'EUR' and DIRECTION == 'LONG'"
                    rolls-up-to: {kind: bank, id: BANK}
                    valid-from: '2026-01-02'
                  - id: PF-NEW
                    name: New one
                    owner: kim
                    type: hierarchy
                    node: MGMT~DESK-Y
                  - id: PF-BAD
                    name: Bad
                    owner: kim
                    type: hierarchy
                    node: MGMT~NOWHERE
                """;
        mvc.perform(post(API + "/import").header("Authorization", as("rita", "risk")).contentType("application/yaml").content(yaml))
                .andExpect(status().isForbidden());
        JsonNode dry = read(mvc.perform(post(API + "/import").header("Authorization", kim).contentType("application/yaml").content(yaml))
                .andExpect(status().isOk()));
        assertThat(dry.get("applied").asBoolean()).isFalse();
        assertThat(dry.get("add").asInt()).isEqualTo(1);
        assertThat(dry.get("change").asInt()).isEqualTo(1);
        assertThat(dry.get("invalid").asInt()).isEqualTo(1);
        assertThat(dry.at("/baskets/0/changes/rule/to").asText()).isEqualTo("CURRENCY == 'EUR' and DIRECTION == 'LONG'");
        assertThat(dry.at("/baskets/2/reason").asText()).contains("MGMT~NOWHERE");
        assertThat(dry.get("keptNotInFile").toString()).contains("PF-MIX");
        mvc.perform(get(API + "/PF-NEW").header("Authorization", kim)).andExpect(status().isNotFound());   // nothing written yet
        JsonNode applied = read(mvc.perform(post(API + "/import").param("apply", "true").header("Authorization", kim).contentType("application/yaml")
                .content(yaml)).andExpect(status().isOk()));
        assertThat(applied.get("applied").asBoolean()).isTrue();
        assertThat(read(mvc.perform(get(API + "/PF-NEW").header("Authorization", kim)).andExpect(status().isOk())).get("node").asText())
                .isEqualTo("MGMT~DESK-Y");
        mvc.perform(get(API + "/PF-BAD").header("Authorization", kim)).andExpect(status().isNotFound());     // an invalid entry is never written
        assertThat(audit.recent(50, "system")).anySatisfy(e -> assertThat(e.action()).isEqualTo("var-system-basket-import"));
        assertThat(audit.recent(50, "PF-NEW")).anySatisfy(e -> assertThat(e.detail()).startsWith("by import"));
        // export: the file every system basket is in; imported again, nothing changes
        String exported = mvc.perform(get(API + "/export").header("Authorization", kim)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(exported).contains("system-baskets:").contains("PF-NEW").contains("rolls-up-to");
        JsonNode again = read(mvc.perform(post(API + "/import").header("Authorization", kim).contentType("application/yaml").content(exported))
                .andExpect(status().isOk()));
        assertThat(again.get("add").asInt()).isZero();
        assertThat(again.get("change").asInt()).isZero();
        assertThat(again.get("invalid").asInt()).isZero();
        assertThat(again.get("same").asInt()).isEqualTo(again.get("baskets").size());
    }

    @Test
    @Order(5)
    void aRetiredBasketIsKeptForTheDatesBeforeAndUnknownAfter() throws Exception {
        String kim = as("kim", "keeper");
        mvc.perform(post(API + "/PF-NEW/retire").header("Authorization", as("rita", "risk")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        JsonNode r = read(mvc.perform(post(API + "/PF-NEW/retire").header("Authorization", kim).contentType(MediaType.APPLICATION_JSON)
                .content("{\"validTo\":\"2026-09-29\",\"reason\":\"desk closed\"}")).andExpect(status().isOk()));
        assertThat(r.get("validTo").asText()).isEqualTo("2026-09-29");
        assertThat(r.get("retiredBy").asText()).isEqualTo("kim");
        scope("PORT:PF-NEW", as("rita", "risk")).andExpect(status().isNotFound());                     // the date is 2026-09-30
        JsonNode all = read(mvc.perform(get(API).param("all", "true").header("Authorization", kim)));
        assertThat(all.get("baskets").findValuesAsText("id")).contains("PF-NEW");
        assertThat(audit.recent(50, "PF-NEW")).anySatisfy(e -> assertThat(e.action()).isEqualTo("var-system-basket-retire"));
    }

    @Test
    @Order(6)
    void onePersonsBasketsAreNeverAnothersWhileTheSystemBasketsAreEveryones() throws Exception {
        String rita = as("rita", "risk");
        String ola = as("ola", "risk");
        mvc.perform(post("/api/v1/risk/var/baskets").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(m("name", "rita-only", "scope", "TRD:T1")))).andExpect(status().isOk());
        JsonNode olas = read(mvc.perform(get("/api/v1/risk/var/baskets").header("Authorization", ola)).andExpect(status().isOk()));
        assertThat(olas.findValuesAsText("id")).doesNotContain("rita/rita-only");
        JsonNode notHers = read(scope("BASKET:rita/rita-only", ola).andExpect(status().isOk()));
        assertThat(notHers.get("var").asDouble()).isZero();
        assertThat(notHers.at("/provenance/notices").toString()).contains("not shared with you");
        assertThat(read(mvc.perform(get(API).header("Authorization", ola))).get("baskets").findValuesAsText("id"))
                .isEqualTo(read(mvc.perform(get(API).header("Authorization", rita))).get("baskets").findValuesAsText("id"));
        // a person's basket is not a system basket, and a system basket is not anyone's
        assertThat(read(mvc.perform(get(API).header("Authorization", rita))).get("baskets").findValuesAsText("id")).doesNotContain("rita/rita-only");
        assertThat(read(mvc.perform(get("/api/v1/risk/var/baskets").header("Authorization", rita))).findValuesAsText("id")).doesNotContain("PF-EUR");
    }
}
