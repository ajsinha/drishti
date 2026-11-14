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
package com.ash.drishti.server.edition;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.packs.edition.Edition;
import com.ash.drishti.packs.edition.Editions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The distribution's filter (docs/architecture/DISTRIBUTIONS.md) as beans: the {@link Edition} itself (read once at start, from the
 * distribution's property layer and the site's overrides) and {@link EditionCommands}, the commands it shows people. Drishti All: both
 * answer "allowed" everywhere.
 */
@Configuration(proxyBeanMethods = false)
public class EditionConfiguration {

    @Bean
    public Edition edition(Environment env) {
        return Editions.of(env);
    }

    @Bean
    public EditionContext editionContext(Edition edition, Environment env, com.ash.drishti.registry.AccessRegistry access) {
        // a lens whose modules are off is not offered (ACCESS_MODEL.md): the registry's lens of that name decides; a site's own lens the
        // registry does not know stays as it is
        java.util.List<String> lenses = new java.util.ArrayList<>(Editions.lensNames(env));
        lenses.removeIf(name -> access.lensDeclared(name) && !access.lensOffered(name));
        return new EditionContext(edition, lenses);
    }

    @Bean
    public EditionCommands editionCommands(EditionContext context, Mnemonics mnemonics) {
        return new EditionCommands(context, mnemonics);
    }

    /**
     * Health's distribution card (docs/architecture/DISTRIBUTIONS.md): the running distribution, the modules it asked for and which of them
     * loaded (each module's domain module, today), and the old product keys the site still sets (read for one release).
     */
    @Bean
    public com.ash.drishti.kernel.api.health.HealthFigures distributionHealth(Environment env,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.modules.ModuleConfiguration.Started> started,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.modules.deploy.ModuleNode> node) {
        String info = com.ash.drishti.packs.edition.EditionEnvironmentPostProcessor.INFO;
        return new com.ash.drishti.kernel.api.health.HealthFigures() {
            @Override
            public String key() {
                return "distribution";
            }

            @Override
            public Object value() {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", env.getProperty(info + ".id", "drishti.all"));
                m.put("title", env.getProperty(info + ".title", env.getProperty("drishti.branding.product", "")));
                m.put("file", env.getProperty(info + ".file", ""));
                boolean all = Boolean.parseBoolean(env.getProperty(info + ".all", "true"));
                com.ash.drishti.server.modules.ModuleConfiguration.Started s = started.getIfAvailable();
                java.util.Map<String, Boolean> loaded = new java.util.LinkedHashMap<>(s == null ? java.util.Map.of() : s.loaded());
                com.ash.drishti.server.modules.deploy.ModuleNode n = node.getIfAvailable();   // and the .dmod modules this node holds
                if (n != null && n.runtime() != null) {
                    n.runtime().health().forEach(h -> loaded.putIfAbsent(h.id(), h.state() == com.ash.drishti.module.runtime.ModuleHealth.State.LOADED));
                }
                java.util.List<String> asked = java.util.Arrays.stream(env.getProperty(info + ".modules", "").split(",")).filter(x -> !x.isBlank()).toList();
                java.util.List<String> domains = java.util.Arrays.stream(env.getProperty(info + ".domains", "").split(",")).filter(x -> !x.isBlank()).toList();
                java.util.Map<String, Object> modules = new java.util.LinkedHashMap<>();
                if (all) {
                    loaded.forEach((k, v) -> modules.put(k, Boolean.TRUE.equals(v) ? "loaded" : "off"));
                } else {
                    for (String pair : domains) {                       // market-data=banking: the module and the domain module carrying it
                        String d = pair.substring(pair.indexOf('=') + 1);
                        modules.put(pair.substring(0, pair.indexOf('=') < 0 ? pair.length() : pair.indexOf('=')),
                                Boolean.TRUE.equals(loaded.get(d)) ? "loaded" : loaded.containsKey(d) ? "off" : "not installed");
                    }
                }
                m.put("all", all);
                m.put("asked", all ? java.util.List.of("all") : asked);
                m.put("modules", modules);
                m.put("deprecated", java.util.Arrays.stream(env.getProperty(info + ".deprecated", "").split("\n")).filter(x -> !x.isBlank()).toList());
                return m;
            }
        };
    }

    @Bean
    public EditionBi editionBi(EditionContext context) {
        return new EditionBi(context);
    }
}
