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
package com.ash.drishti.server.pivot;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cell comments on saved pivot views (PIVOT_GUIDE.md, cell comments; API_GUIDE.md, the pivot engine), under
 * {@code /api/v1/pivot/views/{owner}/{name}/comments}: listed (one cell's with {@code cell}, open only with {@code open=true}), added on a
 * cell by its coordinates, resolved, reopened and deleted by the author or the view's owner. Every change is audited with the view, the
 * cell and the comment's id, never its text. A personal API token needs the {@code pivot:comment} scope to change them.
 */
@RestController
@RequestMapping("/api/v1/pivot/views/{owner}/{name}/comments")
public class PivotCommentsController {

    private final PivotController pivot;
    private final PivotCellComments comments;
    private final AuditLog audit;

    public PivotCommentsController(PivotController pivot, PivotCellComments comments, AuditLog audit) {
        this.pivot = pivot;
        this.comments = comments;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @RequestParam(required = false) String cell, @RequestParam(defaultValue = "false") boolean open) {
        pivot.on(p);
        return comments.list(p, owner + "/" + name, cell, open);
    }

    /** {@code {coords: {DESK: "RATES"}, measure: "Notional", text}}. */
    @PostMapping
    public Map<String, Object> add(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @RequestBody Map<String, Object> body) {
        pivot.on(p);
        Map<String, Object> c = comments.add(p, owner + "/" + name, body);
        audit.record(p.user(), "pivot.comment.add", owner + "/" + name, c.get("id") + "; cell=" + c.get("cell") + "; version=" + c.get("version"));
        return c;
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @PathVariable String id) {
        pivot.on(p);
        Map<String, Object> c = comments.resolve(p, owner + "/" + name, id, true);
        audit.record(p.user(), "pivot.comment.resolve", owner + "/" + name, id + "; cell=" + c.get("cell"));
        return c;
    }

    @PostMapping("/{id}/reopen")
    public Map<String, Object> reopen(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @PathVariable String id) {
        pivot.on(p);
        Map<String, Object> c = comments.resolve(p, owner + "/" + name, id, false);
        audit.record(p.user(), "pivot.comment.reopen", owner + "/" + name, id + "; cell=" + c.get("cell"));
        return c;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @PathVariable String id) {
        pivot.on(p);
        Map<String, Object> c = comments.delete(p, owner + "/" + name, id);
        audit.record(p.user(), "pivot.comment.delete", owner + "/" + name, id + "; cell=" + c.get("cell") + "; by " + c.get("by"));
        return Map.of("deleted", id);
    }
}
