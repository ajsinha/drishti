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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Personal API tokens with write scopes: the scope matrix, the roles ceiling, expiry, disabled users and the audit trail. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class TokenScopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier signer;
    @Autowired IdentityRepositories.ApiTokens tokenRows;

    private String as(String user, String... roles) {
        return "Bearer " + signer.mint(user, List.of(roles), 300);
    }

    private void user(String name, String role) throws Exception {
        mvc.perform(post("/api/v1/admin/users").header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"roles\":[\"" + role + "\"],\"password\":\"scoped-pass-12\"}")).andExpect(status().isCreated());
    }

    /** Makes a token for the user (signed in as them) and returns its bearer header. */
    private String token(String user, String role, String body) throws Exception {
        var res = mvc.perform(post("/api/v1/me/tokens").header("Authorization", as(user, role)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
        String out = res.getContentAsString();
        return "Bearer " + JSON.readTree(out).path("secret").asText();
    }

    private ResultActions send(String bearer, String method, String path) throws Exception {
        var b = switch (method) {
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> post(path);
        };
        return mvc.perform(b.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private int code(String bearer, String method, String path) throws Exception {
        return send(bearer, method, path).andReturn().getResponse().getStatus();
    }

    private static String idOf(String bearer) {
        return bearer.substring("Bearer drk_".length(), "Bearer drk_".length() + 12);
    }

    @Test
    void readTokensCannotWriteAndWriteScopesOpenOnlyTheirDoor() throws Exception {
        user("ada", "author");
        String read = token("ada", "author", "{\"name\":\"plain\",\"days\":30}");
        String write = token("ada", "author", "{\"name\":\"ci\",\"days\":30,\"scopes\":[\"design:write\"]}");
        send(read, "POST", "/api/v1/builder/designs").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("this token lacks the scope design:write"));
        send(write, "POST", "/api/v1/builder/designs").andExpect(status().isCreated());                       // design:write creates a design
        assertThat(code(write, "POST", "/api/v1/sutras/proposals/nope/approve")).isEqualTo(403);              // but cannot approve
        assertThat(code(write, "PUT", "/api/v1/admin/packs/market-risk")).isEqualTo(403);                     // nor administer packs
        assertThat(code(write, "POST", "/api/v1/me/tokens")).isEqualTo(403);                                  // nor mint tokens
        assertThat(code(write, "POST", "/api/v1/admin/users")).isEqualTo(403);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", write)).andExpect(jsonPath("$.username").value("ada"));   // and it still reads
    }

    @Test
    void aTokenNeverExceedsItsUsersRoles() throws Exception {
        user("ari", "author");
        user("rae", "approver");
        user("pat", "author");
        String authorApprove = token("ari", "author", "{\"name\":\"a\",\"days\":30,\"scopes\":[\"design:approve\"]}");
        String approverApprove = token("rae", "approver", "{\"name\":\"a\",\"days\":30,\"scopes\":[\"design:approve\"]}");
        String authorPacks = token("pat", "author", "{\"name\":\"p\",\"days\":30,\"scopes\":[\"packs:admin\"]}");
        String adminPacks = token("drishti-dev-admin", "admin", "{\"name\":\"p\",\"days\":30,\"scopes\":[\"packs:admin\"]}");
        // the scope opens the door; the role behind it decides
        assertThat(code(authorApprove, "POST", "/api/v1/sutras/proposals/nope/approve")).isEqualTo(403);
        assertThat(code(approverApprove, "POST", "/api/v1/sutras/proposals/nope/approve")).isNotIn(401, 403);   // unknown proposal, not a refusal
        assertThat(code(authorPacks, "PUT", "/api/v1/admin/packs/market-risk")).isEqualTo(403);
        assertThat(code(adminPacks, "PUT", "/api/v1/admin/packs/market-risk")).isNotIn(401, 403);
    }

    @Test
    void writeTokensMustExpireAndScopesMustExist() throws Exception {
        user("eli", "author");
        for (String body : new String[] {"{\"name\":\"x\",\"scopes\":[\"design:write\"]}",                        // no expiry
                "{\"name\":\"x\",\"days\":91,\"scopes\":[\"design:write\"]}",                                     // longer than the maximum
                "{\"name\":\"x\",\"days\":30,\"scopes\":[\"root\"]}"}) {                                          // not a scope
            mvc.perform(post("/api/v1/me/tokens").header("Authorization", as("eli", "author")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/me/tokens").header("Authorization", as("eli", "author")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\",\"scopes\":[\"read\"]}")).andExpect(status().isCreated());              // read keeps today's rules
        mvc.perform(get("/api/v1/me/tokens/scopes").header("Authorization", as("eli", "author")))
                .andExpect(jsonPath("$.writeMaxDays").value(90)).andExpect(jsonPath("$.scopes[*].name").value(hasItem("packs:admin")));
        mvc.perform(get("/api/v1/me/tokens").header("Authorization", as("eli", "author"))).andExpect(jsonPath("$[0].scopes").isArray());
    }

    @Test
    void expiredAndDisabledUsersTokensFail() throws Exception {
        user("exa", "author");
        user("dis", "author");
        String expiring = token("exa", "author", "{\"name\":\"old\",\"days\":30,\"scopes\":[\"design:write\"]}");
        var row = tokenRows.findById(idOf(expiring)).orElseThrow();
        row.expiresAt = Instant.now().minusSeconds(60);
        tokenRows.save(row);
        send(expiring, "POST", "/api/v1/builder/designs").andExpect(status().isUnauthorized());
        String live = token("dis", "author", "{\"name\":\"ci\",\"days\":30,\"scopes\":[\"design:write\"]}");
        send(live, "POST", "/api/v1/builder/designs").andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/users/dis/enabled").header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        send(live, "POST", "/api/v1/builder/designs").andExpect(status().isUnauthorized());
    }

    @Test
    void everyWriteDoneWithATokenIsAudited() throws Exception {
        user("aud", "author");
        String bearer = token("aud", "author", "{\"name\":\"ci\",\"days\":30,\"scopes\":[\"design:write\"]}");
        String id = idOf(bearer);
        send(bearer, "POST", "/api/v1/builder/designs").andExpect(status().isCreated());
        send(bearer, "POST", "/api/v1/admin/users").andExpect(status().isForbidden());
        String audit = mvc.perform(get("/api/v1/admin/audit").param("limit", "500").header("Authorization", as("drishti-dev-admin", "admin")))
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("token-write").contains("token " + id + " POST /api/v1/builder/designs -> 201")
                .contains("token-denied").contains("token " + id + " POST /api/v1/admin/users -> 403").doesNotContain("drk_");
        mvc.perform(delete("/api/v1/me/tokens/" + id).header("Authorization", as("aud", "author"))).andExpect(status().isNoContent());   // revoke
        send(bearer, "POST", "/api/v1/builder/designs").andExpect(status().isUnauthorized());
    }
}
