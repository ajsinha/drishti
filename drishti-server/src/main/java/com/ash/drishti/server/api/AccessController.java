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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Who looked at what (administrators): the access log, filtered by user, action, entity and time. */
@RestController
@RequestMapping("/api/v1/admin/access")
public class AccessController {

    private final AccessLog log;
    private final Entitlements entitlements;

    public AccessController(AccessLog log, Entitlements entitlements) {
        this.log = log;
        this.entitlements = entitlements;
    }

    @GetMapping
    public List<AccessLog.Event> find(@RequestParam(required = false) String user, @RequestParam(required = false) String action,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        log.flush();                                              // include what was read a moment ago
        return log.find(new AccessLog.Filter(blank(user), blank(action), blank(kind), blank(id), instant(from), instant(to), limit));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return log.stats();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** An instant, or a date (its start, UTC). */
    private static Instant instant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return s.length() == 10 ? java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() : Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + s + "' is not a date (2026-09-30) or an instant (2026-09-30T14:00:00Z)");
        }
    }
}
