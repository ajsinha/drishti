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

import com.ash.drishti.kernel.api.function.FunctionProvider;

/** A module's functions (the kernel API's {@link FunctionProvider}) as Rachana's table entries: scalars, their arguments evaluated. */
final class RachanaFunctions {

    private RachanaFunctions() {
    }

    /** Adds the module's functions to Rachana while it is loaded; the returned action takes them out. */
    static Runnable register(FunctionProvider module) {
        return com.ash.drishti.rachana.el.Functions.register(module.source(), module.functions().stream()
                .map(f -> new com.ash.drishti.rachana.el.FunctionProvider.Provided(f.name(), f.minArgs(), f.maxArgs(), f.args(), "scalar",
                        f.description(), "", (args, context) -> f.body().apply(args))).toList());
    }
}
