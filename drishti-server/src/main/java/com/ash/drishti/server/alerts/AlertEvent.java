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

import java.time.Instant;

/**
 * A fired alert.
 *
 * @param seq monotonic per server
 * @param at when it fired
 * @param user whose rule fired
 * @param rule the rule's name
 * @param kind entity kind
 * @param id entity id
 * @param severity severity
 * @param message the rendered message
 * @param generation the entity generation that made it true
 */
public record AlertEvent(long seq, Instant at, String user, String rule, String kind, String id, String severity, String message,
        long generation) {}
