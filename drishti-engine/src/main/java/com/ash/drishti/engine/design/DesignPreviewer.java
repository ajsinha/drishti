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
package com.ash.drishti.engine.design;

import com.ash.drishti.engine.view.ViewModel;
import com.fasterxml.jackson.databind.JsonNode;

/** Renders a Sutra against a pasted document: Studio's preview path. Supplied by the server; faked in unit tests. */
@FunctionalInterface
public interface DesignPreviewer {

    ViewModel preview(String yaml, String kind, JsonNode document);
}
