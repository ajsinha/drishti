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
package com.ash.drishti.identity;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.NoteEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notes on entities and their fields: what a desk wants the next reader to know ("restated on 28 Sep after the
 * fixing correction"). Everyone who may open the kind reads them; the author edits; the author or an administrator
 * deletes. Every change is audited.
 */
public final class NoteStore {

    /** A note as shown. */
    public record Note(long id, String kind, String entityId, String path, String author, String body, Instant createdAt, Instant updatedAt) {}

    public static final int MAX_LENGTH = 2000;
    public static final int MAX_PER_ENTITY = 200;
    private final IdentityRepositories.Notes notes;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    public NoteStore(IdentityRepositories.Notes notes, TransactionTemplate tx, AuditLog audit) {
        this.notes = notes;
        this.tx = tx;
        this.audit = audit;
    }

    /** The entity's notes, oldest first. */
    public List<Note> of(String kind, String id) {
        return notes.findByKindAndEntityIdOrderByIdAsc(kind, id).stream().map(NoteStore::view).toList();
    }

    /** Every note, oldest first (the import of notes into threads reads them once). */
    public List<Note> all() {
        return notes.findAll(org.springframework.data.domain.Sort.by("id")).stream().map(NoteStore::view).toList();
    }

    public Note add(String kind, String id, String path, String author, String body) {
        String text = check(body);
        String field = path == null || path.isBlank() ? null : path.trim();
        if (field != null && (field.length() > 200 || !field.startsWith("$"))) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a note's field is a path such as $.mtm");
        }
        Note n = tx.execute(s -> {
            if (notes.countByKindAndEntityId(kind, id) >= MAX_PER_ENTITY) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, kind + " " + id + " already has " + MAX_PER_ENTITY + " notes");
            }
            NoteEntity e = new NoteEntity();
            e.kind = kind;
            e.entityId = id;
            e.path = field;
            e.username = author;
            e.body = text;
            e.createdAt = Instant.now();
            e.updatedAt = e.createdAt;
            return view(notes.save(e));
        });
        audit.record(author, "note.add", kind + "/" + id, field == null ? "" : field);
        return n;
    }

    /** Only the author edits a note. */
    public Note edit(long noteId, String actor, String body) {
        String text = check(body);
        Note n = tx.execute(s -> {
            NoteEntity e = find(noteId);
            if (!e.username.equals(actor)) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "only " + e.username + " may edit this note");
            }
            e.body = text;
            e.updatedAt = Instant.now();
            return view(notes.save(e));
        });
        audit.record(actor, "note.edit", n.kind() + "/" + n.entityId(), "#" + noteId);
        return n;
    }

    /** The author, or an administrator ({@code admin} true), deletes a note. */
    public void delete(long noteId, String actor, boolean admin) {
        Note n = tx.execute(s -> {
            NoteEntity e = find(noteId);
            if (!admin && !e.username.equals(actor)) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "only " + e.username + " or an administrator may delete this note");
            }
            notes.delete(e);
            return view(e);
        });
        audit.record(actor, "note.delete", n.kind() + "/" + n.entityId(), "#" + noteId + " by " + n.author());
    }

    /** One note, for the entitlement check before an edit or delete. */
    public Note get(long noteId) {
        return view(find(noteId));
    }

    private NoteEntity find(long noteId) {
        return notes.findById(noteId).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no note #" + noteId));
    }

    private static String check(String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a note needs some text");
        }
        if (text.length() > MAX_LENGTH) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a note is at most " + MAX_LENGTH + " characters");
        }
        return text;
    }

    private static Note view(NoteEntity e) {
        return new Note(e.id, e.kind, e.entityId, e.path, e.username, e.body, e.createdAt, e.updatedAt);
    }
}
