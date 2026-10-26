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
        VarService svc = new VarService(props, new VarLoader(router, props, sums, rules(props, packs), new VarTier(props.tier()).start()), access,
                new VarSaved(store, access::seesAll), new VarOfficialStore(store), audit.getIfAvailable(), sums);
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
        VarOfficial o = new VarOfficial(svc, views, new VarPublisher(local, props.official().retentionYears()));
        LoadService l = loads.getIfAvailable();
        if (l != null) {
            l.onReady(r -> o.onLoad(r.kind(), r.businessDate()));          // the data-load signal reloads the date, then captures it
        }
        return o;
    }

    @Bean
    VarFigures varFigures(VarService svc, VarViews views, ObjectProvider<ViewPipeline> pipeline) {
        VarFigures f = new VarFigures(svc, views);
        ViewPipeline p = pipeline.getIfAvailable();
        if (p != null) {
            java.util.List<com.ash.drishti.engine.ViewAugmenter> all = new java.util.ArrayList<>(p.augmenters());
            all.add(f);                                                // beside the others (the exposure engine's on netting sets)
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
