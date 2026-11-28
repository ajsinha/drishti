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

import java.time.LocalDate;

/**
 * Read-only views of vectors a module already holds, offered to a hosted engine without a copy (R2_CUT_PLAN 3a, MC-12; ENGINE_INTEGRATION_DESIGN
 * section 17): the engine host asks every bean of this type when an engine calls {@code InputTable.vectorView(name)} on an input bound to
 * {@code kind}; the first answer that is not null is handed to the engine as it is (its shape is the named view's contract, documented by
 * the module that serves it). A module that holds no view answers null and the engine builds what it needs itself.
 */
public interface VectorViews {

    /** The view {@code name} of {@code kind}'s vectors on {@code date}, or null when this module holds none. */
    Object view(String kind, String name, LocalDate date);
}
