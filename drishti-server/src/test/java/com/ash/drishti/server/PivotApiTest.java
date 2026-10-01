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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Pivot tab of Sutra panels: offered only where the Sutra says {@code pivot:}, the panel's whole rows for it (beyond
 * the table's limit), each user's saved arrangement, and promoting one to the Sutra's next version through review. The
 * finance pack's netting-set Sutra opts its member trades in; its trade search results opt in through pack.yaml.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.rachana.studio-save=true", "drishti.rachana.dirs=${java.io.tmpdir}/drishti-pivot-${random.uuid}",
        "drishti.governance.dir=${java.io.tmpdir}/drishti-pivot-props-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.desk.kinds[0]=trade", "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.identity.database-url=jdbc:sqlite:target/pivots-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PivotApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    @Test
    void onlyAPanelWhoseSutraSaysSoOffersAPivot() throws Exception {
        String admin = as("ada", "admin");
        mvc.perform(get("/api/v1/views/netting-set/NS-NORTH-01").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[?(@.id == 'trades')].data.pivot.rows[0]").value(hasItem("product")))
                .andExpect(jsonPath("$.panels[?(@.id == 'trades')].data.pivot.columns[0]").value(hasItem("currency")))
                .andExpect(jsonPath("$.panels[?(@.id == 'trades')].data.pivot.values[0].agg").value(hasItem("sum")))
                .andExpect(jsonPath("$.panels[?(@.id == 'trades')].data.pivot.fields[4].label").value(hasItem("MTM (USD)")))
                .andExpect(jsonPath("$.panels[?(@.id == 'trades')].data.pivot.fields[4].numeric").value(hasItem(true)))
                .andExpect(jsonPath("$.panels[?(@.id == 'trades')].data.rows.length()").value(hasItem(4)));     // the table keeps its limit
        // a table without pivot: has none
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.panels[?(@.id == 'cashflows')].data.columns").exists())
                .andExpect(jsonPath("$.panels[?(@.id == 'cashflows')].data.pivot").value(org.hamcrest.Matchers.empty()));
    }

    @Test
    void theRecordsAreEveryRowAsRawValues() throws Exception {
        String admin = as("ada", "admin");
        String body = mvc.perform(get("/api/v1/views/netting-set/NS-NORTH-01/panels/trades/records").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.panel").value("trades"))
                .andExpect(jsonPath("$.total").value(14)).andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows.length()").value(14))
                .andExpect(jsonPath("$.fields[*].name").value(org.hamcrest.Matchers.contains("product", "currency", "maturity", "notional", "mtm", "trade")))
                .andExpect(jsonPath("$.fields[5].kind").value("trade"))                        // trade ids open their trade
                .andExpect(jsonPath("$.rows[0][4]").value(-412580))
                .andReturn().getResponse().getContentAsString();
        long sum = 0;
        for (JsonNode r : json.readTree(body).path("rows")) {
            sum += r.get(4).asLong();
        }
        org.assertj.core.api.Assertions.assertThat(sum).isEqualTo(-1259900);                   // the netting set's net MTM
        mvc.perform(get("/api/v1/views/netting-set/NS-NORTH-01/panels/exposure/records").header("Authorization", admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value(containsString("no panel 'exposure' that offers a pivot")));
        mvc.perform(get("/api/v1/views/netting-set/NS-NORTH-01/panels/trades/records").header("Authorization", as("c", "curves")))
                .andExpect(status().isForbidden());
    }

    @Test
    void eachUserKeepsTheirOwnArrangementCheckedAgainstTheFields() throws Exception {
        String una = as("una", "trader");
        String mine = "{\"rows\":[\"currency\"],\"columns\":[],\"values\":[{\"field\":\"notional\",\"agg\":\"avg\",\"show\":\"pctTotal\"}],"
                + "\"filters\":[{\"field\":\"mtm\",\"min\":-1000000}],\"heat\":true,\"chart\":\"bar\"}";
        mvc.perform(put("/api/v1/me/pivots/panel/netting-set/trades").header("Authorization", as("ada", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(mine)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("panel")).andExpect(jsonPath("$.kind").value("netting-set"))
                .andExpect(jsonPath("$.values[0].show").value("pctTotal")).andExpect(jsonPath("$.heat").value(true));
        mvc.perform(get("/api/v1/me/pivots/panel/netting-set/trades").header("Authorization", as("ada", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0]").value("currency"));
        mvc.perform(get("/api/v1/me/pivots/panel/netting-set/trades").header("Authorization", as("bo", "admin")))
                .andExpect(status().isNotFound());                                                    // another user has none
        mvc.perform(put("/api/v1/me/pivots/panel/netting-set/trades").header("Authorization", as("ada", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[\"desk\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("pivot rows name 'desk'")));
        mvc.perform(put("/api/v1/me/pivots/panel/netting-set/exposure").header("Authorization", as("ada", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("does not offer a pivot")));
        mvc.perform(put("/api/v1/me/pivots/panel/netting-set/trades").header("Authorization", as("c", "curves"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        // search results of trade (the finance pack opts them in)
        mvc.perform(put("/api/v1/me/pivots/search/TRD").header("Authorization", una).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"desk\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"}]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("trade")).andExpect(jsonPath("$.scope").value("search"));
        mvc.perform(get("/api/v1/me/pivots").header("Authorization", una)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.promote").value(false))
                .andExpect(jsonPath("$.pivots[*].scope").value(hasItem("search")));
        mvc.perform(put("/api/v1/me/pivots/search/curve").header("Authorization", as("ada", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("do not offer a pivot")));
        mvc.perform(delete("/api/v1/me/pivots/search/trade").header("Authorization", una)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/me/pivots/search/trade").header("Authorization", una)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/pivots").header("Authorization", una))
                .andExpect(jsonPath("$.pivots[*].scope").value(not(hasItem("search"))));
    }

    @Test
    void anAuthorPromotesAPivotToTheSutrasNextVersionThroughReview() throws Exception {
        String author = as("ann", "author");
        mvc.perform(put("/api/v1/me/pivots/panel/netting-set/trades").header("Authorization", author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"currency\",\"product\"],\"values\":[{\"field\":\"notional\",\"agg\":\"sum\"}],\"chart\":\"bar\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/pivots").header("Authorization", author)).andExpect(jsonPath("$.promote").value(true))
                .andExpect(jsonPath("$.review").value(true));
        mvc.perform(get("/api/v1/me/pivots/panel/netting-set/trades/promotion").header("Authorization", author))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fromVersion").value(1)).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.text").value(containsString("      rows: [currency, product]\n      values: [{ field: notional, agg: sum }]")))
                .andExpect(jsonPath("$.text").value(containsString("      fields: [product, currency, maturity, notional, mtm, trade]")))
                .andExpect(jsonPath("$.text").value(containsString("# the Pivot tab: every member trade")))
                .andExpect(jsonPath("$.base").value(containsString("version: 1")))
                .andExpect(jsonPath("$.changes").value(hasItem("the pivot of 'trades' opens with rows currency, product (was product)")));
        mvc.perform(post("/api/v1/me/pivots/panel/netting-set/trades/promotion").header("Authorization", author)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"desk asked for it\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.proposal.name").value("netting-set"))
                .andExpect(jsonPath("$.proposal.version").value(2)).andExpect(jsonPath("$.proposal.status").value("pending"));
        // a trader may keep a pivot but not promote it
        String tom = as("tom", "risk");
        mvc.perform(put("/api/v1/me/pivots/panel/netting-set/trades").header("Authorization", tom).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"currency\"]}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/pivots/panel/netting-set/trades/promotion").header("Authorization", tom))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(containsString("role with author")));
    }

    @Test
    void theFinanceTradeSearchOffersAPivotReadFromDocumentsWhenAsked() throws Exception {
        String admin = as("ada", "admin");
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm != null").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pivot.rows[0]").value("book")).andExpect(jsonPath("$.pivot.fields[0].promoted").value(false));
        mvc.perform(get("/api/v1/search").param("q", "CPTY").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pivot").doesNotExist());
        String arrangement = "{\"q\":\"TRD where mtm != null\",\"rows\":[\"book\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"},{\"field\":\"mtm\",\"agg\":\"count\"}]";
        mvc.perform(post("/api/v1/search/pivot/TRD").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(arrangement + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("no source of trade keeps its fields as columns")))
                .andExpect(jsonPath("$.detail").value(containsString("documents: true")));
        mvc.perform(post("/api/v1/search/pivot/TRD").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(arrangement + ",\"documents\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("documents")).andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.rowKeys[0][0]").exists()).andExpect(jsonPath("$.total").value(16))
                .andExpect(jsonPath("$.count").value(16)).andExpect(jsonPath("$.values[0].label").value(containsString("Sum of")));
    }
}
