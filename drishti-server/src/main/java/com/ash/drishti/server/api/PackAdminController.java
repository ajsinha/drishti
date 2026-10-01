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
 * switched off or on for everyone at once (its kinds, mnemonics and views disappear or return at the next request);
 * a pack that was not loaded is listed with how to load it at the next start. Every switch is audited.
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

    public PackAdminController(PackRegistry registry, PackAccess access, PackStateStore states, Entitlements entitlements, Environment env) {
        this.registry = registry;
        this.access = access;
        this.states = states;
        this.entitlements = entitlements;
        this.dir = Path.of(env.getProperty("drishti.packs.dir", "./packs"));
    }

    @GetMapping
    public List<Map<String, Object>> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> loaded = registry.packs().stream().map(Pack::name).collect(Collectors.toSet());
        for (Pack pack : registry.packs()) {
            Map<String, Object> m = row(pack.name(), pack.title(), pack.description(), pack.version());
            m.put("loaded", true);
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

    private List<Map<String, Object>> onDisk() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
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
