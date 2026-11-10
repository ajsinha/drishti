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
package com.ash.drishti.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The class loaders of the modules loaded now (docs/architecture/KERNEL_AND_MODULES.md section 12.3): where the kernel finds a class a
 * connector file names by its name (a pack's reactive engine whose code is a module's), and who must let go of a module's objects when it
 * leaves. The module host adds a loader when the module starts and the returned action takes it out (unload step 4, before the leak
 * check); every listener then drops what it holds of that loader.
 */
public final class ModuleLoaders {

    private static final List<ClassLoader> LIVE = new CopyOnWriteArrayList<>();
    private static final List<Consumer<ClassLoader>> LEAVING = new CopyOnWriteArrayList<>();

    private ModuleLoaders() {
    }

    /** A module's loader is live; the action takes it out and tells the listeners. */
    public static Runnable add(ClassLoader loader) {
        LIVE.add(loader);
        return () -> {
            if (LIVE.remove(loader)) {
                LEAVING.forEach(c -> c.accept(loader));
            }
        };
    }

    /** The live modules' loaders. */
    public static List<ClassLoader> live() {
        return new ArrayList<>(LIVE);
    }

    /** Called with a module's loader as it leaves: drop every object of it (a kernel part, registered once). */
    public static void onLeave(Consumer<ClassLoader> listener) {
        LEAVING.add(listener);
    }

    /** True when the object's class is of a module's loader, or of one that it loaded. */
    public static boolean of(Object o, ClassLoader loader) {
        for (ClassLoader l = o == null ? null : o.getClass().getClassLoader(); l != null; l = l.getParent()) {
            if (l == loader) {
                return true;
            }
        }
        return false;
    }
}
