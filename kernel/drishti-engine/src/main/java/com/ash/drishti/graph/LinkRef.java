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
package com.ash.drishti.graph;

import com.ash.drishti.api.EntityRef;

/**
 * A reference found in a document.
 *
 * @param label how the panel labels it ({@code Netting set})
 * @param target the referenced entity
 * @param display text to show for the target (a name when the document has one, else the id)
 * @param path the document path it came from
 */
public record LinkRef(String label, EntityRef target, String display, String path) {}
