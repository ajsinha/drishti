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
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.PackSettings;
import com.ash.drishti.server.deploy.DataSourceService;
import com.ash.drishti.server.deploy.PackDeployService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Packs → Deploy archive and Data source. An archive (the pack only, never data) is uploaded as the raw request
 * body, verified and previewed ({@code POST /deploy}), then confirmed ({@code POST /deploy/{id}}): the files are swapped into
 * the installed folder (the previous version kept) and the server restarts in place to read them, putting the old files back
 * if it cannot start. {@code POST /{pack}/rollback} goes back to a kept version. The data source of a loaded pack is read,
 * tried, changed and reset under {@code /{pack}/datasource}; a change is saved to the pack's override file and applied the same
 * safe way. Everything needs an administrator (and, for a personal API token, the {@code packs:admin} scope); every change is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/packs")
public class PackDeployController {

    /** {"connectors": {"risk-store": {"enabled": true, "settings": {"root": "/mnt/lake"}}}}: the whole desired override. */
    public record DataSource(Map<String, Object> connectors) {}

    /** {"connector": "risk-store", "connectors": {...}}: what to try; no connectors means the settings in force. */
    public record TestRequest(String connector, Map<String, Object> connectors) {}

    private final PackDeployService deploy;
    private final DataSourceService sources;
    private final PackAdminController admin;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public PackDeployController(PackDeployService deploy, DataSourceService sources, PackAdminController admin, Entitlements entitlements, AuditLog audit) {
        this.deploy = deploy;
        this.sources = sources;
        this.admin = admin;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    // -- archive ---------------------------------------------------------------------------------------------------

    /**
     * Verifies and previews an archive sent as the request body (headers: {@code X-Drishti-Filename},
     * {@code X-Drishti-Sha256}, {@code X-Drishti-Signature} + {@code X-Drishti-Publisher}). Answers {@code 200} with {@code ok} and the
     * list of checks, whatever they found; {@code 413} when the archive is larger than allowed. Nothing changes until it is confirmed.
     */
    @PostMapping("/deploy")
    public Map<String, Object> upload(HttpServletRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        entitlements.requireAdmin(p);
        long max = deploy.properties().maxArchiveMb() * 1024L * 1024L;
        if (req.getContentLengthLong() > max) {
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the archive is larger than " + deploy.properties().maxArchiveMb() + " MB (drishti.packs.deploy.max-archive-mb)");
        }
        byte[] data = req.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
        if (data.length > max) {
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the archive is larger than " + deploy.properties().maxArchiveMb() + " MB (drishti.packs.deploy.max-archive-mb)");
        }
        if (data.length == 0) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send the archive (.tar.gz or .zip) as the request body");
        }
        Map<String, Object> out = deploy.stage(data, clean(req.getHeader("X-Drishti-Filename")), req.getHeader("X-Drishti-Sha256"),
                req.getHeader("X-Drishti-Signature"), req.getHeader("X-Drishti-Publisher"), p.user());
        audit.record(p.user(), "pack-upload-checked", String.valueOf(out.getOrDefault("pack", "")),
                (Boolean.TRUE.equals(out.get("ok")) ? "passed" : "refused") + " " + out.get("file") + " sha256 " + out.get("sha256"));
        return out;
    }

