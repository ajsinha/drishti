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

import com.ash.drishti.kernel.api.modules.ApiAlias;
import com.ash.drishti.packs.Modules;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.registry.EmbeddedAccessRegistry;
import com.ash.drishti.registry.ModuleState;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The access registry in the server (docs/architecture/ACCESS_MODEL.md): the one {@link AccessRegistry} (the environment's, the same the
 * packs' start and the modules' conditions asked), watched for changes; which modules were loaded at this start; the old API paths the
 * modules still answer ({@link ApiAliasFilter}); and the gate that answers a module's API with {@code 404 DRS-1025} when it is off.
 */
@Configuration
public class ModuleConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleConfiguration.class);

    /** The modules as this server started: which were loaded (a switch changed since takes effect at the next start). */
    public record Started(Map<String, Boolean> loaded) {

        public Started {
            loaded = Map.copyOf(loaded);
        }

        /** True when the module's code was loaded at this start. */
        public boolean loaded(String id) {
            return Boolean.TRUE.equals(loaded.get(id));
        }
    }

    @Bean
    public AccessRegistry accessRegistry(Environment env) {
        EmbeddedAccessRegistry r = Modules.of(env);
        r.refresh();                                                  // the context's settings are all there now
        r.watch(Duration.ofSeconds(env.getProperty("drishti.modules.watch-seconds", Integer.class, 5)));
        r.warnings().forEach(w -> LOG.warn("access model: {}", w));
        LOG.info("modules: {} ({}; licence: {})", r.modules().stream().map(s -> s.id() + (s.on() ? " on" : " off: " + s.reason())).toList(),
                r.describe(), r.licence().source());
        return r;
    }

    @Bean
    public Started modulesStarted(AccessRegistry registry) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (ModuleState s : registry.modules()) {
            m.put(s.id(), s.on());
        }
        return new Started(m);
    }

    /** Outermost of the server's own filters: an old path is the new one from here on. */
    @Bean
    public FilterRegistrationBean<ApiAliasFilter> apiAliasFilter(ObjectProvider<ApiAlias> aliases) {
        List<ApiAlias> all = aliases.orderedStream().toList();
        FilterRegistrationBean<ApiAliasFilter> r = new FilterRegistrationBean<>(new ApiAliasFilter(all));
        r.addUrlPatterns("/api/*");
        r.setOrder(-30);
        return r;
    }

    /** Just before the engine gate (4), after the caller is known: a module that is off says so, before its engines are asked. */
    @Bean
    public FilterRegistrationBean<ModuleGateFilter> moduleGateFilter(AccessRegistry registry, Started started) {
        FilterRegistrationBean<ModuleGateFilter> r = new FilterRegistrationBean<>(new ModuleGateFilter(registry, started::loaded));
        r.addUrlPatterns("/api/*");
        r.setOrder(3);
        return r;
    }
}
