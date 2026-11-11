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
package com.ash.drishti.server.collab.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.identity.JpaAuditLog;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.FileHoldStore;
import com.ash.drishti.identity.collab.FileSealStore;
import com.ash.drishti.identity.collab.FileThreadStore;
import com.ash.drishti.identity.collab.Hold;
import com.ash.drishti.identity.collab.HoldStore;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Revision;
import com.ash.drishti.identity.collab.Seal;
import com.ash.drishti.identity.collab.Seals;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.identity.db.AuditEventEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S3-04 and S3-05: the verifier finds a changed live comment row, a removed newest revision, tampered holds and audit rows, and never fails on a bad row. */
class ChainVerifierTamperTest {

    private static final Instant T = Instant.parse("2026-10-05T10:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path dir;
    FileThreadStore raw;
    ThreadStore threads;
    HoldStore holds;
    Seal.Store seals;
    ShareStore shares = mock(ShareStore.class);
    List<AuditEventEntity> auditRows = new ArrayList<>();
    JpaAuditLog audit;

    @BeforeEach
    void stores() {
        seals = new FileSealStore(dir);
        raw = new FileThreadStore(dir);
        threads = Seals.sealing(raw, seals);
        holds = Seals.sealing(new FileHoldStore(dir), seals);
        IdentityRepositories.Audit repo = mock(IdentityRepositories.Audit.class);
        when(repo.save(any())).thenAnswer(inv -> {
            AuditEventEntity e = inv.getArgument(0);
            e.id = (long) auditRows.size() + 1;
            auditRows.add(e);
            return e;
        });
        when(repo.findByIdGreaterThanOrderByIdAsc(any(), any())).thenAnswer(inv -> auditRows.stream().filter(e -> e.id > (Long) inv.getArgument(0)).limit(500).toList());
        audit = new JpaAuditLog(repo, seals);
        when(shares.page(any(), anyInt())).thenReturn(List.of());
    }

    private ChainVerifier verifier() {
        return new ChainVerifier(threads, shares, holds, seals, audit);
    }

    private void thread() {
        CommentThread t = new CommentThread("th_1", "trade", "T1", "entity", null, null, null, "view", "open", "ann", T, T, 1);
        threads.saveThread(t);
        Comment c = new Comment("cm_1", "th_1", "ann", T, null, 1, new Pin(null, true, T, 0, "demo"), "first text", List.of(), Comment.LIVE, null);
        threads.append(c, Revision.draft("cm_1", 1, T, "ann", Revision.CREATED, "first text", null), List.of());
        Comment e = c.edited("second text", List.of(), T.plusSeconds(5), 2);
        threads.append(e, Revision.draft("cm_1", 2, T.plusSeconds(5), "ann", Revision.EDITED, "second text", null), List.of());
    }

    /** Rewrites the thread's log, letting {@code edit} change each line's JSON (returning null drops the line). */
    private void rewrite(java.util.function.BiFunction<Integer, ObjectNode, ObjectNode> edit) throws IOException {
        Path log;
        try (var files = Files.walk(dir.resolve("threads"))) {
            log = files.filter(p -> p.toString().endsWith(".jsonl") && !p.toString().contains("note-links")).findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ObjectNode n = edit.apply(i, (ObjectNode) json.readTree(lines.get(i)));
            if (n != null) {
                out.add(json.writeValueAsString(n));
            }
        }
        Files.write(log, out, StandardCharsets.UTF_8);
        raw.reload();
    }

    @Test
    void anUntouchedRecordVerifies() {
        thread();
        ChainVerifier.Report r = verifier().verifyAll(null, null, 50);
        assertThat(r.problems()).isEmpty();
        assertThat(r.ok()).isTrue();
    }

    @Test
    void aChangedLiveCommentBodyIsFoundThoughEveryRevisionStillVerifies() throws Exception {
        thread();
        rewrite((i, n) -> {
            if (i == 2) {
                ((ObjectNode) n.get("comment")).put("body", "second text X");
            }
            return n;
        });
        ChainVerifier.Report r = verifier().verifyAll(null, null, 50);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).extracting(ChainVerifier.Problem::problem).anyMatch(p -> p.contains("text of comment cm_1"));
    }

