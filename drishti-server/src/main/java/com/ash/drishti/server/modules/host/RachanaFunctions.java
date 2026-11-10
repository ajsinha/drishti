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
import java.util.List;

/** A module's functions (the kernel API's {@link FunctionProvider}) as Rachana's provider of functions: scalars, arguments evaluated. */
final class RachanaFunctions implements com.ash.drishti.rachana.el.FunctionProvider {

    private final FunctionProvider module;

    RachanaFunctions(FunctionProvider module) {
        this.module = module;
    }

    @Override
    public String source() {
        return module.source();
    }

    @Override
    public List<Provided> functions() {
        return module.functions().stream().map(f -> new Provided(f.name(), f.minArgs(), f.maxArgs(), f.args(), "scalar", f.description(), "",
                (args, context) -> f.body().apply(args))).toList();
    }
}
