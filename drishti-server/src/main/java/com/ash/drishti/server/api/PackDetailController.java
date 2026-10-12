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
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.server.design.FormDefinitions;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Packs → one pack: what a loaded pack brings, read from the running server for the pack's page: each kind with its mnemonic,
 * whether it has a declared form and which fields, its Sutras (those whose files live in the pack), the connectors it names and which
 * kinds each serves, and what its about files cover. Administrators only; it needs no data, so it also works while the pack is off.
 */
@RestController
@RequestMapping("/api/v1/admin/packs")
public class PackDetailController {

    private final PackRegistry registry;
    private final Entitlements entitlements;
    private final Mnemonics mnemonics;
    private final SutraRegistry sutras;
    private final FormDefinitions forms;
    private final AboutCatalog about;

    public PackDetailController(PackRegistry registry, Entitlements entitlements, Mnemonics mnemonics, SutraRegistry sutras,
            FormDefinitions forms, AboutCatalog about) {
        this.registry = registry;
        this.entitlements = entitlements;
        this.mnemonics = mnemonics;
        this.sutras = sutras;
        this.forms = forms;
        this.about = about;
    }

    @GetMapping("/{name}/detail")
    public Map<String, Object> detail(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Pack pack = registry.packs().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow(
                () -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "'" + name + "' is not a loaded pack"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", pack.name());
        out.put("code", pack.code());
        out.put("routes", pack.routes());
        List<Map<String, Object>> kinds = new ArrayList<>();
        int glossary = 0;
        for (String kind : pack.kinds()) {
            Map<String, Object> k = new LinkedHashMap<>();
            String code = mnemonics.codeFor(kind);
            k.put("kind", kind);
            k.put("mnemonic", code);
            k.put("label", code == null ? kind : mnemonics.of(code).map(m -> m.label()).orElse(kind));
            var spec = forms.declared(pack, kind);
            k.put("declaredForm", spec.isPresent());
            k.put("formFields", spec.map(s -> s.fields().stream().map(f -> f.path()).toList()).orElse(List.of()));
            var text = about.forKind(kind);
            k.put("about", text.isPresent() && text.get().pack().equals(pack.name()));
            k.put("glossaryTerms", text.map(t -> t.glossary().size()).orElse(0));
            glossary += text.map(t -> t.glossary().size()).orElse(0);
            kinds.add(k);
        }
        out.put("kinds", kinds);
        out.put("glossaryTerms", glossary);
        Path dir = pack.dir().toAbsolutePath().normalize();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var s : sutras.all()) {
            boolean here = sutras.fileOf(s.id()).map(f -> f.toAbsolutePath().normalize().startsWith(dir)).orElse(false);
            if (here) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", s.name());
                r.put("version", s.version());
                r.put("versions", sutras.versions(s.name()));
                r.put("kind", s.match().kind());
                rows.add(r);
            }
        }
        out.put("sutras", rows);
        out.put("sutraProblems", PackSutraProblems.of(sutras, dir));
        return out;
    }
}
