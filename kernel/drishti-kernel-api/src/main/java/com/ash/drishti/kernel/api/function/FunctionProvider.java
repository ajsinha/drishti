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
package com.ash.drishti.kernel.api.function;

import java.util.List;
import java.util.function.Function;

/**
 * Functions a module adds to Rachana (docs/architecture/KERNEL_AND_MODULES.md sections 2.3 and 12.3): {@code greet(name)} in any
 * expression, Sutra or measure while the module is loaded. A bean of the module's context (or a {@link java.util.ServiceLoader} entry of its
 * jars); the kernel adds its functions when the module starts and takes them out when it unloads. A name Rachana or another module already
 * has refuses the module's start. Functions are pure and thread-safe; an expression evaluated after the module left is refused with the
 * reason.
 */
public interface FunctionProvider {

    /** Who provides them, as the catalogue and a refusal name it ({@code the genomics module}). */
    String source();

    /** The functions, each name once. */
    List<Provided> functions();

    /**
     * One function.
     *
     * @param name        its one name, lower case ({@code greet})
     * @param minArgs     the fewest arguments
     * @param maxArgs     the most arguments
     * @param args        its arguments as the catalogue writes them ({@code name})
     * @param description what it means, one line
     * @param body        the implementation: its arguments evaluated, in order
     */
    record Provided(String name, int minArgs, int maxArgs, String args, String description, Function<List<Object>, Object> body) {
    }
}
