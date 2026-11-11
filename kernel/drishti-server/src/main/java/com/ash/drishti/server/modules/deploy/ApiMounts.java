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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.kernel.api.module.ModuleApi;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.LoadedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Mounts a module's {@code @ModuleApi} controllers under its namespace ({@code /api/v1/<prefix>/…}, KERNEL_AND_MODULES.md section 4.5) in
 * the kernel's own request mapping, each mapping recorded in the registry of contributions with the action that unmounts it (unload step
 * 1: the gate closes before anything else goes). Unmounting also empties Spring MVC's per-handler caches of the module's classes, so its
 * class loader can go (the leak check).
 */
public final class ApiMounts {

    private static final Logger LOG = LoggerFactory.getLogger(ApiMounts.class);

    private final Supplier<RequestMappingHandlerMapping> mapping;
    private final Supplier<Collection<Object>> mvcCaches;

    /**
     * @param mapping   the kernel's request mapping
     * @param mvcCaches the MVC objects whose caches hold handler classes (the handler adapter, the exception resolvers)
     */
    public ApiMounts(Supplier<RequestMappingHandlerMapping> mapping, Supplier<Collection<Object>> mvcCaches) {
        this.mapping = mapping;
        this.mvcCaches = mvcCaches;
    }

    /** Mounts every {@code @ModuleApi} bean of the module's own context; a namespace other than the module's prefix is skipped in words. */
    public void mount(LoadedModule module, Contributions contributions) {
        RequestMappingHandlerMapping m = mapping.get();
        if (module.context() == null || m == null) {
            return;
        }

        String prefix = module.manifest().api() == null ? null : module.manifest().api().prefix();
        ClassLoader loader = module.loader();
        java.util.Set<String> names = new java.util.LinkedHashSet<>(java.util.Arrays.asList(module.context().getBeanNamesForAnnotation(ModuleApi.class)));
        java.util.Set<String> absolute = new java.util.LinkedHashSet<>();
        if (prefix != null) {        // a REST controller whose every path is already in the module's namespace (/api/v1/<prefix>/...) is mounted as it is
            for (String name : module.context().getBeanNamesForAnnotation(org.springframework.web.bind.annotation.RestController.class)) {
                if (!names.contains(name) && module.context().getBeanFactory().containsBeanDefinition(name)) {
                    String ns = "/api/v1/" + prefix;
                    java.util.List<String> full = paths(ClassUtils.getUserClass(module.context().getType(name)));
                    if (!full.isEmpty() && full.stream().allMatch(p -> p.equals(ns) || p.startsWith(ns + "/") || p.startsWith(ns + "."))) {
                        absolute.add(name);
                    } else {
                        LOG.warn("module {}: the controller {} is outside its namespace {} (and has no @ModuleApi): not mounted", module.id(), name, ns);
                    }
                }
            }
        }
        names.addAll(absolute);
        for (String name : names) {
            Object bean = module.context().getBean(name);
            Class<?> type = ClassUtils.getUserClass(bean);
            boolean asItIs = absolute.contains(name);
            ModuleApi api = asItIs ? null : AnnotatedElementUtils.findMergedAnnotation(type, ModuleApi.class);
            if (!asItIs && (api == null || !api.value().equals(prefix))) {
                LOG.warn("module {}: {} names the namespace {}, not its own ({}): not mounted", module.id(), type.getName(),
                        api == null ? "none" : api.value(), prefix);
                continue;
            }
            if (!AnnotatedElementUtils.hasAnnotation(type, org.springframework.web.bind.annotation.ResponseBody.class)) {
                LOG.warn("module {}: {} is not a REST controller (add @RestController beside @ModuleApi): not mounted", module.id(), type.getName());
                continue;
            }
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] bases = typeMapping == null || typeMapping.path().length == 0 ? new String[] {""} : typeMapping.path();
            for (Method method : type.getMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (rm == null) {
                    continue;
                }
                String[] paths = rm.path().length == 0 ? new String[] {""} : rm.path();
                for (String base : bases) {
                    for (String path : paths) {
                        String full = asItIs ? join(base, path) : "/api/v1/" + prefix + join(base, path);
                        RequestMappingInfo info = RequestMappingInfo.paths(full).methods(rm.method()).produces(rm.produces())
                                .consumes(rm.consumes()).params(rm.params()).headers(rm.headers()).options(m.getBuilderConfiguration()).build();
                        m.registerMapping(info, bean, method);
                        contributions.add(module.id(), Contributions.Surface.ROUTE, "endpoint " + full, loader, () -> {
                            m.unregisterMapping(info);
                            forget(loader);
                        });
                    }
                }
            }
        }
    }

    /** Every path a controller class maps (its type's paths joined with each method's). */
    private static java.util.List<String> paths(Class<?> type) {
        java.util.List<String> out = new java.util.ArrayList<>();
        RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String[] bases = typeMapping == null || typeMapping.path().length == 0 ? new String[] {""} : typeMapping.path();
        for (Method method : type.getMethods()) {
            RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (rm != null) {
                for (String base : bases) {
                    for (String path : rm.path().length == 0 ? new String[] {""} : rm.path()) {
                        out.add(join(base, path));
                    }
                }
            }
        }
        return out;
    }

    private static String join(String base, String path) {
        String b = base.isEmpty() || base.startsWith("/") ? base : "/" + base;
        String p = path.isEmpty() || path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    /**
     * Empties Spring MVC's caches keyed by a handler's class, method or method parameter for the loader: the handler adapter's, the
     * exception resolvers', and those of every argument resolver and return value handler they hold (a path variable's name cache, ...).
     */
    void forget(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object o : mvcCaches.get()) {
            if (o instanceof com.fasterxml.jackson.databind.ObjectMapper om) {     // its serializers and deserializers of the module's types
                if (om.getSerializerProvider() instanceof com.fasterxml.jackson.databind.ser.DefaultSerializerProvider p) {
                    p.flushCachedSerializers();
                }
                try {
                    Field f = om.getDeserializationContext().getClass().getSuperclass().getDeclaredField("_cache");
                    f.setAccessible(true);
                    ((com.fasterxml.jackson.databind.deser.DeserializerCache) f.get(om.getDeserializationContext())).flushCachedDeserializers();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.debug("the deserializers of {} were not flushed: {}", om, e.toString());
                }
                continue;
            }
            if (o instanceof org.springframework.core.convert.support.GenericConversionService g) {
                g.removeConvertible(Void.class, Void.class);   // empties its converter cache (keys hold a handler's method parameters)
                continue;
            }
            sweep(o, loader, 4, seen);
        }
    }

    private static void sweep(Object o, ClassLoader loader, int depth, java.util.Set<Object> seen) {
        if (o == null || depth < 0 || !seen.add(o)) {
            return;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Map<?, ?> map) {
                        map.keySet().removeIf(k -> loaderOf(k) == loader);
                        map.values().removeIf(x -> loaderOf(x) == loader);
                    } else if (v instanceof Collection<?> list) {
                        for (Object x : list.toArray()) {
                            if (spring(x)) {
                                sweep(x, loader, depth - 1, seen);
                            }
                        }
                    } else if (spring(v)) {
                        sweep(v, loader, depth - 1, seen);
                    }
                } catch (RuntimeException | IllegalAccessException e) {
                    // not ours to empty (a module system boundary, an immutable map): nothing to do
                }
            }
        }
    }

    private static boolean spring(Object x) {
        return x != null && x.getClass().getName().startsWith("org.springframework.web.");
    }

    private static ClassLoader loaderOf(Object k) {
        if (k instanceof Class<?> c) {
            return c.getClassLoader();
        }
        if (k instanceof MethodParameter p) {
            return p.getDeclaringClass().getClassLoader();
        }
        if (k instanceof Method m) {
            return m.getDeclaringClass().getClassLoader();
        }
        if (k instanceof HandlerMethod h) {
            return h.getBeanType().getClassLoader();
        }
        return null;
    }
}
