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
 * showcase its source: the kinds of the packs genomics and protein as rows through the kernel's bindings (never a connection).
 */
public class GenomicsModule implements DrishtiModule {

    private final ShowcaseData showcase;

    /** The kernel registers the entry in the module's Spring context, which gives it the showcase's tables (ModuleConfig). */
    public GenomicsModule(ShowcaseData showcase) {
        this.showcase = showcase;
    }

    @Override
    public String id() {
        return "genomics";
    }

    @Override
    public void start(ModuleContext context) {
        context.audit("genomics.started", java.util.Map.of("data", context.dataDir().toString()));
        if (showcase != null) {
            // a site that binds a showcase kind under another name says so: drishti.module.genomics.showcase.kinds.<kind>=<its name>
            showcase.attach((kind, sink) -> context.kinds().documents(context.settings().get("showcase.kinds." + kind).orElse(kind), AsOf.LATEST, d -> {
                if (!d.deleted() && d.data() != null && d.data().unwrap() instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    sink.accept(row);
                }
            }));
        }
    }

    @Override
    public void stop() {
        if (showcase != null) {
            showcase.clear();
        }
    }
}
