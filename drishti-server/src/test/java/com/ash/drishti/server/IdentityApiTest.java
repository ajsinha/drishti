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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class IdentityApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @Test
    void theConsoleVerifiesSignInsForTheSeededAdmin() throws Exception {
        String login = "{\"username\":\"drishti-dev-admin\",\"password\":\"drishti-dev-admin123\"}";
        mvc.perform(post("/api/v1/auth/login").header("Authorization", as("console", "service")).contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(hasItem("admin")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/v1/auth/login").header("Authorization", as("mallory", "admin")).contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Authorization", as("console", "service")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"drishti-dev-admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("DRS-6004"));
        mvc.perform(get("/api/v1/admin/status").header("Authorization", as("drishti-dev-admin", "admin")))
                .andExpect(jsonPath("$.defaultAdminPasswordInUse").value(true));
    }

    @Test
    void adminsManageUsersAndOthersCannot() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tina\",\"displayName\":\"Tina\",\"desk\":\"FX desk\",\"roles\":[\"trader\"],\"password\":\"trader-pass-1\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mustChangePassword").value(false));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", as("tina", "trader"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/users/tina").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"trader\",\"risk\"],\"desk\":\"Risk\"}"))
                .andExpect(jsonPath("$.roles").value(hasItem("risk")));
        mvc.perform(post("/api/v1/admin/users/tina/enabled").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/admin/users/tina/password").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"x\"}")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-6003"));
        mvc.perform(get("/api/v1/admin/users").param("q", "tin").header("Authorization", admin)).andExpect(jsonPath("$[0].username").value("tina"));
        mvc.perform(put("/api/v1/admin/users/drishti-dev-admin").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"risk\"]}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-6006"));
        mvc.perform(delete("/api/v1/admin/users/tina").header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/users/tina").header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", admin))
                .andExpect(jsonPath("$[*].action").value(hasItem("user-deleted")))
                .andExpect(jsonPath("$[*].action").value(hasItem("user-seeded")));
        mvc.perform(get("/api/v1/admin/roles").header("Authorization", admin)).andExpect(jsonPath("$").value(hasItem("admin")));
    }

    @Test
    void usersChangeTheirOwnPassword() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rita\",\"roles\":[\"risk\"],\"password\":\"risk-pass-123\"}")).andExpect(status().isCreated());
        String rita = as("rita", "risk");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", rita)).andExpect(jsonPath("$.username").value("rita"));
        mvc.perform(post("/api/v1/auth/password").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content("{\"current\":\"nope\",\"next\":\"new-risk-pass-9\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/password").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content("{\"current\":\"risk-pass-123\",\"next\":\"new-risk-pass-9\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void adminsDefineRolesThatTakeEffectAtOnceAndCannotDeleteOnesInUse() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(put("/api/v1/admin/role-definitions/fx-viewer").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"FX spot only\",\"kinds\":[\"fx-spot\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.builtIn").value(false)).andExpect(jsonPath("$.updatedBy").value("drishti-dev-admin"));
        mvc.perform(put("/api/v1/admin/role-definitions/admin").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[\"*\"]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/role-definitions/x2").header("Authorization", as("tina", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[\"*\"]}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/role-definitions").header("Authorization", admin))
                .andExpect(jsonPath("$[?(@.name=='admin')].builtIn").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.name=='fx-viewer')].kinds[0]").value(hasItem("fx-spot")));
        mvc.perform(get("/api/v1/admin/roles").header("Authorization", admin)).andExpect(jsonPath("$").value(hasItem("fx-viewer")));

        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"fiona\",\"roles\":[\"fx-viewer\"],\"password\":\"fx-viewer-pass-1\"}")).andExpect(status().isCreated());
        String fiona = as("fiona", "fx-viewer");
        mvc.perform(get("/api/v1/views/fx-spot/EURUSD").header("Authorization", fiona)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", fiona)).andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/admin/role-definitions/fx-viewer").header("Authorization", admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-6009"));
        mvc.perform(put("/api/v1/admin/users/fiona").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"viewer\"]}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/role-definitions/fx-viewer").header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/views/fx-spot/EURUSD").header("Authorization", fiona)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").param("subject", "fx-viewer").header("Authorization", admin))
                .andExpect(jsonPath("$[*].action").value(hasItem("role-deleted")));
    }
}
