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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.core.SmartClassLoader;

/**
 * One module's class loader (KERNEL_AND_MODULES.md section 4.4). Its parent is the JDK's platform loader, so every kernel class outside
 * the shared list is invisible to the module ({@code ClassNotFoundException}). Order: the JDK's {@code java.*} first; then the shared list
 * (the kernel API and the shared libraries) from the kernel's loader, parent-first; then the module's own jars, child-first (its own copy of
 * a library wins); then the rest of the platform ({@code javax.*} of the JDK). Resources follow the same order.
 */
public final class ModuleClassLoader extends URLClassLoader implements SmartClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final String moduleId;
    private final ClassLoader kernel;
    private final List<String> shared;
    private final List<String> sharedPaths;

    private final ClassLoader lib;
    private final List<String> allowance;

    public ModuleClassLoader(String moduleId, URL[] jars, ClassLoader kernel, List<String> shared) {
        this(moduleId, jars, kernel, shared, null);
    }

    /**
     * @param lib the site's shared lib loader ({@code drishti.lib.path}), asked after the module's own jars for the classes its jars
     *            define (never a kernel class through it), or null for none
     */
    public ModuleClassLoader(String moduleId, URL[] jars, ClassLoader kernel, List<String> shared, ClassLoader lib) {
        this(moduleId, jars, kernel, shared, lib, List.of());
    }

    /**
     * @param allowance a verified first-party module's kernel-internals allowance ({@link FirstParty#allowance}): the kernel packages
     *                  (exact, not their sub-packages) asked of the kernel like the shared list; empty for every other module
     */
    public ModuleClassLoader(String moduleId, URL[] jars, ClassLoader kernel, List<String> shared, ClassLoader lib, List<String> allowance) {
        super("module:" + moduleId, jars, ClassLoader.getPlatformClassLoader());
        this.lib = lib == kernel ? null : lib;
        this.moduleId = moduleId;
        this.kernel = kernel;
        this.shared = List.copyOf(shared);
        this.sharedPaths = shared.stream().map(p -> p.replace('.', '/')).toList();
        this.allowance = List.copyOf(allowance);
    }

    /** The module's id. */
    public String moduleId() {
        return moduleId;
    }

    /** True when the class is asked of the kernel (the kernel API or a shared library). */
    public boolean isShared(String className) {
        if (!allowance.isEmpty() && FirstParty.allowed(allowance, className)) {
            return true;
        }
        for (String p : shared) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (name.startsWith("java.")) {
                    c = getParent().loadClass(name);
                } else if (isShared(name)) {
                    c = kernel.loadClass(name);
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = fromLib(name);
                        if (c == null) {
                            c = getParent().loadClass(name);   // the JDK's own javax.*, jdk.*, sun.*; never a kernel class
                        }
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    /**
     * The module's own classes are reloadable: Spring then keeps no cache of them (the CGLIB proxies of its {@code @Configuration} classes
     * are generated uncached), which is what lets an unloaded module's loader be collected.
     */
    @Override
    public boolean isClassReloadable(Class<?> clazz) {
        return clazz != null && clazz.getClassLoader() == this;
    }

    /** Proxies of the module's classes are defined in the module's own loader. */
    @Override
    public Class<?> publicDefineClass(String name, byte[] b, ProtectionDomain protectionDomain) {
        return defineClass(name, b, 0, b.length, protectionDomain);
    }

    @Override
    public URL getResource(String name) {
        if (sharedResource(name)) {
            return kernel.getResource(name);
        }
        URL u = findResource(name);
        return u != null ? u : getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (sharedResource(name)) {
            return kernel.getResources(name);
        }
        List<URL> out = new ArrayList<>(Collections.list(findResources(name)));
        out.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(out);
    }

    /** A class of the site's lib jars, or null (a kernel class the lib loader would hand on is not the lib's to give). */
    private Class<?> fromLib(String name) {
        if (lib == null) {
            return null;
        }
        try {
            Class<?> c = lib.loadClass(name);
            return c.getClassLoader() == lib ? c : null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private boolean sharedResource(String name) {
        String n = name.startsWith("/") ? name.substring(1) : name;
        for (String p : sharedPaths) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
