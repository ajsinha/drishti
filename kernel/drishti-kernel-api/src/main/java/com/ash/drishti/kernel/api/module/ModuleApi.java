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
 * Marks a module's REST controller (docs/architecture/KERNEL_AND_MODULES.md sections 2.3 and 4.5): the kernel mounts it under the module's
 * namespace, {@code /api/v1/<value>/…}, and unmounts it when the module unloads. The class is a REST controller ({@code @RestController}
 * beside this annotation): its answers are the response body; a class without it is not mounted.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModuleApi {

    /** The namespace: {@code genomics} for {@code /api/v1/genomics/**}. */
    String value();
}
