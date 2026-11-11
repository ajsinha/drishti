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
package com.ash.drishti.engine.shape;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * The result of shape extraction.
 *
 * @param schema JSON Schema draft 2020-12 of every sample, with {@code x-drishti} roles
 * @param roles role and reason per path
 * @param report per-path facts, conflicts and rare fields first
 */
public record Shape(ObjectNode schema, Map<String, RoleInfo> roles, ShapeReport report) {}
