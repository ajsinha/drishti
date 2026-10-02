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
        "drishti.security.metrics-token=scrape-token-for-tests",
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

    @Test
    void operationalEndpointsAreGuardedWhenSecurityIsOn() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());                                    // probes stay open
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info").header("Authorization", "Bearer scrape-token-for-tests")).andExpect(status().isOk());   // tests export no Prometheus registry
        mvc.perform(get("/actuator/metrics").header("Authorization", as("tina", "trader"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", as("drishti-dev-admin", "admin"))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/role-definitions/ops-admin").header("Authorization", as("drishti-dev-admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"kinds\":[\"*\"],\"admin\":true}")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics").header("Authorization", as("olga", "ops-admin"))).andExpect(status().isOk());  // a defined role with the admin power
        mvc.perform(get("/api/docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/docs").header("Authorization", as("tina", "trader"))).andExpect(status().isOk());
    }

    @Test
    void consoleSessionsFollowTheUser() throws Exception {
        // QA 2026-10-01 SEC-01/SEC-05: the console asks per request; a disabled, demoted or signed-out user is told at once
        String admin = as("drishti-dev-admin", "admin");
        String console = as("console", "service");
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sid\",\"roles\":[\"admin\"],\"password\":\"admin-pass-123\"}")).andExpect(status().isCreated());
        String body = mvc.perform(post("/api/v1/auth/sessions").header("Authorization", console).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sid\",\"seconds\":3600}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.roles").value(hasItem("admin")))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("id").asText();
        mvc.perform(get("/api/v1/auth/sessions/" + id).header("Authorization", as("sid", "admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/sessions/" + id).header("Authorization", console))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("sid")).andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(put("/api/v1/admin/users/sid").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"trader\"]}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/sessions/" + id).header("Authorization", console))
                .andExpect(jsonPath("$.user.roles[0]").value("trader"));                                       // demoted: current roles
        mvc.perform(post("/api/v1/admin/users/sid/enabled").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/sessions/" + id).header("Authorization", console)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/tokens").header("Authorization", as("sid", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"after disable\"}")).andExpect(status().isForbidden());                  // no API token either
        mvc.perform(post("/api/v1/auth/sessions").header("Authorization", console).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sid\",\"seconds\":3600}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users/sid/enabled").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/sessions/" + id).header("Authorization", console)).andExpect(status().isUnauthorized()); // not revived

        String again = mvc.perform(post("/api/v1/auth/sessions").header("Authorization", console).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sid\",\"seconds\":3600}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id2 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(again).path("id").asText();
        mvc.perform(delete("/api/v1/auth/sessions/" + id2).header("Authorization", console)).andExpect(status().isNoContent());   // sign-out
        mvc.perform(get("/api/v1/auth/sessions/" + id2).header("Authorization", console)).andExpect(status().isUnauthorized());
    }

    @Test
    void personalApiTokensReadAsTheirUserAndNeverWrite() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"nora\",\"roles\":[\"trader\"],\"password\":\"trader-pass-12\"}")).andExpect(status().isCreated());
        String body = mvc.perform(post("/api/v1/me/tokens").header("Authorization", as("nora", "trader")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Risk notebook\",\"days\":30}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token.active").value(true)).andReturn().getResponse().getContentAsString();
        String secret = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("secret").asText();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).at("/token/id").asText();
        org.assertj.core.api.Assertions.assertThat(secret).startsWith("drk_" + id + "_");
        String bearer = "Bearer " + secret;

        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer)).andExpect(jsonPath("$.username").value("nora"));
        mvc.perform(post("/api/v1/me/tokens").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());                                                           // tokens only read
        mvc.perform(get("/api/v1/me/tokens").header("Authorization", as("nora", "trader")))
                .andExpect(jsonPath("$[0].name").value("Risk notebook")).andExpect(jsonPath("$[0].secretHash").doesNotExist());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + secret.substring(0, secret.length() - 2) + "xx"))
                .andExpect(status().isUnauthorized());                                                        // a wrong secret

        mvc.perform(post("/api/v1/admin/users/nora/enabled").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());   // a disabled user's token
        mvc.perform(post("/api/v1/admin/users/nora/enabled").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/tokens").header("Authorization", admin)).andExpect(jsonPath("$[*].user").value(hasItem("nora")));
        mvc.perform(delete("/api/v1/admin/tokens/" + id).header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());   // revoked
        mvc.perform(get("/api/v1/admin/audit").param("subject", "nora").header("Authorization", admin))
                .andExpect(jsonPath("$[*].action").value(hasItem("token-created")));
    }

    @Test
    void notesAreReadByWhoeverMayOpenTheKindAndChangedByTheirAuthor() throws Exception {
        String tess = as("tess", "trader");
        String ravi = as("ravi", "viewer");
        String created = mvc.perform(post("/api/v1/notes/trade/IRS-48213").header("Authorization", tess).contentType("application/json")
                .content("{\"body\": \"Restated after the fixing correction\", \"path\": \"$.mtm\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.author").value("tess")).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(get("/api/v1/notes/trade/IRS-48213").header("Authorization", ravi)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("$.mtm"));
        mvc.perform(put("/api/v1/notes/" + id).header("Authorization", ravi).contentType("application/json").content("{\"body\": \"no\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notes/netting-set/NS-NORTH-01").header("Authorization", tess)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/notes/" + id).header("Authorization", ravi)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/notes/" + id).header("Authorization", as("ada", "admin"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notes/trade/IRS-48213").header("Authorization", ravi)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aSharedWorkspaceIsReadOnlyAndHidesPanesTheReaderMayNotOpen() throws Exception {
        String ada = as("ada", "admin");
        String tess = as("tess", "trader");
        String ravi = as("ravi", "viewer");
        String ws = "{\"layout\": \"2col\", \"panes\": [{\"ref\": {\"kind\": \"trade\", \"id\": \"IRS-48213\"}},"
                + " {\"ref\": {\"kind\": \"netting-set\", \"id\": \"NS-NORTH-01\"}}]}";
        mvc.perform(put("/api/v1/me/workspaces/desk").header("Authorization", ada).contentType("application/json").content(ws))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/me/workspaces/desk/share").header("Authorization", ada).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/workspaces/desk/share").header("Authorization", ada).contentType("application/json")
                .content("{\"roles\": [\"trader\"]}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/workspaces/shared").header("Authorization", tess)).andExpect(jsonPath("$[0].owner").value("ada"));
        mvc.perform(get("/api/v1/workspaces/shared/ada/desk").header("Authorization", tess)).andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.panes[0].ref.id").value("IRS-48213"))
                .andExpect(jsonPath("$.panes[1].hidden").value(true));                   // a trader may not open netting sets
        mvc.perform(get("/api/v1/workspaces/shared").header("Authorization", ravi)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/workspaces/shared/ada/desk").header("Authorization", ravi)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/me/workspaces/desk/share").header("Authorization", ada).contentType("application/json")
                .content("{\"everyone\": true}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/workspaces/shared/ada/desk").header("Authorization", ravi)).andExpect(jsonPath("$.panes[1].ref.id").value("NS-NORTH-01"));
        mvc.perform(delete("/api/v1/me/workspaces/desk").header("Authorization", ada)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/workspaces/shared").header("Authorization", ravi)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/me/workspaces/desk/share").header("Authorization", ada)).andExpect(status().isNotFound());
    }

    @Test
    void readsAreRecordedAndOnlyAdministratorsSeeWhoReadWhat() throws Exception {
        String tess = as("tess", "trader");
        String ada = as("ada", "admin");
        mvc.perform(get("/api/v1/views/trade/IRS-48213").header("Authorization", tess)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/entities/trade/IRS-48213/raw").header("Authorization", tess).header("X-Drishti-As-Of", "2026-09-29"));
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm < 0").header("Authorization", tess)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/views/netting-set/NS-NORTH-01").header("Authorization", tess)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/access").param("user", "tess").header("Authorization", ada)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("search"))
                .andExpect(jsonPath("$[0].detail").value("TRD where mtm < 0"))
                .andExpect(jsonPath("$[?(@.action=='view')].entityId").value(org.hamcrest.Matchers.hasItem("IRS-48213")))
                .andExpect(jsonPath("$[?(@.entityId=='NS-NORTH-01')]").isEmpty());          // a refused read is not a read
        mvc.perform(get("/api/v1/admin/access").param("kind", "trade").param("id", "IRS-48213").param("action", "raw").header("Authorization", ada))
                .andExpect(jsonPath("$[0].user").value("tess"));
        mvc.perform(get("/api/v1/admin/access").header("Authorization", tess)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/access").param("from", "yesterday").header("Authorization", ada)).andExpect(status().isBadRequest());
    }
}
