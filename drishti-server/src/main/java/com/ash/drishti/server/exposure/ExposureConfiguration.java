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
package com.ash.drishti.server.exposure;

import com.ash.drishti.engine.ViewAugmenter;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.security.Entitlements;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * The exposure engine's beans (EXPOSURE_GUIDE.md), as VaR's: its settings, the service the API answers from, the computed kind
 * {@code exposure-result} (the reactive connector {@code exposure-engine}), the official end-of-day captures (after the data-load signal
 * of an input, and once at start for the newest date), and the exposure figures the netting-set and counterparty pages show.
 */
@Configuration
@EnableConfigurationProperties(ExposureProperties.class)
public class ExposureConfiguration {

    @Bean(destroyMethod = "close")
    ExposureService exposureService(ExposureProperties props, SourceRouter router, Entitlements entitlements, ContentStore store,
            ObjectProvider<AuditLog> audit) {
        return new ExposureService(props, new ExposureLoader(router, props, new ExposureTier(props).start()), new ExposureAccess(props, entitlements),
                new ExposureOfficialStore(store), audit.getIfAvailable());
    }

    @Bean
    ExposureViews exposureViews(ExposureService svc) {
        ExposureViews v = new ExposureViews(svc);
        ExposureResultEngine.install(v);                               // the computed kind exposure-result (the reactive connector exposure-engine)
        return v;
    }

    @Bean(destroyMethod = "close")
    ExposureOfficial exposureOfficial(ExposureService svc, ExposureProperties props, ObjectProvider<LoadService> loads) {
        String root = props.official().root();
        Path local = root == null || root.contains("://") ? null : Path.of(root);
        ExposureOfficial o = new ExposureOfficial(svc, new ExposurePublisher(local, props.official().retentionYears(), props.official().jsonl()));
        LoadService l = loads.getIfAvailable();
        if (l != null) {
            l.onReady(r -> o.onLoad(r.kind(), r.businessDate()));          // the data-load signal reloads the date, then captures it
        }
        return o;
    }

    @Bean
    ExposureFigures exposureFigures(ExposureService svc, ExposureViews views, ObjectProvider<ViewPipeline> pipeline) {
        ExposureFigures f = new ExposureFigures(svc, views);
        ViewPipeline p = pipeline.getIfAvailable();
        if (p != null) {
            List<ViewAugmenter> all = new ArrayList<>(p.augmenters());
            all.add(f);
            all.add(new ExposureTradeLinks(svc));                      // a trade page's Risk links: its exposure
            p.augmenters(all);
        }
        return f;
    }

    /** At start: the newest date captured officially when it has not been (a lake built while the server was down). */
    @EventListener(ApplicationReadyEvent.class)
    void captureNewest(ApplicationReadyEvent e) {
        ExposureProperties props = e.getApplicationContext().getBean(ExposureProperties.class);
        if (!props.enabled() || !props.official().enabled() || !props.official().onStart()) {
            return;
        }
        ExposureService svc = e.getApplicationContext().getBean(ExposureService.class);
        e.getApplicationContext().getBean(ExposureOfficial.class).later(() -> svc.loader().latest());
    }
}
