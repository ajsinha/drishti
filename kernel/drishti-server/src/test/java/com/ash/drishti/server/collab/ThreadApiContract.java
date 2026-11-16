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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.HashChain;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Comment threads over the whole stack (security on, banking packs, edit window 3 s): visibility is "may open the entity" (kind, pack,
 * and a panel's gate kind); a mention reaches only people who may reach the kind; a masked value typed into a comment is scrubbed for
 * readers without {@code raw} and quotes are rendered per reader; edits are windowed and stale-checked; retract and hide keep the
 * text in the revisions and the chain verifies. Run against both stores ({@link ThreadApiJpaTest}, {@link ThreadApiFileTest}).
 *
 * <p>Users as in {@link ShareApiContract}: ann (risk, finance), ravi (trader, finance, not raw), rng (risk, finance), kay (risk, finance and
 * logistics), sam (ops, finance: ops does not open trades), dkim (risk, logistics only), lena (ops, logistics), spam (risk, finance).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ThreadApiContract {

    static final String TRADE = "IRS-48213";
    static final String SECRET = "A. Shah";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ThreadStore store;
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
        user("tina", List.of("trader"), List.of("finance"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    String text(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    ResultActions startOn(String user, String role, String kind, String id, Map<String, Object> req) throws Exception {
        return mvc.perform(post("/api/v1/threads/" + kind + "/" + id).header("Authorization", as(user, role)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));
    }

    Map<String, Object> panelThread(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor", "panel");
        m.put("panel", "cashflows");
        m.put("generation", 0);
        m.put("body", text);
        return m;
    }

    Map<String, Object> entityThread(String text) {
        return Map.of("anchor", "entity", "generation", 0, "body", text);
    }

    ResultActions listFor(String user, String role, String query) throws Exception {
        return mvc.perform(get("/api/v1/threads/trade/" + TRADE + query).header("Authorization", as(user, role)));
    }

    ResultActions replyTo(String user, String role, String thread, String text) throws Exception {
        return mvc.perform(post("/api/v1/threads/" + thread + "/comments").header("Authorization", as(user, role))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("generation", 0, "body", text))));
    }

    long unread(String user, String role) throws Exception {
        return body(mvc.perform(get("/api/v1/me/inbox/count").header("Authorization", as(user, role)))).get("unread").asLong();
    }

    JsonNode inbox(String user, String role, String type) throws Exception {
        return body(mvc.perform(get("/api/v1/me/inbox").param("type", type).param("limit", "50").header("Authorization", as(user, role))));
    }

    // ---- mentions and the cross-pack rule ------------------------------------------------------------------------------

    @Test
    void aMentionReachesOnlyPeopleWhoMayReachTheKindAndAGenomicsStyleUserGetsNothing() throws Exception {
        long ravi0 = unread("ravi", "trader"), rng0 = unread("rng", "risk"), sam0 = unread("sam", "ops"), dkim0 = unread("dkim", "risk");
        JsonNode res = body(startOn("ann", "risk", "trade", TRADE, panelThread("@rng @ravi @sam is the cashflow leg right? @risk please look"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.threadId").value(org.hamcrest.Matchers.startsWith("th_")))
                .andExpect(jsonPath("$.comment.id").value(org.hamcrest.Matchers.startsWith("cm_"))));
        assertThat(res.get("comment").get("pin").has("generation")).isTrue();
        assertThat(unread("rng", "risk")).as("a named user and a member of the role: one notice, not two").isEqualTo(rng0 + 1);
        assertThat(unread("ravi", "trader")).isEqualTo(ravi0 + 1);
        assertThat(unread("sam", "ops")).as("ops does not open trades: nothing, not even 'someone mentioned you'").isEqualTo(sam0);
        assertThat(unread("dkim", "risk")).as("a member of @risk without the finance pack: nothing").isEqualTo(dkim0);
        assertThat(res.get("skipped").toString()).contains("sam").contains("may not open trade views").contains("role:risk");
        assertThat(res.toString()).doesNotContain("dkim").doesNotContain("lena");
        JsonNode row = inbox("ravi", "trader", "mention").get(0);
        assertThat(row.get("type").asText()).isEqualTo("mention");
        assertThat(row.get("title").asText()).contains("ANN Person mentioned you on trade " + TRADE);
        assertThat(row.get("excerpt").asText()).contains("is the cashflow leg right?");
        assertThat(row.get("threadId").asText()).isEqualTo(res.get("threadId").asText());
    }

    @Test
    void aMentionOfAUserTheAuthorCanSeeButWhoCannotReachTheKindIsReportedNotDelivered() throws Exception {
        long dkim0 = unread("dkim", "risk");
        JsonNode res = body(startOn("kay", "risk", "trade", TRADE, entityThread("@dkim have a look at this swap")).andExpect(status().isCreated()));
        assertThat(unread("dkim", "risk")).as("finance is not assigned to dkim").isEqualTo(dkim0);
        assertThat(res.get("notified").asInt()).isZero();
        assertThat(res.get("skipped").toString()).contains("dkim").contains("does not have the pack for trade views");
    }

    @Test
    void anAtThatNamesNoOneIsPlainTextAndNotifiesNobody() throws Exception {
        JsonNode res = body(startOn("ann", "risk", "trade", TRADE, entityThread("mail me at ann@desk.test or @nobody-here, ok")).andExpect(status().isCreated()));
        assertThat(res.get("notified").asInt()).isZero();
        assertThat(res.get("comment").get("body").asText()).isEqualTo("mail me at ann@desk.test or @nobody-here, ok");
    }

    @Test
    void repliesNotifyTheThreadsFollowersNotTheAuthorAndMutedFollowersAreSkipped() throws Exception {
        JsonNode t = body(startOn("ann", "risk", "trade", TRADE, entityThread("who owns the reset?")).andExpect(status().isCreated()));
        String tid = t.get("threadId").asText();
        replyTo("rng", "risk", tid, "I do").andExpect(status().isCreated());
        long ann0 = unread("ann", "risk"), rng0 = unread("rng", "risk");
        mvc.perform(put("/api/v1/threads/" + tid + "/follow").header("Authorization", as("kay", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"muted\":true}")).andExpect(status().isOk());
        long kay0 = unread("kay", "risk");
        replyTo("spam", "risk", tid, "thanks both").andExpect(status().isCreated());
        assertThat(unread("ann", "risk")).isEqualTo(ann0 + 1);
        assertThat(unread("rng", "risk")).isEqualTo(rng0 + 1);
        assertThat(unread("kay", "risk")).as("muted").isEqualTo(kay0);
        assertThat(inbox("ann", "risk", "reply").get(0).get("title").asText()).contains("SPAM Person replied on trade " + TRADE);
        mvc.perform(delete("/api/v1/threads/" + tid + "/follow").header("Authorization", as("kay", "risk"))).andExpect(status().isNoContent());
    }

    // ---- visibility ----------------------------------------------------------------------------------------------------

    @Test
    void visibilityIsMayOpenTheEntityKindPackAndAPanelsGateKind() throws Exception {
        startOn("ann", "risk", "trade", TRADE, entityThread("visible to whoever opens trades")).andExpect(status().isCreated());
        Map<String, Object> gated = panelThread("only for those who open netting sets");
        gated.put("gateKind", "netting-set");
        String gatedId = body(startOn("ann", "risk", "trade", TRADE, gated).andExpect(status().isCreated())).get("threadId").asText();

        listFor("dkim", "risk", "").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        listFor("sam", "ops", "").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        startOn("dkim", "risk", "trade", TRADE, entityThread("hello")).andExpect(status().isForbidden());

        String ravi = text(listFor("ravi", "trader", "").andExpect(status().isOk()));
        assertThat(ravi).contains("visible to whoever opens trades").doesNotContain("only for those who open netting sets").doesNotContain(gatedId);
        assertThat(text(listFor("rng", "risk", "").andExpect(status().isOk()))).contains(gatedId);
        // the thread is not there for a reader who may not open its gate kind, however it is asked for
        replyTo("ravi", "trader", gatedId, "can I see this?").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-7005"));
        Map<String, Object> counts = json.convertValue(body(mvc.perform(get("/api/v1/threads/trade/" + TRADE + "/counts")
                .header("Authorization", as("ravi", "trader")))), Map.class);
        Map<String, Object> countsRng = json.convertValue(body(mvc.perform(get("/api/v1/threads/trade/" + TRADE + "/counts")
                .header("Authorization", as("rng", "risk")))), Map.class);
        assertThat(((Map<?, ?>) countsRng.get("panels")).get("cashflows")).as("rng counts the gated panel thread").isNotNull();
        assertThat(((Number) counts.get("entity")).intValue()).isPositive();
        assertThat(((Map<?, ?>) counts.get("panels")).get("cashflows")).as("ravi's count leaves it out").satisfiesAnyOf(
                v -> assertThat(v).isNull(), v -> assertThat(((Number) v).intValue()).isLessThan(((Number) ((Map<?, ?>) countsRng.get("panels")).get("cashflows")).intValue()));
        // filters
        assertThat(body(listFor("rng", "risk", "?anchor=panel&panel=cashflows")).findValuesAsText("anchor")).containsOnly("panel");
    }

    // ---- masked values -------------------------------------------------------------------------------------------------

    @Test
    void aMaskedValueTypedIntoACommentIsScrubbedForReadersWithoutRawAndTheAuthorIsWarned() throws Exception {
        JsonNode res = body(startOn("ann", "risk", "trade", TRADE, entityThread("Ask " + SECRET + " about the fixing")).andExpect(status().isCreated()));
        assertThat(res.get("warnings").toString()).contains("hidden from some readers");
        assertThat(res.get("comment").get("body").asText()).as("the author (raw) reads what she wrote").contains(SECRET);
        String cid = res.get("comment").get("id").asText();

        String ravi = text(listFor("tina", "trader", "").andExpect(status().isOk()));
        assertThat(ravi).contains("Ask ••• about the fixing").doesNotContain(SECRET);
        assertThat(text(listFor("rng", "risk", "")).contains("Ask " + SECRET + " about the fixing")).isTrue();
        // the mention inbox, the revisions and the stored span too
        assertThat(text(mvc.perform(get("/api/v1/comments/" + cid + "/revisions").header("Authorization", as("ravi", "trader"))))).doesNotContain(SECRET);
        assertThat(store.comment(cid).orElseThrow().maskedSpans()).hasSize(1);
        // an edit is scrubbed the same way, and the earlier text stays out of a non-raw reader's hands
        mvc.perform(patch("/api/v1/comments/" + cid).header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("body", "Ask " + SECRET + " about the reset", "revision", 1)))).andExpect(status().isOk());
        String hist = text(mvc.perform(get("/api/v1/comments/" + cid + "/revisions").header("Authorization", as("ravi", "trader"))));
        assertThat(hist).contains("about the fixing").contains("about the reset").doesNotContain(SECRET);
    }

    @Autowired com.ash.drishti.identity.AuditLog auditLog;

    @Test
    void anAuthorWithoutRawLearnsNothingFromAHitSoAGuessCannotBeConfirmed() throws Exception {
        JsonNode hit = body(startOn("ravi", "trader", "trade", TRADE, entityThread("is it " + SECRET + "?")).andExpect(status().isCreated()));
        JsonNode miss = body(startOn("ravi", "trader", "trade", TRADE, entityThread("is it Zzz Qqq?")).andExpect(status().isCreated()));
        assertThat(hit.get("warnings")).as("no signal to the author on a hit").isEqualTo(miss.get("warnings"));
        assertThat(hit.get("warnings")).isEmpty();
        assertThat(hit.get("comment").get("body").asText()).as("the author reads their own text verbatim").isEqualTo("is it " + SECRET + "?");
        assertThat(miss.get("comment").get("body").asText()).isEqualTo("is it Zzz Qqq?");
        String forTina = text(listFor("tina", "trader", ""));
        assertThat(forTina).contains("is it •••?").contains("is it Zzz Qqq?").doesNotContain(SECRET);
        assertThat(text(listFor("rng", "risk", ""))).contains("is it " + SECRET + "?");
        assertThat(text(listFor("ravi", "trader", ""))).as("the author's own list is the same for a hit and a miss").contains("is it " + SECRET + "?");
        assertThat(auditLog.recent(200, "ravi")).extracting(com.ash.drishti.identity.AuditLog.Event::action).contains("collab.masked-copy");
    }

    @Test
    void aTokenForAMissingOrDeletedUserIsRefusedEverywhere() throws Exception {
        mvc.perform(get("/api/v1/me/inbox/count").header("Authorization", as("qa-ghost", "risk"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/threads/trade/" + TRADE).header("Authorization", as("qa-ghost", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(entityThread("from nobody")))).andExpect(status().isUnauthorized());
        user("qa-gone", List.of("risk"), List.of("finance"));
        mvc.perform(get("/api/v1/me/inbox/count").header("Authorization", as("qa-gone", "risk"))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/users/qa-gone").header("Authorization", admin())).andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/me/inbox/count").header("Authorization", as("qa-gone", "risk"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/directory").param("q", "an").header("Authorization", as("qa-gone", "risk"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aQuoteIsFilledFromTheReadersOwnViewSoAMaskedFieldStaysMasked() throws Exception {
        JsonNode res = body(startOn("ann", "risk", "trade", TRADE, entityThread("trader {$.trader}, notional {$.notional}, gone {$.nothing.here}"))
                .andExpect(status().isCreated()));
        String author = res.get("comment").get("body").asText();
        assertThat(author).contains("trader " + SECRET).contains("gone —");
        JsonNode ravi = body(listFor("ravi", "trader", "")).get(0).get("items");
        String forRavi = ravi.findValuesAsText("body").stream().filter(b -> b.startsWith("trader")).findFirst().orElseThrow();
        assertThat(forRavi).contains("trader •••").doesNotContain(SECRET).contains("gone —");
        assertThat(text(listFor("ravi", "trader", ""))).contains("\"t\":\"quote\"").contains("\"path\":\"$.trader\"");
    }

    @Test
    void textIsPlainAndValidated() throws Exception {
        JsonNode res = body(startOn("ann", "risk", "trade", TRADE, entityThread("<script>alert(1)</script> {{7*7}} javascript:x")).andExpect(status().isCreated()));
        assertThat(res.get("comment").get("body").asText()).isEqualTo("<script>alert(1)</script> {{7*7}} javascript:x");
        startOn("ann", "risk", "trade", TRADE, entityThread("   ")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7011"));
        startOn("ann", "risk", "trade", TRADE, entityThread("x".repeat(4001))).andExpect(status().isUnprocessableEntity());
        Map<String, Object> badPin = new LinkedHashMap<>(entityThread("pin"));
        badPin.put("generation", 999999);
        startOn("ann", "risk", "trade", TRADE, badPin).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7011"));
        Map<String, Object> noPanel = new LinkedHashMap<>(entityThread("pin"));
        noPanel.put("anchor", "panel");
        startOn("ann", "risk", "trade", TRADE, noPanel).andExpect(status().isBadRequest());
        Map<String, Object> badField = new LinkedHashMap<>(entityThread("pin"));
        badField.put("anchor", "field");
        badField.put("path", "mtm");
        startOn("ann", "risk", "trade", TRADE, badField).andExpect(status().isBadRequest());
        startOn("ann", "risk", "trade", "NO-SUCH-TRADE", entityThread("nothing there")).andExpect(status().isNotFound());
    }

    // ---- edit, retract, hide, audit ------------------------------------------------------------------------------------

    @Test
    void editRetractHideAndTheAuditTrail() throws Exception {
        JsonNode first = body(startOn("ann", "risk", "trade", TRADE, entityThread("first version")).andExpect(status().isCreated()));
        String tid = first.get("threadId").asText();
        String cid = first.get("comment").get("id").asText();

        // within the window: the author edits on the current revision
        JsonNode edited = body(mvc.perform(patch("/api/v1/comments/" + cid).header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"second version\",\"revision\":1}")).andExpect(status().isOk()));
        assertThat(edited.get("revision").asInt()).isEqualTo(2);
        assertThat(edited.get("edited").asBoolean()).isTrue();
        mvc.perform(patch("/api/v1/comments/" + cid).header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"stale\",\"revision\":1}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-7009"));
        mvc.perform(patch("/api/v1/comments/" + cid).header("Authorization", as("rng", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"not mine\",\"revision\":2}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7008"));
        mvc.perform(patch("/api/v1/comments/cm_NOPE").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"x\"}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-7006"));
        // a reader who may not open the entity does not learn the comment exists
        mvc.perform(patch("/api/v1/comments/" + cid).header("Authorization", as("sam", "ops")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"x\"}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-7006"));

        // after the window: no edit (403 DRS-7008), only retract
        Thread.sleep(3300);
        mvc.perform(patch("/api/v1/comments/" + cid).header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"too late\",\"revision\":2}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7008"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("the 3-second edit window has passed")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PT"))));
        mvc.perform(get("/api/v1/collab").header("Authorization", as("ann", "risk"))).andExpect(jsonPath("$.editWindowSeconds").value(3));
        JsonNode view = body(listFor("ann", "risk", "?state=open")).findValues("items").stream().flatMap(a -> java.util.stream.StreamSupport.stream(a.spliterator(), false))
                .filter(c -> c.get("id").asText().equals(cid)).findFirst().orElseThrow();
        assertThat(view.get("editable").asBoolean()).isFalse();

        // hide: administrators only, with a reason; readers see the reason, not the text
        mvc.perform(post("/api/v1/admin/collab/comments/" + cid + "/hide").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"client name\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/collab/comments/" + cid + "/hide").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/admin/collab/comments/" + cid + "/hide").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"client name\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("hidden"));
        String hidden = text(listFor("ravi", "trader", ""));
        assertThat(hidden).contains("client name").doesNotContain("second version");
        assertThat(text(mvc.perform(get("/api/v1/comments/" + cid + "/revisions").header("Authorization", as("ravi", "trader"))))).doesNotContain("first version")
                .doesNotContain("second version");
        assertThat(text(mvc.perform(get("/api/v1/comments/" + cid + "/revisions").header("Authorization", admin())))).contains("first version").contains("second version");
        mvc.perform(post("/api/v1/admin/collab/comments/" + cid + "/unhide").header("Authorization", admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("live")).andExpect(jsonPath("$.body").value("second version"));

        // retract: the author, at any time; the text leaves readers' hands, stays in the record
        mvc.perform(post("/api/v1/comments/" + cid + "/retract").header("Authorization", as("rng", "risk"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/comments/" + cid + "/retract").header("Authorization", as("ann", "risk"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("retracted")).andExpect(jsonPath("$.body").doesNotExist());
        assertThat(text(listFor("rng", "risk", ""))).doesNotContain("second version");
        mvc.perform(post("/api/v1/admin/collab/comments/" + cid + "/unhide").header("Authorization", admin())).andExpect(status().isBadRequest());

        // every step is a revision, the chain verifies, and each action was audited
        List<String> actions = store.revisions(cid).stream().map(r -> r.action()).toList();
        assertThat(actions).containsExactly("created", "edited", "hidden", "unhidden", "retracted");
        assertThat(store.revisions(cid).get(0).body()).as("the first text is kept").isEqualTo("first version");
        assertThat(HashChain.verify(tid, store.chain(tid))).isNull();
        Comment kept = store.comment(cid).orElseThrow();
        assertThat(kept.body()).isEqualTo("second version");
        String audit = text(mvc.perform(get("/api/v1/admin/audit").param("subject", "trade/" + TRADE).header("Authorization", admin())));
        assertThat(audit).contains("collab.comment.add").contains("collab.comment.edit").contains("collab.comment.hide").contains("collab.comment.unhide")
                .contains("collab.comment.retract");
    }

    // ---- state, follow, mentions list, security ------------------------------------------------------------------------

    @Test
    void participantsResolveAdministratorsLockAndALockedThreadRefusesComments() throws Exception {
        String tid = body(startOn("ann", "risk", "trade", TRADE, entityThread("lock me")).andExpect(status().isCreated())).get("threadId").asText();
        mvc.perform(post("/api/v1/threads/" + tid + "/state").header("Authorization", as("spam", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"resolved\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/threads/" + tid + "/state").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"resolved\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("resolved"));
        mvc.perform(post("/api/v1/threads/" + tid + "/state").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"locked\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/threads/" + tid + "/state").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"locked\"}")).andExpect(status().isOk());
        replyTo("rng", "risk", tid, "late").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-7007"));
        mvc.perform(post("/api/v1/threads/" + tid + "/state").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"open\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/threads/" + tid + "/state").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"open\"}")).andExpect(status().isOk());
        replyTo("rng", "risk", tid, "now fine").andExpect(status().isCreated());
    }

    @Test
    void myMentionsListsCommentsAddressedToMeOrMyRolesWhereIMayOpenTheEntity() throws Exception {
        String note = "mentions list check @rng and @risk";
        startOn("spam", "risk", "trade", TRADE, entityThread(note)).andExpect(status().isCreated());
        String rng = text(mvc.perform(get("/api/v1/me/mentions").header("Authorization", as("rng", "risk"))).andExpect(status().isOk()));
        assertThat(rng).contains("mentions list check").contains("SPAM Person");
        assertThat(text(mvc.perform(get("/api/v1/me/mentions").header("Authorization", as("spam", "risk"))))).as("not my own comment").doesNotContain("mentions list check");
        assertThat(text(mvc.perform(get("/api/v1/me/mentions").header("Authorization", as("dkim", "risk"))))).doesNotContain("mentions list check");
        JsonNode rows = body(mvc.perform(get("/api/v1/me/mentions").param("limit", "1").header("Authorization", as("rng", "risk"))));
        assertThat(rows).hasSize(1);
        JsonNode next = body(mvc.perform(get("/api/v1/me/mentions").param("limit", "1").param("before", rows.get(0).get("commentId").asText())
                .header("Authorization", as("rng", "risk"))));
        assertThat(next).hasSizeLessThanOrEqualTo(1);
        if (next.size() == 1) {
            assertThat(next.get(0).get("commentId").asText()).isNotEqualTo(rows.get(0).get("commentId").asText());
        }
    }

    @Test
    void writingNeedsTheCollaboratePowerAndAPersonalApiTokenReadsOnly() throws Exception {
        mvc.perform(put("/api/v1/admin/role-definitions/muted").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[\"*\"],\"collaborate\":false}")).andExpect(status().isOk());
        user("hush", List.of("muted"), List.of("finance"));
        startOn("hush", "muted", "trade", TRADE, entityThread("shh")).andExpect(status().isForbidden());
        listFor("hush", "muted", "").andExpect(status().isOk());
        String secret = body(mvc.perform(post("/api/v1/me/tokens").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"t\"}"))).path("secret").asText();
        assertThat(secret).startsWith("drk_");
        mvc.perform(post("/api/v1/threads/trade/" + TRADE).header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(entityThread("from a token")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/threads/trade/" + TRADE).header("Authorization", "Bearer " + secret)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/threads/trade/" + TRADE)).andExpect(status().isUnauthorized());
    }

    // ---- shares: replies and posting to the discussion -----------------------------------------------------------------

    @Test
    void aShareCanAlsoBePostedToTheDiscussionAndRepliesStayBetweenItsParties() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("kind", "trade");
        req.put("id", TRADE);
        req.put("note", "Please confirm the reset date");
        req.put("generation", 0);
        req.put("to", Map.of("users", List.of("ravi"), "roles", List.of()));
        req.put("postToThread", true);
        JsonNode sent = body(mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req))).andExpect(status().isCreated()));
        assertThat(sent.get("warnings").toString()).doesNotContain("not available");
        String id = sent.get("id").asText();
        assertThat(text(listFor("rng", "risk", ""))).as("the note is in the discussion").contains("Please confirm the reset date");

        // a reply from the recipient tells the sender; the sender's reply tells the recipient; a stranger gets a 404
        long ann0 = unread("ann", "risk"), ravi0 = unread("ravi", "trader");
        mvc.perform(post("/api/v1/shares/" + id + "/replies").header("Authorization", as("ravi", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Reset is the 15th\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.author").value("ravi"));
        assertThat(unread("ann", "risk")).isEqualTo(ann0 + 1);
        JsonNode row = inbox("ann", "risk", "reply").get(0);
        assertThat(row.get("shareId").asText()).isEqualTo(id);
        assertThat(row.get("excerpt").asText()).contains("Reset is the 15th");
        mvc.perform(post("/api/v1/shares/" + id + "/replies").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Thanks\"}")).andExpect(status().isCreated());
        assertThat(unread("ravi", "trader")).isEqualTo(ravi0 + 1);
        mvc.perform(post("/api/v1/shares/" + id + "/replies").header("Authorization", as("spam", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"hi\"}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-7001"));
        mvc.perform(post("/api/v1/shares/" + id + "/replies").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"\"}")).andExpect(status().isUnprocessableEntity());
        // the share page shows the replies to its parties; they are not in the entity's discussion
        String page = text(mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("ann", "risk"))).andExpect(status().isOk()));
        assertThat(page).contains("Reset is the 15th").contains("Thanks");
        assertThat(text(mvc.perform(get("/api/v1/shares/" + id).header("Authorization", as("ravi", "trader"))))).contains("Reset is the 15th");
        assertThat(text(listFor("rng", "risk", ""))).doesNotContain("Reset is the 15th");
        assertThat(text(mvc.perform(get("/api/v1/me/mentions").header("Authorization", as("rng", "risk"))))).doesNotContain("Reset is the 15th");
    }
}
