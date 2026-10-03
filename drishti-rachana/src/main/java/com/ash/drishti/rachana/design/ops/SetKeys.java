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

import java.util.Map;

/**
 * Sets the function keys ({@code F2: panel-id} or an action); an empty mapping removes them.
 *
 * @param keys function key to action
 */
public record SetKeys(Map<String, Object> keys) implements Op {

    @Override
    public String name() {
        return "setKeys";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (keys == null) {
            throw new OpException(OpException.MALFORMED, "setKeys needs 'keys': a mapping such as { F2: legs } (empty to remove them)");
        }
        doc.setTop("keys", OpChecks.plain(keys));
    }
}
