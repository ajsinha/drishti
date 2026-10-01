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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/** The engine's implementation of {@link SourceContext}, one per plugin. */
record EngineSourceContext(Map<String, String> settings, JsonCodec codec, ScheduledExecutorService scheduler, com.ash.drishti.api.EntityReader reader)
        implements SourceContext {

    @Override
    public DataNode parseJson(InputStream in) throws IOException {
        return codec.read(in);
    }
}
