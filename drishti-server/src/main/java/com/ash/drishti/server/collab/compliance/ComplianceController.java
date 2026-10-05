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
package com.ash.drishti.server.collab.compliance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.Hold;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.identity.collab.Revision;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Administration and compliance (COLLABORATION.md, API): searching shares and threads ({@code admin} or {@code compliance}), legal
 * holds, the export job, chain verification ({@code compliance}), and retention and permanent removal ({@code admin}). Hiding and
 * locking are in the thread controller. Every failure is a problem+json with a {@code DRS-} code.
 */
@RestController
@RequestMapping("/api/v1/admin/collab")
public class ComplianceController {

    private static final int SEARCH_MAX = 200;

    private final HoldService holds;
    private final ExportService exports;
    private final ChainVerifier verifier;
    private final CollabPurge purge;
    private final ShareStore shares;
    private final ThreadStore threads;
    private final Entitlements entitlements;
    private final AuditLog audit;

    @SuppressWarnings("java:S107")
    public ComplianceController(HoldService holds, ExportService exports, ChainVerifier verifier, CollabPurge purge, ShareStore shares,
            ThreadStore threads, Entitlements entitlements, AuditLog audit) {
        this.holds = holds;
        this.exports = exports;
        this.verifier = verifier;
        this.purge = purge;
        this.shares = shares;
        this.threads = threads;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    // ---- search ------------------------------------------------------------------------------------------------------------------

    @GetMapping("/shares")
    public Map<String, Object> shares(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(required = false) String user,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String id, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String after) {
        requireSearch(p);
        Instant f = ComplianceDates.from(from);
        Instant t = ComplianceDates.to(to);
        int max = Math.min(Math.max(1, limit), SEARCH_MAX);
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = after;
        boolean more = false;
        scan:
        while (true) {
            List<Share> page = shares.page(cursor, SEARCH_MAX);
            if (page.isEmpty()) {
                break;
            }
            for (Share s : page) {
                cursor = s.id();
                if ((kind != null && !kind.equals(s.kind())) || (id != null && !id.equals(s.entityId())) || (f != null && s.createdAt().isBefore(f))
                        || (t != null && s.createdAt().isAfter(t))) {
                    continue;
                }
                List<Recipient> rs = shares.recipients(s.id());
                if (user != null && !user.equals(s.sender()) && rs.stream().noneMatch(r -> r.username().equals(user))) {
                    continue;
                }
                if (items.size() == max) {
                    more = true;
                    break scan;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("sender", s.sender());
                m.put("createdAt", s.createdAt());
                m.put("kind", s.kind());
                m.put("entityId", s.entityId());
                m.put("panelId", s.panelId());
                m.put("channels", s.channels());
                m.put("threadId", s.threadId());
                m.put("recipients", rs.size());
                m.put("hashOk", s.intact());
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next", more && !items.isEmpty() ? items.get(items.size() - 1).get("id") : null);
        return out;
    }

    @GetMapping("/threads")
    public Map<String, Object> threads(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(required = false) String user,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String id, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String after) {
        requireSearch(p);
        Instant f = ComplianceDates.from(from);
        Instant t = ComplianceDates.to(to);
        int max = Math.min(Math.max(1, limit), SEARCH_MAX);
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = after;
        boolean more = false;
        scan:
        while (true) {
            List<CommentThread> page = threads.page(cursor, SEARCH_MAX);
            if (page.isEmpty()) {
                break;
            }
            for (CommentThread th : page) {
                cursor = th.id();
                if ((kind != null && !kind.equals(th.kind())) || (id != null && !id.equals(th.entityId())) || (f != null && th.lastAt().isBefore(f))
                        || (t != null && th.createdAt().isAfter(t))) {
                    continue;
                }
                if (user != null && !user.equals(th.createdBy()) && threads.comments(th.id()).stream().noneMatch(c -> c.author().equals(user))) {
                    continue;
                }
                if (items.size() == max) {
                    more = true;
                    break scan;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", th.id());
                m.put("kind", th.kind());
                m.put("entityId", th.entityId());
                m.put("anchor", th.anchor());
                m.put("state", th.state());
                m.put("createdBy", th.createdBy());
                m.put("createdAt", th.createdAt());
                m.put("lastAt", th.lastAt());
                m.put("comments", th.comments());
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next", more && !items.isEmpty() ? items.get(items.size() - 1).get("id") : null);
        return out;
    }

    /**
     * The hidden comments, in the scan order of {@code /threads}: who wrote them, in which thread and why they were hidden
     * (never the text). {@code admin} or {@code compliance}; {@code next} is the thread to continue after. The admin page lists them without
     * opening every thread.
     */
    @GetMapping("/threads/hidden")
    public Map<String, Object> hiddenComments(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String after) {
        requireSearch(p);
        int max = Math.min(Math.max(1, limit), SEARCH_MAX);
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = after;
        boolean more = false;
        scan:
        while (true) {
            List<CommentThread> page = threads.page(cursor, SEARCH_MAX);
            if (page.isEmpty()) {
                break;
            }
            for (CommentThread th : page) {
                cursor = th.id();
                for (Comment c : threads.comments(th.id())) {
                    if (!Comment.HIDDEN.equals(c.state())) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("commentId", c.id());
                    m.put("threadId", th.id());
                    m.put("kind", th.kind());
                    m.put("entityId", th.entityId());
                    m.put("author", c.author());
                    m.put("createdAt", c.createdAt());
                    m.put("reason", c.stateReason());
                    items.add(m);
                }
                if (items.size() >= max) {
                    more = true;
                    break scan;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next", more ? cursor : null);
        return out;
    }

    /** One thread as the record has it: hidden and retracted comments, every revision, unscrubbed. {@code compliance} only; audited. */
    @GetMapping("/threads/{tid}")
    public Map<String, Object> thread(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String tid) {
        entitlements.requireCompliance(p);
        CommentThread th = threads.thread(tid).orElseThrow(() -> new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread " + tid));
        audit.record(p.user(), "collab.thread.read", th.kind() + "/" + th.entityId(), tid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("thread", th);
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Comment c : threads.comments(tid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("comment", c);
            List<Revision> revs = threads.revisions(c.id());
            m.put("revisions", revs);
            cs.add(m);
        }
        out.put("comments", cs);
        out.put("chain", verifier.verifyThread(tid));
        return out;
    }

    /** Removes a whole thread for good (administrators); {@code 423 DRS-7010} while a legal hold covers it. */
    @DeleteMapping("/threads/{tid}")
    public ResponseEntity<Void> removeThread(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String tid) {
        purge.removeThread(p, tid);
        return ResponseEntity.noContent().build();
    }

    // ---- holds -------------------------------------------------------------------------------------------------------------------

    @GetMapping("/holds")
    public List<Hold> holds(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(defaultValue = "false") boolean active) {
        return holds.list(p, active);
    }

    @PostMapping("/holds")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Hold place(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody HoldService.Request body) {
        return holds.place(p, body);
    }

    /** Releases the hold (it stays in the list, released). */
    @DeleteMapping("/holds/{id}")
    public Hold release(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable long id) {
        return holds.release(p, id);
    }

    // ---- retention ---------------------------------------------------------------------------------------------------------------

    /** Runs retention now ({@code dryRun=true} only counts). Administrators; it removes only what configuration already says may go. */
    @PostMapping("/retention/run")
    public CollabPurge.Result runRetention(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(defaultValue = "false") boolean dryRun) {
        entitlements.requireAdmin(p);
        CollabPurge.Result r = purge.run(dryRun);
        audit.record(p.user(), "collab.retention.run", dryRun ? "dry run" : "run", r.toString());
        return r;
    }

    // ---- export ------------------------------------------------------------------------------------------------------------------

    @PostMapping("/exports")
    public ResponseEntity<Map<String, Object>> startExport(@RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestBody(required = false) ExportService.Request body) {
        ExportService.Job job = exports.start(p, body == null ? new ExportService.Request(null, null, null, null, null, null, null) : body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.view());
    }

    @GetMapping("/exports/{id}")
    public Map<String, Object> exportStatus(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id) {
        return exports.status(p, id).view();
    }

    /** The zip, once, for the person who asked. */
    @GetMapping("/exports/{id}/download")
    public ResponseEntity<InputStreamResource> download(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id) {
        ExportService.Download d = exports.download(p, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(d.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"drishti-collab-export-" + d.name() + "\"")
                .body(new InputStreamResource(d.stream()));
    }

    // ---- verification ------------------------------------------------------------------------------------------------------------

    /** One thread's chain ({@code ?thread=}), or every thread and share (optionally one entity: {@code ?kind=&id=}). */
    @GetMapping("/verify")
    public Object verify(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(required = false) String thread,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String id,
            @RequestParam(defaultValue = "100") int maxProblems) {
        entitlements.requireCompliance(p);
        Object result = thread != null && !thread.isBlank() ? verifier.verifyThread(thread.strip())
                : verifier.verifyAll(blank(kind), blank(id), Math.min(Math.max(1, maxProblems), 1000));
        audit.record(p.user(), "collab.verify", thread != null && !thread.isBlank() ? thread : "all", "");
        return result;
    }

    private void requireSearch(Principal p) {
        if (!entitlements.isAdmin(p) && !entitlements.mayCompliance(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is neither an administrator nor in a compliance role");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
