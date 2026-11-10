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

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

/**
 * The class-loader leak check (KERNEL_AND_MODULES.md sections 4.4 and 4.10): after an unload, the module's loader must be collectable.
 * The check holds only a weak reference, clears the shared libraries' class caches for the loader, then asks for collection a bounded
 * number of times. A loader still reachable is a leak: a hot unload is refused and the next rolling restart is the way out.
 */
public final class LeakCheck {

    private static final Logger LOG = LoggerFactory.getLogger(LeakCheck.class);

    private final WeakReference<ClassLoader> ref;
    private final String module;

    public LeakCheck(String module, ClassLoader loader) {
        this.module = module;
        this.ref = new WeakReference<>(loader);
    }

    /**
     * Clears the shared libraries' caches that may hold the loader's classes (Spring's introspection, reflection and annotation caches).
     * The CGLIB class cache of {@code @Configuration} classes is never filled for a module: its loader is a {@code SmartClassLoader} that
     * marks its classes reloadable, so Spring generates their proxies uncached.
     */
    public static void clearSharedCaches(ClassLoader loader) {
        CachedIntrospectionResults.clearClassLoader(loader);
        ReflectionUtils.clearCache();
        AnnotationUtils.clearCache();
        ResolvableType.clearCache();
        com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance().clearCache();   // Jackson's shared type cache (a module's REST answers)
        // the @Bean method caches of configuration-class proxies: soft, so only memory pressure would free them; emptied for the loader
        for (String field : new String[] {"beanNameCache", "scopedProxyCache"}) {
            try {
                Field f = Class.forName("org.springframework.context.annotation.BeanAnnotationHelper").getDeclaredField(field);
                f.setAccessible(true);
                ((Map<?, ?>) f.get(null)).keySet().removeIf(k -> k instanceof Method m && m.getDeclaringClass().getClassLoader() == loader);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.debug("Spring's {} could not be emptied for {}: {}", field, loader, e.toString());
            }
        }
    }

    /** True when the loader was collected within the attempts. */
    public boolean collected(int attempts) {
        List<byte[]> pressure = new ArrayList<>();
        for (int i = 0; i < attempts && ref.get() != null; i++) {
            System.gc();
            if (ref.get() == null) {
                break;
            }
            try {
                Thread.sleep(10L * (i + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (i > attempts / 2) {
                pressure.add(new byte[1 << 20]);              // soft references go under a little pressure
            }
        }
        pressure.clear();
        return ref.get() == null;
    }

    /** The words of a failed check. */
    public String words() {
        return module + ": its class loader is still reachable after the unload (a leak); the next rolling restart removes it";
    }
}
