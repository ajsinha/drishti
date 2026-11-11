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
package com.ash.drishti.kernel.api.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a module's static field as engine-wide state on purpose (docs/architecture/KERNEL_AND_MODULES.md section 8.6, conformance check
 * C12 "Two people at once"): one value for the whole engine on this server (a memory limit the server sets, the warnings Health lists, the
 * running streams of a connector), never one person's data. C12 refuses every other static field that is not final, and every static
 * collection that is not unmodifiable.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface EngineWide {

    /** Why the field is one value for the engine (read by people reviewing the module). */
    String value();
}
