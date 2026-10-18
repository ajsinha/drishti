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
package com.ash.drishti.rachana.design.ops;

import java.util.List;
import java.util.Map;

/**
 * Sets the parameters of one use of a shared block ({@code { use: legs@1, with: { rate-fmt: pct4 } }}) or removes the use: what the
 * designer's inspector does to a block group on the canvas. The block's panels live in its own file and are not edited here.
 *
 * @param use which use (0-based, counting only the uses of blocks in the panel list)
 * @param with the parameters; empty or null leaves the block's defaults
 * @param remove true removes the use (and so the block's panels) from the Sutra
 */
public record SetBlock(int use, Map<String, Object> with, boolean remove) implements Op {

    @Override
    public String name() {
        return "setBlock";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (with != null) {
            with.forEach((k, v) -> {
                if (!k.matches("[a-z][a-z0-9-]*")) {
                    throw new OpException(OpException.BAD_VALUE, "a block parameter is lower-case kebab (rate-fmt), not '" + k + "'");
                }
                if (v instanceof Map<?, ?> || v instanceof List<?>) {
                    throw new OpException(OpException.BAD_VALUE, "block parameter '" + k + "' is one value (text, a number, true or false)");
                }
            });
        }
        doc.setUse(use, with, remove);
    }
}
