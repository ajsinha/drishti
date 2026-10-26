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
package com.ash.drishti.server.switches;

import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Functions (FUNC, docs/admin/COMMANDS.md): every command the caller can use, grouped by pack, with what it does and an example. For
 * everyone: a command is listed only when the caller may open its kind now ({@link Entitlements#mayOpen}: the role allows it, its pack is
 * switched on and active for the caller, and no administrator turned the command off). Nothing about the switches themselves (who turned
 * what off, when, why) is here: that is the administrators' {@code /api/v1/admin/commands}.
 */
@RestController
@RequestMapping("/api/v1/functions")
public class FunctionsController {

    private final CommandCatalog catalog;
    private final Entitlements entitlements;

    public FunctionsController(CommandCatalog catalog, Entitlements entitlements) {
        this.catalog = catalog;
        this.entitlements = entitlements;
    }

    /** {@code {total, packs[{name, code, title, commands[{code, kind, label, description, example}]}]}}: packs with no usable command are left out. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<Map<String, Object>> packs = new ArrayList<>();
        int total = 0;
        for (CommandCatalog.PackCommands pc : catalog.packs()) {
            List<Map<String, Object>> cmds = new ArrayList<>();
            for (CommandCatalog.Command c : pc.commands()) {
                if (!entitlements.mayOpen(p, c.kind())) {
                    continue;
                }
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("code", c.code());
                cm.put("kind", c.kind());
                cm.put("label", c.label());
                cm.put("description", c.description());
                cm.put("example", c.example());
                cmds.add(cm);
            }
            if (cmds.isEmpty()) {
                continue;
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", pc.pack().name());
            pm.put("code", pc.pack().code());
            pm.put("title", pc.pack().title());
            pm.put("commands", cmds);
            packs.add(pm);
            total += cmds.size();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("packs", packs);
        return out;
    }
}