    /** Deploys a verified upload: swaps the files in, then loads them (restart in place, undone if it cannot start). */
    @PostMapping("/deploy/{id}")
    public Map<String, Object> confirm(@PathVariable String id, @RequestParam(defaultValue = "false") boolean acceptBreaking,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        PackDeployService.Deployed d = deploy.deploy(id, acceptBreaking, p.user());
        audit.record(p.user(), "pack-deployed", d.name(), d.version() + (d.previous() == null ? " (new)" : " replacing " + d.previous()));
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.putAll(d.wasLoaded() ? admin.reload("pack-deploy-applied", d.name(), p, d.undo()) : admin.load(d.name(), p));
        } catch (DrishtiException e) {
            if (!d.wasLoaded()) {
                d.undo().run();                      // reload undoes by itself; a load that was refused leaves the files to us
            }
            throw e;
        }
        out.put("deployed", d.version());
        out.put("previous", d.previous());
        return out;
    }

    /** Throws a verified upload away. */
    @DeleteMapping("/deploy/{id}")
    public Map<String, Object> discard(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return Map.of("discarded", deploy.discard(id));
    }

    /** Deployments, rollbacks and reverted attempts, newest first, and the versions kept for a rollback. */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(required = false) String pack, @RequestParam(defaultValue = "100") int limit,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> rows = deploy.history().all(pack, Math.max(1, Math.min(limit, 1000)));
        Set<String> names = new LinkedHashSet<>();
        if (pack != null) {
            names.add(pack);
        }
        rows.forEach(r -> names.add(String.valueOf(r.get("pack"))));
        Map<String, Object> kept = new LinkedHashMap<>();
        names.forEach(n -> kept.put(n, deploy.kept(n)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("history", rows);
        out.put("kept", kept);
        return out;
    }

    /** Goes back to a kept version ({@code version} omitted: the newest kept; {@code shipped}: the copy that ships with the server). */
    @PostMapping("/{name}/rollback")
    public Map<String, Object> rollback(@PathVariable String name, @RequestParam(required = false) String version, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        PackDeployService.Deployed d = deploy.rollback(name, version, p.user());
        audit.record(p.user(), "pack-rolled-back", name, d.version() + (d.previous() == null ? "" : " replacing " + d.previous()));
        Map<String, Object> out = new LinkedHashMap<>();
        if (d.wasLoaded()) {
            out.putAll(admin.reload("pack-rollback-applied", name, p, d.undo()));
        } else {
            out.put("name", name);
            out.put("restarting", false);
            out.put("note", "Saved; the pack is not loaded, so nothing restarts.");
        }
        out.put("restored", d.version());
        out.put("replaced", d.previous());
        return out;
    }

    // -- data source -------------------------------------------------------------------------------------------------

    /** The pack's connectors with each setting's pack default, administrator override, site value and the one in force. */
    @GetMapping("/{name}/datasource")
    public Map<String, Object> dataSource(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return sources.get(name);
    }

    /** Saves the whole desired override (only what differs from the pack stays) and applies it with the safe restart. */
    @PutMapping("/{name}/datasource")
    public Map<String, Object> save(@PathVariable String name, @RequestBody DataSource body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> doc = sources.normalise(name, Map.of("connectors", body.connectors() == null ? Map.of() : body.connectors()));
        return apply(name, doc, "pack-datasource-changed", p);
    }

    /** Removes the override (one connector's with {@code connector}, else all of it): the pack's own settings apply again. */
    @DeleteMapping("/{name}/datasource")
    public Map<String, Object> reset(@PathVariable String name, @RequestParam(required = false) String connector, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return apply(name, sources.without(name, connector), "pack-datasource-reset", p);
    }

    /** Tries the connectors (the settings in force, or an edit not yet saved): dates and how many entities per kind. Changes nothing. */
    @PostMapping("/{name}/datasource/test")
    public Map<String, Object> test(@PathVariable String name, @RequestBody(required = false) TestRequest body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> candidate = body == null || body.connectors() == null ? null : Map.of("connectors", body.connectors());
        Map<String, Object> out = sources.test(name, candidate, body == null ? null : body.connector());
        audit.record(p.user(), "pack-datasource-tested", name, (Boolean.TRUE.equals(out.get("ok")) ? "reachable" : "problem") + ", " + out.get("tested"));
        return out;
    }

    private Map<String, Object> apply(String name, Map<String, Object> doc, String action, Principal p) {
        PackSettings st = sources.settings();
        List<String> changed = sources.changed(name, doc);
        String before = st.text(name);
        st.write(name, doc);
        audit.record(p.user(), action, name, changed.isEmpty() ? "no change" : "settings " + changed);
        Map<String, Object> out = new LinkedHashMap<>();
        if (changed.isEmpty()) {
            out.put("name", name);
            out.put("restarting", false);
            out.put("note", "Nothing changed; the server was not restarted.");
        } else {
            out.putAll(admin.reload(action + "-applied", name, p, () -> st.putText(name, before)));
        }
        out.put("overridden", !doc.isEmpty());
        out.put("changed", changed);
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}/\\\\]", "_");
    }
}
