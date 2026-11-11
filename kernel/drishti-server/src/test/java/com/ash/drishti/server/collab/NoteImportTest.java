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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The notes of earlier releases become single-comment threads once (idempotent; the table stays), and the deprecated {@code /notes}
 * API keeps its shape and its numbers over threads: a delete retracts, an edit stops after the edit window.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/noteimport-${random.uuid}/identity.db",
        "drishti.packs.overlay=${drishti.test.dir}/noteimport-overlay/added.yaml", "drishti.collab.threads.edit-window=2s"})
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
