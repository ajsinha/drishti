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
package com.ash.drishti.server.var;

import com.ash.drishti.compute.var.PositionRules;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.security.Entitlements;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * The VaR engine's beans: its settings, the bounded pool its sums run on, the service the API answers from, the position rules (a
 * loaded pack's {@code positions:} section, else {@code drishti.var.positions}), the official end-of-day captures (run after the
 * data-load signal of an input, and once at start for the newest date), the {@code var-positions} connector's engine, and the VaR
 * figures the position, portfolio and desk pages show.
 */
@Configuration
@EnableConfigurationProperties(VarProperties.class)
public class VarConfiguration {

    @Bean(destroyMethod = "close")
    public VarService varService(VarProperties props, SourceRouter router, Entitlements entitlements, ContentStore store,
            ObjectProvider<AuditLog> audit, ObjectProvider<PackRegistry> packs) {
        ParallelSums sums = new ParallelSums(props.threads(), props.parallelRows());
        VarAccess access = new VarAccess(props, entitlements);
        java.util.function.Supplier<Hierarchies> hierarchies = Hierarchies.of(props, packs::getIfAvailable);   // a bad setting refuses the start
        VarService svc = new VarService(props, new VarLoader(router, props, sums, rules(props, packs), new VarTier(props.tier()).start(),
                hierarchies), access,
                new VarSaved(store, access::seesAll), new VarOfficialStore(store), audit.getIfAvailable(), sums);
        svc.saved().words(() -> svc.words().keySet());
        VarPositions.install(new VarPositions(svc));
        return svc;
    }

    /** The rules of the first loaded pack with a {@code positions:} section (the market-risk pack), else the server's setting. */
    static Supplier<PositionRules> rules(VarProperties props, ObjectProvider<PackRegistry> packs) {
        return () -> {
            PackRegistry r = packs.getIfAvailable();
            if (r != null) {
                for (Pack p : r.packs()) {
                    if (p.manifest().get("positions") instanceof Map<?, ?> m) {
                        return PositionRules.of(m);
                    }
                }
            }
            return props.positions().toRules();
        };
    }

    @Bean
    VarViews varViews(VarService svc) {
        VarViews v = new VarViews(svc);
        VarResultEngine.install(v);                                    // the computed kind var-result (the reactive connector var-engine)
        return v;
    }

    @Bean(destroyMethod = "close")
    VarOfficial varOfficial(VarService svc, VarViews views, VarProperties props, ObjectProvider<LoadService> loads) {
        String root = props.official().root();
        Path local = root == null || root.contains("://") ? null : Path.of(root);
        VarOfficial o = new VarOfficial(svc, views, new VarPublisher(local, props.official().retentionYears(), props.official().jsonl()));
        LoadService l = loads.getIfAvailable();
        if (l != null) {
            l.onReady(r -> o.onLoad(r.kind(), r.businessDate()));          // the data-load signal reloads the date, then captures it
        }
        return o;
    }

    /**
     * The PV topic's windows into the lake (the {@code var-pv-stream} connector, VAR_GUIDE.md section 8.4): each window written to the risk
     * lake (its {@code lake} setting, else drishti.var.official.root, the risk lake's root) with the trades' tables' layout of the loaded
     * pack, then announced as a data-load of the kind (LoadService), so the intraday layer applies it as it applies a file.
     */
    @Bean
    Object varPvStreamSinks(VarService svc, VarOfficial official, VarProperties props, ObjectProvider<LoadService> loads,
            ObjectProvider<PackRegistry> packs, com.ash.drishti.engine.source.SourceRegistry registry) {
        VarPvStreamPlugin.install(settings -> {
            String root = settings.getOrDefault("lake", props.official().root());
            if (root == null || root.isBlank()) {
                throw new com.ash.drishti.api.PluginNotConfigured("var-pv-stream needs settings.lake (the risk lake's root)");
            }
            String pack = settings.getOrDefault("pack", "market-risk");
            String topic = settings.getOrDefault("topic", "drishti.risk.pv-updates");
            return new VarPvStream(svc, Path.of(root), (kind, what) -> layout(packs, kind, what), (kind, date, batch, rows) -> {
                LoadService l = loads.getIfAvailable();
                try {
                    if (l != null) {
                        l.announce(pack, new LoadService.Announcement(kind, date.toString(), null, "ready", rows, 0L, "kafka:" + topic, batch,
                                "a window of the PV topic: " + rows + " intraday updates"), VarPvStreamPlugin.NAME);
                        return;
                    }
                } catch (com.ash.drishti.common.DrishtiException e) {
                    // the pack not loaded under that name (another pack serves the kinds): the engine is told directly
                }
                registry.plugins().stream().filter(x -> x.manifest().serves(kind))
                        .forEach(com.ash.drishti.api.SourcePlugin::refreshVersions);   // the new version seen at once
                official.onLoad(kind, date);
            });
        }, props.intraday().kafka());
        return new Object();                                            // a marker: the install is what this bean is for
    }

    /** The promoted columns of a trades' table in the loaded pack's Delta layout ({@code layout.<kind>.columns}); the engine's own else. */
    @SuppressWarnings("unchecked")
    static java.util.List<String> layout(ObjectProvider<PackRegistry> packs, String kind, String what) {
        PackRegistry r = packs == null ? null : packs.getIfAvailable();
        if (r != null) {
            for (Pack p : r.packs()) {
                if (p.manifest().get("connectors") instanceof Map<?, ?> cs) {
                    for (Object c : cs.values()) {
                        if (c instanceof Map<?, ?> cm && cm.get("settings") instanceof Map<?, ?> st && st.get("layout") instanceof Map<?, ?> lay
                                && lay.get(kind) instanceof Map<?, ?> k && k.get(what) instanceof java.util.List<?> l) {
                            return l.stream().map(String::valueOf).toList();
                        }
                    }
                }
            }
        }
        java.util.List<String> out = new java.util.ArrayList<>(java.util.List.of("TRADE_ID"));
        com.ash.drishti.compute.var.VarBook.TRADE_TEXTS.stream().filter(c -> !c.startsWith("POSITION_") && !c.equals("PV_CURRENCY")).forEach(out::add);
        out.addAll(com.ash.drishti.compute.var.VarBook.TRADE_NUMBERS);
        out.addAll(VarIntake.OPTIONAL);
        if (kind.equals("scenario-pv")) {
            out.remove("PV");
            out.addAll(VarIntake.PV_COLUMNS);
        }
        return out;
    }

    @Bean
    VarFigures varFigures(VarService svc, VarViews views, ObjectProvider<ViewPipeline> pipeline) {
        VarFigures f = new VarFigures(svc, views);
        ViewPipeline p = pipeline.getIfAvailable();
        if (p != null) {
            java.util.List<com.ash.drishti.engine.ViewAugmenter> all = new java.util.ArrayList<>(p.augmenters());
            all.add(f);                                                // beside the others (the exposure engine's on netting sets)
            all.add(new VarTradeLinks(f.service()));                   // a trade page's Risk links: its P&L vector and its VaR
            p.augmenters(all);
        }
        return f;
    }

    /** At start: the newest date captured officially when it has not been (a lake built while the server was down). */
    @EventListener(ApplicationReadyEvent.class)
    void captureNewest(ApplicationReadyEvent e) {
        VarProperties props = e.getApplicationContext().getBean(VarProperties.class);
        if (!props.enabled() || !props.official().enabled() || !props.official().onStart()) {
            return;
        }
        VarService svc = e.getApplicationContext().getBean(VarService.class);
        e.getApplicationContext().getBean(VarOfficial.class).later(() -> svc.loader().latest());
    }
}
