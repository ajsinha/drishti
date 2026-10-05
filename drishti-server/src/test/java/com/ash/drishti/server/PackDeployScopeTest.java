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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Who may deploy a pack archive and change a data source (admin role, and for a token the packs:admin scope), and what /me/token says. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PackDeployScopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier signer;

    private String as(String user, String... roles) {
        return "Bearer " + signer.mint(user, List.of(roles), 300);
    }

    private String token(String user, String role, String body) throws Exception {
        var res = mvc.perform(post("/api/v1/me/tokens").header("Authorization", as(user, role)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
        return "Bearer " + JSON.readTree(res.getContentAsString()).path("secret").asText();
    }

    private ResultActions call(String bearer, String method, String path) throws Exception {
        var b = switch (method) {
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> post(path);
        };
        boolean upload = path.endsWith("/deploy");
        return mvc.perform(b.header("Authorization", bearer).contentType(upload ? MediaType.APPLICATION_OCTET_STREAM : MediaType.APPLICATION_JSON)
                .content(upload ? "not an archive" : "{\"connectors\":{}}"));
    }

    private static final String[][] DOORS = {{"POST", "/api/v1/admin/packs/deploy"}, {"POST", "/api/v1/admin/packs/deploy/abc"},
            {"DELETE", "/api/v1/admin/packs/deploy/abc"}, {"POST", "/api/v1/admin/packs/finance/rollback"}, {"PUT", "/api/v1/admin/packs/finance/datasource"},
            {"DELETE", "/api/v1/admin/packs/finance/datasource"}, {"POST", "/api/v1/admin/packs/finance/datasource/test"}};

    @Test
    void anAdminSessionMayAndAnAuthorMayNot() throws Exception {
        call(as("drishti-dev-admin", "admin"), "POST", "/api/v1/admin/packs/deploy").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false));   // not an archive: refused in the answer
        for (String[] d : DOORS) {
            assertThat(call(as("deploy-ada", "author"), d[0], d[1]).andReturn().getResponse().getStatus()).as("author " + d[0] + " " + d[1]).isEqualTo(403);
        }
        mvc.perform(get("/api/v1/admin/packs/history").header("Authorization", as("deploy-ada", "author"))).andExpect(status().isForbidden());
    }

    @Test
    void aTokenNeedsThePacksAdminScopeAndStillItsUsersAdminRole() throws Exception {
        String read = token("drishti-dev-admin", "admin", "{\"name\":\"plain\",\"days\":30}");
        String scoped = token("drishti-dev-admin", "admin", "{\"name\":\"deploy\",\"days\":30,\"scopes\":[\"packs:admin\"]}");
        for (String[] d : DOORS) {
            call(read, d[0], d[1]).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("this token lacks the scope packs:admin"));
            assertThat(call(scoped, d[0], d[1]).andReturn().getResponse().getStatus()).as("scoped " + d[0] + " " + d[1]).isNotIn(401, 403);
        }
        call(scoped, "POST", "/api/v1/admin/packs/deploy").andExpect(status().isOk());
        // a scope never lifts a user above their role
        mvc.perform(post("/api/v1/admin/users").header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"deploy-author\",\"roles\":[\"author\"],\"password\":\"scoped-pass-12\"}")).andExpect(status().isCreated());
        String authorScoped = token("deploy-author", "author", "{\"name\":\"deploy\",\"days\":30,\"scopes\":[\"packs:admin\"]}");
        call(authorScoped, "POST", "/api/v1/admin/packs/deploy").andExpect(status().isForbidden());
    }

    @Test
    void meTokenNamesTheCallingTokenAndIsNotFoundForASession() throws Exception {
        String scoped = token("drishti-dev-admin", "admin", "{\"name\":\"deploy\",\"days\":30,\"scopes\":[\"packs:admin\"]}");
        String id = scoped.substring("Bearer drk_".length(), "Bearer drk_".length() + 12);
        mvc.perform(get("/api/v1/me/token").header("Authorization", scoped)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.user").value("drishti-dev-admin"))
                .andExpect(jsonPath("$.scopes[0]").value("packs:admin")).andExpect(jsonPath("$.expiresAt").isNotEmpty());
        String plain = token("drishti-dev-admin", "admin", "{\"name\":\"plain\",\"days\":30}");
        mvc.perform(get("/api/v1/me/token").header("Authorization", plain)).andExpect(status().isOk()).andExpect(jsonPath("$.scopes[0]").value("read"));
        mvc.perform(get("/api/v1/me/token").header("Authorization", as("drishti-dev-admin", "admin"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("session")));
    }
}
