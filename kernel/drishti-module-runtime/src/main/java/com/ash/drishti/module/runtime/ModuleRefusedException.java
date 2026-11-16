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

/**
 * A module refused (KERNEL_AND_MODULES.md section 4.3): the check that failed ({@code V1} to {@code V8}, or {@code START}) and the plain
 * words an administrator reads (<i>banking 3.0.0: built for kernel API >=2.0; this kernel is 1.4</i>). A refused module is absent everywhere.
 */
public class ModuleRefusedException extends RuntimeException {

    private final String check;

    public ModuleRefusedException(String check, String words) {
        super(words);
        this.check = check;
    }

    public ModuleRefusedException(String check, String words, Throwable cause) {
        super(words, cause);
        this.check = check;
    }

    /** The check that refused it: {@code V1}..{@code V8} or {@code START}. */
    public String check() {
        return check;
    }
}
