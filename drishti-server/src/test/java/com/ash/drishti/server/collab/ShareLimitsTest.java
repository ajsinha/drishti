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
package com.ash.drishti.server.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.Ulid;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Rate limits (per minute and per day, per user, 429 DRS-7003 with Retry-After) and the text policy (a masked value typed into a
 * note with {@code on-masked-copy: reject}, a {@code deny-patterns} match) as configured.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/sharelimits-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/sharelimits-overlay/added.yaml", "drishti.collab.limits.shares-per-minute=2",
        "drishti.collab.limits.shares-per-day=3", "drishti.collab.limits.directory-per-minute=2",
        "drishti.collab.text.on-masked-copy=reject", "drishti.collab.text.deny-patterns[0]=[0-9]{3}-[0-9]{2}-[0-9]{4}",
        "drishti.collab.share.undeliverable=silent"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShareLimitsTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ShareStore store;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    @BeforeAll
    void users() throws Exception {
        for (String u : List.of("minute", "daily", "texty", "target")) {
            mvc.perform(post("/api/v1/admin/users").header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", u, "roles", List.of("risk"), "packs", List.of("finance"), "password", "long-enough-pass-1"))))
                    .andExpect(status().isCreated());
        }
    }

    private ResultActions send(String user, String note) throws Exception {
        return mvc.perform(post("/api/v1/shares").header("Authorization", as(user, "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "trade", "id", "IRS-48213", "note", note, "to", Map.of("users", List.of("target"))))));
    }

    @Test
    void perMinuteLimitRefusesTheThirdShareWithRetryAfterAndOnlyForThatUser() throws Exception {
        send("minute", "one").andExpect(status().isCreated());
        send("minute", "two").andExpect(status().isCreated());
        send("minute", "three").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("DRS-7003"))
                .andExpect(header().exists("Retry-After"));
        send("texty", "someone else is unaffected").andExpect(status().isCreated());
    }

    @Test
    void perDayLimitCountsSharesTheStoreHolds() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < 3; i++) {
            store.save(new Share(Ulid.next("sh_"), "daily", now, "trade", "IRS-48213", null, null, new Pin(null, true, now, 0, null), "earlier",
                    List.of(), "in-app", null, null).signed(), List.of(new Recipient("user:target", "target", Recipient.NOTIFIED, null)));
        }
        send("daily", "one more").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("DRS-7003"))
                .andExpect(header().string("Retry-After", "3600"));
    }

    @Test
    void aMaskedValueInTheNoteIsRefusedUnderRejectAndADenyPatternIsRefused() throws Exception {
        send("texty", "A. Shah moved it").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7011"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("hidden from some readers")));
        send("texty", "ref 123-45-6789").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7011"));
        assertThat(store.sent("texty", 10, null)).extracting(Share::body).doesNotContain("A. Shah moved it", "ref 123-45-6789");
    }

    @Test
    void directorySearchesAreLimitedToo() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/directory").param("q", "ta")
                    .header("Authorization", as("target", "risk"))).andExpect(status().isOk());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/directory").param("q", "ta")
                .header("Authorization", as("target", "risk"))).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void underTheSilentPolicyTheSenderIsNotToldWhoWasSkipped() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/role-definitions/limited")
                .header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON).content("{\"kinds\":[\"curve\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/users").header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "plain", "roles", List.of("limited"), "packs", List.of("finance"), "password", "long-enough-pass-1"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/shares").header("Authorization", as("texty", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "trade", "id", "IRS-48213", "note", "quiet", "to", Map.of("users", List.of("plain", "target"))))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.delivered").value(1)).andExpect(jsonPath("$.skipped.length()").value(0));
    }
}
