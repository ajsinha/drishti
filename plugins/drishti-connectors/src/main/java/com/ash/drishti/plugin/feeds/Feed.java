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
package com.ash.drishti.plugin.feeds;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * One public data feed: where to fetch it, how to read the response into dated observations per entity, and how to
 * turn an entity's observations up to a business date into the document its market-data Sutra reads. Adapters are
 * stateless; the plugin fetches, caches and serves.
 */
interface Feed {

    /** One entity's observations, by date; each observation is a small map of values. */
    record Series(String kind, String id, NavigableMap<LocalDate, Map<String, Object>> observations) {}

    /** The URLs to fetch (the plugin may substitute a test URL); {@code today} lets a feed ask for recent months. */
    List<String> urls(Map<String, String> settings, LocalDate today);

    /** Reads the responses (one per URL) into series. */
    List<Series> parse(List<String> bodies, Map<String, String> settings) throws Exception;

    /** The document for {@code s} as of {@code date}, from its observations on or before that date. */
    Map<String, Object> document(Series s, LocalDate date);

    /** The kinds this feed produces. */
    List<String> kinds();
}
