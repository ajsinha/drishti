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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.ResultActions;

/** W21: personal settings are validated, per user, patched field by field, and default when unset. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class SettingsTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private ResultActions patchAs(String user, String body) throws Exception {
        return mvc.perform(patch("/api/v1/me/settings").header("Authorization", "Bearer " + tokens.mint(user, List.of("viewer"), 60))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void settingsArePerUserValidatedAndPatchedFieldByField() throws Exception {
        String ana = "ana-" + System.nanoTime();
        String bea = "bea-" + System.nanoTime();
        mvc.perform(get("/api/v1/me/settings").header("Authorization", "Bearer " + tokens.mint(ana, List.of("viewer"), 60)))
                .andExpect(jsonPath("$.landing").value("/t")).andExpect(jsonPath("$.flash").value(true)).andExpect(jsonPath("$.pinned", hasSize(0)));
        patchAs(ana, "{\"theme\":\"crimson\",\"clockZone\":\"Europe/London\",\"density\":\"compact\",\"landing\":\"/w/Rates\","
                + "\"pinned\":[{\"kind\":\"trade\",\"id\":\"T-1\"},{\"kind\":\"trade\",\"id\":\"T-1\"}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("crimson")).andExpect(jsonPath("$.pinned", hasSize(1)));
        patchAs(ana, "{\"flash\":false,\"theme\":null}").andExpect(jsonPath("$.flash").value(false))
                .andExpect(jsonPath("$.theme").isEmpty()).andExpect(jsonPath("$.clockZone").value("Europe/London"));
        mvc.perform(get("/api/v1/me/settings").header("Authorization", "Bearer " + tokens.mint(bea, List.of("viewer"), 60)))
                .andExpect(jsonPath("$.density").value("comfortable"));                      // another user's settings are their own
        patchAs(ana, "{\"locale\":\"fr-CA\"}").andExpect(jsonPath("$.locale").value("fr-CA"));      // the language of the pack's help text
        patchAs(ana, "{\"locale\":\"not a tag\"}").andExpect(status().isBadRequest());
        patchAs(ana, "{\"theme\":\"neon\"}").andExpect(status().isBadRequest());
        patchAs(ana, "{\"clockZone\":\"Mars/Olympus\"}").andExpect(status().isBadRequest());
        patchAs(ana, "{\"landing\":\"https://evil.example\"}").andExpect(status().isBadRequest());
        patchAs(ana, "{\"landing\":\"//evil.example\"}").andExpect(status().isBadRequest());
        patchAs(ana, "{\"searchLimit\":5}").andExpect(status().isBadRequest());
        patchAs(ana, "{\"colour\":\"red\"}").andExpect(status().isBadRequest());
        patchAs(ana, "{\"pinned\":[{\"kind\":\"../x\",\"id\":\"1\"}]}").andExpect(status().isBadRequest());
    }
}
