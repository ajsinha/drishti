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
package com.ash.drishti.module.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Guard G12 (the child-context design, KERNEL_AND_MODULES.md section 4.4): each module's Spring context is a child of the kernel's, holds
 * the module's beans and none reach the kernel's; after an unload the child context is closed and neither it nor the module's class loader
 * stays reachable (the {@link LeakCheck}).
 */
class ModuleChildContextGuardTest {

    @TempDir
    Path tmp;

    private List<Path> dirs() throws Exception {
        List<Path> out = new ArrayList<>();
        try (var ids = Files.list(tmp.resolve("modules")).sorted()) {
            for (Path id : ids.toList()) {
                try (var versions = Files.list(id).sorted()) {
                    versions.filter(v -> Files.isRegularFile(v.resolve("module.yaml"))).forEach(out::add);
                }
            }
        }
        return out;
    }

    @Test
    void eachModuleHasItsOwnChildOfTheKernelAndAnUnloadClosesAndFreesIt() throws Exception {
        new TestModule("alpha").install(tmp, tmp.resolve("modules"));
        TestModule beta = new TestModule("beta");
        beta.functions = "[{word: HELLOB}]";                                         // a word of its own: two modules never claim one
        beta.install(tmp, tmp.resolve("modules"));
        boolean[] closed = {false, false};
        WeakReference<ConfigurableApplicationContext> alphaContext;
        try (AnnotationConfigApplicationContext kernel = new AnnotationConfigApplicationContext()) {
            kernel.refresh();
            ModuleRuntime rt = new ModuleRuntime(KernelFacts.ofKernel(), kernel, ModuleRuntime.class.getClassLoader(), new ModuleHost() {
                @Override
                public void event(String name, String module) {
                }
            });
            rt.start(dirs(), id -> true);
            alphaContext = watch(rt, kernel, closed);

            assertThat(rt.unload("alpha", true).leakFree()).isTrue();
            assertThat(closed[0]).as("alpha's context closed").isTrue();
            assertThat(closed[1]).as("beta's context untouched").isFalse();
            assertThat(rt.loaded("beta")).isPresent();
            assertThat(rt.unload("beta", true).leakFree()).isTrue();
            assertThat(closed[1]).isTrue();
            rt.close();
        }
        for (int i = 0; i < 30 && alphaContext.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertThat(alphaContext.get()).as("the unloaded module's context is collectable").isNull();
    }

    /** In a frame of its own, so nothing of the modules stays reachable from the test once it returns. */
    private static WeakReference<ConfigurableApplicationContext> watch(ModuleRuntime rt, AnnotationConfigApplicationContext kernel, boolean[] closed) {
        LoadedModule a = rt.loaded("alpha").orElseThrow();
        LoadedModule b = rt.loaded("beta").orElseThrow();
        assertThat(a.context()).isNotSameAs(b.context());
        assertThat(a.context().getParent()).isSameAs(kernel);
        assertThat(b.context().getParent()).isSameAs(kernel);
        assertThat(a.context().getBean("greeting")).isEqualTo("hello from alpha");     // the same bean name, one in each
        assertThat(b.context().getBean("greeting")).isEqualTo("hello from beta");
        assertThat(kernel.containsBean("greeting")).as("a module's bean never reaches the kernel's context").isFalse();
        assertThat(kernel.containsBean("greeter")).isFalse();
        a.context().addApplicationListener(e -> {
            if (e instanceof ContextClosedEvent) {
                closed[0] = true;
            }
        });
        b.context().addApplicationListener(e -> {
            if (e instanceof ContextClosedEvent) {
                closed[1] = true;
            }
        });
        return new WeakReference<>(a.context());
    }
}
