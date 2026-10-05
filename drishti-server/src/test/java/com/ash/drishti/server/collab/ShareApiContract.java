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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.collab.InboxStore;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Share with a note, over the whole stack (security on, banking packs): recipients are resolved through the directory, roles and
 * pack assignment; each is checked at delivery and again when they open; notes and notices never carry a masked value or a data
 * value; the access log records the send at once. Run against both stores ({@link ShareApiJpaTest}, {@link ShareApiFileTest}).
 *
 * <p>Users: ann (sender, role risk, finance), ravi (trader, finance), rng (risk, finance), kay (risk, finance and logistics), sam (ops,
 * finance: his role does not open trades), dkim (risk, logistics only: the finance pack is not assigned), lena (ops, logistics).
 * The trade IRS-48213's trader is "A. Shah", a masked field.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ShareApiContract {

    static final String TRADE = "IRS-48213";
    static final String SECRET = "A. Shah";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired Principals principals;
    @Autowired InboxStore inboxStore;
    @Autowired InboxHub hub;
    final ObjectMapper json = new ObjectMapper();

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    void user(String name, List<String> roles, List<String> packs) throws Exception {
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "displayName", name.toUpperCase() + " Person", "email", name + "@desk.test",
                        "roles", roles, "packs", packs, "password", "long-enough-pass-1")))).andExpect(status().isCreated());
    }

    @BeforeAll
    void users() throws Exception {
        user("ann", List.of("risk"), List.of("finance"));
        user("ravi", List.of("trader"), List.of("finance"));
        user("rng", List.of("risk"), List.of("finance"));
        user("kay", List.of("risk"), List.of("finance", "logistics"));
        user("sam", List.of("ops"), List.of("finance"));
        user("dkim", List.of("risk"), List.of("logistics"));
        user("lena", List.of("ops"), List.of("logistics"));
        user("spam", List.of("risk"), List.of("finance"));
    }

    ResultActions send(String sender, String senderRole, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/shares").header("Authorization", as(sender, senderRole)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    Map<String, Object> share(String note, List<String> users, List<String> roles) {
        return Map.of("kind", "trade", "id", TRADE, "note", note, "generation", 0, "to", Map.of("users", users, "roles", roles));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---- the send ------------------------------------------------------------------------------------------------------

    @Test
    void aShareReachesUsersAndRolesWhoMayOpenTheKindAndReportsTheRest() throws Exception {
        String note = SECRET + " says the curve moved: can you confirm?";
        long ravi0 = unread("ravi", "trader"), sam0 = unread("sam", "ops"), dkim0 = unread("dkim", "risk"), rng0 = unread("rng", "risk");
        JsonNode res = body(send("ann", "risk", share(note, List.of("ravi", "sam"), List.of("risk")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("sh_")))
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.startsWith("/share/sh_"))));
        // delivered: ravi (trader opens trades), rng, kay and spam (risk, finance assigned); sam's role (ops) does not open trades; dkim has no finance pack
        assertThat(res.get("delivered").asInt()).isEqualTo(4);
        List<String> skippedNames = res.get("skipped").findValuesAsText("name");
        assertThat(skippedNames).contains("sam").doesNotContain("ravi", "rng", "kay");
        assertThat(res.get("skipped").toString()).contains("may not open trade views");
        // dkim shares no pack with ann: never named to her, only counted inside the role
        assertThat(res.toString()).doesNotContain("dkim").doesNotContain("lena");
        assertThat(res.get("skipped").toString()).contains("role:risk");
        assertThat(res.get("warnings").toString()).contains("hidden from some readers");
        assertThat(res.get("pin").get("generation").asInt()).isZero();

        // inbox rows only for those reached
        assertThat(unread("ravi", "trader")).isEqualTo(ravi0 + 1);
        assertThat(unread("rng", "risk")).isEqualTo(rng0 + 1);
        assertThat(unread("sam", "ops")).as("no right to the kind: nothing is sent").isEqualTo(sam0);
        assertThat(unread("dkim", "risk")).as("finance not assigned: nothing is sent").isEqualTo(dkim0);
    }

    long unread(String user, String role) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/me/inbox/count").header("Authorization", as(user, role))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("unread").asLong();
    }

    @Test
    void theNoteAndTheNoticeShowAMaskedValueAsTheMaskToReadersWithoutRaw() throws Exception {
        JsonNode res = body(send("ann", "risk", share("Ask " + SECRET + " about the fixing", List.of("ravi"), List.of("risk"))).andExpect(status().isCreated()));
        String id = res.get("id").asText();

        // ravi's role (trader) is not raw: the inbox row, the share and the view all show the mask
        String ravi = as("ravi", "trader");
        String row = mvc.perform(get("/api/v1/me/inbox").header("Authorization", ravi).param("limit", "1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(row).contains("•••").doesNotContain(SECRET);
        JsonNode first = json.readTree(row).get(0);
        assertThat(first.get("access").asBoolean()).isTrue();
        assertThat(first.get("shareId").asText()).isEqualTo(id);
        assertThat(first.get("title").asText()).contains("ANN Person shared trade " + TRADE);
        String opened = mvc.perform(get("/api/v1/shares/" + id).header("Authorization", ravi)).andExpect(status().isOk())
                .andExpect(jsonPath("$.access").value(true)).andExpect(jsonPath("$.role").value("recipient"))
                .andExpect(jsonPath("$.recipients").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(opened).contains("Ask ••• about the fixing").doesNotContain(SECRET);
        String view = mvc.perform(get("/api/v1/views/trade/" + TRADE).header("Authorization", ravi)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(view).doesNotContain(SECRET);

        // rng has raw: the same share reads as written
        String rng = mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("rng", "risk"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(rng).contains("Ask " + SECRET + " about the fixing");
        // and the sender (risk, raw) sees who it went to, and that ravi opened it
        mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("ann", "risk"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("sender")).andExpect(jsonPath("$.recipients[?(@.name=='ravi')].openedAt").exists());
    }

    @Test
    void aNoticeCarriesNoDataValueOnlyWhoWhatKindAndEntityId() throws Exception {
        send("ann", "risk", share("have a look", List.of("rng"), List.of())).andExpect(status().isCreated());
        String row = mvc.perform(get("/api/v1/me/inbox").header("Authorization", as("rng", "risk")).param("limit", "1")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode r = json.readTree(row).get(0);
        assertThat(r.fieldNames()).toIterable().containsExactlyInAnyOrder("seq", "at", "type", "actor", "actorName", "kind", "id", "panel", "shareId",
                "threadId", "commentId", "read", "access", "title", "excerpt");
        String doc = mvc.perform(get("/api/v1/entities/trade/" + TRADE + "/raw").header("Authorization", as("rng", "risk"))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode d = json.readTree(doc);
        assertThat(d.findValue("trader")).as("the raw document has the masked field").isNotNull();
        assertThat(r.toString()).doesNotContain(d.findValue("trader").asText());
        int checked = 0;
        for (String field : List.of("notional", "mtm", "currency", "counterparty")) {
            JsonNode v = d.findValue(field);
            if (v != null && !v.isContainerNode() && v.asText().length() > 3) {
                assertThat(r.toString()).as(field).doesNotContain(v.asText());
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(0);
    }

    // ---- rights --------------------------------------------------------------------------------------------------------

    @Test
    void aRecipientWithoutTheRightCannotOpenAndLearnsNothingAboutTheEntity() throws Exception {
        JsonNode res = body(send("ann", "risk", share("for rng", List.of("rng"), List.of())).andExpect(status().isCreated()));
        String id = res.get("id").asText();
        mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("rng", "risk"))).andExpect(jsonPath("$.access").value(true));

        // an administrator changes rng's role: from now on the share is a clean "no access"
        mvc.perform(put("/api/v1/admin/users/rng").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"ops\"]}")).andExpect(status().isOk());
        principals.invalidate("rng");
        String denied = mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("rng", "ops"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.access").value(false)).andExpect(jsonPath("$.reason").value("no-access"))
                .andExpect(jsonPath("$.kind").value("trade")).andExpect(jsonPath("$.senderName").value("ANN Person"))
                .andExpect(jsonPath("$.entityId").doesNotExist()).andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.pin").doesNotExist()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(denied).doesNotContain(TRADE).doesNotContain("for rng");
        // the inbox row is rendered for the current rights too
        String row = mvc.perform(get("/api/v1/me/inbox").header("Authorization", as("rng", "ops")).param("limit", "1")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode r = json.readTree(row).get(0);
        assertThat(r.get("access").asBoolean()).isFalse();
        assertThat(r.get("title").asText()).startsWith("(no access)").doesNotContain(TRADE);
        assertThat(r.get("id").isNull()).isTrue();
        mvc.perform(put("/api/v1/admin/users/rng").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"risk\"]}")).andExpect(status().isOk());
        principals.invalidate("rng");
    }

    @Test
    void aStrangerAndAnUnreachedUserGetTheSame404AsAShareThatDoesNotExist() throws Exception {
        JsonNode res = body(send("ann", "risk", share("to ravi only", List.of("ravi", "sam"), List.of())).andExpect(status().isCreated()));
        String id = res.get("id").asText();
        for (String[] who : new String[][] {{"lena", "ops"}, {"dkim", "risk"}, {"sam", "ops"}, {"rng", "risk"}}) {
            mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as(who[0], who[1]))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DRS-7001"));
        }
        mvc.perform(get("/api/v1/shares/sh_01NOSUCHSHAREIDATALL0000").header("Authorization", as("ann", "risk"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRS-7001"));
        mvc.perform(get("/api/v1/shares/not-an-id").header("Authorization", as("ann", "risk"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRS-7001"));
    }

    @Test
    void aRecipientWhoseAssignedPackIsSwitchedOffIsToldAndOffered() throws Exception {
        mvc.perform(put("/api/v1/me/packs").header("Authorization", as("kay", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":[\"logistics\"]}")).andExpect(status().isOk());
        JsonNode res = body(send("ann", "risk", share("for kay", List.of("kay"), List.of())).andExpect(status().isCreated()));
        assertThat(res.get("delivered").asInt()).isEqualTo(1);       // assigned, though not active: still told
        mvc.perform(get("/api/v1/shares/" + res.get("id").asText()).header("Authorization", as("kay", "risk")))
                .andExpect(jsonPath("$.access").value(false)).andExpect(jsonPath("$.reason").value("pack-off"))
                .andExpect(jsonPath("$.pack").value("finance")).andExpect(jsonPath("$.entityId").doesNotExist());
        mvc.perform(put("/api/v1/me/packs").header("Authorization", as("kay", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":[\"finance\",\"logistics\"]}")).andExpect(status().isOk());
    }

    @Test
    void theSenderMustOpenTheKindAndRecipientsAreCheckedAgainstTheDirectory() throws Exception {
        // sam's ops role does not open trades
        send("sam", "ops", share("x", List.of("ann"), List.of())).andExpect(status().isForbidden());
        // an unknown user, a user outside the sender's packs and a role that may not be addressed are all "bad recipients"
        send("ann", "risk", share("x", List.of("nobody"), List.of())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7002"));
        send("ann", "risk", share("x", List.of("lena"), List.of())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7002"));
        send("ann", "risk", share("x", List.of(), List.of("admin"))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7002"));
        send("ann", "risk", share("x", List.of(), List.of("no-such-role"))).andExpect(status().isUnprocessableEntity());
        send("ann", "risk", share("x", List.of(), List.of())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7002"));
        send("ann", "risk", share("x", List.of("ann"), List.of())).andExpect(status().isUnprocessableEntity());   // only yourself
    }

    @Test
    void textIsValidatedAndABadPinIsRefused() throws Exception {
        send("ann", "risk", share("   ", List.of("rng"), List.of())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7011"));
        send("ann", "risk", share("x".repeat(2001), List.of("rng"), List.of())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRS-7011"));
        send("ann", "risk", Map.of("kind", "trade", "id", TRADE, "note", "x", "generation", 999999999, "to", Map.of("users", List.of("rng"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7011")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("generation")));
        send("ann", "risk", Map.of("kind", "trade", "id", "NO-SUCH-TRADE", "note", "x", "to", Map.of("users", List.of("rng")))).andExpect(status().isNotFound());
        send("ann", "risk", Map.of("kind", "trade", "id", TRADE, "note", "x", "to", Map.of("users", List.of("rng")), "channels", Map.of("email", true)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DRS-7012"));
    }

    @Test
    void sharingCanBeSwitchedOffForAPackByConfiguration() throws Exception {
        mvc.perform(post("/api/v1/shares").header("Authorization", as("lena", "ops")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"shipment\",\"id\":\"SHP-10042\",\"note\":\"x\",\"to\":{\"users\":[\"kay\"]}}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-7004"));
    }

    @Test
    void thePinCarriesTheDateGenerationAndSourceOfWhatThePageShowed() throws Exception {
        String today = json.readTree(mvc.perform(get("/api/v1/business-date").header("Authorization", as("ann", "risk"))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("current").asText();
        long gen = json.readTree(mvc.perform(get("/api/v1/views/trade/" + TRADE).header("Authorization", as("ann", "risk")).header("X-Drishti-As-Of", today))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("provenance").path("generation").asLong();
        JsonNode res = body(mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).header("X-Drishti-As-Of", today)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("kind", "trade", "id", TRADE, "note", "pinned", "generation", gen,
                        "panel", "cashflows", "to", Map.of("users", List.of("rng")))))).andExpect(status().isCreated()));
        JsonNode pin = res.get("pin");
        assertThat(pin.get("businessDate").asText()).isEqualTo(today);
        assertThat(pin.get("generation").asLong()).isEqualTo(gen);
        assertThat(pin.get("live").asBoolean()).isFalse();
        assertThat(pin.get("source").asText()).isNotBlank();
        JsonNode opened = json.readTree(mvc.perform(get("/api/v1/shares/" + res.get("id").asText()).header("Authorization", as("rng", "risk"))).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(opened.get("pin")).isEqualTo(pin);
        assertThat(opened.get("panel").asText()).isEqualTo("cashflows");
    }

    // ---- the directory -------------------------------------------------------------------------------------------------

    @Test
    void thePickerNeverListsUsersFromPacksTheSenderCannotSee() throws Exception {
        String ann = as("ann", "risk");
        mvc.perform(get("/api/v1/directory").param("q", "ra").header("Authorization", ann)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(hasItem("ravi"))).andExpect(jsonPath("$[0].email").value(true));
        // lena (logistics only) and dkim (logistics only) share no pack with ann
        String all = mvc.perform(get("/api/v1/directory").param("q", "er").header("Authorization", ann)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(all).doesNotContain("lena").doesNotContain("dkim");
        mvc.perform(get("/api/v1/directory").param("q", "dkim").header("Authorization", ann)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/directory").param("q", "lena").header("Authorization", ann)).andExpect(jsonPath("$.length()").value(0));
        // the other way round: lena sees kay (who has logistics too) and dkim, not ann or ravi
        String lena = as("lena", "ops");
        mvc.perform(get("/api/v1/directory").param("q", "ka").header("Authorization", lena)).andExpect(jsonPath("$[*].name").value(hasItem("kay")));
        mvc.perform(get("/api/v1/directory").param("q", "ravi").header("Authorization", lena)).andExpect(jsonPath("$.length()").value(0));
        // roles are groups, counted by the people the caller can see
        mvc.perform(get("/api/v1/directory").param("q", "risk").header("Authorization", ann)).andExpect(jsonPath("$[?(@.type=='role' && @.name=='risk')].size")
                .value(hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(2))));
        mvc.perform(get("/api/v1/directory").param("q", "admin").header("Authorization", ann)).andExpect(jsonPath("$[?(@.type=='role')]").isEmpty());
        mvc.perform(get("/api/v1/directory").param("q", "r").header("Authorization", ann)).andExpect(status().isBadRequest());
    }

    @Test
    void theSenderLearnsWhoCanReachTheKindOnlyUnderTheTellPolicy() throws Exception {
        mvc.perform(get("/api/v1/directory").param("q", "sa").param("kind", "trade").header("Authorization", as("ann", "risk")))
                .andExpect(jsonPath("$[?(@.name=='sam')].reach").value(hasItem(false)));
        mvc.perform(get("/api/v1/directory").param("q", "ra").param("kind", "trade").header("Authorization", as("ann", "risk")))
                .andExpect(jsonPath("$[?(@.name=='ravi')].reach").value(hasItem(true)));
        mvc.perform(get("/api/v1/directory").param("q", "ra").header("Authorization", as("ann", "risk"))).andExpect(jsonPath("$[0].reach").doesNotExist());
    }

    // ---- powers --------------------------------------------------------------------------------------------------------

    @Test
    void theCollaboratePowerGatesWritesAndTheCompliancePowerReadsAnyShare() throws Exception {
        mvc.perform(put("/api/v1/admin/role-definitions/readonly").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[\"*\"],\"collaborate\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.collaborate").value(false));
        mvc.perform(put("/api/v1/admin/role-definitions/auditor").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[\"*\"],\"compliance\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.compliance").value(true));
        user("quiet", List.of("readonly"), List.of("finance"));
        user("aud", List.of("auditor"), List.of("finance"));
        send("quiet", "readonly", share("x", List.of("ravi"), List.of())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(get("/api/v1/directory").param("q", "ra").header("Authorization", as("quiet", "readonly"))).andExpect(status().isForbidden());
        // any role (the bundled viewer too) keeps collaborate unless a role says otherwise
        JsonNode res = body(send("ann", "risk", share("audit me", List.of("ravi"), List.of())).andExpect(status().isCreated()));
        String id = res.get("id").asText();
        mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("aud", "auditor"))).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("compliance"))
                .andExpect(jsonPath("$.recipients").isArray());
        mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("quiet", "readonly"))).andExpect(status().isNotFound());
    }

    @Test
    void aPersonalApiTokenCannotSend() throws Exception {
        // API tokens read only (as for notes): a POST that is not on the read-post allow-list is refused for them
        String body = mvc.perform(post("/api/v1/me/tokens").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"t\"}")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String secret = json.readTree(body).path("secret").asText();
        assertThat(secret).startsWith("drk_");
        mvc.perform(post("/api/v1/shares").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(share("x", List.of("rng"), List.of())))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/shares").header("Authorization", "Bearer " + secret)).andExpect(status().isOk());
    }

    // ---- the record ------------------------------------------------------------------------------------------------------

    @Test
    void aShareIsInTheAccessLogAtOnceAndAViewOpenedThroughItSaysSo() throws Exception {
        JsonNode res = body(send("ann", "risk", share("log me", List.of("ravi", "sam"), List.of("risk"))).andExpect(status().isCreated()));
        String id = res.get("id").asText();
        // no flush, no wait: it was written in the share's transaction
        mvc.perform(get("/api/v1/admin/access").header("Authorization", admin()).param("action", "share").param("user", "ann").param("limit", "50"))
                .andExpect(jsonPath("$[?(@.entityId=='" + TRADE + "')].detail")
                        .value(hasItem(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.startsWith(id + " to 4 ("), org.hamcrest.Matchers.containsString("user:ravi"),
                                org.hamcrest.Matchers.containsString("role:risk")))));
        // ravi opens the view from the link: the console adds X-Drishti-Share
        mvc.perform(get("/api/v1/views/trade/" + TRADE).header("Authorization", as("ravi", "trader")).header("X-Drishti-Share", id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/access").header("Authorization", admin()).param("action", "view").param("user", "ravi").param("limit", "20"))
                .andExpect(jsonPath("$[*].detail").value(hasItem("share:" + id)));
        mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("ann", "risk"))).andExpect(jsonPath("$.recipients[?(@.name=='ravi')].openedAt").exists());
        // someone who was not a recipient does not open it by sending the header
        mvc.perform(get("/api/v1/views/trade/" + TRADE).header("Authorization", as("kay", "risk")).header("X-Drishti-Share", "sh_NOTAREALSHAREID0000000000")).andExpect(status().isOk());
    }

    @Test
    void theBellStreamGetsAnInboxRowTheMomentTheShareIsSentAndAnotherServersRowsWithinThePoll() throws Exception {
        List<Notice> got = new CopyOnWriteArrayList<>();
        try (var sub = hub.listen("ravi", got::add)) {
            send("ann", "risk", share("live one", List.of("ravi"), List.of())).andExpect(status().isCreated());
            assertThat(got).hasSize(1);
            assertThat(got.get(0).type()).isEqualTo("share");
            // a row written by another server (straight into the shared store): found by the poll
            Notice other = inboxStore.add(new Notice(0, "ravi", Instant.now(), "share", "trade", TRADE, null, "sh_ELSEWHERE", null, null, "ann", null));
            long deadline = System.currentTimeMillis() + 5_000;
            while (got.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(got).extracting(Notice::seq).contains(other.seq());
            Thread.sleep(400);
            assertThat(got).as("each row once").hasSize(2);
        }
    }

    @Test
    void myBoxesListWhatISentAndWhatReachedMe() throws Exception {
        JsonNode res = body(send("kay", "risk", share("from kay", List.of("rng"), List.of())).andExpect(status().isCreated()));
        mvc.perform(get("/api/v1/me/shares").param("box", "sent").header("Authorization", as("kay", "risk"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(res.get("id").asText())).andExpect(jsonPath("$[0].recipients").value(1));
        mvc.perform(get("/api/v1/me/shares").param("box", "received").header("Authorization", as("rng", "risk"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(res.get("id").asText())));
        mvc.perform(get("/api/v1/me/shares").param("box", "received").header("Authorization", as("lena", "ops"))).andExpect(jsonPath("$[*].id")
                .value(not(hasItem(res.get("id").asText()))));
        // read marks
        String ravi = as("ravi", "trader");
        long unread = json.readTree(mvc.perform(get("/api/v1/me/inbox/count").header("Authorization", ravi)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("unread").asLong();
        assertThat(unread).isGreaterThan(0);
        mvc.perform(post("/api/v1/me/inbox/read").header("Authorization", ravi).contentType(MediaType.APPLICATION_JSON).content("{\"upTo\":999999999}"))
                .andExpect(jsonPath("$.unread").value(0));
        mvc.perform(post("/api/v1/me/inbox/read").header("Authorization", ravi).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/collab").header("Authorization", ravi)).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.collaborate").value(true))
                .andExpect(jsonPath("$.compliance").value(false));
    }
}
