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
package com.ash.drishti.server.dataload;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.dataload.DataInbox;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data inbox for administrators (DATALOAD_INBOX.md section 10) and {@code drishti.py dataload}: {@code GET /api/v1/admin/dataload} the
 * status (files waiting and why, the last cycles, failures with their reasons, the lock, the next cycle), {@code POST …/run} Load now,
 * {@code POST …/retry} a failed file back into the inbox. Load now and Retry are audited.
 */
@RestController
public class DataLoadController {

    private final DataInbox inbox;
    private final Entitlements entitlements;
    private final AuditLog audit;

    DataLoadController(DataInbox inbox, Entitlements entitlements, AuditLog audit) {
        this.inbox = inbox;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    @GetMapping("/api/v1/admin/dataload")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>(inbox.status());
        var s = inbox.settings();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("stableSeconds", s.stableSeconds());
        cfg.put("maxFiles", s.maxFiles());
        cfg.put("maxGb", s.maxGb());
        cfg.put("loaderHeap", s.loaderHeap());
        cfg.put("loadTimeout", s.loadTimeout().toString());
        cfg.put("rejectMaxPercent", s.rejectMaxPercent());
        cfg.put("reloadDuplicates", s.reloadDuplicates());
        cfg.put("keepOutboxDays", s.keepOutboxDays());
        cfg.put("lake", s.lake());
        cfg.put("rulesFile", s.rulesFile());
        out.put("settings", cfg);
        return out;
    }

    /** Load now: a cycle queued (after the one running, if any). */
    @PostMapping("/api/v1/admin/dataload/run")
    public Map<String, Object> run(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!inbox.runNow(p.user())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the data inbox is off on this server: turn it on in Admin, Engines (dataload)");
        }
        record(p, "dataload.run", "inbox", "Load now");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", true);
        out.put("by", p.user());
        return out;
    }

    /** Retry: {@code {"file": "<name>"}} (or {@code ?file=}): a failed file and its marker back into the inbox. */
    @PostMapping("/api/v1/admin/dataload/retry")
    public Map<String, Object> retry(@RequestParam(name = "file", required = false) String file,
            @RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String name = file != null ? file : body == null || body.get("file") == null ? null : String.valueOf(body.get("file"));
        if (name == null || name.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give the failed file's name: {\"file\": \"trades-2026-11-03.jsonl.gz\"}");
        }
        try {
            Map<String, Object> out = inbox.retry(name.trim(), p.user());
            record(p, "dataload.retry", "inbox/" + name.trim(), "from " + out.get("from"));
            return out;
        } catch (NoSuchFileException e) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the file could not be moved back: " + e.getMessage());
        }
    }

    private void record(Principal p, String action, String subject, String detail) {
        if (audit != null) {
            try {
                audit.record(p.user(), action, subject, detail);
            } catch (RuntimeException e) {
                // the move is done either way
            }
        }
    }
}
