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
 * The distribution's filter (docs/architecture/KERNEL_AND_MODULES.md) as beans: the {@link Edition} itself (read once at start, from the
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
        // the lenses offered (LENSES.md): drishti.lenses, "default" being every lens of the modules that are on; a lens whose modules are
        // off, or a name nothing knows, is left out: never a failed start, but an ERROR in the log and a red line on Admin → Health
        return new EditionContext(edition, new LensMemo(env, access));
    }

    /** The lenses resolved once per state of the access registry (a module loaded, switched on or off); a new problem logged as an ERROR. */
    static final class LensMemo implements java.util.function.Supplier<LensOffer> {
        private final Environment env;
        private final com.ash.drishti.registry.AccessRegistry access;
        private Object seen;
        private LensOffer offer;

        LensMemo(Environment env, com.ash.drishti.registry.AccessRegistry access) {
            this.env = env;
            this.access = access;
        }

        @Override
        public synchronized LensOffer get() {
            Object now = access.lenses();                              // a new list for each new state of the registry
            if (offer == null || now != seen) {
                LensOffer next = lensOffer(env, access);
                if (offer == null || !next.problems().equals(offer.problems())) {
                    next.problems().forEach(p -> System.getLogger(EditionConfiguration.class.getName()).log(System.Logger.Level.ERROR, p));
                }
                offer = next;
                seen = now;
            }
            return offer;
        }
    }

    /** At the end of the start (every module loaded): the lenses resolved once, so a lens that cannot be offered is in the start-up log. */
    @Bean
    public org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> lensesAtStart(EditionContext context) {
        return e -> System.getLogger(EditionConfiguration.class.getName()).log(System.Logger.Level.INFO, "lenses offered: {0}", context.lensNames());
    }

    /** The lens files: {lens: the module folder it is in ("" at the top of the folder)}, in name order. */
    static java.util.Map<String, String> lensFiles(java.nio.file.Path dir) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return out;
        }
        // a linked folder is followed (a site whose config/lenses, or one module's folder in it, is a link to the shipped files)
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir, 2, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
            s.filter(f -> f.getFileName().toString().endsWith(".yaml") && java.nio.file.Files.isRegularFile(f)).forEach(f -> {
                String lens = f.getFileName().toString().replaceAll("\\.yaml$", "");
                out.putIfAbsent(lens, f.getParent().equals(dir) ? "" : f.getParent().getFileName().toString());
            });
        } catch (java.io.IOException e) {
            return out;
        }
        return out;
    }

    /** {@code drishti.lenses} against the access registry and the lens files ({@code drishti.lenses-dir}, else {@code config/lenses}). */
    static LensOffer lensOffer(Environment env, com.ash.drishti.registry.AccessRegistry access) {
        java.nio.file.Path dir = com.ash.drishti.api.Roots.anchored(env.getProperty("drishti.lenses-dir", com.ash.drishti.api.Roots.config("lenses")));
        java.util.List<String> configured = Editions.lensNames(env);
        return LensOffer.of(configured, new LensOffer.Catalogue() {
            @Override
            public java.util.List<String> offered() {
                return access.lenses().stream().map(com.ash.drishti.registry.Lens::id).toList();
            }

            @Override
            public boolean declared(String name) {
                return access.lensDeclared(name);
            }

            @Override
            public java.util.List<String> files() {
                return lensFiles(dir).keySet().stream().toList();
            }

            @Override
            public boolean fileModuleOn(String name) {
                String module = lensFiles(dir).get(name);
                return module == null || module.isEmpty() || access.moduleOn(module);
            }

            @Override
            public boolean offDefault(String name) {
                java.util.Map<String, Object> f = com.ash.drishti.server.console.ConsoleDistribution.lens(dir, name);
                return f != null && "false".equalsIgnoreCase(String.valueOf(f.get("offered-by-default")));
            }
        });
    }

    @Bean
    public EditionCommands editionCommands(EditionContext context, Mnemonics mnemonics) {
        return new EditionCommands(context, mnemonics);
    }

    /**
     * Health's distribution card (docs/architecture/KERNEL_AND_MODULES.md): the running distribution, the modules it asked for and which of them
     * loaded (each module's domain module, today).
     */
    @Bean
    public com.ash.drishti.kernel.api.health.HealthFigures distributionHealth(Environment env,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.modules.ModuleConfiguration.Started> started,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.modules.ModuleNode> node,
            org.springframework.beans.factory.ObjectProvider<EditionContext> editionContext) {
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
                com.ash.drishti.server.modules.ModuleNode n = node.getIfAvailable();   // and the .dmod modules this node holds
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
                EditionContext ec = editionContext.getIfAvailable();   // a lens named that is not offered: red on Health (LensOffer)
                m.put("lensProblems", ec == null ? java.util.List.of() : ec.lensProblems());
                return m;
            }
        };
    }

    @Bean
    public EditionBi editionBi(EditionContext context) {
        return new EditionBi(context);
    }
}
