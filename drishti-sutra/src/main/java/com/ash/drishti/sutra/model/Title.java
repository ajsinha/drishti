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
package com.ash.drishti.sutra.model;

/**
 * The view title line: {@code [pill] ID with Counterparty}.
 *
 * @param pill the pill text, for example {@code Trade · Interest rate swap}
 * @param id expression for the identifier
 * @param with optional expression for the counterparty (usually a {@code link(...)})
 */
public record Title(String pill, String id, String with) {}
