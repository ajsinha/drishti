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
package com.ash.drishti.server.modules.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.module.runtime.LoadedModule;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Guard G12 (the child-context design, KERNEL_AND_MODULES.md section 12.3): a module's objects of a kernel extension type reach the
 * kernel's collection points only through the bridge, once each. The bridge reads the module's own child context (never the kernel's,
 * its parent), and an object that is a bean under two names counts once.
 */
class ModuleBridgeTest {

    /** A stand-in extension type: the bridge works on any type. */
    record ApiAlias(String from, String to) {}

    @Test
    void theBridgeTakesTheModulesOwnBeansOnceAndNeverTheKernels() {
        try (AnnotationConfigApplicationContext kernel = new AnnotationConfigApplicationContext();
                AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            ApiAlias kernelsOwn = new ApiAlias("/api/v1/old-kernel", "/api/v1/kernel");
            ApiAlias modules = new ApiAlias("/api/v1/old-mod", "/api/v1/mod");
            kernel.registerBean("kernelAlias", ApiAlias.class, () -> kernelsOwn);
            kernel.refresh();
            child.setParent(kernel);
            child.registerBean("modAlias", ApiAlias.class, () -> modules);
            child.registerAlias("modAlias", "modAliasAgain");
            child.refresh();
            LoadedModule m = new LoadedModule(null, null, child, java.util.List.of(), java.util.List.of());

            assertThat(ModuleBridge.found(m, ApiAlias.class)).containsExactly(modules);
        }
    }

    @Test
    void twoModulesWithTheSameBeanNameStayApartAndTheKernelHasNeither() {
        try (AnnotationConfigApplicationContext kernel = new AnnotationConfigApplicationContext();
                AnnotationConfigApplicationContext a = new AnnotationConfigApplicationContext();
                AnnotationConfigApplicationContext b = new AnnotationConfigApplicationContext()) {
            kernel.refresh();
            for (var pair : java.util.List.of(java.util.Map.entry(a, "a"), java.util.Map.entry(b, "b"))) {
                pair.getKey().setParent(kernel);
                String id = pair.getValue();
                pair.getKey().registerBean("alias", ApiAlias.class, () -> new ApiAlias("/api/v1/old-" + id, "/api/v1/" + id));
                pair.getKey().refresh();
            }
            assertThat(ModuleBridge.found(new LoadedModule(null, null, a, java.util.List.of(), java.util.List.of()), ApiAlias.class))
                    .singleElement().extracting(ApiAlias::to).isEqualTo("/api/v1/a");
            assertThat(ModuleBridge.found(new LoadedModule(null, null, b, java.util.List.of(), java.util.List.of()), ApiAlias.class))
                    .singleElement().extracting(ApiAlias::to).isEqualTo("/api/v1/b");
            assertThat(kernel.getBeansOfType(ApiAlias.class)).isEmpty();
        }
    }
}
