/*
 * Copyright (c) the publisher of ${artifactId}. All rights reserved.
 */
package ${package};

import com.ash.drishti.kernel.api.module.DrishtiModule;
import com.ash.drishti.kernel.api.module.ModuleContext;

/** The module's entry point: the kernel starts and stops it; everything it needs comes through the kernel API. */
public class HelloModule implements DrishtiModule {

    @Override
    public String id() {
        return "${artifactId}";
    }

    @Override
    public void start(ModuleContext context) {
        // nothing to start: the greeting needs no data
    }
}
