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
package com.ash.drishti.identity.collab;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.NoteStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grows the notes of earlier releases into comment threads, once (COLLABORATION.md, Decision 1): each {@code drishti_note} row becomes
 * a single-comment thread on the whole view or, with a path, on that field, pinned with no business date, {@code knownAt} = the note's
 * creation and generation 0 (unknown). The table stays in place; {@code drishti_note_link} records which note became which comment, so
 * a second run (every start) imports only what is new, and the deprecated {@code /notes} facade keeps the old numbers.
 */
public final class NoteImport {

    private static final Logger LOG = LoggerFactory.getLogger(NoteImport.class);

    private NoteImport() {}

    /** Imports the notes not yet linked; returns how many were imported. */
    public static int run(NoteStore notes, ThreadStore threads, CollabTx tx, AuditLog audit) {
        Set<Long> done = threads.linkedNotes();
        int n = 0;
        for (NoteStore.Note note : notes.all()) {
            if (done.contains(note.id())) {
                continue;
            }
            tx.run(() -> {
                importOne(note, threads);
                return null;
            });
            n++;
        }
        if (n > 0) {
            audit.record("system", "collab.notes-imported", "", n + " notes became comment threads");
            LOG.info("imported {} notes as comment threads", n);
        }
        return n;
    }

    private static void importOne(NoteStore.Note note, ThreadStore threads) {
        Instant at = note.createdAt().truncatedTo(ChronoUnit.MILLIS);
        Instant last = note.updatedAt() == null ? at : note.updatedAt().truncatedTo(ChronoUnit.MILLIS);
        boolean field = note.path() != null && !note.path().isBlank();
        CommentThread t = new CommentThread(Ulid.next("th_", at.toEpochMilli()), note.kind(), note.entityId(),
                field ? CommentThread.FIELD : CommentThread.ENTITY, null, field ? note.path() : null, null, field ? note.path() : "whole view",
                CommentThread.OPEN, note.author(), at, last, 1);
        Comment c = new Comment(Ulid.next("cm_", at.toEpochMilli()), t.id(), note.author(), at, last.isAfter(at) ? last : null, 1,
                new Pin(null, false, at, 0, null), note.body(), List.of(), Comment.LIVE, null);
        threads.saveThread(t);
        threads.append(c, Revision.draft(c.id(), 1, at, note.author(), Revision.CREATED, note.body(), null), List.of());
        threads.follow(new Follow(t.id(), note.author(), false, at));
        threads.linkNote(note.id(), c.id());
    }
}
