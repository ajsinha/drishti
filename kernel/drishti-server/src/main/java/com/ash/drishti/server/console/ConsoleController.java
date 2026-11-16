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
package com.ash.drishti.server.console;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.server.api.CatalogController;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a console asks instead of reading the server's files (docs/architecture/THIN_CONSOLE_DESIGN.md section 3; API_REFERENCE.md 2.16):
 * the installation's answer (the console's service identity only), the person's answer, and a pack's console contributions with its
 * files. Each answer carries an ETag (its digest) and answers {@code 304} to a matching {@code If-None-Match}.
 */
@RestController
public class ConsoleController {

    private final ConsoleAnswers answers;
    private final Entitlements entitlements;
    private final PackAccess access;
    private final CatalogController catalog;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.modules.ModuleAdminController> modules;

    public ConsoleController(ConsoleAnswers answers, Entitlements entitlements, PackAccess access, CatalogController catalog,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.modules.ModuleAdminController> modules) {
        this.engines = engines;
        this.modules = modules;
        this.answers = answers;
        this.entitlements = entitlements;
        this.access = access;
        this.catalog = catalog;
    }

    /**
     * The installation's answer (section 3.2): the distribution, the modules, the engines and every command word with what it needs. For the
     * console's service identity only (it lists what the installation has); nothing of any person is in it.
     */
    @GetMapping("/api/v1/console/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrap(@RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @org.springframework.web.bind.annotation.RequestParam(name = "distribution", required = false) String distribution) {
        entitlements.requireService(p);
        ConsoleAnswers.Answer a;
        try {
            a = answers.bootstrap(distribution);                     // a console's own distribution (Q3): compiled here, never by the console
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
        }
        return answer(a, ifNoneMatch, CacheControl.noCache().cachePrivate());
    }

    /**
     * Every distribution file of the installation compiled as the console draws it, with the files that do not compile (Admin -> White
     * label, step S2). Administrators only.
     */
    @GetMapping("/api/v1/admin/distributions")
    public Map<String, Object> distributions(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return answers.distributions();
    }

