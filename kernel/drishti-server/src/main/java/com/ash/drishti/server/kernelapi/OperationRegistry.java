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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.kernel.api.services.Operation;
import com.ash.drishti.kernel.api.services.Person;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The operations modules serve (KERNEL_AND_MODULES.md section 2.3): every method of a module's bean annotated {@link Operation}, of the
 * shape {@code (Person, <request>) -> <answer>}, registered as {@code <module>:<name>} when the module starts (the beans of its own
 * child context, {@code ModuleWiring}) and withdrawn when it stops. Two of one name in one module stop the module's start, both named.
 */
@Component
public class OperationRegistry {

    /**
     * One operation: who serves it, and the doors it passes ({@link Operation#rest()}: the REST path it answers as; null for the module's
     * own doors).
     */
    public record Entry(String module, String name, Object bean, Method method, String restMethod, String restPath) {

        /** The provider's request type. */
        java.lang.reflect.Type request() {
            return method.getGenericParameterTypes()[1];
        }

        String where() {
            return AopUtils.getTargetClass(bean).getName() + "." + method.getName();
        }
    }

    private final Map<String, Entry> operations = new ConcurrentHashMap<>();

    /** The operations of a module's own beans (its child context's, never its parent's); returns their withdrawal. */
    public Runnable register(String module, ApplicationContext context) {
        List<Object> beans = new ArrayList<>();
        if (context instanceof ConfigurableApplicationContext c) {
            for (String name : c.getBeanFactory().getBeanNamesForType(Object.class, false, false)) {
                Class<?> type = c.getBeanFactory().getType(name, false);
                if (type != null && hasOperation(type)) {
                    beans.add(c.getBean(name));
                }
            }
        }
        return register(module, beans.toArray());
    }

    /** The operations of the given objects, served by {@code module}; returns their withdrawal. */
    public synchronized Runnable register(String module, Object... beans) {
        Map<String, Entry> found = new LinkedHashMap<>();
        for (Object bean : beans) {
            for (Method m : AopUtils.getTargetClass(bean).getMethods()) {
                Operation op = m.getAnnotation(Operation.class);
                if (op == null || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (m.getParameterCount() != 2 || !Person.class.equals(m.getParameterTypes()[0]) || m.getReturnType() == void.class) {
                    throw new IllegalStateException("module " + module + ": " + m.getDeclaringClass().getName() + "." + m.getName()
                            + " is not an operation: @Operation(\"name\") on (Person, <request record>) -> <answer record>");
                }
                String[] rest = rest(module, op);
                Entry e = new Entry(module, op.value(), bean, AopUtils.selectInvocableMethod(m, bean.getClass()), rest[0], rest[1]);
                String key = key(module, op.value());
                Entry had = found.containsKey(key) ? found.get(key) : operations.get(key);
                if (had != null) {
                    throw new IllegalStateException("module " + module + " serves the operation " + op.value() + " twice: " + had.where()
                            + " and " + e.where());
                }
                found.put(key, e);
            }
        }
        operations.putAll(found);
        return () -> found.forEach(operations::remove);
    }

    private static boolean hasOperation(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(Operation.class)) {
                return true;
            }
        }
        return false;
    }

    private static String[] rest(String module, Operation op) {
        String r = op.rest().strip();
        if (r.isEmpty()) {
            return new String[] {null, null};
        }
        int sp = r.indexOf(' ');
        String method = sp < 0 ? "" : r.substring(0, sp).toUpperCase(Locale.ROOT);
        String path = sp < 0 ? "" : r.substring(sp + 1).strip();
        if (!method.matches("GET|POST|PUT|PATCH|DELETE") || !path.startsWith("/api/")) {
            throw new IllegalStateException("module " + module + ": the operation " + op.value() + " says rest = \"" + r
                    + "\": write the REST path it answers as, \"POST /api/v1/...\"");
        }
        return new String[] {method, path};
    }

    static String key(String module, String name) {
        return module + ":" + name;
    }

    /** The operation {@code name} of {@code module}, when it is served here. */
    public Optional<Entry> find(String module, String name) {
        return Optional.ofNullable(operations.get(key(module, name)));
    }

    /** Whether {@code module} serves any operation here. */
    public boolean serves(String module) {
        return operations.values().stream().anyMatch(e -> e.module().equals(module));
    }

    /** Every operation served here, {@code <module>:<name>}, sorted. */
    public List<String> names() {
        return List.copyOf(new TreeSet<>(operations.keySet()));
    }
}
