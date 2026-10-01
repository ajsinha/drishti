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
package com.ash.drishti.server.api;

import com.ash.drishti.identity.NoteStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notes on entities and their fields. Whoever may open the kind reads and adds notes; the author edits; the author or
 * an administrator deletes.
 */
@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

    private final NoteStore notes;
    private final Entitlements entitlements;

    public NoteController(NoteStore notes, Entitlements entitlements) {
        this.notes = notes;
        this.entitlements = entitlements;
    }

    @GetMapping("/{kind}/{id}")
    public List<NoteStore.Note> of(@PathVariable String kind, @PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, kind);
        return notes.of(kind, id);
    }

    /** Body: {@code {"body": "…", "path": "$.mtm"}} ({@code path} optional: a note on the whole entity). */
    @PostMapping("/{kind}/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteStore.Note add(@PathVariable String kind, @PathVariable String id, @RequestBody Map<String, String> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, kind);
        return notes.add(kind, id, body.get("path"), p.user(), body.get("body"));
    }

    @PutMapping("/{noteId}")
    public NoteStore.Note edit(@PathVariable long noteId, @RequestBody Map<String, String> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, notes.get(noteId).kind());
        return notes.edit(noteId, p.user(), body.get("body"));
    }

    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long noteId, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, notes.get(noteId).kind());
        notes.delete(noteId, p.user(), entitlements.isAdmin(p));
    }
}
