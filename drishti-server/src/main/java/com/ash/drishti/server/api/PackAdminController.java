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

import com.ash.drishti.identity.PackStateStore;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * Admin → Packs: every pack on disk, whether the server loaded it, and whether it is switched on. A loaded pack is
 * switched off or on for everyone at once (its kinds, mnemonics and views disappear or return at the next request).
 * A pack on disk that is not loaded can be <b>loaded</b>: it is checked together with the loaded packs, recorded in the
 * pack overlay ({@code drishti.packs.added}), and the server restarts in its own process to read it, putting the
 * overlay back if it cannot start. A pack loaded that way can be <b>unloaded</b> the same way. Every change is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/packs")
public class PackAdminController {

    /** {"enabled": true|false}. */
    public record Switch(Boolean enabled) {}

    private final PackRegistry registry;
    private final PackAccess access;
    private final PackStateStore states;
    private final Entitlements entitlements;
    private final Path dir;
    private final Environment env;
    private final com.ash.drishti.server.PackOverlay overlay;
    private final com.ash.drishti.identity.AuditLog audit;

    public PackAdminController(PackRegistry registry, PackAccess access, PackStateStore states, Entitlements entitlements, Environment env,
            com.ash.drishti.identity.AuditLog audit) {
        this.registry = registry;
        this.access = access;
        this.states = states;
        this.entitlements = entitlements;
        this.env = env;
        this.audit = audit;
        this.dir = Path.of(env.getProperty("drishti.packs.dir", "./packs"));
        this.overlay = new com.ash.drishti.server.PackOverlay(Path.of(env.getProperty("drishti.packs.overlay", "./data/packs/added.yaml")));
    }

