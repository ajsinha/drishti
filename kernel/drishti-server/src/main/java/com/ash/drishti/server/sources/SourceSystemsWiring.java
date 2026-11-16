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
package com.ash.drishti.server.sources;

import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourceSystems;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.registry.ModuleState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The trading source systems of this installation (docs/guides/DATA_LAKE_GUIDE.md, "Several source systems: shared or separate tables"): each
 * running connector whose {@code set.SOURCE_SYSTEM} (or {@code where: SOURCE_SYSTEM = ...}) names a system, its source module (the module that
 * owns the connector, else the module {@code source-<system>}), the kinds it is bound to, and whether that module is on. The module switch
 * (Admin → Modules) takes effect at once for a source module: switched off, its connectors serve nothing, its rows leave every read and its
 * live feed stops; switched on, they come back; no restart. The allowed values of {@code SOURCE_SYSTEM} are these systems.
 */
@Component
public class SourceSystemsWiring {

    private static final Logger LOG = LoggerFactory.getLogger(SourceSystemsWiring.class);

    private final ObjectProvider<SourceRouter> router;
    private final ObjectProvider<AccessRegistry> access;

    public SourceSystemsWiring(ObjectProvider<SourceRouter> router, ObjectProvider<AccessRegistry> access) {
        this.router = router;
        this.access = access;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        AccessRegistry a = access.getIfAvailable();
        if (a != null) {
            a.onChange(this::refresh);                         // the module switch: at once, no restart
        }
        refresh();
    }

    /** Works out the systems again (a module switched, a connector changed). Never fails: a problem is logged. */
    public void refresh() {
        SourceRouter r = router.getIfAvailable();
        if (r == null) {
            return;
        }
        try {
            r.registry().systems().installed(systems(r, access.getIfAvailable()));
        } catch (RuntimeException e) {
            LOG.warn("source systems: not worked out: {}", e.toString());
        }
    }

    static List<SourceSystems.System> systems(SourceRouter r, AccessRegistry a) {
        Map<String, List<String>> bySystem = r.registry().connectorsBySystem();
        Map<String, List<String>> bound = r.bound();
        List<SourceSystems.System> out = new ArrayList<>();
        bySystem.forEach((system, connectors) -> {
            Set<String> kinds = new TreeSet<>();
            bound.forEach((kind, names) -> {
                if (names.stream().anyMatch(connectors::contains)) {
                    kinds.add(kind);
                }
            });
            connectors.forEach(c -> r.registry().plugin(c).ifPresent(p -> kinds.addAll(p.manifest().kinds())));
            String module = module(a, system, connectors).orElse("");
            boolean on = module.isEmpty() || a == null || a.module(module).map(ModuleState::on).orElse(true);
            out.add(new SourceSystems.System(system, module, kinds, on));
        });
        return out;
    }

    /** The module a system's connectors belong to: the one that owns a connector, else {@code source-<system>} when installed. */
    static Optional<String> module(AccessRegistry a, String system, List<String> connectors) {
        if (a == null) {
            return Optional.empty();
        }
        for (String c : connectors) {
            Optional<String> m = a.moduleOfConnector(c);
            if (m.isPresent()) {
                return m;
            }
        }
        String named = "source-" + system.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return a.module(named).isPresent() ? Optional.of(named) : Optional.empty();
    }

    /** What Admin → Modules shows of a module's source systems: {@code [{system, on, connectors, says}]}, empty for another module. */
    public List<Map<String, Object>> of(String module) {
        SourceRouter r = router.getIfAvailable();
        if (r == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<String>> bySystem = r.registry().connectorsBySystem();
        r.registry().systems().installed().values().stream().filter(s -> s.module().equals(module)).forEach(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("system", s.id());
            m.put("on", s.on());
            m.put("kinds", s.kinds());
            m.put("connectors", bySystem.getOrDefault(s.id(), List.of()));
            m.put("says", "source " + s.id() + (s.on() ? " on" : " off"));
            out.add(m);
        });
        return out;
    }
}
