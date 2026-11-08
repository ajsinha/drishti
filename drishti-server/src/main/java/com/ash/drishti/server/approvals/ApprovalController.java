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
package com.ash.drishti.server.approvals;

import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one Approvals API (API_REFERENCE.md "Approvals"): the caller's queue, one item, the decision and the questions. Every four-eyes kind is
 * decided here; the kinds' own approve and reject endpoints are gone.
 *
 * <pre>
 *   GET  /api/v1/approvals?box=inbox|sent|done|all&amp;group=data|screens|risk|access&amp;kind=&amp;limit=   the list, due soon first
 *   GET  /api/v1/approvals/count                                    {inbox, dueSoon}: the top bar's count
 *   GET  /api/v1/approvals/{kind}/{id}                              the item: diff, effect, rules, facts, questions, actions
 *   POST /api/v1/approvals/{kind}/{id}/approve                      {comment, params}
 *   POST /api/v1/approvals/{kind}/{id}/reject                       {comment}: a reason is required
 *   POST /api/v1/approvals/{kind}/{id}/withdraw                     the maker's own
 *   POST /api/v1/approvals/{kind}/{id}/questions                    {text}: ask a question, or answer one
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    /**
     * @param comment the reason (required to reject) or a note
     * @param params what a kind needs besides ({@code scope} of a pivot function, {@code step} of a report run, {@code force} of a BI item)
     */
    public record Decision(String comment, Map<String, Object> params) {}

    /** @param text the question or the answer */
    public record Question(String text) {}

    private final ApprovalQueue queue;

    public ApprovalController(ApprovalQueue queue) {
        this.queue = queue;
    }

    @GetMapping
    public ApprovalQueue.Listing list(@RequestParam(required = false) String box, @RequestParam(required = false) String group,
            @RequestParam(required = false) String kind, @RequestParam(defaultValue = "200") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return queue.list(p, box, group, kind, Math.min(limit, 1000));
    }

    @GetMapping("/count")
    public Map<String, Integer> count(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return queue.count(p);
    }

    @GetMapping("/{kind}/{id}")
    public ApprovalQueue.Opened open(@PathVariable String kind, @PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return queue.open(kind, id, p);
    }

    @PostMapping("/{kind}/{id}/{action:approve|reject|withdraw}")
    public ApprovalSource.Item decide(@PathVariable String kind, @PathVariable String id, @PathVariable String action,
            @RequestBody(required = false) Decision body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return queue.decide(kind, id, action, body == null ? null : body.comment(), body == null || body.params() == null ? Map.of() : body.params(), p);
    }

    @PostMapping("/{kind}/{id}/questions")
    public List<ApprovalQueue.Post> ask(@PathVariable String kind, @PathVariable String id, @RequestBody Question body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return queue.ask(kind, id, body == null ? null : body.text(), p);
    }
}
