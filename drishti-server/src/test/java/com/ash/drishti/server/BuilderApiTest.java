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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** {@code POST /api/v1/builder/shape}: the shape of sample documents, for authors only, with the limits enforced cleanly. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/builder-${random.uuid}/identity.db",
        "drishti.builder.max-samples=3", "drishti.builder.max-file-mb=1", "drishti.builder.max-total-mb=2", "drishti.builder.max-depth=6"})
@AutoConfigureMockMvc
class BuilderApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private static String samples(int n) {
        StringBuilder b = new StringBuilder("{\"samples\":[");
        for (int i = 0; i < n; i++) {
            b.append(i == 0 ? "" : ",").append("{\"name\":\"t").append(i).append(".json\",\"document\":{\"tradeId\":\"T").append(i)
                    .append("\",\"notional\":").append(1000 * (i + 1)).append(",\"counterparty\":{\"id\":\"CP-A\",\"name\":\"A\"}}}");
        }
        return b.append("]}").toString();
    }

    @Test
    void anAuthorGetsTheSchemaRolesAndReport() throws Exception {
        mvc.perform(post("/api/v1/builder/shape").header("Authorization", as("ann", "author")).contentType(MediaType.APPLICATION_JSON)
                        .content(samples(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema.$schema").value("https://json-schema.org/draft/2020-12/schema"))
                .andExpect(jsonPath("$.schema.required[0]").value("tradeId"))
                .andExpect(jsonPath("$.roles['$.tradeId'].role").value("id"))
                .andExpect(jsonPath("$.roles['$.counterparty'].role").value("link"))
                .andExpect(jsonPath("$.report.samples").value(3))
                .andExpect(jsonPath("$.report.paths[?(@.path=='$.notional')].role").value("measure"));
    }

    @Test
    void someoneWhoIsNotAnAuthorIsRefused() throws Exception {
        mvc.perform(post("/api/v1/builder/shape").header("Authorization", as("vic", "viewer")).contentType(MediaType.APPLICATION_JSON)
                        .content(samples(1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(post("/api/v1/builder/shape").contentType(MediaType.APPLICATION_JSON).content(samples(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tooManySamplesAreRefusedWithAProblem() throws Exception {
        mvc.perform(post("/api/v1/builder/shape").header("Authorization", as("ann", "author")).contentType(MediaType.APPLICATION_JSON)
                        .content(samples(4)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("DRS-5003"))
                .andExpect(jsonPath("$.detail").value(containsString("max-samples")));
    }

    @Test
    void aDocumentOverTheFileLimitIsRefusedByName() throws Exception {
        String big = "x".repeat(1_100_000);
        String body = "{\"samples\":[{\"name\":\"huge.json\",\"document\":{\"blob\":\"" + big + "\"}}]}";
        mvc.perform(post("/api/v1/builder/shape").header("Authorization", as("ann", "author")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("DRS-5003"))
                .andExpect(jsonPath("$.detail").value(containsString("'huge.json'"))).andExpect(jsonPath("$.detail").value(containsString("max-file-mb")));
    }

    @Test
    void aRequestOverTheTotalLimitIsRefusedBeforeItIsRead() throws Exception {
        String body = "{\"samples\":[{\"name\":\"a\",\"document\":{\"blob\":\"" + "x".repeat(2_200_000) + "\"}}]}";
        mvc.perform(post("/api/v1/builder/shape").header("Authorization", as("ann", "author")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.detail").value(containsString("max-total-mb")));
    }

    @Test
    void aTooDeepDocumentIsRefusedWithAProblem() throws Exception {
        String deep = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":1}}}}}}}";
        mvc.perform(post("/api/v1/builder/shape").header("Authorization", as("ann", "author")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"samples\":[{\"name\":\"deep.json\",\"document\":" + deep + "}]}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.detail").value(containsString("max-depth")));
    }

    @Test
    void badBodiesAreCleanProblems() throws Exception {
        for (String body : new String[] {"{not json", "[1]", "{}", "{\"samples\":[]}", "{\"samples\":{}}", "{\"samples\":[1]}",
                "{\"samples\":[{\"name\":\"a\"}]}"}) {
            mvc.perform(post("/api/v1/builder/shape").header("Authorization", as("ann", "author")).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5001")).andExpect(jsonPath("$.detail").isNotEmpty());
        }
    }
}