    @Test
    void aChangedAuthorAndAStateSetBackToLiveAreFound() throws Exception {
        thread();
        Comment c = threads.comment("cm_1").orElseThrow().inState(Comment.RETRACTED, "mistake", 3);
        threads.append(c, Revision.draft("cm_1", 3, T.plusSeconds(9), "ann", Revision.RETRACTED, null, "mistake"), List.of());
        assertThat(verifier().verifyAll(null, null, 50).ok()).isTrue();
        rewrite((i, n) -> {
            if (i == 3) {
                ((ObjectNode) n.get("comment")).put("author", "qa-viewer").put("state", "live");
            }
            return n;
        });
        assertThat(verifier().verifyThread("th_1").problem()).contains("author of comment cm_1").doesNotContain("unreadable");
        rewrite((i, n) -> {
            if (i == 3) {
                ((ObjectNode) n.get("comment")).put("author", "ann");
            }
            return n;
        });
        assertThat(verifier().verifyThread("th_1").problem()).contains("state of comment cm_1");
    }

    @Test
    void aRemovedNewestRevisionIsFound() throws Exception {
        thread();
        rewrite((i, n) -> i == 2 ? null : n);
        ChainVerifier.ThreadReport r = verifier().verifyThread("th_1");
        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("2 were written");
    }

    @Test
    void aChangedHoldAndARemovedHoldAreFound() throws Exception {
        holds.place(new Hold(0, "all", null, null, null, null, null, null, "case 7", "carol", T, null, null));
        holds.place(new Hold(0, "all", null, null, null, null, null, null, "case 8", "carol", T, null, null));
        assertThat(verifier().verifyAll(null, null, 50).ok()).isTrue();
        Path f = dir.resolve("holds.json");
        Files.writeString(f, Files.readString(f).replace("case 7", "case 0"));
        ChainVerifier.Report changed = verifier().verifyAll(null, null, 50);
        assertThat(changed.ok()).isFalse();
        assertThat(changed.problems()).extracting(ChainVerifier.Problem::type).contains("hold");
        JsonNode all = json.readTree(f.toFile());
        Files.writeString(f, json.writeValueAsString(List.of(all.get(0))));
        assertThat(verifier().verifyAll(null, null, 50).problems()).extracting(ChainVerifier.Problem::problem).anyMatch(p -> p.contains("holds were removed"));
    }

    @Test
    void aChangedOrRemovedAuditRowIsFound() {
        audit.record("ann", "login", "ann", "ok");
        audit.record("ann", "user-created", "bob", "x");
        audit.record("ann", "logout", "ann", "y");
        assertThat(verifier().verifyAll(null, null, 50).auditOk()).isTrue();
        auditRows.get(1).detail = "changed";
        assertThat(verifier().verifyAll(null, null, 50).auditOk()).isFalse();
        auditRows.get(1).detail = "x";
        auditRows.remove(2);
        ChainVerifier.Report r = verifier().verifyAll(null, null, 50);
        assertThat(r.auditOk()).isFalse();
        assertThat(r.problems()).extracting(ChainVerifier.Problem::type).contains("audit");
    }

    @Test
    void aRowThatCannotBeReadIsReportedNotThrown() {
        ThreadStore broken = mock(ThreadStore.class);
        CommentThread t = new CommentThread("th_9", "trade", "T9", "entity", null, null, null, "view", "open", "ann", T, T, 1);
        when(broken.page(any(), anyInt())).thenReturn(List.of(t)).thenReturn(List.of());
        when(broken.chain("th_9")).thenThrow(new IllegalStateException("Text 'x' could not be parsed"));
        ChainVerifier.Report r = new ChainVerifier(broken, shares, holds, seals, audit).verifyAll(null, null, 50);
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).extracting(ChainVerifier.Problem::problem).anyMatch(p -> p.contains("unreadable"));
    }
}
