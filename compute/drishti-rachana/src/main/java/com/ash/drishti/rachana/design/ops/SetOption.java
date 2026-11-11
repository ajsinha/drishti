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

import com.ash.drishti.rachana.model.Panel;

/**
 * Sets one option (or one of the panel's own keys: {@code title}, {@code key}, {@code code}, {@code area}, {@code span},
 * {@code height}, {@code columns}) of a panel; a null value removes it.
 *
 * @param panel the panel's id
 * @param option the option name, valid for the panel's kind
 * @param value the new value (text, number, true/false, list or mapping), or null to remove the option
 */
public record SetOption(String panel, String option, Object value) implements Op {

    @Override
    public String name() {
        return "setOption";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        Panel p = doc.panel(panel);
        Object v = OpChecks.plain(value);
        OpChecks.option(p.kind(), option, v);
        if ("area".equals(option) && v instanceof String s) {
            v = OpChecks.area(s) == com.ash.drishti.rachana.model.Area.RIGHT ? "right" : null;
        }
        doc.setPanelKey(panel, option, v);
    }
}
