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
package com.ash.drishti.server.sources;

import com.ash.drishti.dataload.ReadyCheck;
import com.ash.drishti.dataload.ReadyRule;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourceSystems;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The trade contract's {@code SOURCE_SYSTEM} check, applied to a file before it is pushed: the allowed values are the systems of the installed
 * source modules ({@link SourceSystems#allowed()}), never a fixed list. It speaks only for the kinds a source system feeds, and says nothing
 * while no source module is installed.
 */
@Component
public class SourceSystemReadyRule implements ReadyRule {

    private static final int LISTED = 5;
    private final ObjectProvider<SourceRouter> router;

    public SourceSystemReadyRule(ObjectProvider<SourceRouter> router) {
        this.router = router;
    }

    @Override
    public List<ReadyCheck> check(Input in) {
        SourceRouter r = router.getIfAvailable();
        return r == null ? List.of() : check(r.registry().systems(), in);
    }

    static List<ReadyCheck> check(SourceSystems systems, Input in) {
        Set<String> allowed = systems.allowed();
        boolean fed = systems.installed().values().stream().anyMatch(s -> s.kinds().contains(in.kind()));
        if (allowed.isEmpty() || !fed) {
            return List.of();
        }
        Set<String> bad = new TreeSet<>();
        int rows = 0;
        for (JsonNode n : in.sample()) {
            JsonNode v = n.get("SOURCE_SYSTEM");
            if (v != null && !v.isNull() && !v.asText().isBlank() && !allowed.contains(v.asText())) {
                bad.add(v.asText());
                rows++;
            }
        }
        if (bad.isEmpty()) {
            return List.of(ReadyCheck.pass("Every sampled SOURCE_SYSTEM is one of the installed source systems."));
        }
        return List.of(ReadyCheck.refused(rows + " of the sampled rows name a source system that is not installed here (" + String.join(", ", bad.stream().limit(LISTED).toList())
                + "); the installed ones are " + String.join(", ", allowed) + ".", "Install the source module of the system, or correct SOURCE_SYSTEM at the source."));
    }
}
