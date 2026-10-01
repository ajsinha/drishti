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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.identity.database-url=jdbc:sqlite:target/prefs-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class WorkspaceApiTest {

    @Autowired MockMvc mvc;

    static final String CREDIT = """
            {"layout":"1+2","panes":[
              {"ref":{"kind":"netting-set","id":"NS-NORTH-01"},"title":"Netting set"},
              {"ref":null,"follows":0,"title":"Selected trade"},
              {"ref":{"kind":"curve","id":"USD-SOFR"},"title":"Curve"}]}""";

    @Test
    void saveListReadDelete() throws Exception {
        mvc.perform(put("/api/v1/me/workspaces/Credit desk").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON).content(CREDIT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.panes[1].follows").value(0));
        mvc.perform(get("/api/v1/me/workspaces").header("X-Drishti-User", "ash")).andExpect(jsonPath("$[0]").value("Credit desk"));
        mvc.perform(get("/api/v1/me/workspaces").header("X-Drishti-User", "tina")).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/me/workspaces/Credit desk").header("X-Drishti-User", "ash"))
                .andExpect(jsonPath("$.panes[0].ref.id").value("NS-NORTH-01"));
        mvc.perform(delete("/api/v1/me/workspaces/Credit desk").header("X-Drishti-User", "ash")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/workspaces/Credit desk").header("X-Drishti-User", "ash")).andExpect(status().isNotFound());
    }

    @Test
    void invalidWorkspacesAreRefused() throws Exception {
        for (String bad : new String[] {
                "{\"layout\":\"9x9\",\"panes\":[{}]}",
                "{\"layout\":\"2col\",\"panes\":[]}",
                "{\"layout\":\"2col\",\"panes\":[{\"follows\":0}]}",
                "{\"layout\":\"2col\",\"panes\":[{\"follows\":1},{\"follows\":0}]}",
                "{\"layout\":\"2col\",\"panes\":[{},{},{},{},{}]}"}) {
            mvc.perform(put("/api/v1/me/workspaces/x").header("X-Drishti-User", "ash").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }
}
