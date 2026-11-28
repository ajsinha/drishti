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
package org.helixlab.genomics;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.kernel.api.module.DrishtiModule;
import com.ash.drishti.kernel.api.module.ModuleContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The module's entry point: the kernel starts and stops it; everything it needs comes through the kernel API. Started, it gives the
 * screens' data (variants, samples, genes, cohorts) and the showcase their source: the kinds of the packs genomics-showcase and protein-engineering as rows through the kernel's bindings (never a connection).
 */
public class GenomicsModule implements DrishtiModule {

    private final ShowcaseData showcase;
    private final GenomicsHome home;
    private final GenomicsData data;

    /** The kernel registers the entry in the module's Spring context, which gives it the showcase's tables and the Home's (ModuleConfig). */
    public GenomicsModule(GenomicsData data, ShowcaseData showcase, GenomicsHome home) {
        this.data = data;
        this.showcase = showcase;
        this.home = home;
    }

    @Override
    public String id() {
        return "genomics";
    }

    @Override
    public void start(ModuleContext context) {
        context.audit("genomics.started", java.util.Map.of("data", context.dataDir().toString()));
        ShowcaseData.Source rows = (kind, sink) -> context.kinds().documents(kind, AsOf.LATEST, d -> {
            if (!d.deleted() && d.data() != null && d.data().unwrap() instanceof Map<?, ?> m) {
                Map<String, Object> row = new LinkedHashMap<>();
                m.forEach((k, v) -> row.put(String.valueOf(k), v));
                sink.accept(row);
            }
        });
        if (data != null) {
            data.attach(rows);
        }
        if (showcase != null) {
            showcase.attach(rows);
        }
        if (home != null) {
            home.attach(rows);
        }
    }

    @Override
    public void stop() {
        if (data != null) {
            data.clear();
        }
        if (showcase != null) {
            showcase.clear();
        }
        if (home != null) {
            home.clear();
        }
    }
}
