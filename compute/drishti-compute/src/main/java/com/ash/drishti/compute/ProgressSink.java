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
package com.ash.drishti.compute;

import java.util.Map;

/**
 * Receives a running request's progress events (docs/architecture/DATA_ARCHITECTURE.md › Reactive connectors, section 6.2, step 9): the engine's partial
 * results ({@code {"done": 7, "of": 12, "estimate": {...}}}), tagged with the request id they belong to. Called on the worker's reader
 * thread: keep it quick (hand the event to the live channel and return).
 */
@FunctionalInterface
public interface ProgressSink {

    ProgressSink NONE = (id, data) -> { };

    void progress(String requestId, Map<String, Object> data);
}
