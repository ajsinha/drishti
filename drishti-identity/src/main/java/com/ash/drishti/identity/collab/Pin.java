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
package com.ash.drishti.identity.collab;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Which data a share or comment is about (COLLABORATION.md, "The pin"): the business date the page showed, whether it was
 * live, the instant it was read as known at, the generation it showed and the source that answered. Generation is evidence,
 * never an address: no store can read "generation N".
 *
 * @param businessDate the date shown; null for undated sources
 * @param live the page was live
 * @param knownAt the "known at" instant of the page; for a live or plain-date page, the moment of writing
 * @param generation the generation the page showed (0 = unknown)
 * @param source the source that answered; null when unknown
 */
public record Pin(LocalDate businessDate, boolean live, Instant knownAt, long generation, String source) {}
