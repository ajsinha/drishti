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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's inbox: notices (shares first) rendered for the caller's current rights. The live feed is {@code /me/alerts/stream}. */
@RestController
@RequestMapping("/api/v1/me/inbox")
public class InboxController {

    /** Rows to mark read: these {@code seqs}, or everything {@code upTo} a row number. */
    public record ReadBody(List<Long> seqs, Long upTo) {}

    private final InboxService inbox;
    private final ShareService shares;

    public InboxController(InboxService inbox, ShareService shares) {
        this.inbox = inbox;
        this.shares = shares;
    }

    @GetMapping
    public List<InboxService.Row> list(@RequestParam(required = false) String type, @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") long before,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        shares.requireOn();
        return inbox.list(p, type, unread, limit, before);
    }

    @GetMapping("/count")
    public Map<String, Object> count(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        shares.requireOn();
        return Map.of("unread", inbox.unread(p.user()));
    }

    @PostMapping("/read")
    public Map<String, Object> read(@RequestBody ReadBody body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        shares.requireOn();
        if (body == null || (body.upTo() == null && (body.seqs() == null || body.seqs().isEmpty()))) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send {\"seqs\": [...]} or {\"upTo\": n}");
        }
        int changed = inbox.markRead(p.user(), body.seqs(), body.upTo());
        return Map.of("changed", changed, "unread", inbox.unread(p.user()));
    }
}
