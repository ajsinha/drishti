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
package com.ash.drishti.kernel.api.data;

/**
 * How a kind is bound to its data (docs/architecture/KERNEL_AND_MODULES.md section 5.6): in Health, on Admin → Data bindings and to modules
 * through {@code ModuleContext.Kinds.state}.
 */
public enum BindingState {
    /** A source serves it and its schema meets the contract. */
    BOUND,
    /** Found by convention (a lake file holds the contract's hinted table); the administrator is asked to confirm. */
    AUTO,
    /** The demo lake's samples serve it. */
    DEMO,
    /** Bound, but nothing loaded within the contract's cadence. */
    STALE,
    /** The source's columns break the contract: treated as unbound for the need. */
    MISMATCH,
    /** Nothing serves it. */
    UNBOUND,
    /** A module computes it (a {@link KindProvider}); not bindable. */
    COMPUTED;

    /** True when the kind can be read: bound, found by convention, demo, stale or computed. */
    public boolean usable() {
        return this != MISMATCH && this != UNBOUND;
    }
}