    /**
     * The person's answer (section 3.3): who they are, and the packs assigned to them, each with what the console draws from it (examples,
     * monitors, alerts, mnemonics, form counts, kinds, what Calc offers) and the digest of its contributions ({@code console.digest}: when to
     * ask {@code /packs/{pack}/console} again). Derived from the caller alone; {@code private, no-store}.
     */
    @GetMapping("/api/v1/me/console")
    public ResponseEntity<Map<String, Object>> me(@RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("user", p.user());
        person.put("roles", p.roles());
        person.put("admin", entitlements.isAdmin(p));
        doc.put("person", person);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : catalog.packs(p)) {
            Pack pack = answers.pack(String.valueOf(row.get("name"))).orElse(null);
            if (pack == null) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>(row);
            r.remove("origin");                                       // where a pack came from is the administrators' (Admin -> Packs)
            Object console = r.remove("console");
            Map<?, ?> c = console instanceof Map<?, ?> m ? m : Map.of();
            Map<String, Object> manifest = pack.manifest();
            r.put("examples", c.get("examples") instanceof List<?> l ? l : List.of());
            r.put("monitors", c.get("monitors") instanceof Map<?, ?> mon ? mon : Map.of());
            r.put("alerts", manifest.get("alerts") instanceof List<?> al ? al : List.of());
            r.put("mnemonics", mnemonics(manifest));
            r.put("forms", forms(manifest));
            r.put("console", Map.of("digest", answers.packConsole(pack).answer().digest()));
            rows.add(r);
        }
        doc.put("packs", rows);
        java.util.Set<String> assigned = new java.util.TreeSet<>();
        catalog.packs(p).stream().filter(r -> !Boolean.FALSE.equals(r.get("assigned"))).forEach(r -> assigned.add(String.valueOf(r.get("name"))));
        doc.put("engines-opened", enginesOpened(p, assigned));
        doc.put("grants", grants(p));
        com.ash.drishti.server.modules.ModuleAdminController m = modules.getIfAvailable();
        doc.put("modules", m == null ? null : m.mine(p));        // the modules, lenses and functions the person may see (GET /api/v1/modules)
        ResponseEntity<Map<String, Object>> out = answer(answers.person(doc), ifNoneMatch, CacheControl.noStore().cachePrivate());
        return ResponseEntity.status(out.getStatusCode()).headers(out.getHeaders()).header("Vary", "Authorization, X-Drishti-Lens").body(out.getBody());
    }

    /**
     * A pack's console contributions (section 3.4): workspace starters, Help guides and contextual help, the facts file, the README's
     * {@code sources}, and the files the console may fetch. 404 when the pack is not assigned to the caller.
     */
    @GetMapping("/api/v1/packs/{name}/console")
    public ResponseEntity<Map<String, Object>> packConsole(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        Pack pack = assigned(p, name);
        if (pack == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return answer(answers.packConsole(pack).answer(), ifNoneMatch, CacheControl.noCache().cachePrivate());
    }

    /** One file a pack's console answer lists (a Help guide or a picture beside it); any other path is 404. */
    @GetMapping("/api/v1/packs/{name}/console/files/**")
    public ResponseEntity<Resource> packFile(@PathVariable String name, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        Pack pack = assigned(p, name);
        if (pack == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String marker = "/console/files/";
        String uri = request.getRequestURI();
        int at = uri.indexOf(marker);
        String rel = at < 0 ? "" : URLDecoder.decode(uri.substring(at + marker.length()), StandardCharsets.UTF_8);
        ConsoleAnswers.PackFile f = answers.packConsole(pack).files().get(rel);       // the allow-list: no path arithmetic
        if (f == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String etag = "\"" + f.sha() + "\"";
        CacheControl cache = CacheControl.noCache().cachePrivate();
        if (ConsoleAnswers.matches(ifNoneMatch, f.sha())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).contentType(type(rel)).header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox")
                .body(new FileSystemResource(f.file()));
    }

    /**
     * The engines this person opens (THIN_CONSOLE_DESIGN.md 3.3, step S4): each catalogue engine's {@code opens:} evaluated here, the one
     * place it is: an administrator opens every engine; an engine without {@code opens:} opens for everyone signed in; else any rule holds:
     * {@code role:<name>} (they hold the role) or {@code pack:<name>} (the pack is assigned to them). The console draws an engine's pages,
     * words and type-ahead suggestions by this list.
     */
    List<String> enginesOpened(Principal p, java.util.Set<String> assigned) {
        com.ash.drishti.server.engines.EngineRegistry r = engines.getIfAvailable();
        List<String> out = new ArrayList<>();
        if (r == null) {
            return out;
        }
        boolean admin = entitlements.isAdmin(p);
        java.util.Set<String> roles = new java.util.HashSet<>(p.roles());
        for (com.ash.drishti.server.engines.EngineCatalogue.Entry e : r.entries()) {
            List<String> rules = e.list("opens");
            boolean opens = admin || rules.isEmpty() || rules.stream().anyMatch(rule -> {
                int at = rule.indexOf(':');
                String kind = at < 0 ? rule : rule.substring(0, at);
                String value = at < 0 ? "" : rule.substring(at + 1);
                return "role".equals(kind) && roles.contains(value) || "pack".equals(kind) && assigned.contains(value);
            });
            if (opens) {
                out.add(e.id());
            }
        }
        return out;
    }

    /** What the pages ask as "may this person ...": the powers the server enforces, as decisions (step S4). */
    List<String> grants(Principal p) {
        List<String> out = new ArrayList<>();
        if (entitlements.isAdmin(p)) {
            out.add("admin");
        }
        if (entitlements.mayEdit(p)) {
            out.add("edit");
        }
        if (entitlements.mayLayout(p)) {
            out.add("layout");
        }
        if (entitlements.mayCalc(p)) {
            out.add("calc");
        }
        if (entitlements.mayCompliance(p)) {
            out.add("compliance");
        }
        if (entitlements.mayApprove(p)) {
            out.add("approve");
        }
        return out;
    }

    /** The loaded pack of that name assigned to the caller (enabled, not hidden, a sample only where samples show), or null. */
    private Pack assigned(Principal p, String name) {
        boolean listed = catalog.packs(p).stream().anyMatch(r -> name.equals(r.get("name")) && !Boolean.FALSE.equals(r.get("assigned")));
        return listed && access.isEnabled(name) ? answers.pack(name).orElse(null) : null;
    }

    private static ResponseEntity<Map<String, Object>> answer(ConsoleAnswers.Answer a, String ifNoneMatch, CacheControl cache) {
        String etag = "\"" + a.digest() + "\"";
        if (ConsoleAnswers.matches(ifNoneMatch, a.digest())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(a.body());
    }

    /** A pack's mnemonics as {@code {CODE: kind}} (the ones that name a kind). */
    private static Map<String, String> mnemonics(Map<String, Object> manifest) {
        Map<String, String> out = new LinkedHashMap<>();
        if (manifest.get("mnemonics") instanceof Map<?, ?> m) {
            m.forEach((code, d) -> {
                if (d instanceof Map<?, ?> dm && dm.get("kind") != null) {
                    out.put(String.valueOf(code).toUpperCase(java.util.Locale.ROOT), String.valueOf(dm.get("kind")));
                }
            });
        }
        return out;
    }

    /** The fields a pack's {@code form:} declares per kind, counted (query by form). */
    private static Map<String, Integer> forms(Map<String, Object> manifest) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (manifest.get("form") instanceof Map<?, ?> f) {
            f.forEach((k, v) -> {
                if (v instanceof List<?> l) {
                    out.put(String.valueOf(k), l.size());
                }
            });
        }
        return out;
    }

    private static MediaType type(String rel) {
        String r = rel.toLowerCase(java.util.Locale.ROOT);
        if (r.endsWith(".md")) {
            return MediaType.parseMediaType("text/markdown;charset=UTF-8");
        }
        if (r.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (r.endsWith(".jpg") || r.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (r.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (r.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (r.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
