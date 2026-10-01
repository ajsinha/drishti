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
package com.ash.drishti.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads other kinds through the server's normal routing, for connectors built on other connectors (derived kinds).
 * What it returns is what the routed sources hold: no entitlements are applied (the server redacts what it serves).
 */
public interface EntityReader {

    /** The entities of {@code kind} that the sources can list, at most {@code limit}. */
    List<EntityRef> list(String kind, AsOf asOf, int limit);

    /** The documents, read concurrently; entities that cannot be read are absent. */
    Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, AsOf asOf);
}
