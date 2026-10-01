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
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.registry.PackRegistryClient;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin → Packs → Registry: the packs a signed registry offers, installed and upgraded after their signatures check. */
@RestController
@RequestMapping("/api/v1/admin/registry")
public class RegistryController {

    private final PackRegistryClient registry;
    private final PackRegistry packs;
    private final PackAdminController admin;
    private final Entitlements entitlements;
    private final com.ash.drishti.identity.AuditLog audit;

    public RegistryController(PackRegistryClient registry, PackRegistry packs, PackAdminController admin, Entitlements entitlements,
            com.ash.drishti.identity.AuditLog audit) {
        this.registry = registry;
        this.packs = packs;
        this.admin = admin;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** The registry's packs, newest version first per pack, each with what is installed and loaded here. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", registry.url());
        out.put("configured", registry.configured());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (registry.configured()) {
            Map<String, String> loaded = packs.packs().stream().collect(Collectors.toMap(Pack::name, Pack::version, (a, b) -> a));
            for (PackRegistryClient.Entry e : registry.index()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.name());
                m.put("version", e.version());
                m.put("title", e.title());
                m.put("description", e.description());
                m.put("requires", e.requires());
                m.put("publisher", e.publisher());
                m.put("trusted", e.trusted());
                m.put("size", e.size());
                m.put("installedVersion", registry.installed(e.name()).map(n -> n.path("version").asText()).orElse(null));
                m.put("loadedVersion", loaded.get(e.name()));
                rows.add(m);
            }
            rows.sort(java.util.Comparator.comparing((Map<String, Object> m) -> (String) m.get("name"))
                    .thenComparing((a, b) -> compareVersions((String) b.get("version"), (String) a.get("version"))));
        }
        out.put("packs", rows);
        return out;
    }

    /** Installs (or upgrades to) a version after every check passes; then loads it, or restarts to use the new files. */
    @PostMapping("/{name}/{version}/install")
    public Map<String, Object> install(@PathVariable String name, @PathVariable String version, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        PackRegistryClient.Installed done = registry.install(name, version);
        audit.record(p.user(), "pack-installed", name, version + " from " + registry.url() + " signed by " + done.publisher() + ", sha256 " + done.sha256());
        Set<String> loaded = packs.packs().stream().map(Pack::name).collect(Collectors.toSet());
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.putAll(loaded.contains(name) ? admin.reload("pack-upgraded", name, p, () -> registry.undo(done)) : admin.load(name, p));
        } catch (DrishtiException e) {
            registry.undo(done);
            throw e;
        }
        out.put("installed", version);
        out.put("replaced", done.replaced());
        return out;
    }

    /** Goes back to the version installed before the current one. */
    @PostMapping("/{name}/rollback")
    public Map<String, Object> rollback(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String now = registry.rollback(name);
        Map<String, Object> out = new LinkedHashMap<>(admin.reload("pack-rolled-back", name, p, () -> { }));
        out.put("installed", now);
        return out;
    }

    /** 1.10.0 after 1.9.2; numeric parts compare as numbers. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+") ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
