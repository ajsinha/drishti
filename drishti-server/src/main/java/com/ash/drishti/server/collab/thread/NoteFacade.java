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
package com.ash.drishti.server.collab.thread;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.NoteStore;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The deprecated {@code /notes} API, kept for one release as a thin facade over threads (COLLABORATION.md, Decision 1): a note is a
 * single-comment thread on the whole view or on a field, linked to the number the old API gave it. The shape is the old one
 * ({@link NoteStore.Note}); what changed is behind it: text is scrubbed for readers without {@code raw}, edits keep every revision and
 * stop after the edit window, and a delete is a retract (the author) or a hide (an administrator). Notes written through the new
 * thread API are not listed here.
 */
public final class NoteFacade {

    static final String HIDE_REASON = "removed through the notes API";
    private final ThreadService threads;
    private final ThreadStore store;
    private final Entitlements entitlements;
    private final ReentrantLock ids = new ReentrantLock();

    public NoteFacade(ThreadService threads, Entitlements entitlements) {
        this.threads = threads;
        this.store = threads.store();
        this.entitlements = entitlements;
    }

    /** The entity's notes, oldest first, for this reader. */
    public List<NoteStore.Note> of(Principal p, String kind, String id) {
        entitlements.requireOpen(p, kind);
        List<NoteStore.Note> out = new ArrayList<>();
        for (CommentThread t : store.threads(kind, id)) {
            if (!(CommentThread.ENTITY.equals(t.anchor()) || CommentThread.FIELD.equals(t.anchor())) || t.comments() != 1) {
                continue;
            }
            List<Comment> cs = store.comments(t.id());
            Long noteId = cs.size() == 1 ? store.noteOfComment(cs.get(0).id()).orElse(null) : null;
            if (noteId != null && Comment.LIVE.equals(cs.get(0).state())) {
                out.add(note(p, noteId, t, cs.get(0)));
            }
        }
        out.sort(Comparator.comparingLong(NoteStore.Note::id));
        return out;
    }

    /** A note on the whole entity or, with {@code path}, on one field. */
    public NoteStore.Note add(Principal p, String kind, String id, String path, String body, AsOf asOf) {
        entitlements.requireOpen(p, kind);
        boolean field = path != null && !path.isBlank();
        ThreadService.Posted posted = threads.start(p, kind, id, new ThreadService.NewThread(field ? CommentThread.FIELD : CommentThread.ENTITY, null,
                field ? path : null, null, null, null, body), asOf, true);
        String commentId = posted.comment().id();
        ids.lock();
        try {
            long noteId = store.linkedNotes().stream().mapToLong(Long::longValue).max().orElse(0) + 1;
            store.linkNote(noteId, commentId);
            return note(p, noteId, store.thread(posted.threadId()).orElseThrow(), store.comment(commentId).orElseThrow());
        } finally {
            ids.unlock();
        }
    }

    /** Only the author edits a note, and only within the edit window. */
    public NoteStore.Note edit(Principal p, long noteId, String body) {
        Comment c = commentOf(noteId);
        CommentThread t = store.thread(c.threadId()).orElseThrow();
        entitlements.requireOpen(p, t.kind());
        threads.edit(p, c.id(), body, null);
        return note(p, noteId, t, store.comment(c.id()).orElseThrow());
    }

    /** The author retracts; an administrator hides. Anyone else may not. */
    public void delete(Principal p, long noteId) {
        Comment c = commentOf(noteId);
        CommentThread t = store.thread(c.threadId()).orElseThrow();
        entitlements.requireOpen(p, t.kind());
        if (c.author().equals(p.user())) {
            threads.retract(p, c.id());
        } else if (entitlements.isAdmin(p)) {
            threads.hide(p, c.id(), HIDE_REASON);
        } else {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "only " + c.author() + " or an administrator may delete this note");
        }
    }

    private Comment commentOf(long noteId) {
        return store.commentOfNote(noteId).flatMap(store::comment)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no note #" + noteId));
    }

    private NoteStore.Note note(Principal reader, long noteId, CommentThread t, Comment c) {
        String body = threads.view(reader, t, c).body();
        Instant updated = c.editedAt() != null ? c.editedAt() : c.createdAt();
        return new NoteStore.Note(noteId, t.kind(), t.entityId(), t.path(), c.author(), body == null ? "" : body, c.createdAt(), updated);
    }
}
