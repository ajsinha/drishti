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
import com.ash.drishti.identity.NoteStore;
import com.ash.drishti.identity.collab.CollabTx;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.HashChain;
import com.ash.drishti.identity.collab.NoteImport;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The notes of earlier releases become single-comment threads once (idempotent; the table stays), and the deprecated {@code /notes}
 * API keeps its shape and its numbers over threads: a delete retracts, an edit stops after the edit window.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/noteimport-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/noteimport-overlay/added.yaml", "drishti.collab.threads.edit-window=2s"})
@AutoConfigureMockMvc
class NoteImportTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired NoteStore notes;
    @Autowired ThreadStore threads;
    @Autowired CollabTx tx;
    @Autowired AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    @Test
    void oldNotesBecomeThreadsOnceAndSurviveInTheFacadeWithTheirNumbers() throws Exception {
        String entity = "IRS-IMPORT-" + System.nanoTime();
        NoteStore.Note whole = notes.add("trade", entity, null, "tess", "Restated after the fixing correction");
        NoteStore.Note field = notes.add("trade", entity, "$.mtm", "ravi", "MTM includes the CVA adjustment");

        assertThat(NoteImport.run(notes, threads, tx, audit)).isEqualTo(2);
        assertThat(NoteImport.run(notes, threads, tx, audit)).as("a second run imports nothing").isZero();
        notes.add("trade", entity, null, "tess", "added later");
        assertThat(NoteImport.run(notes, threads, tx, audit)).as("only what is new").isEqualTo(1);
        assertThat(notes.of("trade", entity)).as("the table is left in place").hasSize(3);

        List<CommentThread> ts = threads.threads("trade", entity);
        assertThat(ts).hasSize(3).extracting(CommentThread::anchor).containsExactlyInAnyOrder("entity", "field", "entity");
        CommentThread onField = ts.stream().filter(t -> "field".equals(t.anchor())).findFirst().orElseThrow();
        assertThat(onField.path()).isEqualTo("$.mtm");
        Comment c = threads.comments(onField.id()).get(0);
        assertThat(c.author()).isEqualTo("ravi");
        assertThat(c.pin().businessDate()).isNull();
        assertThat(c.pin().generation()).isZero();
        assertThat(c.pin().knownAt()).isEqualTo(field.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(HashChain.verify(onField.id(), threads.chain(onField.id()))).isNull();
        assertThat(threads.commentOfNote(whole.id())).isPresent();

        // the facade: the old shape, the old numbers
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/notes/trade/" + entity).header("Authorization", as("ravi", "trader")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(whole.id());
        assertThat(list.get(1).get("id").asLong()).isEqualTo(field.id());
        assertThat(list.get(1).get("path").asText()).isEqualTo("$.mtm");
        assertThat(list.get(1).get("author").asText()).isEqualTo("ravi");
        assertThat(list.get(1).get("body").asText()).isEqualTo("MTM includes the CVA adjustment");
        assertThat(list.get(1).has("createdAt")).isTrue();
        assertThat(list.get(1).has("updatedAt")).isTrue();
    }

    @Test
    void theFacadeWritesThroughThreadsAndDeleteRetractsOrHides() throws Exception {
        String entity = "IRS-48213";
        String created = mvc.perform(post("/api/v1/notes/trade/" + entity).header("Authorization", as("tess", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"facade note\", \"path\": \"$.mtm\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.author").value("tess"))
                .andExpect(jsonPath("$.path").value("$.mtm")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(created).get("id").asLong();
        assertThat(threads.commentOfNote(id)).as("a note made through the facade is a comment").isPresent();
        mvc.perform(put("/api/v1/notes/" + id).header("Authorization", as("tess", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"facade note, edited\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("facade note, edited"));
        mvc.perform(put("/api/v1/notes/" + id).header("Authorization", as("ravi", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"mine now\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/notes/" + id).header("Authorization", as("ravi", "trader"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notes/netting-set/NS-NORTH-01").header("Authorization", as("tess", "trader"))).andExpect(status().isForbidden());
        Thread.sleep(2300);
        mvc.perform(put("/api/v1/notes/" + id).header("Authorization", as("tess", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"too late\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-7008"));
        mvc.perform(delete("/api/v1/notes/" + id).header("Authorization", as("ada", "admin"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notes/trade/" + entity).header("Authorization", as("ravi", "trader"))).andExpect(jsonPath("$[?(@.id==" + id + ")]").isEmpty());
        assertThat(threads.comment(threads.commentOfNote(id).orElseThrow()).orElseThrow().state()).isEqualTo("hidden");

        String second = mvc.perform(post("/api/v1/notes/trade/" + entity).header("Authorization", as("tess", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"another\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id2 = json.readTree(second).get("id").asLong();
        assertThat(id2).isGreaterThan(id);
        mvc.perform(delete("/api/v1/notes/" + id2).header("Authorization", as("tess", "trader"))).andExpect(status().isNoContent());
        assertThat(threads.comment(threads.commentOfNote(id2).orElseThrow()).orElseThrow().state()).isEqualTo("retracted");
    }

    @Test
    void aMaskedValueTypedIntoAnOldNoteStaysMaskedAfterTheImport() throws Exception {
        // the old Notes were never checked for masked values; an imported one is checked when it is read, against the entity
        String entity = "IRS-48213";                                   // its trader, A. Shah, is masked for roles without raw
        notes.add("trade", entity, null, "tess", "Booked by A. Shah after the call");
        NoteImport.run(notes, threads, tx, audit);
        String asTrader = mvc.perform(get("/api/v1/threads/trade/" + entity).header("Authorization", as("tom", "trader")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(asTrader).as("a reader without raw").contains("Booked by").doesNotContain("A. Shah").contains(com.ash.drishti.api.DataNode.MASK);
        String asRisk = mvc.perform(get("/api/v1/threads/trade/" + entity).header("Authorization", as("rita", "risk")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(asRisk).as("a reader with raw").contains("Booked by A. Shah after the call");
    }
}
