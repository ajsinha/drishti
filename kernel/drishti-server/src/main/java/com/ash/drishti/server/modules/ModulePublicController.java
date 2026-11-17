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
package com.ash.drishti.server.modules;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.module.runtime.RouteTable;
import com.ash.drishti.server.net.NetCallFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the server tells the console and its peers about the modules it booted (KERNEL_AND_MODULES.md sections 4.7 and 14.2.1):
 * {@code /api/v1/modules/…} for every signed-in person (what a saved artefact needs, a console path's route), {@code /public/modules/…}
 * for the console (the console parts, the manifests a distribution reads), and {@code /api/v1/net/modules/status} for the cluster's nodes.
 * Nothing here changes a module: a module is a file beside the server and a restart. Every read is over the boot result
 * ({@link ModuleNode}); the cluster compares the nodes' modules through their digests ({@link ModuleNode#identity()}) and
 * Admin → Modules shows that comparison ({@code GET /api/v1/admin/modules}, {@link ModuleAdminController}).
 */
@RestController
public class ModulePublicController {

    /** Items to mark: each an id and its stored text. */
    public record NeedsIn(List<Map<String, String>> items) {}

    private final ModuleNode node;
    private final org.springframework.core.env.Environment env;

    public ModulePublicController(ModuleNode node, org.springframework.core.env.Environment env) {
        this.node = node;
        this.env = env;
    }

    /** What each saved artefact needs that is off ({@code {id: [{module, title, state, words}]}}; an artefact needing nothing is left out). */
    @PostMapping("/api/v1/modules/needs")
    public Map<String, Object> needs(@RequestBody NeedsIn in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null && in.items() != null) {
            for (Map<String, String> item : in.items().subList(0, Math.min(500, in.items().size()))) {
                List<Map<String, Object>> n = node.needs(item.get("text"));
                if (!n.isEmpty()) {
                    out.put(String.valueOf(item.get("id")), n);
                }
            }
        }
        return Map.of("needs", out);
    }

    /** A console path's answer from the route table (the console's notice pages): 200 active, 308 moved, 404 off or unknown. */
    @GetMapping("/api/v1/modules/route")
    public Map<String, Object> route(@RequestParam String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        RouteTable.Match m = node.routes().lookup(path).orElse(null);
        ModuleNode.Entry e = node.byPath(path).orElse(null);
        if (m != null) {
            out.put("status", m.status());
            out.put("module", m.route().module());
            out.put("route", m.route().id());
        } else if (e != null && e.state() != ModuleNode.State.ACTIVE) {
            out.put("status", 404);
            out.put("module", e.id());
        } else {
            out.put("status", e == null ? 404 : 200);
            out.put("module", e == null ? null : e.id());
        }
        if (e != null) {
            out.put("title", e.title());
            out.put("state", e.state().name().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    /**
     * Every module of the boot as a console hosts it: id, version, state (active, off), title, whether it has a console part, and the
     * names it owns (claims: lenses, words, kinds), with a fingerprint that changes with any of them.
     */
    @GetMapping("/public/modules/console")
    public Map<String, Object> consoleParts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModuleNode.Entry e : node.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("version", e.version());
            m.put("state", e.state().name().toLowerCase(java.util.Locale.ROOT));
            m.put("title", e.title());
            m.put("console", node.hasConsole(e.id()));
            m.put("claims", e.claims());
            out.add(m);
        }
        return Map.of("modules", out, "fingerprint", node.fingerprint());
    }

    /**
     * The modules a distribution may list, as this server reads them now (DISTRIBUTIONS.md section 10), each with what a distribution
     * takes from it. The console's distributions read this list; the fingerprint is {@code /public/about}'s {@code manifests}.
     */
    @GetMapping("/public/modules/manifests")
    public Map<String, Object> manifests() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.ash.drishti.packs.distribution.ModuleManifests.Entry e : com.ash.drishti.packs.distribution.Distributions.known(env).values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("domain", e.domain());
            m.put("packs", e.packs());
            m.put("words", e.words());
            m.put("features", e.features());
            m.put("menus", e.menus());
            m.put("docs", e.docs());
            m.put("requires", e.requires());
            out.add(m);
        }
        return Map.of("modules", out, "fingerprint", node.fingerprint());
    }

    /**
     * An active module version's console part as a zip ({@code module.yaml}, {@code console/}, {@code elements/}, {@code docs/}): the
     * console reads it declaratively and runs nothing of it. 404 when the module is off, unknown or not that version.
     */
    @GetMapping("/public/modules/console/{id}/{version}")
    public org.springframework.http.ResponseEntity<byte[]> consolePart(@PathVariable String id, @PathVariable String version) {
        java.nio.file.Path dir = node.consoleFolder(id, version);
        if (dir == null) {
            return org.springframework.http.ResponseEntity.status(404).build();
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String part : List.of("module.yaml", "console", "elements", "docs")) {
                java.nio.file.Path p = dir.resolve(part);
                if (!java.nio.file.Files.exists(p)) {
                    continue;
                }
                try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(p)) {
                    for (java.nio.file.Path f : s.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(dir.relativize(f).toString().replace(java.io.File.separatorChar, '/')));
                        java.nio.file.Files.copy(f, zip);
                        zip.closeEntry();
                    }
                }
            }
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return org.springframework.http.ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Cache-Control", "no-store").body(bytes.toByteArray());
    }

    /** What this node holds, for the cluster's digest: each module with its version, state, Health row and content hash. */
    @GetMapping("/api/v1/net/modules/status")
    public Map<String, Object> status(@RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        if (peer == null) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "this endpoint is for the cluster's nodes only");
        }
        return node.status();
    }
}
