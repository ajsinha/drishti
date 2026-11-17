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
package com.ash.drishti.kernel.api.services;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names an operation: on a provider's bean method, the operation it serves; on a method of a consumer's interface, the operation it asks
 * ({@link Operations#of}). The name is the module's own ({@code var.scope}); registered as {@code <module>:<name>}, and two of one name in
 * one module stop the start.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Operation {

    /** The operation's name within its module ({@code var.scope}). */
    String value();

    /**
     * On a provider's method: the REST path the operation answers as ({@code "POST /api/v1/risk/var/scope"}), whose doors it passes: the
     * token's scope, the module and function gate, the engine gate and the part it is placed with. Empty (the default, and always on a
     * consumer's interface): the module's own doors, its {@code api} part, and a token scope no API token holds.
     */
    String rest() default "";
}
