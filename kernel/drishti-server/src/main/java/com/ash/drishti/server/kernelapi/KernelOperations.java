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

import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Operation;
import com.ash.drishti.kernel.api.services.Operations;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.springframework.stereotype.Component;

/**
 * {@link Operations} before any module registers one: no module is available, and every call refuses as an absent module's would
 * ({@code 404 DRS-1020}). The registry of operations and their placement come with K3 (docs/plans/I7A_BUILD_PLAN.md); the shape of a
 * consumer's interface is checked already, so a module written against it now fails the same way it will then.
 */
@Component
public class KernelOperations implements Operations {

    @Override
    public boolean available(String module) {
        return false;
    }

    @Override
    public <T> T of(String module, Class<T> consumerInterface) {
        if (!consumerInterface.isInterface()) {
            throw new IllegalArgumentException(consumerInterface.getName() + " is not an interface");
        }
        for (Method m : consumerInterface.getMethods()) {
            if (m.isDefault() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getAnnotation(Operation.class) == null || m.getParameterCount() != 2
                    || !com.ash.drishti.kernel.api.services.Person.class.equals(m.getParameterTypes()[0])) {
                throw new IllegalArgumentException(consumerInterface.getName() + "." + m.getName()
                        + " is not an operation: @Operation(\"name\") on (Person, <request record>)");
            }
        }
        Object proxy = Proxy.newProxyInstance(consumerInterface.getClassLoader(), new Class<?>[] {consumerInterface}, (self, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> "Operations[" + module + "]";
                };
            }
            Operation op = m.getAnnotation(Operation.class);
            throw new Kernel.Refused(404, "DRS-1020", "the module " + module + " serves no operation " + (op == null ? m.getName() : op.value())
                    + " here (absent, off, or not serving operations)");
        });
        return consumerInterface.cast(proxy);
    }
}
