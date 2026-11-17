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
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.SmartClassLoader;

/**
 * One module's class loader (KERNEL_AND_MODULES.md section 4.4). The kernel's libraries always win: every class and resource is asked of
 * the kernel first (the JDK, the kernel API and every library the kernel ships), then of the site's lib jars ({@code drishti.lib.path},
 * {@code lib/ext}), and only then of the module's own jars ({@code java/}, then {@code lib/}). A module's copy of a library the kernel ships
 * is therefore never used ({@link KernelLibraries} warns of it at load and in {@code module verify}); a library the kernel does not ship
 * comes from the module's jars. One filter: a class of {@code com.ash.drishti.} outside the kernel API ({@link #KERNEL_API}) is never the
 * kernel's (its internals stay invisible) unless it is in the module's verified kernel-internals allowance; it can only be the module's own.
 * No module sees another module's classes. Resources follow the same order; a kernel {@code META-INF/services} file is handed on only when
 * every provider it names is a class the module may take from the kernel.
 */
public final class ModuleClassLoader extends URLClassLoader implements SmartClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** The kernel API packages (and below) every module sees: {@code drishti-kernel-api} and {@code drishti-api}. */
    public static final List<String> KERNEL_API = List.of("com.ash.drishti.kernel.api.", "com.ash.drishti.api.");

    /** The kernel's own namespace: hidden from a module except {@link #KERNEL_API} and an allowance. */
    public static final String KERNEL_NAMESPACE = "com.ash.drishti.";

    private final String moduleId;
    private final ClassLoader kernel;
    private final ClassLoader lib;
    private final List<String> allowance;

    public ModuleClassLoader(String moduleId, URL[] jars, ClassLoader kernel) {
        this(moduleId, jars, kernel, null, List.of());
    }

    /**
     * @param lib       the site's shared lib loader ({@code drishti.lib.path}), asked after the kernel for the classes its jars define (a
     *                  site jar wins over a module's copy), or null for none
     * @param allowance a verified first-party module's kernel-internals allowance ({@link FirstParty#allowance}): the kernel packages
     *                  (exact, not their sub-packages) asked of the kernel like the kernel API; empty for every other module
     */
    public ModuleClassLoader(String moduleId, URL[] jars, ClassLoader kernel, ClassLoader lib, List<String> allowance) {
        super("module:" + moduleId, jars, ClassLoader.getPlatformClassLoader());
        this.lib = lib == kernel ? null : lib;
        this.moduleId = moduleId;
        this.kernel = kernel;
        this.allowance = List.copyOf(allowance);
    }

    /** The module's id. */
    public String moduleId() {
        return moduleId;
    }

    /**
     * True when a class may be the kernel's: anything outside {@code com.ash.drishti.}, the kernel API, and the module's allowance. False:
     * a kernel internal, which only the module's own jars may define.
     */
    public boolean kernelMayGive(String className) {
        if (!className.startsWith(KERNEL_NAMESPACE)) {
            return true;
        }
        for (String p : KERNEL_API) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return !allowance.isEmpty() && FirstParty.allowed(allowance, className);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null && kernelMayGive(name)) {
                c = fromKernel(name);
            }
            if (c == null) {
                c = findClass(name);      // the module's own jars; ClassNotFoundException when not there either
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
        if (kernelMayGiveResource(name)) {
            URL u = kernel.getResource(name);
            if (u == null && lib != null) {
                u = lib.getResource(name);
            }
            if (u != null && servable(name, u)) {
                return u;
            }
        }
        return findResource(name);
    }

    /** The kernel's, then the site lib's, then the module's own (a {@code ServiceLoader} with this loader finds the kernel's providers too). */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Set<URL> out = new LinkedHashSet<>();
        if (kernelMayGiveResource(name)) {
            Collections.list(kernel.getResources(name)).stream().filter(u -> servable(name, u)).forEach(out::add);
            if (lib != null) {
                Collections.list(lib.getResources(name)).stream().filter(u -> servable(name, u)).forEach(out::add);
            }
        }
        out.addAll(Collections.list(findResources(name)));
        return Collections.enumeration(out);
    }

    /** A class of the kernel (the JDK's included), else of the site's lib jars, else null. */
    private Class<?> fromKernel(String name) {
        try {
            return kernel.loadClass(name);
        } catch (ClassNotFoundException e) {
            if (lib == null) {
                return null;
            }
            try {
                return lib.loadClass(name);
            } catch (ClassNotFoundException e2) {
                return null;
            }
        }
    }

    /**
     * A kernel-side resource as this loader may hand it on: a {@code META-INF/services} file only when every provider it names is a class
     * this loader may take from the kernel (the kernel's own providers are kernel internals: a {@code ServiceLoader} with a module's loader
     * would name a class it cannot load). Every other resource as it is.
     */
    private boolean servable(String name, URL u) {
        String n = name.startsWith("/") ? name.substring(1) : name;
        if (!n.startsWith("META-INF/services/")) {
            return true;
        }
        try (java.io.InputStream in = u.openStream()) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                int hash = line.indexOf('#');
                String provider = (hash >= 0 ? line.substring(0, hash) : line).strip();
                if (!provider.isEmpty() && !kernelMayGive(provider)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean kernelMayGiveResource(String name) {
        String n = name.startsWith("/") ? name.substring(1) : name;
        int slash = n.lastIndexOf('/');
        String pkg = slash < 0 ? "" : n.substring(0, slash).replace('/', '.');
        return kernelMayGive(pkg + ".R");
    }
}
