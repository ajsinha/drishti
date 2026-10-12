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
package com.ash.drishti.server.connectors;

import com.ash.drishti.api.DatedHitIndex;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin &rarr; Connectors &rarr; Index: what a connector's multi-day type-ahead index holds (kinds by days, with sizes, build
 * time, the day being rebuilt and the last error) and a reload that rebuilds chosen days from the source in the background.
 * Administrators only (never for a personal API token); one reload per connector at a time; every reload is audited when it
 * starts and again with its result when it ends.
 */
@RestController
@RequestMapping("/api/v1/admin/connectors/{name}/index")
public class IndexReloadController {

    private final SourceRegistry sources;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public IndexReloadController(SourceRegistry sources, Entitlements entitlements, AuditLog audit) {
        this.sources = sources;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    private DatedHitIndex index(String name) {
        SourcePlugin p = sources.plugin(name).orElseThrow(() -> new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no running connector named '" + name + "'"));
        return p.typeAheadIndex().orElse(null);
    }

    /** The index of the connector: enabled flag, the automatic check, the reload in progress, and every kind by day. */
    @GetMapping
    public Map<String, Object> status(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return view(index(name));
    }

    /**
     * Rebuilds days. Body (all optional): {@code kind} (omitted: every kind) and {@code date} (a business date in the window, or
     * {@code "all"}; omitted: all). 409 when a reload of this connector is already running.
     */
    @PostMapping("/reload")
    public Map<String, Object> reload(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        DatedHitIndex idx = index(name);
        if (idx == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "connector '" + name + "' keeps no multi-day type-ahead index (index.days is 1)");
        }
        String kind = text(body, "kind");
        String date = text(body, "date");
        LocalDate day = null;
        if (date != null && !"all".equalsIgnoreCase(date)) {
            try {
                day = "undated".equalsIgnoreCase(date) ? DatedHitIndex.UNDATED : LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "date must be a business date (yyyy-MM-dd) or 'all', not '" + date + "'");
            }
        }
        String what = (kind == null ? "all kinds" : kind) + ", " + (day == null ? "all days" : date);
        int queued;
        try {
            queued = idx.reload(kind, day);
        } catch (IllegalStateException e) {
            audit.record(p.user(), "index-reload-refused", name, what + ": " + e.getMessage());
            throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        audit.record(p.user(), "index-reload", name, what + ": " + queued + (queued == 1 ? " day" : " days") + " started");
        String user = p.user();
        Thread.ofVirtual().name("index-reload-audit").start(() -> finish(idx, user, name, what));
        Map<String, Object> out = view(idx);
        out.put("queued", queued);
        return out;
    }

    private void finish(DatedHitIndex idx, String user, String name, String what) {
        try {
            while (idx.reloadProgress().running()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        DatedHitIndex.ReloadProgress done = idx.reloadProgress();
        audit.record(user, "index-reload-finished", name, what + ": " + done.done() + " of " + done.total() + " days rebuilt"
                + (done.error() == null ? ", no errors" : "; " + done.error()));
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static Map<String, Object> view(DatedHitIndex idx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", idx != null);
        if (idx == null) {
            m.put("days", List.of());
            return m;
        }
        DatedHitIndex.ReloadProgress pr = idx.reloadProgress();
        Map<String, Object> reload = new LinkedHashMap<>();
        reload.put("running", pr.running());
        reload.put("trigger", pr.trigger());
        reload.put("total", pr.total());
        reload.put("done", pr.done());
        reload.put("current", pr.current());
        reload.put("startedAt", str(pr.startedAt()));
        reload.put("finishedAt", str(pr.finishedAt()));
        reload.put("error", pr.error());
        m.put("reload", reload);
        DatedHitIndex.AutoCheck ac = idx.autoCheckInfo();
        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("enabled", ac.enabled());
        auto.put("everySeconds", ac.everyMillis() / 1000);
        auto.put("lastCheck", str(ac.lastCheck()));
        auto.put("nextCheck", str(ac.nextCheck()));
        auto.put("lastChange", str(ac.lastChange()));
        auto.put("lastChangeWhat", ac.lastChangeWhat());
        m.put("auto", auto);
        List<Map<String, Object>> days = new ArrayList<>();
        for (DatedHitIndex.DayStatus d : idx.status()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", d.kind());
            r.put("date", DatedHitIndex.UNDATED.equals(d.day()) ? "undated" : d.day().toString());
            r.put("ids", d.ids());
            r.put("tier", d.tier());
            r.put("bytes", d.bytes());
            r.put("builtAt", str(d.builtAt()));
            r.put("building", d.building());
            r.put("lastError", d.lastError());
            days.add(r);
        }
        m.put("days", days);
        return m;
    }

    private static String str(java.time.Instant t) {
        return t == null ? null : t.toString();
    }
}
