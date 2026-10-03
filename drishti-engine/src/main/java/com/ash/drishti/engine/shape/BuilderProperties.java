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
package com.ash.drishti.engine.shape;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.builder.*}: the Screen Builder's limits and the vocabulary its shape inference reads roles from.
 *
 * @param maxSamples most documents in one request
 * @param maxFileMb largest single document, in megabytes
 * @param maxTotalMb largest request, in megabytes
 * @param maxDepth deepest nesting of one document
 * @param enumMaxDistinct text with at most this many distinct values is an enum...
 * @param enumMinSeen ...when it was seen at least this many times
 * @param examples example values kept per path in the report
 * @param statusWords values that are states (Live, Settled...), compared ignoring case
 * @param statusNames field names (regex, case-insensitive) that hold a state
 * @param idNames field names (regex, case-insensitive) that identify their record
 * @param labelNames property names that hold the label of a row (events, steps, graph nodes)
 * @param ohlcNames the four property names of a candle: open, high, low, close
 * @param graphNodeNames property names that hold the nodes of a graph
 * @param graphEdgeNames property names that hold the edges of a graph
 * @param rareBelow a field present in fewer than this share of its records is listed as rare
 * @param longTextChars text this long, or with a line break, is prose
 * @param pruneShare auto-design drops or demotes a panel that is empty or errors for more than this share of the samples
 * @param stripMax most figures in a drafted strip
 * @param maxPanels most panels auto-design puts in the main column
 * @param maxSidePanels most panels it puts in the right column (besides the links panel)
 * @param lineMinPoints dated rows shorter than this are shown as a ladder, not a line
 * @param maxAlternatives runner-up kinds kept per drafted panel
 * @param limitNames property names that hold a limit for a sibling measure (a gauge's maximum)
 */
@ConfigurationProperties("drishti.builder")
public record BuilderProperties(Integer maxSamples, Integer maxFileMb, Integer maxTotalMb, Integer maxDepth, Integer enumMaxDistinct,
        Integer enumMinSeen, Integer examples, List<String> statusWords, String statusNames, String idNames, List<String> labelNames,
        List<String> ohlcNames, List<String> graphNodeNames, List<String> graphEdgeNames, Integer longTextChars, Double rareBelow, Double pruneShare, Integer stripMax,
        Integer maxPanels, Integer maxSidePanels, Integer lineMinPoints, Integer maxAlternatives, List<String> limitNames) {

    public BuilderProperties {
        maxSamples = positive(maxSamples, 50);
        maxFileMb = positive(maxFileMb, 5);
        maxTotalMb = positive(maxTotalMb, 25);
        maxDepth = positive(maxDepth, 64);
        enumMaxDistinct = positive(enumMaxDistinct, 12);
        enumMinSeen = positive(enumMinSeen, 3);
        examples = positive(examples, 3);
        longTextChars = positive(longTextChars, 80);
        rareBelow = rareBelow == null || rareBelow <= 0 || rareBelow > 1 ? 0.5 : rareBelow;
        pruneShare = pruneShare == null || pruneShare < 0 || pruneShare > 1 ? 0.5 : pruneShare;
        stripMax = positive(stripMax, 6);
        maxPanels = positive(maxPanels, 16);
        maxSidePanels = positive(maxSidePanels, 4);
        lineMinPoints = positive(lineMinPoints, 5);
        maxAlternatives = positive(maxAlternatives, 2);
        limitNames = list(limitNames, "limit", "max", "cap", "threshold", "budget", "capacity");
        statusWords = list(statusWords, "live", "settled", "failed", "pending", "confirmed", "cleared", "scheduled", "paid", "done",
                "booked", "active", "inactive", "open", "closed", "cancelled", "matured", "approved", "rejected", "ok", "error");
        statusNames = statusNames == null || statusNames.isBlank() ? "(status|state|stage)$" : statusNames;
        idNames = idNames == null || idNames.isBlank() ? "(^id$|[a-z0-9]Id$|^code$|(^|_)ref$|Ref$|^uuid$|^key$)" : idNames;
        labelNames = list(labelNames, "label", "name", "title", "event", "step", "description", "text", "note", "category");
        ohlcNames = list(ohlcNames, "open", "high", "low", "close");
        graphNodeNames = list(graphNodeNames, "nodes", "vertices");
        graphEdgeNames = list(graphEdgeNames, "edges", "links");
    }

    /** The defaults, for code and tests that run without Spring. */
    public static BuilderProperties defaults() {
        return new BuilderProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public long maxFileBytes() {
        return maxFileMb * 1024L * 1024L;
    }

    public long maxTotalBytes() {
        return maxTotalMb * 1024L * 1024L;
    }

    private static Integer positive(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }

    private static List<String> list(List<String> v, String... fallback) {
        return v == null || v.isEmpty() ? List.of(fallback) : List.copyOf(v);
    }
}
