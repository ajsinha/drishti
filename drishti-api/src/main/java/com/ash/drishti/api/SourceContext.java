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

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Services the engine offers a plugin when it starts. Plugins depend only on this API module, so the
 * engine supplies JSON parsing and scheduling instead of plugins bundling their own.
 */
public interface SourceContext {

    /** Plugin settings from {@code drishti.sources.plugins.<name>.settings}. */
    Map<String, String> settings();

    /** Parses a JSON document into a {@link DataNode}. */
    DataNode parseJson(InputStream in) throws IOException;

    /** A shared scheduler for polling or ticking; tasks must be short and non-blocking. */
    ScheduledExecutorService scheduler();

    /**
     * Reads other kinds through the server's routing (for a connector built on others, such as a derived kind). Usable
     * once the server has started, not inside {@link SourcePlugin#start}.
     */
    default EntityReader reader() {
        throw new UnsupportedOperationException("this host does not let connectors read other kinds");
    }

    default String setting(String key, String fallback) {
        String v = settings().get(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
