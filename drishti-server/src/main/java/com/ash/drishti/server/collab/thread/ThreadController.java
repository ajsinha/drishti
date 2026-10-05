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
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comment threads (docs/architecture/COLLABORATION.md, API): threads on an entity, a panel or a field; replies; edit, retract and
 * (administrators) hide; resolve, lock, follow; the caller's mentions. Reads take the as-of headers; every write needs the
 * {@code collaborate} power, and personal API tokens read only.
 */
@RestController
@RequestMapping("/api/v1")
public class ThreadController {

    /** {@code PATCH /comments/{id}}: the new text and the revision it was written against. */
    public record EditBody(String body, Integer revision) {}

    public record StateBody(String state) {}

    public record FollowBody(Boolean muted) {}

    public record HideBody(String reason) {}

    private final ThreadService threads;

    public ThreadController(ThreadService threads) {
        this.threads = threads;
    }

    @GetMapping("/threads/{kind}/{id}")
    public List<ThreadService.ThreadView> list(@PathVariable String kind, @PathVariable String id, @RequestParam(required = false) String anchor,
            @RequestParam(required = false) String panel, @RequestParam(required = false) String path,
            @RequestParam(required = false) String state, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String before, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.list(p, kind, id, anchor, panel, path, state, limit, before);
    }

    @GetMapping("/threads/{kind}/{id}/counts")
    public Map<String, Object> counts(@PathVariable String kind, @PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.counts(p, kind, id);
    }

    @PostMapping("/threads/{kind}/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreadService.Posted start(@PathVariable String kind, @PathVariable String id, @RequestBody ThreadService.NewThread body, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.start(p, kind, id, body, asOf, false);
    }

    @PostMapping("/threads/{tid}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreadService.Posted reply(@PathVariable String tid, @RequestBody ThreadService.NewComment body, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.reply(p, tid, body, asOf);
    }

    @PatchMapping("/comments/{cid}")
    public ThreadService.CommentView edit(@PathVariable String cid, @RequestBody EditBody body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.edit(p, cid, body.body(), body.revision());
    }

    @PostMapping("/comments/{cid}/retract")
    public ThreadService.CommentView retract(@PathVariable String cid, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.retract(p, cid);
    }

    @GetMapping("/comments/{cid}/revisions")
    public List<ThreadService.RevisionView> revisions(@PathVariable String cid, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.revisions(p, cid);
    }

    @PostMapping("/threads/{tid}/state")
    public ThreadService.ThreadView state(@PathVariable String tid, @RequestBody StateBody body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.state(p, tid, body.state());
    }

    @PutMapping("/threads/{tid}/follow")
    public Map<String, Object> follow(@PathVariable String tid, @RequestBody(required = false) FollowBody body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        boolean muted = body != null && Boolean.TRUE.equals(body.muted());
        threads.follow(p, tid, muted);
        return Map.of("following", true, "muted", muted);
    }

    @DeleteMapping("/threads/{tid}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable String tid, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        threads.unfollow(p, tid);
    }

    @GetMapping("/me/mentions")
    public List<ThreadService.MentionRow> mentions(@RequestParam(required = false) Integer limit, @RequestParam(required = false) String before,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.mentions(p, limit, before);
    }

    @PostMapping("/admin/collab/comments/{cid}/hide")
    public ThreadService.CommentView hide(@PathVariable String cid, @RequestBody HideBody body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.hide(p, cid, body.reason());
    }

    @PostMapping("/admin/collab/comments/{cid}/unhide")
    public ThreadService.CommentView unhide(@PathVariable String cid, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return threads.unhide(p, cid);
    }
}
