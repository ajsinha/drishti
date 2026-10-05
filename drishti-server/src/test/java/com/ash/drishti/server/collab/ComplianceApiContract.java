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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.HashChain;
import com.ash.drishti.identity.collab.HoldStore;
import com.ash.drishti.identity.collab.Revision;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.collab.compliance.CollabPurge;
import com.ash.drishti.server.collab.compliance.HoldService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Retention, legal holds, the compliance export and chain verification over the whole stack (security on, retention one day, a clock
 * thirty days ahead for the purge): only the {@code compliance} power holds, exports and verifies; a purge removes whole old threads and
 * shares and skips what an active hold covers; the export's manifest carries the checksum of every file and each thread's chain proof;
 * verification finds a tampered revision. Run against both stores ({@link ComplianceApiJpaTest}, {@link ComplianceApiFileTest}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ComplianceApiContract {

    static final String TRADE = "IRS-48213";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ThreadStore threads;
    @Autowired ShareStore shares;
    @Autowired HoldStore holdStore;
    @Autowired HoldService holds;
    @Autowired CollabProperties props;
    @Autowired PackAccess packs;
    @Autowired Entitlements entitlements;
    @Autowired AuditLog audit;
    @Autowired com.ash.drishti.identity.collab.CollabTx tx;
    final ObjectMapper json = new ObjectMapper();

    /** Changes the stored text of a comment's first revision behind the application's back. */
    abstract void tamper(String commentId, String originalText);

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    void user(String name, List<String> roles) throws Exception {
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "displayName", name.toUpperCase() + " Person", "email", name + "@desk.test",
                        "roles", roles, "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
    }

    @BeforeAll
    void users() throws Exception {
        mvc.perform(put("/api/v1/admin/role-definitions/auditor").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[\"*\"],\"compliance\":true}")).andExpect(status().isOk());
        user("ann", List.of("risk"));
        user("ravi", List.of("trader"));
        user("carol", List.of("auditor"));
        user("cass", List.of("auditor"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    String thread(String user, String role, String text) throws Exception {
        return body(mvc.perform(post("/api/v1/threads/trade/" + TRADE).header("Authorization", as(user, role)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("anchor", "entity", "generation", 0, "body", text)))).andExpect(status().isCreated()))
                .get("threadId").asText();
    }

    String reply(String thread, String user, String role, String text) throws Exception {
        return body(mvc.perform(post("/api/v1/threads/" + thread + "/comments").header("Authorization", as(user, role))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("generation", 0, "body", text))))
                .andExpect(status().isCreated())).get("comment").get("id").asText();
    }

    String shareTo(String text, String... to) throws Exception {
        return body(mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "trade", "id", TRADE, "note", text, "generation", 0,
                        "to", Map.of("users", List.of(to), "roles", List.of())))))
                .andExpect(status().isCreated())).get("id").asText();
    }

    ResultActions hold(String who, String role, Map<String, Object> req) throws Exception {
        return mvc.perform(post("/api/v1/admin/collab/holds").header("Authorization", as(who, role)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));
    }

    long place(Map<String, Object> req) throws Exception {
        return body(hold("carol", "auditor", req).andExpect(status().isCreated())).get("id").asLong();
    }

    void release(long id) throws Exception {
        mvc.perform(delete("/api/v1/admin/collab/holds/" + id).header("Authorization", as("carol", "auditor"))).andExpect(status().isOk());
    }

    /** Retention as it would run thirty days from now: everything the tests made is older than the one day kept. */
    CollabPurge.Result purgeLater(boolean dryRun) {
        CollabPurge purge = new CollabPurge(props, threads, shares, holds, packs, entitlements, audit, tx,
                Clock.offset(Clock.systemUTC(), Duration.ofDays(30)));
        return purge.run(dryRun);
    }

    boolean audited(String action, String contains) {
        return audit.recent(500, null).stream().anyMatch(e -> e.action().equals(action) && e.detail() != null && e.detail().contains(contains));
    }

    // ---- authorization -------------------------------------------------------------------------------------------------

    @Test
    void onlyTheComplianceRoleHoldsExportsAndVerifies() throws Exception {
        String t = thread("ann", "risk", "who may look");
        for (String who : new String[] {as("ann", "risk"), admin()}) {
            mvc.perform(get("/api/v1/admin/collab/holds").header("Authorization", who)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/collab/holds").header("Authorization", who).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"scope\":\"all\",\"reason\":\"x\"}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/collab/exports").header("Authorization", who).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/collab/verify").param("thread", t).header("Authorization", who)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/collab/threads/" + t).header("Authorization", who)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/collab/holds").header("Authorization", as("carol", "auditor"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/collab/verify").param("thread", t).header("Authorization", as("carol", "auditor"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // searching is for an administrator or a compliance officer, not for everyone
        mvc.perform(get("/api/v1/admin/collab/threads").header("Authorization", as("ann", "risk"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/collab/shares").header("Authorization", as("ann", "risk"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/collab/threads").param("kind", "trade").param("id", TRADE).header("Authorization", admin()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/collab/shares").header("Authorization", as("carol", "auditor"))).andExpect(status().isOk());
        // retention and permanent removal are the administrator's; the compliance power does not include them
        mvc.perform(post("/api/v1/admin/collab/retention/run").header("Authorization", as("carol", "auditor"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/collab/threads/" + t).header("Authorization", as("carol", "auditor"))).andExpect(status().isForbidden());
    }

    @Test
    void searchFiltersByUserEntityAndDate() throws Exception {
        String t = thread("ann", "risk", "searchable");
        String s = shareTo("searchable too", "ravi");
        String carol = as("carol", "auditor");
        JsonNode byUser = body(mvc.perform(get("/api/v1/admin/collab/threads").param("user", "ann").param("limit", "200").header("Authorization", carol)));
        assertThat(byUser.get("items").findValuesAsText("id")).contains(t);
        JsonNode nobody = body(mvc.perform(get("/api/v1/admin/collab/threads").param("user", "nobody-here").header("Authorization", carol)));
        assertThat(nobody.get("items")).isEmpty();
        JsonNode old = body(mvc.perform(get("/api/v1/admin/collab/threads").param("to", "2001-01-01").header("Authorization", carol)));
        assertThat(old.get("items")).isEmpty();
        JsonNode sharesByRecipient = body(mvc.perform(get("/api/v1/admin/collab/shares").param("user", "ravi").param("kind", "trade").param("id", TRADE)
                .param("limit", "200").header("Authorization", carol)));
        assertThat(sharesByRecipient.get("items").findValuesAsText("id")).contains(s);
        mvc.perform(get("/api/v1/admin/collab/shares").param("from", "not-a-date").header("Authorization", carol)).andExpect(status().isBadRequest());
    }

    // ---- legal holds ---------------------------------------------------------------------------------------------------

    @Test
    void holdsAreValidatedPlacedReleasedOnceAndAudited() throws Exception {
        hold("carol", "auditor", Map.of("scope", "entity", "reason", "no id")).andExpect(status().isBadRequest());
        hold("carol", "auditor", Map.of("scope", "user", "reason", "no user")).andExpect(status().isBadRequest());
        hold("carol", "auditor", Map.of("scope", "nonsense", "reason", "x")).andExpect(status().isBadRequest());
        hold("carol", "auditor", Map.of("scope", "all")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-7011"));
        hold("carol", "auditor", Map.of("scope", "thread", "thread", "th_NOPE", "reason", "x")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRS-7005"));
        hold("carol", "auditor", Map.of("scope", "kind", "kind", "trade", "from", "2026-09-30", "to", "2026-09-01", "reason", "x"))
                .andExpect(status().isBadRequest());
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("scope", "entity");
        req.put("kind", "trade");
        req.put("id", TRADE);
        req.put("from", "2026-09-01");
        req.put("to", "2026-09-30");
        req.put("reason", "case 4411");
        JsonNode placed = body(hold("carol", "auditor", req).andExpect(status().isCreated()).andExpect(jsonPath("$.placedBy").value("carol")));
        long id = placed.get("id").asLong();
        assertThat(placed.get("from").asText()).startsWith("2026-09-01T00:00:00");
        assertThat(placed.get("to").asText()).as("a date as the end means the whole day").startsWith("2026-09-30T23:59:59");
        assertThat(audited("collab.hold.place", "hold " + id)).isTrue();
        mvc.perform(get("/api/v1/admin/collab/holds").param("active", "true").header("Authorization", as("carol", "auditor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id==" + id + ")]").exists());
        release(id);
        assertThat(audited("collab.hold.release", "hold " + id)).isTrue();
        mvc.perform(delete("/api/v1/admin/collab/holds/" + id).header("Authorization", as("carol", "auditor"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/collab/holds").param("active", "true").header("Authorization", as("carol", "auditor")))
                .andExpect(jsonPath("$[?(@.id==" + id + ")]").doesNotExist());
        mvc.perform(get("/api/v1/admin/collab/holds").header("Authorization", as("carol", "auditor")))
                .andExpect(jsonPath("$[?(@.id==" + id + ")].releasedBy").value("carol"));
    }

    // ---- retention -----------------------------------------------------------------------------------------------------

    @Test
    void aPurgeRemovesWholeOldThreadsAndSharesAndSkipsWhatAHoldCovers() throws Exception {
        String free = thread("ann", "risk", "nobody holds this");
        String held = thread("ann", "risk", "a thread ravi joins");
        reply(held, "ravi", "trader", "ravi was here");
        String freeShare = shareTo("a share to nobody special", "cass");
        String heldShare = shareTo("a share to ravi", "ravi");
        List<Revision> before = threads.chain(held);

        long hold = place(Map.of("scope", "user", "user", "ravi", "reason", "case 17"));
        long outsideRange = place(Map.of("scope", "entity", "kind", "trade", "id", TRADE, "from", "2001-01-01", "to", "2001-12-31", "reason", "old"));

        CollabPurge.Result dry = purgeLater(true);
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.threadsPurged()).isGreaterThanOrEqualTo(1);
        assertThat(dry.threadsHeld()).isGreaterThanOrEqualTo(1);
        assertThat(threads.thread(free)).as("a dry run removes nothing").isPresent();

        CollabPurge.Result done = purgeLater(false);
        assertThat(done.threadsPurged()).isGreaterThanOrEqualTo(1);
        assertThat(done.sharesPurged()).isGreaterThanOrEqualTo(1);
        assertThat(threads.thread(free)).as("the thread nobody holds is gone, whole").isEmpty();
        assertThat(threads.chain(free)).isEmpty();
        assertThat(threads.comments(free)).isEmpty();
        assertThat(shares.find(freeShare)).isEmpty();
        assertThat(threads.thread(held)).as("held by the user hold: ravi wrote in it").isPresent();
        assertThat(threads.chain(held)).as("the chain of a held thread is untouched").isEqualTo(before);
        assertThat(HashChain.verify(held, threads.chain(held))).isNull();
        assertThat(shares.find(heldShare)).as("held: ravi received it").isPresent();
        assertThat(audited("collab.purge.thread", free + " last hash")).isTrue();
        assertThat(audited("collab.purge.share", freeShare)).isTrue();

        release(hold);
        CollabPurge.Result after = purgeLater(false);
        assertThat(after.threadsHeld()).isZero();
        assertThat(threads.thread(held)).as("released: retention takes it").isEmpty();
        assertThat(shares.find(heldShare)).isEmpty();
        release(outsideRange);
    }

    @Test
    void aHoldByEntityKindThreadOrDateRangeEachKeepsTheirOwnItems() throws Exception {
        String a = thread("ann", "risk", "kept by entity");
        long byEntity = place(Map.of("scope", "entity", "kind", "trade", "id", TRADE, "reason", "e"));
        assertThat(purgeLater(false).threadsHeld()).isGreaterThanOrEqualTo(1);
        assertThat(threads.thread(a)).isPresent();
        release(byEntity);

        long byKind = place(Map.of("scope", "kind", "kind", "trade", "reason", "k"));
        assertThat(purgeLater(false).threadsPurged()).isZero();
        assertThat(threads.thread(a)).isPresent();
        release(byKind);

        long byThread = place(Map.of("scope", "thread", "thread", a, "reason", "t"));
        purgeLater(false);
        assertThat(threads.thread(a)).isPresent();
        release(byThread);

        // a range that includes today (the thread's activity) holds; one in the past does not
        String today = Instant.now().toString().substring(0, 10);
        long inRange = place(Map.of("scope", "all", "from", today, "to", today, "reason", "day"));
        assertThat(purgeLater(false).threadsPurged()).isZero();
        assertThat(threads.thread(a)).isPresent();
        release(inRange);
        long past = place(Map.of("scope", "all", "from", "2001-01-01", "to", "2001-01-31", "reason", "past"));
        assertThat(purgeLater(false).threadsPurged()).isGreaterThanOrEqualTo(1);
        assertThat(threads.thread(a)).isEmpty();
        release(past);
    }

    @Test
    void permanentRemovalIsTheAdministratorsAndRefusedUnderAHold() throws Exception {
        String t = thread("ann", "risk", "remove me");
        long h = place(Map.of("scope", "thread", "thread", t, "reason", "litigation"));
        mvc.perform(delete("/api/v1/admin/collab/threads/" + t).header("Authorization", as("ann", "risk"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/collab/threads/" + t).header("Authorization", admin())).andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("DRS-7010"));
        assertThat(threads.thread(t)).isPresent();
        release(h);
        mvc.perform(delete("/api/v1/admin/collab/threads/" + t).header("Authorization", admin())).andExpect(status().isNoContent());
        assertThat(threads.thread(t)).isEmpty();
        assertThat(audited("collab.thread.delete", t + " last hash")).isTrue();
        mvc.perform(delete("/api/v1/admin/collab/threads/" + t).header("Authorization", admin())).andExpect(status().isNotFound());
    }

    @Test
    void administratorsRunRetentionByHandAndItReportsWhatItWouldDo() throws Exception {
        thread("ann", "risk", "recent, within the day kept");
        mvc.perform(post("/api/v1/admin/collab/retention/run").param("dryRun", "true").header("Authorization", admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true)).andExpect(jsonPath("$.threadsPurged").value(0));
    }

    // ---- export --------------------------------------------------------------------------------------------------------

    /** Every entry of the zip by name. */
    Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    JsonNode runExport(String who, String role, Map<String, Object> filters, Map<String, byte[]>[] zipOut) throws Exception {
        JsonNode started = body(mvc.perform(post("/api/v1/admin/collab/exports").header("Authorization", as(who, role))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(filters))).andExpect(status().isAccepted()));
        String id = started.get("id").asText();
        JsonNode st = started;
        for (int i = 0; i < 200 && !"done".equals(st.get("state").asText()) && !"failed".equals(st.get("state").asText()); i++) {
            Thread.sleep(50);
            st = body(mvc.perform(get("/api/v1/admin/collab/exports/" + id).header("Authorization", as(who, role))).andExpect(status().isOk()));
        }
        assertThat(st.get("state").asText()).as(st.toString()).isEqualTo("done");
        if (zipOut != null) {
            byte[] zip = mvc.perform(get("/api/v1/admin/collab/exports/" + id + "/download").header("Authorization", as(who, role)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            zipOut[0] = unzip(zip);
        }
        return st;
    }

    static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    List<JsonNode> lines(byte[] ndjson) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : new String(ndjson, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                out.add(json.readTree(line));
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExportHoldsEveryRevisionTheChainProofAndChecksumsThatMatch() throws Exception {
        String secret = "client A. Shah moved the curve " + System.nanoTime();
        String t = thread("ann", "risk", secret);
        String c2 = reply(t, "ravi", "trader", "ravi says no");
        mvc.perform(post("/api/v1/admin/collab/comments/" + c2 + "/hide").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"names a client\"}")).andExpect(status().isOk());
        String s = shareTo("a note for ravi " + secret, "ravi");
        long hold = place(Map.of("scope", "thread", "thread", t, "reason", "export test"));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("kind", "trade");
        filters.put("id", TRADE);
        filters.put("from", Instant.now().toString().substring(0, 10));
        Map<String, byte[]>[] z = new Map[1];
        JsonNode status = runExport("carol", "auditor", filters, z);
        Map<String, byte[]> zip = z[0];
        assertThat(zip.keySet()).containsExactlyInAnyOrder("README.txt", "shares.ndjson", "threads.ndjson", "chains.ndjson", "holds.ndjson",
                "manifest.json", "inbox.ndjson", "outbox.ndjson");
        assertThat(status.get("counts").get("threads").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode manifest = json.readTree(zip.get("manifest.json"));
        assertThat(manifest.get("format").asText()).isEqualTo("drishti-collab-export/1");
        assertThat(manifest.get("requestedBy").asText()).isEqualTo("carol");
        assertThat(manifest.get("filters").get("kind").asText()).isEqualTo("trade");
        assertThat(manifest.get("software").get("version").asText()).isNotBlank();
        for (JsonNode f : manifest.get("files")) {
            byte[] content = zip.get(f.get("name").asText());
            assertThat(f.get("sha256").asText()).as(f.get("name").asText()).isEqualTo(sha256(content));
            assertThat(f.get("bytes").asLong()).isEqualTo(content.length);
            assertThat(f.get("lines").asLong()).isEqualTo(lines(content).size());
        }

        JsonNode thread = lines(zip.get("threads.ndjson")).stream().filter(n -> n.get("id").asText().equals(t)).findFirst().orElseThrow();
        assertThat(thread.get("comments")).hasSize(2);
        JsonNode first = thread.get("comments").get(0);
        assertThat(first.get("body").asText()).as("the record is unscrubbed").isEqualTo(secret);
        assertThat(first.get("authorName").asText()).isEqualTo("ANN Person");
        JsonNode hidden = thread.get("comments").get(1);
        assertThat(hidden.get("state").asText()).isEqualTo("hidden");
        assertThat(hidden.get("revisions").findValuesAsText("action")).containsExactly("created", "hidden");
        assertThat(hidden.get("revisions").get(0).get("body").asText()).as("a hidden comment's text is in the record").isEqualTo("ravi says no");
        // re-verify the chain from the export alone
        List<Revision> chain = new ArrayList<>();
        for (JsonNode c : thread.get("comments")) {
            for (JsonNode r : c.get("revisions")) {
                chain.add(new Revision(c.get("id").asText(), r.get("revision").asInt(), Instant.parse(r.get("at").asText()), r.get("actor").asText(),
                        r.get("action").asText(), r.get("body").isNull() ? null : r.get("body").asText(),
                        r.get("reason").isNull() ? null : r.get("reason").asText(), r.get("prevHash").asText(), r.get("hash").asText()));
            }
        }
        chain.sort(java.util.Comparator.comparing(Revision::at));
        assertThat(HashChain.verify(t, chain)).as("the chain verifies from the exported fields alone").isNull();
        assertThat(thread.get("chain").get("verified").asBoolean()).isTrue();
        assertThat(thread.get("chain").get("genesis").asText()).isEqualTo(HashChain.genesis(t));
        assertThat(thread.get("chain").get("lastHash").asText()).isEqualTo(chain.get(chain.size() - 1).hash());
        JsonNode chainLine = lines(zip.get("chains.ndjson")).stream().filter(n -> n.get("thread").asText().equals(t)).findFirst().orElseThrow();
        assertThat(chainLine.get("lastHash").asText()).isEqualTo(thread.get("chain").get("lastHash").asText());

        JsonNode share = lines(zip.get("shares.ndjson")).stream().filter(n -> n.get("id").asText().equals(s)).findFirst().orElseThrow();
        assertThat(share.get("body").asText()).contains(secret);
        assertThat(share.get("hashOk").asBoolean()).isTrue();
        assertThat(share.get("recipients").get(0).get("user").asText()).isEqualTo("ravi");
        assertThat(lines(zip.get("inbox.ndjson")).stream().map(n -> n.path("shareId").asText())).as("the share's inbox notices are in the export").contains(s);
        assertThat(new String(zip.get("inbox.ndjson"), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(new String(zip.get("outbox.ndjson"), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(lines(zip.get("holds.ndjson")).stream().map(n -> n.get("id").asLong())).contains(hold);

        assertThat(audited("collab.export.start", "kind=trade")).isTrue();
        assertThat(audited("collab.export.download", ".zip")).isTrue();
        release(hold);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExportIsDownloadedOnceByItsRequesterAndFiltersApply() throws Exception {
        thread("ann", "risk", "for the filters");
        JsonNode started = body(mvc.perform(post("/api/v1/admin/collab/exports").header("Authorization", as("carol", "auditor"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"user\":\"nobody-at-all\"}")).andExpect(status().isAccepted()));
        String id = started.get("id").asText();
        for (int i = 0; i < 200 && !"done".equals(body(mvc.perform(get("/api/v1/admin/collab/exports/" + id).header("Authorization", as("cass", "auditor"))))
                .get("state").asText()); i++) {
            Thread.sleep(50);
        }
        mvc.perform(get("/api/v1/admin/collab/exports/" + id + "/download").header("Authorization", as("cass", "auditor"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/collab/exports/" + id + "/download").header("Authorization", as("ann", "risk"))).andExpect(status().isForbidden());
        byte[] zip = mvc.perform(get("/api/v1/admin/collab/exports/" + id + "/download").header("Authorization", as("carol", "auditor")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> files = unzip(zip);
        assertThat(lines(files.get("threads.ndjson"))).as("no thread involves that user").isEmpty();
        assertThat(lines(files.get("shares.ndjson"))).isEmpty();
        mvc.perform(get("/api/v1/admin/collab/exports/" + id + "/download").header("Authorization", as("carol", "auditor")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/collab/exports/nope").header("Authorization", as("carol", "auditor"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/collab/exports").header("Authorization", as("carol", "auditor")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"includeShares\":false,\"includeThreads\":false}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/collab/exports").header("Authorization", as("carol", "auditor")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-10-01\",\"to\":\"2026-09-01\"}")).andExpect(status().isBadRequest());
        assertThat(audited("collab.export.start", "nobody-at-all")).isTrue();
    }

    // ---- verification --------------------------------------------------------------------------------------------------

    @Test
    void verificationFindsATamperedRevisionInThreadReportAndExport() throws Exception {
        String text = "tamper-evident text " + System.nanoTime();
        String t = thread("ann", "risk", text);
        String c2 = reply(t, "ravi", "trader", "second comment");
        String carol = as("carol", "auditor");
        try {
            mvc.perform(get("/api/v1/admin/collab/verify").param("thread", t).header("Authorization", carol)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.steps").value(2));
            mvc.perform(get("/api/v1/admin/collab/verify").param("thread", "th_NOPE").header("Authorization", carol)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DRS-7005"));
            JsonNode all = body(mvc.perform(get("/api/v1/admin/collab/verify").header("Authorization", carol)).andExpect(status().isOk()));
            assertThat(all.get("problems").findValuesAsText("id")).doesNotContain(t);

            String firstComment = threads.comments(t).get(0).id();
            tamper(firstComment, text);

            JsonNode bad = body(mvc.perform(get("/api/v1/admin/collab/verify").param("thread", t).header("Authorization", carol)).andExpect(status().isOk()));
            assertThat(bad.get("ok").asBoolean()).isFalse();
            assertThat(bad.get("problem").asText()).contains("was changed");
            JsonNode report = body(mvc.perform(get("/api/v1/admin/collab/verify").param("kind", "trade").param("id", TRADE).header("Authorization", carol)));
            assertThat(report.get("ok").asBoolean()).isFalse();
            assertThat(report.get("problems").findValuesAsText("id")).contains(t);
            assertThat(report.get("threadsOk").asLong()).isLessThan(report.get("threads").asLong());
            mvc.perform(get("/api/v1/admin/collab/threads/" + t).header("Authorization", carol)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.chain.ok").value(false));

            @SuppressWarnings("unchecked")
            Map<String, byte[]>[] z = new Map[1];
            JsonNode st = runExport("carol", "auditor", Map.of("kind", "trade", "id", TRADE), z);
            assertThat(st.get("counts").get("chainsBroken").asLong()).isEqualTo(1);
            JsonNode line = lines(z[0].get("chains.ndjson")).stream().filter(n -> n.get("thread").asText().equals(t)).findFirst().orElseThrow();
            assertThat(line.get("verified").asBoolean()).isFalse();
            assertThat(c2).isNotBlank();
        } finally {
            threads.deleteThread(t);
        }
    }
}
