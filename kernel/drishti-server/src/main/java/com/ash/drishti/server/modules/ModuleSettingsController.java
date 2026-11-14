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
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.packs.module.ModuleSettingsEnvironmentPostProcessor;
import com.ash.drishti.packs.module.ModuleSettingsSchema;
import com.ash.drishti.server.security.Entitlements;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Modules → a module → Settings: each setting its manifest describes ({@code settings:}, read as {@code drishti.module.<id>.*}), its value now
 * and where it comes from (the module's key, the old server key read for one release, or the default), and the old keys a site still sets (also
 * shown in Health).
 */
@RestController
public class ModuleSettingsController {

    private final Environment env;
    private final Entitlements entitlements;

    public ModuleSettingsController(Environment env, Entitlements entitlements) {
        this.env = env;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/admin/modules/{id}/settings")
    public Map<String, Object> settings(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        ModuleSettingsSchema s = ModuleSettingsEnvironmentPostProcessor.schemas(env).get(id);
        if (s == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "module " + id + " declares no settings");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("module", id);
        out.put("prefix", ModuleSettingsSchema.prefix(id));
        out.put("settings", s.resolve(env::getProperty).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key());
            m.put("type", r.item().type());
            m.put("group", r.item().group());
            m.put("meaning", r.item().meaning());
            m.put("was", r.item().was());
            m.put("source", r.source());
            m.put("value", r.value());
            return m;
        }).toList());
        out.put("deprecated", deprecated(env));
        return out;
    }

    /** The old keys a site still sets, one note each (read for one release). */
    static List<String> deprecated(Environment env) {
        return Arrays.stream(env.getProperty(ModuleSettingsEnvironmentPostProcessor.INFO + ".deprecated", "").split("\n")).filter(x -> !x.isBlank()).toList();
    }

    /** The figure Health shows: the old module settings a site still sets. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    public static class Health {
        @Bean
        public HealthFigures moduleSettingsHealth(Environment env) {
            return new HealthFigures() {
                @Override
                public String key() {
                    return "moduleSettings";
                }

                @Override
                public Object value() {
                    return Map.of("modules", Arrays.stream(env.getProperty(ModuleSettingsEnvironmentPostProcessor.INFO + ".modules", "").split(","))
                            .filter(x -> !x.isBlank()).toList(), "deprecated", deprecated(env));
                }
            };
        }
    }
}
