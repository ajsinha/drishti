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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The Sutra designer's "my version of this page" flags are per user, validated, set and cleared by kind; /packs lists each pack's links. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class MyPagesTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user) {
        return "Bearer " + tokens.mint(user, List.of("viewer"), 60);
    }

    @Test
    void pagesArePerUserSetAndCleared() throws Exception {
        String ana = "ana-" + System.nanoTime();
        String bea = "bea-" + System.nanoTime();
        mvc.perform(get("/api/v1/me/pages").header("Authorization", as(ana))).andExpect(status().isOk()).andExpect(jsonPath("$.trade").doesNotExist());
        mvc.perform(put("/api/v1/me/pages/trade").header("Authorization", as(ana)).contentType(MediaType.APPLICATION_JSON).content("{\"design\":\"d-1\"}"))
                .andExpect(jsonPath("$.trade").value("d-1"));
        mvc.perform(get("/api/v1/me/pages").header("Authorization", as(ana))).andExpect(jsonPath("$.trade").value("d-1"));
        mvc.perform(get("/api/v1/me/pages").header("Authorization", as(bea))).andExpect(jsonPath("$.trade").doesNotExist());
        mvc.perform(put("/api/v1/me/pages/Bad_Kind").header("Authorization", as(ana)).contentType(MediaType.APPLICATION_JSON).content("{\"design\":\"d-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/pages/trade").header("Authorization", as(ana)).contentType(MediaType.APPLICATION_JSON).content("{\"design\":\"a b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/me/pages/trade").header("Authorization", as(ana))).andExpect(jsonPath("$.trade").doesNotExist());
        mvc.perform(get("/api/v1/me/pages").header("Authorization", as(ana))).andExpect(jsonPath("$.trade").doesNotExist());
    }

    @Test
    void anArrangementIsPerUserCheckedAndReset() throws Exception {
        String ana = "ana-" + System.nanoTime();
        String body = "{\"columns\":[6.5,5.5],\"collapsed\":{\"risk\":true},\"panels\":[{\"id\":\"trades\",\"area\":\"detail\",\"span\":6,\"order\":2}],\"junk\":1}";
        mvc.perform(put("/api/v1/me/arrange/book-desk").header("Authorization", as(ana)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.junk").doesNotExist());
        mvc.perform(get("/api/v1/me/arrange/book-desk").header("Authorization", as(ana)))
                .andExpect(jsonPath("$.columns[0]").value(6.5)).andExpect(jsonPath("$.collapsed.risk").value(true))
                .andExpect(jsonPath("$.panels[0].span").value(6));
        mvc.perform(get("/api/v1/me/arrange/book-desk").header("Authorization", as("bea-" + System.nanoTime()))).andExpect(jsonPath("$.columns").doesNotExist());
        mvc.perform(put("/api/v1/me/arrange/book-desk").header("Authorization", as(ana)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"panels\":[{\"id\":\"x\",\"span\":13}]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/arrange/Bad_Name").header("Authorization", as(ana)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/me/arrange/book-desk").header("Authorization", as(ana))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/arrange/book-desk").header("Authorization", as(ana))).andExpect(jsonPath("$.columns").doesNotExist());
    }

    @Test
    void packsListTheFieldsThatNameOtherEntities() throws Exception {
        var links = com.ash.drishti.server.api.CatalogController.links(java.util.Map.of("graph", java.util.Map.of("fields",
                java.util.Map.of("COUNTERPARTY", java.util.Map.of("kind", "counterparty", "label", "Counterparty"), "X", "not a ref"))));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("COUNTERPARTY", java.util.Map.of("kind", "counterparty", "label", "Counterparty")), links);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of(), com.ash.drishti.server.api.CatalogController.links(java.util.Map.of()));
        mvc.perform(get("/api/v1/packs").header("Authorization", as("ana"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].links").exists());
    }
}
