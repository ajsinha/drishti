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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.spi.PivotProvider;

/**
 * A pivot source a module contributes (docs/architecture/DOMAIN_MODULES.md, the cycles: J1): a bean of this type gives the pivot engine one
 * more provider, asked before the tabular one that answers every kind, panel and table. The core names no module's source; a module's
 * configuration registers its own (the banking module: the VaR source and the MR cube's lens).
 */
@FunctionalInterface
public interface PivotSourceProvider {

    /** The provider, made when the pivot engine is built; null when it has nothing to offer here. */
    PivotProvider provider();
}
