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

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sources that could not answer while one search or listing was answered, each with the first reason it gave, so
 * a result that misses them says so (partial, and why) instead of looking exact and empty. Filled concurrently by the
 * reads of one search; thread-safe.
 */
public final class SourceFailures {

    private final Map<String, String> bySource = new ConcurrentHashMap<>();

    /** Records that {@code source} could not answer; the first reason per source is kept. */
    public void add(String source, String reason) {
        bySource.putIfAbsent(source == null ? "a source" : source, reason == null ? "failed" : reason);
    }

    public boolean isEmpty() {
        return bySource.isEmpty();
    }

    /** Source to reason, by source name. */
    public Map<String, String> asMap() {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(bySource));
    }
}