    /** Loads a pack that is on disk but not loaded: checked first, then the server restarts in place to read it. */
    @org.springframework.web.bind.annotation.PostMapping("/{name}/load")
    public Map<String, Object> load(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (registry.packs().stream().anyMatch(x -> x.name().equals(name))) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.BAD_REQUEST, "'" + name + "' is already loaded");
        }
        List<String> added = new ArrayList<>(overlay.added());
        added.add(name);
        return change(added, "pack-loaded", name, p);
    }

    /** Unloads a pack an administrator loaded (packs named in the site configuration stay). */
    @org.springframework.web.bind.annotation.PostMapping("/{name}/unload")
    public Map<String, Object> unload(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<String> added = new ArrayList<>(overlay.added());
        if (!added.remove(name)) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.BAD_REQUEST,
                    "'" + name + "' was not loaded from Admin → Packs; it is in the site configuration (drishti.packs.enabled)");
        }
        return change(added, "pack-unloaded", name, p);
    }

    /**
     * After a loaded pack's files changed (a registry upgrade or rollback): the same check as at start, then a restart
     * in place; {@code undo} puts the files back if the check fails or the server cannot start with them.
     */
    public Map<String, Object> reload(String action, String name, Principal p, Runnable undo) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(java.util.Arrays.stream(
                env.getProperty("drishti.packs.enabled", "finance").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        names.addAll(overlay.added());
        try {
            com.ash.drishti.packs.PackLoader loader = new com.ash.drishti.packs.PackLoader();
            loader.properties(loader.load(com.ash.drishti.packs.PackLoader.dirs(dir.toString(),
                    env.getProperty("drishti.packs.installed-dir", "./data/packs/installed")), List.copyOf(names)));
        } catch (RuntimeException e) {
            undo.run();
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.BAD_REQUEST, "cannot use '" + name + "': " + e.getMessage());
        }
        audit.record(p.user(), action, name, "");
        boolean restarting = com.ash.drishti.server.Restarter.available();
        com.ash.drishti.server.Restarter.request(action + " " + name + " by " + p.user(), 750, undo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("restarting", restarting);
        out.put("note", restarting ? "The server restarts in place now; live views reconnect by themselves."
                : "Saved; it takes effect when the server next starts.");
        return out;
    }

    private Map<String, Object> change(List<String> added, String action, String name, Principal p) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(java.util.Arrays.stream(
                env.getProperty("drishti.packs.enabled", "finance").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        names.addAll(added);
        try {                                                   // the same check the server does at start, before anything changes
            com.ash.drishti.packs.PackLoader loader = new com.ash.drishti.packs.PackLoader();
            loader.properties(loader.load(com.ash.drishti.packs.PackLoader.dirs(dir.toString(),
                    env.getProperty("drishti.packs.installed-dir", "./data/packs/installed")), List.copyOf(names)));
        } catch (RuntimeException e) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.BAD_REQUEST,
                    "cannot " + (action.equals("pack-loaded") ? "load" : "unload") + " '" + name + "': " + e.getMessage());
        }
        List<String> before = overlay.write(added);
        audit.record(p.user(), action, name, "packs added from Admin → Packs: " + added);
        boolean restarting = com.ash.drishti.server.Restarter.available();
        com.ash.drishti.server.Restarter.request(action + " " + name + " by " + p.user(), 750, () -> overlay.write(before));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("added", added);
        out.put("restarting", restarting);
        out.put("note", restarting ? "The server restarts in place now; live views reconnect by themselves."
                : "Saved; it takes effect when the server next starts.");
        return out;
    }

    @GetMapping
    public List<Map<String, Object>> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> loaded = registry.packs().stream().map(Pack::name).collect(Collectors.toSet());
        for (Pack pack : registry.packs()) {
            Map<String, Object> m = row(pack.name(), pack.title(), pack.description(), pack.version());
            m.put("loaded", true);
            m.put("added", overlay.added().contains(pack.name()));
            m.put("enabled", access.isEnabled(pack.name()));
            m.put("extends", pack.parents());
            m.put("requiredBy", access.requiredBy(pack.name()));
            m.put("kinds", pack.kinds());
            m.put("connectors", List.copyOf(map(pack.manifest().get("connectors")).keySet()));
            m.put("mnemonics", List.copyOf(map(pack.manifest().get("mnemonics")).keySet()));
            states.find(pack.name()).ifPresent(s -> {
                m.put("updatedAt", s.updatedAt());
                m.put("updatedBy", s.updatedBy());
            });
            out.add(m);
        }
        for (Map<String, Object> m : onDisk()) {
            if (!loaded.contains((String) m.get("name"))) {
                m.put("loaded", false);
                m.put("enabled", false);
                out.add(m);
            }
        }
        return out;
    }

    @PutMapping("/{name}")
    public Map<String, Object> change(@PathVariable String name, @RequestBody Switch s, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        access.setEnabled(name, !Boolean.FALSE.equals(s.enabled()), p.user());
        return Map.of("name", name, "enabled", access.isEnabled(name), "enabledPacks", access.enabled());
    }

    /** Packs on disk: installed from a registry first (they win), then the shipped ones. */
    private List<Map<String, Object>> onDisk() {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Path d : com.ash.drishti.packs.PackLoader.dirs(dir.toString(), env.getProperty("drishti.packs.installed-dir", "./data/packs/installed"))) {
            for (Map<String, Object> m : onDisk(d)) {
                if (seen.add((String) m.get("name"))) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> onDisk(Path folder) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (Stream<Path> s = Files.list(folder)) {
            s.filter(d -> Files.isRegularFile(d.resolve("pack.yaml"))).sorted().forEach(d -> {
                try (InputStream in = Files.newInputStream(d.resolve("pack.yaml"))) {
                    Map<String, Object> y = map(new Yaml().load(in));
                    String name = String.valueOf(y.getOrDefault("pack", d.getFileName().toString()));
                    out.add(row(name, String.valueOf(y.getOrDefault("title", name)), String.valueOf(y.getOrDefault("description", "")),
                            String.valueOf(y.getOrDefault("version", ""))));
                } catch (IOException | RuntimeException e) {
                    out.add(row(d.getFileName().toString(), d.getFileName().toString(), "pack.yaml cannot be read: " + e.getMessage(), ""));
                }
            });
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    private static Map<String, Object> row(String name, String title, String description, String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("title", title);
        m.put("description", description);
        m.put("version", version);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
