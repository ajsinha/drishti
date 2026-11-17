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

/**
 * One module asking another (KERNEL_AND_MODULES.md section 5). The provider marks methods of a bean {@link Operation}
 * ({@code @Operation("var.scope")}, shape {@code (Person, <request record>) -> <answer record>}); the consumer writes its own interface with
 * the same operation names and its own records, and asks {@link #of}. No class is shared between modules: request and answer pass by
 * their fields. A call passes the same doors as the provider's REST path (module on and licensed, engine running) and follows placement
 * to the node that holds the provider's part.
 */
public interface Operations {

    /** Whether a module is loaded, switched on and licensed here (false: every call of it refuses). */
    boolean available(String module);

    /**
     * The consumer's view of a module's operations: each method of {@code consumerInterface} carries {@link Operation} and takes
     * {@code (Person, <request record>)}. A call that cannot run refuses with {@link Kernel.Refused} (the module absent or off, the engine
     * off, the provider's own refusal with its status, code and words).
     *
     * @throws IllegalArgumentException a method of the interface without {@link Operation} or of another shape
     */
    <T> T of(String module, Class<T> consumerInterface);
}
