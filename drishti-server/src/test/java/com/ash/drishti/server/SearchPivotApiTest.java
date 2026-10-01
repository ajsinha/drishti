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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The server engine of a search's Pivot tab over a laid-out Delta table (40 trades a day, mtm, book, nettingSet and
 * counterparty.id kept as columns): the whole day aggregated from the columns, only the cells returned, the same numbers
 * as the columns themselves; masked for a role without raw exactly as a search is; a field that is not a column refused
 * with the reason, or read from documents when asked; drill-downs paged; a filter's pick list.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.layout-lake.plugin=delta",
        "drishti.sources.connectors.layout-lake.kinds[0]=trade",
        "drishti.sources.connectors.layout-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake-layout",
        "drishti.sources.connectors.layout-lake.settings.domain=desk",
        "drishti.sources.connectors.layout-lake.settings.layout.trade.columns=mtm,book,nettingSet,counterparty.id",
        "drishti.sources.routes.trade=layout-lake",
        "drishti.search.pivot.trade={\"fields\":[\"book\",\"nettingSet\",\"counterparty.id\",\"mtm\",\"productType\"],"
                + "\"rows\":[\"book\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"}]}",
        "drishti.pivot.drill-page=5",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=book",
        "drishti.security.roles.quant.kinds[0]=*", "drishti.security.roles.curves-quant.kinds[0]=curve",
        "drishti.identity.database-url=jdbc:sqlite:target/search-pivot-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class SearchPivotApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode pivot(String who, String body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/search/pivot/TRD").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theWholeDayIsAggregatedFromTheColumnsAndOnlyTheCellsReturn() throws Exception {
        String admin = as("ada", "admin");
        // the expected sums, straight from the columns
        JsonNode cols = json.readTree(mvc.perform(get("/api/v1/search/columns/TRD").param("paths", "mtm,book,nettingSet")
                .header("Authorization", as("q", "admin"))).andReturn().getResponse().getContentAsString());
        Map<String, Double> byBook = new HashMap<>();
        double total = 0;
        for (int i = 0; i < cols.path("rows").asInt(); i++) {
            double m = cols.path("values").path("mtm").get(i).asDouble();
            byBook.merge(cols.path("values").path("book").get(i).asText(), m, Double::sum);
            total += m;
        }
        JsonNode cube = pivot(admin, "{\"q\":\"TRD\",\"rows\":[\"book\"],\"columns\":[\"nettingSet\"],"
                + "\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"},{\"field\":\"book\",\"agg\":\"count\"},{\"field\":\"counterparty.id\",\"agg\":\"distinct\"}]}");
        assertThat(cube.path("source").asText()).isEqualTo("columns");
        assertThat(cube.path("partial").asBoolean()).isFalse();
        assertThat(cube.path("count").asInt()).isEqualTo(40);
        assertThat(cube.path("rowKeys").size()).isEqualTo(byBook.size());
        assertThat(cube.path("cells").get("\u001e").get(0).asDouble()).isEqualTo(total);
        assertThat(cube.path("cells").get("\u001e").get(1).asInt()).isEqualTo(40);
        for (JsonNode k : cube.path("rowKeys")) {
            String book = k.get(0).asText();
            assertThat(cube.path("cells").get(book + "\u001e").get(0).asDouble()).isEqualTo(byBook.get(book));
        }
        assertThat(cube.path("columnKeys").size()).isGreaterThan(1);
        assertThat(cube.path("values").get(2).path("label").asText()).startsWith("Distinct of");
        // a condition narrows it, evaluated on the columns
        JsonNode neg = pivot(admin, "{\"q\":\"TRD where mtm < 0\",\"rows\":[\"book\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"max\"}]}");
        assertThat(neg.path("cells").get("\u001e").get(0).asDouble()).isLessThan(0);
        // filters: values kept, and a range
        JsonNode one = pivot(admin, "{\"q\":\"TRD\",\"rows\":[\"book\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"}],"
                + "\"filters\":[{\"field\":\"book\",\"values\":[\"" + cube.path("rowKeys").get(0).get(0).asText() + "\"]},{\"field\":\"mtm\",\"min\":0}]}");
        assertThat(one.path("rowKeys").size()).isLessThanOrEqualTo(1);
        assertThat(one.path("cells").get("\u001e").get(0).asDouble()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void maskedFieldsGroupUnderTheMaskAsInASearch() throws Exception {
        JsonNode cube = pivot(as("q", "quant"), "{\"rows\":[\"book\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"},{\"field\":\"book\",\"agg\":\"sum\"}]}");
        assertThat(cube.path("rowKeys").size()).isEqualTo(1);
        assertThat(cube.path("rowKeys").get(0).get(0).asText()).isEqualTo("•••");
        assertThat(cube.path("masked").get(0).asText()).isEqualTo("book");
        assertThat(cube.path("cells").get("\u001e").get(1).isNull()).isTrue();          // a masked field is never added up
        mvc.perform(post("/api/v1/search/pivot/TRD/values").header("Authorization", as("q", "quant")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"book\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.values[*].value").value(everyItem(is("•••"))));
    }

    @Test
    void aFieldThatIsNotAColumnIsRefusedOrReadFromDocumentsWhenAsked() throws Exception {
        String admin = as("ada", "admin");
        String body = "{\"q\":\"TRD\",\"rows\":[\"productType\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"sum\"}]";
        mvc.perform(post("/api/v1/search/pivot/TRD").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(body + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("not kept as columns for trade: [productType]")))
                .andExpect(jsonPath("$.detail").value(containsString("documents: true")));
        JsonNode docs = pivot(admin, body + ",\"documents\":true}");
        assertThat(docs.path("source").asText()).isEqualTo("documents");
        assertThat(docs.path("count").asInt()).isEqualTo(40);
        assertThat(docs.path("partial").asBoolean()).isFalse();
        mvc.perform(post("/api/v1/search/pivot/TRD").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"desk\"]}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("'desk'")));
        mvc.perform(post("/api/v1/search/pivot/TRD").header("Authorization", as("c", "curves-quant")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/search/pivot/NSET").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("do not offer a pivot")));
    }

    @Test
    void aCellDrillsDownToItsEntitiesAPageAtATime() throws Exception {
        String admin = as("ada", "admin");
        JsonNode cube = pivot(admin, "{\"rows\":[\"book\"],\"values\":[{\"field\":\"book\",\"agg\":\"count\"}]}");
        String book = cube.path("rowKeys").get(0).get(0).asText();
        int count = cube.path("cells").get(book + "\u001e").get(0).asInt();
        mvc.perform(post("/api/v1/search/pivot/TRD/drill").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"book\"],\"values\":[{\"field\":\"mtm\",\"agg\":\"count\"}],\"cell\":{\"rows\":[\"" + book + "\"],\"columns\":[]},\"size\":50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(count)).andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.rows.length()").value(Math.min(5, count))).andExpect(jsonPath("$.rows[0].kind").value("trade"))
                .andExpect(jsonPath("$.rows[*].values.book").value(everyItem(is(book))))
                .andExpect(jsonPath("$.fields").value(org.hamcrest.Matchers.hasItems("book", "mtm", "nettingSet", "counterparty.id")));
        mvc.perform(post("/api/v1/search/pivot/TRD/drill").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"book\"],\"cell\":{\"rows\":[],\"columns\":[]},\"offset\":35,\"size\":5}"))
                .andExpect(jsonPath("$.total").value(40)).andExpect(jsonPath("$.rows.length()").value(5)).andExpect(jsonPath("$.offset").value(35));
        mvc.perform(post("/api/v1/search/pivot/TRD/values").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"mtm\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.numeric").value(true))
                .andExpect(jsonPath("$.min").isNumber()).andExpect(jsonPath("$.max").isNumber());
    }
}
