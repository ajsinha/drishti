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
package com.ash.drishti.engine.live;

import java.util.List;

/**
 * What one SSE event carries: the minimal patches that turn the client's view into the current one.
 *
 * @param seq monotonic per stream; the SSE event id
 * @param generation source generation of the entity after this frame
 * @param patches the changes
 * @param latencyMs source tick to frame build, for this frame
 * @param p99Ms rolling p99 of tick-to-send latency across the server
 */
public record Frame(long seq, long generation, List<Patch> patches, double latencyMs, double p99Ms) {}
