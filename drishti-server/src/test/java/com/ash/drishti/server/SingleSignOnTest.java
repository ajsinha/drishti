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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.ash.drishti.server.security.oidc.FakeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** W20: a provider's ID token signs a user in; groups become roles; local disables win; only the console may call. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.security.oidc.enabled=true", "drishti.security.oidc.client-id=drishti",
        "drishti.security.oidc.role-map.desk-rates[0]=trader", "drishti.security.oidc.role-map.market-risk[0]=risk",
        "drishti.security.oidc.role-map.platform[0]=admin"})
@AutoConfigureMockMvc
class SingleSignOnTest {

    static final FakeProvider IDP = newProvider();

    static FakeProvider newProvider() {
        try {
            return new FakeProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry r) {
        r.add("drishti.security.oidc.issuer", IDP::issuer);
    }

    @AfterAll
    static void stop() {
        IDP.close();
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private ResultActions signIn(String caller, String user, List<String> groups) throws Exception {
        String token = IDP.sign("RS256", "rsa-1", IDP.rsa.getPrivate(), IDP.claims(user, "drishti", "nonce-" + user, Map.of("groups", groups)));
        return mvc.perform(post("/api/v1/auth/oidc").header("Authorization", "Bearer " + tokens.mint(caller, List.of(caller.equals("console") ? "service" : "admin"), 60))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("idToken", token, "nonce", "nonce-" + user))));
    }

    @Test
    void groupsBecomeRolesAndTheUserIsProvisionedThenUpdated() throws Exception {
        String ana = "Ana.Lima" + System.nanoTime() + "@Bank.example";
        signIn("console", ana, List.of("desk-rates", "unrelated")).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ana.toLowerCase(java.util.Locale.ROOT).replace('@', '-')))
                .andExpect(jsonPath("$.roles", contains("trader")));
        signIn("console", ana, List.of("desk-rates", "market-risk")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("trader", "risk")));
    }

    @Test
    void noMappedGroupNoEntryAndOnlyTheConsoleMayAsk() throws Exception {
        signIn("console", "stranger", List.of("canteen")).andExpect(status().isUnauthorized());
        signIn("mallory", "mallory", List.of("platform")).andExpect(status().isForbidden());
    }

    @Test
    void aLocallyDisabledUserStaysOut() throws Exception {
        String rui = "rui-" + System.nanoTime();                       // the users file outlives a test run
        signIn("console", rui, List.of("market-risk")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/users/" + rui + "/enabled").header("Authorization", "Bearer " + tokens.mint("root", List.of("admin"), 60))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
        signIn("console", rui, List.of("market-risk")).andExpect(status().isUnauthorized());
    }
}
