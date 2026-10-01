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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.command.RecentEntities;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entity views. Opening a view also records it in the user's recent list for the type-ahead. */
@RestController
@RequestMapping("/api/v1/views")
public class ViewController {

    private final ViewPipeline pipeline;
    private final RecentEntities recents;
    private final Timer timer;
    private final Entitlements entitlements;

    public ViewController(ViewPipeline pipeline, RecentEntities recents, MeterRegistry meters, Entitlements entitlements) {
        this.pipeline = pipeline;
        this.recents = recents;
        this.entitlements = entitlements;
        this.timer = Timer.builder("drishti.view").description("Time to build an entity view")
                .publishPercentiles(0.5, 0.99).register(meters);
    }

    @GetMapping("/{kind}/{id}")
    public ViewModel view(@PathVariable String kind, @PathVariable String id, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        EntityRef ref = EntityRef.of(kind, id);
        ViewModel v = entitlements.restrict(principal, timer.record(() -> pipeline.view(ref, asOf)));
        String subtitle = v.title().pill() == null ? kind : v.title().pill().replace("Trade · ", "")
                + (v.title().with() == null ? "" : " · " + v.title().with().text());
        recents.touch(principal.user(), new EntityHit(ref, v.title().id(), subtitle));
        return v;
    }

    /**
     * A table's or ladder's rows as raw values of the fields its Sutra's {@code pivot:} offers, for the console's Pivot tab
     * ({@code GET /api/v1/views/trade/X/panels/cashflows/records}): every row up to {@code drishti.pivot.max-records}, even
     * when the table shows only its first {@code limit}. The same document, Sutra and business date as the view.
     */
    @GetMapping("/{kind}/{id}/panels/{panel}/records")
    public com.ash.drishti.engine.bind.PivotBinder.Records records(@PathVariable String kind, @PathVariable String id, @PathVariable String panel,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        return pipeline.records(EntityRef.of(kind, id), asOf, panel);
    }
}
