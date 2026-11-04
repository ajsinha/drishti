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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        return inbox.status();               // the settings, the rules and the Admin screen's board included: DataInbox.status, the one source
    }

    /**
     * A report the status links to: a handled file's {@code .loaded.json} or {@code .failed.json}, a loader's summary or its rejects (the
     * first 500 lines), by its path under the inbox's folder ({@code failed/2026-11-03/<run>/x.jsonl.gz.failed.json}); nothing else.
     */
    @GetMapping("/api/v1/admin/dataload/report")
    public Object report(@RequestParam(name = "path") String path, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        try {
            return inbox.report(path);
        } catch (NoSuchFileException e) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the report could not be read: " + e.getMessage());
        }
    }

    /**
     * Load now: a cycle started at once; while a run still loads, none is started and the answer says so ({@code started: false},
     * {@code skipped: run <id> still loading (12 min)}): runs never overlap.
     */
    @PostMapping("/api/v1/admin/dataload/run")
    public Map<String, Object> run(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!inbox.started()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the data inbox is off on this server: turn it on in Admin, Engines (dataload)");
        }
        String skipped = inbox.runNow(p.user());
        record(p, "dataload.run", "inbox", skipped == null ? "Load now" : "Load now " + skipped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", skipped == null);
        if (skipped != null) {
            out.put("skipped", skipped);
        }
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

    /**
     * Undo a load by its id (docs/architecture/DATA_LOAD_RULES.md section 6.3): {@code {"loadId": "<id>"}} says what would be undone (a
     * dry run, nothing changed); {@code {"loadId": "<id>", "confirm": "<the same id>"}} undoes it: its rows removed from every Delta and
     * Iceberg table of the inbox's lake, one commit per table and date, a replace restored. The answer lists each table and date.
     */
    @PostMapping("/api/v1/admin/dataload/undo")
    public Map<String, Object> undo(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String id = body == null || body.get("loadId") == null ? "" : String.valueOf(body.get("loadId")).trim();
        if (id.isEmpty() || !id.matches("[0-9A-Za-z._:-]{3,120}")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give the load's id: {\"loadId\": \"20261104T093000.123-4242.1\"} (a load report's loads)");
        }
        boolean confirmed = id.equals(body.get("confirm") == null ? null : String.valueOf(body.get("confirm")).trim());
        String lake = inbox.settings().lake();
        try {
            List<Map<String, Object>> lines = com.ash.drishti.lakeload.LakeUndo.undoAll(lake, id, List.of(), !confirmed);
            if (confirmed) {
                record(p, "dataload.undo", "load/" + id, lines.size() + " table dates undone");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("loadId", id);
            out.put("undone", confirmed);
            out.put("lines", lines);
            out.put("text", lines.isEmpty() ? "no row of load " + id + " in " + lake
                    : (confirmed ? "undone: " : "would undo: ") + lines.size() + " table date" + (lines.size() == 1 ? "" : "s")
                            + (confirmed ? "" : "; confirm with the load id to undo"));
            return out;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the undo failed: " + e.getMessage());
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
