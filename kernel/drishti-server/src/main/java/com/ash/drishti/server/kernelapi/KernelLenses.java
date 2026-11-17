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

import com.ash.drishti.kernel.api.services.Lenses;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** {@link Lenses}: a lens's file as the server reads it ({@code drishti.lenses-dir}, else {@code config/lenses}), and a block of it. */
@Component
public class KernelLenses implements Lenses {

    private final Environment env;

    public KernelLenses(Environment env) {
        this.env = env;
    }

    /** The lens's file whole, as the server reads it; empty for no such lens. */
    public Map<String, Object> whole(String name) {
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        Map<String, Object> spec = com.ash.drishti.server.console.ConsoleDistribution.lens(com.ash.drishti.api.Roots.anchored(
                env.getProperty("drishti.lenses-dir", com.ash.drishti.api.Roots.config("lenses"))), name.strip());
        return spec == null ? Map.of() : spec;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> block(String lens, String block) {
        return block != null && whole(lens).get(block) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
