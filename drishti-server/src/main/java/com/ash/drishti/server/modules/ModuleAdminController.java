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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.registry.Lens;
import com.ash.drishti.registry.Licence;
import com.ash.drishti.registry.ModuleManifest;
import com.ash.drishti.registry.ModuleState;
import com.ash.drishti.registry.Subject;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The module switch over REST (docs/architecture/ACCESS_MODEL.md, docs/guides/OPERATIONS_GUIDE.md › Modules): every module with its state (installed,
 * licensed, enabled, on, why not), what it brings, whether it was loaded at this start; the licence; the lenses offered; and the
 * administrator's switch (written to {@code config/modules/<id>.yaml}, audited with a reason, taken at the next start). For administrators
 * only, never a token (the security settings' token-never list). {@code GET /api/v1/modules}, for anyone signed in, is what the console asks
 * for every door it draws (menus, the sitemap, the Build home, search, the command line and type-ahead, keys, Help, assets): the modules
 * loaded, those that are not (with why), the lenses open to the caller (with the modules each needs) and the functions the caller may not
 * use (each with its module and the words to say), decided as the person ({@link AccessRegistry#functionAllowed}: everyone may use every
 * function that is on, until per-person grants in 2.9.11.i).
 */
@RestController
public class ModuleAdminController {

    static final int MAX_REASON = 500;

    /**
     * A switch.
     *
     * @param module  the module's id
     * @param enabled on or off
     * @param reason  why (required)
     */
    public record Change(String module, Boolean enabled, String reason) {}

    private final AccessRegistry registry;
    private final ModuleConfiguration.Started started;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final ObjectProvider<SwitchLedger> ledger;

    public ModuleAdminController(AccessRegistry registry, ModuleConfiguration.Started started, Entitlements entitlements, AuditLog audit,
            ObjectProvider<SwitchLedger> ledger) {
        this.registry = registry;
        this.started = started;
        this.entitlements = entitlements;
        this.audit = audit;
        this.ledger = ledger;
    }

    @GetMapping("/api/v1/admin/modules")
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> modules = new ArrayList<>();
        for (ModuleState s : registry.modules()) {
            Map<String, Object> m = state(s);
            registry.manifest(s.id()).ifPresent(man -> m.put("brings", brings(man)));
            modules.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modules", modules);
        out.put("licence", licence(registry.licence()));
        out.put("lenses", registry.lenses().stream().map(ModuleAdminController::lens).toList());
        out.put("store", registry.describe());
        out.put("warnings", registry.warnings());
        return out;
    }

    @PostMapping("/api/v1/admin/modules")
    public Map<String, Object> change(@RequestBody Change c, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (c == null || c.module() == null || c.module().isBlank() || c.enabled() == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a module switch names the module and enabled: true or false");
        }
        String reason = c.reason() == null ? "" : c.reason().strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a module switch needs a reason (at most " + MAX_REASON + " characters)");
        }
        if (registry.module(c.module()).isEmpty()) {
            throw new DrishtiException(ErrorCode.MODULE_OFF, "no module '" + c.module() + "' here");
        }
        try {
            registry.setEnabled(c.module(), c.enabled(), p.user());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        audit.record(p.user(), "module-switch", "module/" + c.module(), (c.enabled() ? "on" : "off") + " | " + reason);
        ModuleState s = registry.module(c.module()).orElseThrow();
        return state(s);
    }

    @GetMapping("/api/v1/modules")
    public Map<String, Object> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Subject who = new Subject(p.user(), Set.of(), Set.copyOf(p.roles()), entitlements.isAdmin(p));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modules", registry.modules().stream().filter(s -> started.loaded(s.id()))
                .map(s -> Map.of("id", s.id(), "title", s.title(), "domain", s.domain())).toList());
        out.put("off", registry.modules().stream().filter(s -> !started.loaded(s.id())).map(this::offEntry).toList());
        out.put("lenses", registry.lensesFor(who).stream().map(ModuleAdminController::lens).toList());
        out.put("functions", Map.of("off", functionsOff(who)));
        return out;
    }

    private Map<String, Object> offEntry(ModuleState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("title", s.title());
        m.put("domain", s.domain());
        m.put("reason", why(s));
        return m;
    }

    /** Why a module is not loaded here, in words. */
    private String why(ModuleState s) {
        return s.on() ? "it was off when this server started; it loads at the next start" : s.reason();
    }

    /**
     * The functions the caller may not use: every word of every module's manifest (on or off) and every word an administrator switched off,
     * each with its module (none: the core's) and what the console says when it is typed anyway.
     */
    private List<Map<String, Object>> functionsOff(Subject who) {
        Set<String> words = new TreeSet<>();
        for (ModuleState s : registry.modules()) {
            registry.manifest(s.id()).ifPresent(m -> words.addAll(m.words()));
        }
        SwitchLedger l = ledger.getIfAvailable();
        if (l != null) {
            l.entries().stream().filter(e -> SwitchLedger.COMMAND.equals(e.scope()) && !e.on()).forEach(e -> words.add(e.name().toUpperCase(Locale.ROOT)));
        }
        List<Map<String, Object>> off = new ArrayList<>();
        for (String w : words) {
            if (registry.functionAllowed(who, w)) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("word", w);
            f.put("module", registry.moduleOfFunction(w).orElse(ModuleManifest.CORE));
            f.put("reason", registry.functionOffReason(w).orElse("not granted to you"));
            f.put("text", w + " is not available to you: ask your administrator");
            off.add(f);
        }
        return off;
    }

    private Map<String, Object> state(ModuleState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("title", s.title());
        m.put("domain", s.domain());
        m.put("installed", s.installed());
        m.put("licensed", s.licensed());
        m.put("enabled", s.enabled());
        m.put("on", s.on());
        m.put("reason", s.reason());
        m.put("switchSource", s.switchSource());
        m.put("loaded", started.loaded(s.id()));
        m.put("restartNeeded", started.loaded(s.id()) != s.on());
        return m;
    }

    private static Map<String, Object> brings(ModuleManifest m) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("engines", m.engines());
        b.put("packs", m.packs());
        b.put("connectors", m.connectors());
        b.put("api", m.api());
        b.put("words", m.words());
        b.put("requires", m.requires());
        b.put("source", m.source());
        return b;
    }

    private static Map<String, Object> licence(Licence l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customer", l.customer());
        m.put("modules", l.modules());
        m.put("validFrom", l.validFrom() == null ? null : l.validFrom().toString());
        m.put("validTo", l.validTo() == null ? null : l.validTo().toString());
        m.put("signed", l.signed());
        m.put("development", l.development());
        m.put("source", l.source());
        return m;
    }

    private static Map<String, Object> lens(Lens l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id());
        m.put("title", l.title());
        m.put("modules", l.modules());
        m.put("functions", l.functions());
        m.put("home", l.home());
        m.put("cards", l.cards());
        m.put("chips", l.chips());                                     // Home's chips (the same rule)                                     // Home's quick cards (the console shows those whose function is on)
        return m;
    }
}
