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
package com.ash.drishti.server.collab.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.ShareItemRenderer;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Watermarked snapshots (COLLABORATION.md, step 10), over the whole stack with snapshots on: the picture is computed for the most
 * restrictive recipient (asserted on what the layout draws, before any pixel), a recipient who may not open the kind is left out, a pack
 * can forbid pictures, the watermark names the sender, the recipients, the time, the data's date and generation and the link, every
 * picture is in the access log, and the email carries it as an attachment.
 *
 * <p>Users: ann (risk, raw), rng (risk, raw), ravi (trader, masked), sam (ops: no trades). The trade view shows a notional of 50,000,000, a field masked in this test.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.identity.database-url=jdbc:sqlite:target/snapshot-api-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/snapshot-api-overlay/added.yaml", "drishti.collab.limits.shares-per-minute=1000",
        "drishti.collab.store=file", "drishti.collab.dir=target/snapshot-api-${random.uuid}/collab", "drishti.collab.console-url=https://drishti.test",
        "drishti.security.redact=trader,notional", "drishti.collab.snapshots.enabled=true", "drishti.collab.snapshots.max-panels=50", "drishti.collab.snapshots.max-rows=200", "drishti.collab.snapshots.max-height=8000", "drishti.collab.packs.logistics.snapshots.enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotApiTest {

    static final String TRADE = "IRS-48213";
    static final String SECRET = "50,000,000";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired Principals principals;
    @Autowired SnapshotService snapshots;
    @Autowired ShareItemRenderer mail;
    @Autowired AccessLog accessLog;
    final ObjectMapper json = new ObjectMapper();

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    @BeforeAll
    void users() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        for (String[] u : new String[][] {{"ann", "risk"}, {"rng", "risk"}, {"ravi", "trader"}, {"sam", "ops"}}) {
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", u[0], "displayName", u[0].toUpperCase() + " Person", "email", u[0] + "@desk.test",
                            "roles", List.of(u[1]), "packs", List.of("finance", "logistics"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    Map<String, Object> req(List<String> users, Boolean picture) {
        return Map.of("kind", "trade", "id", TRADE, "note", "look at the curve", "generation", 0, "to", Map.of("users", users, "roles", List.of()),
                "picture", picture);
    }

    Share draft(String sender) {
        return new Share("sh_draftdraftdraft01", sender, Instant.parse("2026-10-05T09:30:00Z"), "trade", TRADE, null, null,
                new Pin(null, true, Instant.parse("2026-10-05T09:30:00Z"), 0, null), "x", List.of(), "in-app,picture", null, null);
    }

    Principal p(String user) {
        return principals.of(user);
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---- the most restrictive recipient -------------------------------------------------------------------------------

    @Test
    void theMasksOfTheLeastPrivilegedRecipientApplyToTheWholePicture() {
        String forRaw = snapshots.model(draft("ann"), List.of(p("rng"))).allText();
        String forBoth = snapshots.model(draft("ann"), List.of(p("rng"), p("ravi"))).allText();
        assertThat(forRaw).as("a raw audience sees the field").contains(SECRET);
        assertThat(forBoth).as("one masked recipient masks it for everyone").doesNotContain(SECRET).contains(DataNode.MASK);
    }

    @Test
    void aRecipientWhoMayNotOpenTheKindIsNotInThePictureAndAloneGetsNone() {
        assertThat(snapshots.model(draft("ann"), List.of(p("rng"), p("sam"))).allText()).contains("1 recipient");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshots.model(draft("ann"), List.of(p("sam"))))
                .hasMessageContaining("no recipient may open");
    }

    @Test
    void theWatermarkNamesSenderRecipientsTimeDataDateGenerationAndLink() {
        String text = snapshots.model(draft("ann"), List.of(p("rng"), p("ravi"))).allText();
        assertThat(text).contains("Shared by ANN Person").contains("2 recipients").contains("2026-10-05 09:30 UTC").contains("generation")
                .contains("https://drishti.test/share/sh_draftdraftdraft01").contains("Data as of");
    }

    // ---- the API ---------------------------------------------------------------------------------------------------------

    @Test
    void theDialogLearnsWhetherAKindMayHaveAPicture() throws Exception {
        mvc.perform(get("/api/v1/collab").param("kind", "trade").header("Authorization", as("ann", "risk"))).andExpect(jsonPath("$.snapshots").value(true));
        mvc.perform(get("/api/v1/collab").param("kind", "shipment").header("Authorization", as("ann", "risk"))).andExpect(jsonPath("$.snapshots").value(false));
        mvc.perform(get("/api/v1/collab").header("Authorization", as("ann", "risk"))).andExpect(jsonPath("$.snapshots").value(false));
    }

    @Test
    void aPackCanForbidPicturesForItsKinds() throws Exception {
        mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "shipment", "id", "SHP-10042", "note", "x", "to", Map.of("users", List.of("rng")), "picture", true))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7015"));
    }

    @Test
    void aSharePicturePreviewIsAPngMaskedForTheLeastPrivilegedAndRecordedInTheAccessLog() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/shares/preview-picture").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req(List.of("rng", "ravi"), true)))).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsByteArray()).startsWith(PNG);
        assertThat(r.getResponse().getHeader("X-Drishti-Snapshot-Masked")).isEqualTo("true");
        assertThat(accessLog.find(new AccessLog.Filter("ann", "export", "trade", TRADE, null, null, 50)))
                .anyMatch(e -> e.detail().startsWith("snapshot preview for 2 recipients, masked"));
    }

    @Test
    void aPreviewForRecipientsWhoCannotOpenTheKindIsRefused() throws Exception {
        mvc.perform(post("/api/v1/shares/preview-picture").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req(List.of("sam"), true)))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7015"));
    }

    @Test
    void aShareWithAPictureCarriesItForItsPartiesAndStrangersLearnNothing() throws Exception {
        JsonNode res = body(mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req(List.of("rng", "ravi", "sam"), true)))).andExpect(status().isCreated()).andReturn());
        String id = res.get("id").asText();
        assertThat(res.get("delivered").asInt()).as("sam may not open trades: no share, no picture for him").isEqualTo(2);
        MvcResult pic = mvc.perform(get("/api/v1/shares/" + id + "/picture").header("Authorization", as("ravi", "trader"))).andExpect(status().isOk()).andReturn();
        assertThat(pic.getResponse().getContentAsByteArray()).startsWith(PNG);
        assertThat(pic.getResponse().getHeader("Cache-Control")).contains("no-store");
        mvc.perform(get("/api/v1/shares/" + id + "/picture").header("Authorization", as("ann", "risk"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/shares/" + id + "/picture").header("Authorization", as("sam", "ops"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("rng", "risk"))).andExpect(jsonPath("$.picture").value(true));
        assertThat(accessLog.find(new AccessLog.Filter("ravi", "export", "trade", TRADE, null, null, 50))).anyMatch(e -> e.detail().contains(id));
    }

    @Test
    void aShareWithoutAPictureHasNoPictureEndpoint() throws Exception {
        String id = body(mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req(List.of("rng"), false)))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(get("/api/v1/shares/" + id + "/picture").header("Authorization", as("rng", "risk"))).andExpect(status().isNotFound());
    }

    @Test
    void aSecondPictureOfTheSameShareComesFromTheCache() {
        Share s = draft("ann");
        assertThat(snapshots.render(s, List.of(p("rng")), "ann", false).cached()).isFalse();
        assertThat(snapshots.render(s, List.of(p("rng")), "ann", false).cached()).isTrue();
    }

    // ---- the email --------------------------------------------------------------------------------------------------------

    @Test
    void theEmailForAShareWithAPictureCarriesTheMaskedPicture() throws Exception {
        String id = body(mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req(List.of("rng", "ravi"), true)))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        MailRenderer.Content c = mail.content(new OutboxItem(1, "email", "rng", "share", id, "pending", 0, null, null, null, null, Instant.now(), null), p("rng"));
        assertThat(c.image()).as("rng holds raw, but the picture is for the least privileged of the share").startsWith(PNG);
    }
}
