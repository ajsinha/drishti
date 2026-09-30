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
package com.ash.drishti.server.alerts;

import com.ash.drishti.api.EntityRef;

/**
 * A user's alert rule.
 *
 * @param name unique per user
 * @param ref the entity it watches
 * @param when Rachana-EL predicate over the entity's document ({@code $.utilisation > 0.8})
 * @param severity {@code info}, {@code warn} or {@code critical}
 * @param message Rachana-EL template for the notification ({@code Utilisation ${fmt($.utilisation, 'pct0')}})
 * @param enabled whether it is evaluated
 */
public record AlertRule(String name, EntityRef ref, String when, String severity, String message, boolean enabled) {}
