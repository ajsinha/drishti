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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The Upload page's endpoints: who may call them, the {@code loads:write} scope, one upload to one push, and the audit of it. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance", "drishti.loads.scheduler=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db",
        "drishti.engines.dataload.enabled=true", "drishti.dataload.interval=10h", "drishti.dataload.new-tables=true"})
@AutoConfigureMockMvc
class ReadyApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "drishti-dev-admin";
    private static final String ROW = "{\"THING_ID\":\"A1\",\"SOURCE_SYSTEM\":\"ENDUR\"}\n";

    static java.nio.file.Path dataloadDir;

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        dataloadDir = TestRun.temp("ready-api");
        r.add("drishti.dataload.dir", () -> dataloadDir.resolve("dataload").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier signer;

    private String as(String user, String role) {
        return "Bearer " + signer.mint(user, List.of(role), 300);
    }

    private String token(String scopes) throws Exception {
        var r = mvc.perform(post("/api/v1/me/tokens").header("Authorization", as(ADMIN, "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"etl\",\"days\":30" + (scopes.isEmpty() ? "" : ",\"scopes\":[" + scopes + "]") + "}")).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return "Bearer " + JSON.readTree(r.getContentAsString()).path("secret").asText();
    }

    private JsonNode upload(String bearer, String name) throws Exception {
        var r = mvc.perform(post("/api/v1/admin/dataload/upload").param("file", name).header("Authorization", bearer).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(ROW)).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode st = JSON.readTree(r.getContentAsString());
        for (int i = 0; i < 200 && "inspecting".equals(st.path("state").asText()); i++) {
            Thread.sleep(25);
            st = JSON.readTree(mvc.perform(get("/api/v1/admin/dataload/item/" + st.path("id").asText()).header("Authorization", bearer)).andReturn().getResponse().getContentAsString());
        }
        return st;
    }

    @Test
    void onlyAnAdministratorAndATokenWithTheScope() throws Exception {
        String user = as("someone", "viewer");
        mvc.perform(get("/api/v1/admin/dataload/choices").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/dataload/upload").param("file", "a.jsonl").header("Authorization", user).contentType(MediaType.APPLICATION_OCTET_STREAM).content(ROW))
                .andExpect(status().isForbidden());
        String read = token("");
        mvc.perform(post("/api/v1/admin/dataload/ready/push").header("Authorization", read).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("this token lacks the scope loads:write"));
    }

    @Test
    void aNameOutsideTheInboxIsRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/dataload/upload").param("file", "../evil.jsonl").header("Authorization", as(ADMIN, "admin"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(ROW)).andExpect(status().isBadRequest());
    }

    @Test
    void aFileIsNotAnotherPersons() throws Exception {
        String id = upload(as(ADMIN, "admin"), "thing.2026-11-02.jsonl").path("id").asText();
        mvc.perform(get("/api/v1/admin/dataload/item/" + id).header("Authorization", as("other-admin", "admin"))).andExpect(status().isNotFound());
    }

    @Test
    void anUploadIsPreviewedPushedAndAudited() throws Exception {
        String bearer = token("\"loads:write\"");
        JsonNode st = upload(bearer, "thing.2026-11-02.jsonl");
        assertThat(st.path("sha256").asText()).hasSize(64);
        assertThat(st.path("rows").asLong()).isEqualTo(1);
        String form = "{\"item\":\"" + st.path("id").asText() + "\",\"table\":\"thing\",\"pack\":\"finance\",\"newTable\":true,\"mode\":\"append\",\"key\":\"THING_ID\"}";
        mvc.perform(post("/api/v1/admin/dataload/ready/preview").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("new-table: true")));
        mvc.perform(post("/api/v1/admin/dataload/ready/push").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isOk()).andExpect(jsonPath("$.file").value("thing.2026-11-02.jsonl"));
        var audit = mvc.perform(get("/api/v1/admin/audit").header("Authorization", as(ADMIN, "admin"))).andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("dataload.push", "inbox/thing.2026-11-02.jsonl", st.path("sha256").asText(), "new-table: true");
    }

    private JsonNode waitReady(String bearer, JsonNode st) throws Exception {
        for (int i = 0; i < 200 && "inspecting".equals(st.path("state").asText()); i++) {
            Thread.sleep(25);
            st = JSON.readTree(mvc.perform(get("/api/v1/admin/dataload/item/" + st.path("id").asText()).header("Authorization", bearer)).andReturn().getResponse().getContentAsString());
        }
        return st;
    }

    @Test
    void anUploadIsDiscardedAndIsGone() throws Exception {
        String admin = as(ADMIN, "admin");
        String id = upload(admin, "gone.2026-11-02.jsonl").path("id").asText();
        mvc.perform(delete("/api/v1/admin/dataload/upload/" + id).header("Authorization", as("someone", "viewer"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/dataload/item/" + id).header("Authorization", admin)).andExpect(status().isOk());      // a refusal changed nothing
        mvc.perform(delete("/api/v1/admin/dataload/upload/" + id).header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.discarded").value(true));
        mvc.perform(get("/api/v1/admin/dataload/item/" + id).header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/admin/dataload/upload/" + id).header("Authorization", admin)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DRS-")));
    }

    @Test
    void aFileAlreadyInTheInboxFolderIsPickedWhereItLies() throws Exception {
        String admin = as(ADMIN, "admin");
        java.nio.file.Path inbox = java.nio.file.Files.createDirectories(dataloadDir.resolve("dataload/inbox"));
        java.nio.file.Files.writeString(inbox.resolve("picked.2026-11-02.jsonl"), ROW + "{\"THING_ID\":\"A2\",\"SOURCE_SYSTEM\":\"ENDUR\"}\n");
        mvc.perform(post("/api/v1/admin/dataload/pick").header("Authorization", as("someone", "viewer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\":\"picked.2026-11-02.jsonl\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/dataload/pick").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"file\":\"nothere.jsonl\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DRS-")));
        mvc.perform(post("/api/v1/admin/dataload/pick").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"file\":\"../x.jsonl\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DRS-")));
        var r = mvc.perform(post("/api/v1/admin/dataload/pick").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\":\"picked.2026-11-02.jsonl\"}")).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode st = waitReady(admin, JSON.readTree(r.getContentAsString()));
        assertThat(st.path("rows").asLong()).isEqualTo(2);                                         // counted where it lies
        assertThat(st.path("sha256").asText()).hasSize(64);
        assertThat(inbox.resolve("picked.2026-11-02.jsonl")).exists();
        mvc.perform(delete("/api/v1/admin/dataload/upload/" + st.path("id").asText()).header("Authorization", admin)).andExpect(status().isOk());
        assertThat(inbox.resolve("picked.2026-11-02.jsonl")).as("a picked file stays when its desk entry is dropped").exists();
    }

    @Test
    void aReadyTextBecomesTheFormsFieldsAndABadKeyIsSaidAsRefused() throws Exception {
        String admin = as(ADMIN, "admin");
        mvc.perform(post("/api/v1/admin/dataload/ready/read").header("Authorization", as("someone", "viewer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"table: thing\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/dataload/ready/read").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"table: thing\\nbusiness-date: 2026-11-02\\nmode: replace\\nkey: THING_ID\\nnew-table: true\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.table").value("thing")).andExpect(jsonPath("$.businessDate").value("2026-11-02"))
                .andExpect(jsonPath("$.mode").value("replace")).andExpect(jsonPath("$.key").value("THING_ID")).andExpect(jsonPath("$.newTable").value(true));
        mvc.perform(post("/api/v1/admin/dataload/ready/read").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"colour: red\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(org.hamcrest.Matchers.containsString("colour")));
        mvc.perform(post("/api/v1/admin/dataload/ready/read").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"mode: sideways\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(org.hamcrest.Matchers.containsString("mode")));
    }

    @Test
    void aRelayedUploadArrivesInPartsAndIsOneFile() throws Exception {
        String admin = as(ADMIN, "admin");
        String one = "{\"THING_ID\":\"P1\",\"SOURCE_SYSTEM\":\"ENDUR\"}\n";
        String two = "{\"THING_ID\":\"P2\",\"SOURCE_SYSTEM\":\"ENDUR\"}\n";
        String cut = two.substring(0, 12);
        mvc.perform(post("/api/v1/admin/dataload/upload/part").param("file", "parts.2026-11-02.jsonl").param("last", "false").header("Authorization", as("someone", "viewer"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(one + cut)).andExpect(status().isForbidden());
        var first = JSON.readTree(mvc.perform(post("/api/v1/admin/dataload/upload/part").param("file", "parts.2026-11-02.jsonl").param("last", "false")
                .header("Authorization", admin).contentType(MediaType.APPLICATION_OCTET_STREAM).content(one + cut)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String id = first.path("id").asText();
        assertThat(id).isNotBlank();
        var done = JSON.readTree(mvc.perform(post("/api/v1/admin/dataload/upload/part").param("file", "parts.2026-11-02.jsonl").param("id", id).param("last", "true")
                .header("Authorization", admin).contentType(MediaType.APPLICATION_OCTET_STREAM).content(two.substring(12))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode st = waitReady(admin, done);
        assertThat(st.path("id").asText()).isEqualTo(id);
        assertThat(st.path("rows").asLong()).as("both rows, the cut one whole").isEqualTo(2);
        assertThat(st.path("sha256").asText()).hasSize(64);
        mvc.perform(post("/api/v1/admin/dataload/upload/part").param("file", "parts.2026-11-02.jsonl").param("id", "no-such-id").param("last", "true")
                .header("Authorization", admin).contentType(MediaType.APPLICATION_OCTET_STREAM).content(one)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DRS-")));
    }
}
