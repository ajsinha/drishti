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
package com.ash.drishti.server.edit;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write-back's API (WRITE_BACK.md › The design and its reasons, section 13): submit a change (or a form's draft), see what a submission would do (the review and confirm
 * sheet), list changes, read one, approve, reject or withdraw, and a record's history. The same requests as the console and the CLI;
 * a token needs the {@code edit} scope (application.yaml: {@code token-scopes}). With write-back off every write answers
 * {@code 403 DRS-9301} and the reads answer empty.
 */
@RestController
@RequestMapping("/api/v1")
public class ChangesController {

    private final EditService edits;
    private final EditPreview preview;
    private final Entitlements entitlements;

    public ChangesController(EditService edits, EditPreview preview, Entitlements entitlements) {
        this.edits = edits;
        this.preview = preview;
        this.entitlements = entitlements;
    }

    /** What this person may do here: whether editing is on, whether they hold the edit power, whether they approve. */
    @GetMapping("/changes/settings")
    public Map<String, Object> settings(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        EditSettings s = edits.settings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("mayEdit", s.enabled() && entitlements.mayEdit(p));
        m.put("approver", s.enabled() && edits.approver(p));
        m.put("kinds", edits.fields().kinds());
        return m;
    }

    /** Submits: {@code {draft, reason}} or {@code {kind, entity, items, reason, ...}}; {@code 202} with one answer per change. */
    @PostMapping("/changes")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<Map<String, Object>> out = edits.submit(p, body);
        return ResponseEntity.accepted().body(Map.of("changes", out));
    }

    /** The review and confirm sheet (WRITE_BACK.md 18.2): the difference, where it goes, who approves, what it recomputes. Writes nothing. */
    @PostMapping("/changes/preview")
    public Map<String, Object> preview(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return preview.preview(p, body);
    }

    @GetMapping("/changes")
    public Map<String, Object> list(@RequestParam(required = false) String state, @RequestParam(required = false) String kind,
            @RequestParam(required = false) String entity, @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "100") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("changes", edits.list(p, state, kind, entity, mine, Math.max(1, Math.min(limit, 500))));
    }

    @GetMapping("/changes/{id}")
    public Map<String, Object> one(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return edits.get(p, id);
    }

    @PostMapping("/changes/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requireOn();
        return edits.approve(p, id, body == null ? null : body.path("note").asText(null));
    }

    @PostMapping("/changes/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requireOn();
        return edits.reject(p, id, body == null ? null : body.path("reason").asText(null));
    }

    @PostMapping("/changes/{id}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requireOn();
        return edits.withdraw(p, id);
    }

    /** A record's changes from the edit log, newest first, and its version (what a form opens on). */
    @GetMapping("/entities/{kind}/{id}/history")
    public Map<String, Object> history(@PathVariable String kind, @PathVariable String id, @RequestParam(defaultValue = "50") int limit,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", id);
        m.put("version", edits.version(kind, id));
        m.put("changes", edits.history(p, kind, id, Math.max(1, Math.min(limit, 500))));
        return m;
    }

    private void requireOn() {
        if (!edits.settings().enabled()) {
            throw new DrishtiException(ErrorCode.EDIT_OFF, "editing is off on this server (edit.enabled in config/edit/edit.yaml)");
        }
    }
}
