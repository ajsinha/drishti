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
package com.ash.drishti.module.runtime;

import java.util.List;

/**
 * One module's Health row (KERNEL_AND_MODULES.md section 4.9).
 *
 * @param id      the module's id
 * @param version its version, or blank when its manifest did not read
 * @param state   loaded, off, refused, leaving, removed, or not placed (no part of it placed on this node)
 * @param check   the check that refused it ({@code V1}..{@code V8}, {@code START}), or null
 * @param reason  the words of a refusal or a switch, or null
 * @param notes   notes that do not refuse (an absent signature, a resource warning)
 */
public record ModuleHealth(String id, String version, State state, String check, String reason, List<String> notes) {

    /** A module's state. */
    public enum State { LOADED, OFF, REFUSED, LEAVING, REMOVED, NOT_PLACED }

    public ModuleHealth {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The row as Health shows it: {@code LOADED}, {@code OFF (administrator)}, {@code REFUSED (reason)}. */
    public String line() {
        return id + " " + version + ": " + state + (reason == null ? "" : " (" + reason + ")");
    }
}
