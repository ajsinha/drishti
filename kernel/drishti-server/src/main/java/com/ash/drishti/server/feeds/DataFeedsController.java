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
package com.ash.drishti.server.feeds;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data feeds for administrators (docs/guides/WORLD_DATA_GUIDE.md section 6; Admin → Data loads → Feeds): {@code GET /api/v1/admin/feeds}
 * every feed with its last run, last success, rows, duration, failures and last error, and the latest runs of the ledger;
 * {@code POST /api/v1/admin/feeds/{feed}/run} Run now (audited).
 */
@RestController
public class DataFeedsController {

    private final DataFeeds feeds;
    private final Entitlements entitlements;
    private final AuditLog audit;

    DataFeedsController(DataFeeds feeds, Entitlements entitlements, AuditLog audit) {
        this.feeds = feeds;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** Every feed with its ledger summary, and the latest {@code runs} runs (at most 500), newest first. */
    @GetMapping("/api/v1/admin/feeds")
    public Map<String, Object> status(@RequestParam(name = "runs", defaultValue = "30") int runs, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return feeds.status(Math.max(0, Math.min(runs, 500)));
    }

    /** Run now: the feed started in the background ({@code started: true, pid, log}), or {@code started: false, skipped} while one runs. */
    @PostMapping("/api/v1/admin/feeds/{feed}/run")
    public Map<String, Object> run(@PathVariable("feed") String feed, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        try {
            Map<String, Object> out = feeds.runNow(feed, p.user());
            if (audit != null) {
                try {
                    audit.record(p.user(), "feed.run", "feed/" + feed, Boolean.TRUE.equals(out.get("started")) ? "Run now" : "Run now: " + out.get("skipped"));
                } catch (RuntimeException e) {
                    // the run is started either way
                }
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, e.getMessage());
        } catch (IllegalStateException | IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the feed could not be started: " + e.getMessage());
        }
    }
}
