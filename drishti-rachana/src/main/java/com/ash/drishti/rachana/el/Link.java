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
package com.ash.drishti.rachana.el;

/**
 * A reference to another entity produced by {@code link(...)}. The engine resolves it to a view link.
 *
 * @param id the target identifier
 * @param kind the target kind, or null to let the reference catalogue decide
 * @param label display text, or null to show the identifier
 */
public record Link(String id, String kind, String label) {

    public String display() {
        return label == null || label.isBlank() ? id : label;
    }
}
