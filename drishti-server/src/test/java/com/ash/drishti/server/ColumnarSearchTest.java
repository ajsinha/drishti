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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Searches over a laid-out Delta table (the connector's fixture: 40 trades, mtm, book, nettingSet and counterparty.id
 * promoted) are answered from columns, exactly, with role redaction applied to column values as to documents.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.layout-lake.plugin=delta",
        "drishti.sources.connectors.layout-lake.kinds[0]=trade",
        "drishti.sources.connectors.layout-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake-layout",
        "drishti.sources.connectors.layout-lake.settings.domain=desk",
        "drishti.sources.connectors.layout-lake.settings.layout.trade.columns=mtm,book,nettingSet,counterparty.id",
        "drishti.sources.routes.trade=layout-lake",
        "drishti.search.columns.trade[0]=$.book", "drishti.search.columns.trade[1]=$.mtm",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=book",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class ColumnarSearchTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String role) {
        return "Bearer " + tokens.mint("searcher", List.of(role), 300);
    }

    @Test
    void aSearchReadsColumnsAndAnswersExactly() throws Exception {
        // mtm is i * 1000 - 19900 on the newest day: above 5000 from T-025 on; T-007 has none
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm > 5000 order by mtm desc").header("Authorization", as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(16)).andExpect(jsonPath("$.scanned").value(40)).andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.rows[0].ref.id").value("T-040")).andExpect(jsonPath("$.rows[0].values['$.mtm']").value(20100))
                .andExpect(jsonPath("$.rows[15].ref.id").value("T-025"));
        mvc.perform(get("/api/v1/search").param("q", "TRD book=BOOK-B").header("Authorization", as("admin")))
                .andExpect(jsonPath("$.matched").value(20)).andExpect(jsonPath("$.rows[*].values['$.book']").value(everyItem(org.hamcrest.Matchers.is("BOOK-B"))));
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm < -18000").header("Authorization", as("admin")))
                .andExpect(jsonPath("$.rows[*].ref.id").value(contains("T-001")));          // T-001 at -18900; T-002 is -17900
        mvc.perform(get("/api/v1/search").param("q", "TRD T-00").header("Authorization", as("admin")))     // a pick list
                .andExpect(jsonPath("$.matched").value(9)).andExpect(jsonPath("$.rows[*].ref.id").value(hasItem("T-007")));
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm > 0").header("Authorization", as("admin")).header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(jsonPath("$.matched").value(20));                                           // i * 1000 - 20000 > 0
    }

    @Test
    void columnValuesAreRedactedAsDocumentsAre() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "TRD where book = 'BOOK-A'").header("Authorization", as("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(0));                 // a masked field matches nothing
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm > 19000").header("Authorization", as("viewer")))
                .andExpect(jsonPath("$.rows[0].values['$.book']").value("•••"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where book = 'BOOK-A'").header("Authorization", as("admin")))
                .andExpect(jsonPath("$.matched").value(20));                                           // admins see raw data
    }
}
