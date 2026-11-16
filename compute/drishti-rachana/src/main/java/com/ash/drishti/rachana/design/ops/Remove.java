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

/**
 * Removes a panel, with the comment just above it.
 *
 * @param panel the panel's id
 */
public record Remove(String panel) implements Op {

    @Override
    public String name() {
        return "remove";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        doc.removePanel(panel);
    }
}
