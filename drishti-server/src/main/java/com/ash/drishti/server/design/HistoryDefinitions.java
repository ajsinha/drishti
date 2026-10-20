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
package com.ash.drishti.server.design;

import com.ash.drishti.engine.history.HistoryProperties;
import com.ash.drishti.engine.history.HistorySpec;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code history:} entries the packs declare in {@code pack.yaml} (HISTORY_QUERIES.md section 6), read as {@link FormDefinitions}
 * reads {@code form:}. A kind with no entry has the defaults; an entry with errors is logged once (and refused by the deploy check and
 * {@code sutra lint}, {@code DRS-2061}) and the defaults are used, so a typo never takes a kind's history away.
 */
@Component
public class HistoryDefinitions {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryDefinitions.class);

    private final PackRegistry registry;
    private final HistoryProperties props;
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    public HistoryDefinitions(PackRegistry registry, HistoryProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** The history spec of a kind: its pack's entry, else the defaults. */
    public HistorySpec of(String kind) {
        for (Pack p : registry.packs()) {
            if (p.manifest().get("history") instanceof Map<?, ?> m && m.containsKey(kind)) {
                List<String> problems = new ArrayList<>();
                HistorySpec s = HistorySpec.parse(kind, m.get(kind), props.limits().maxDays(), problems);
                if (!problems.isEmpty() && logged.add(p.name() + "/" + kind + "/" + problems)) {
                    LOG.warn("pack {}: {}", p.name(), String.join("; ", problems));
                }
                return HistorySpec.hasErrors(problems) ? HistorySpec.defaults(kind) : s;
            }
        }
        return HistorySpec.defaults(kind);
    }

    /**
     * The problems of a pack manifest's {@code history:} block: each {@code "error: ..."} or {@code "warning: ..."}. A kind the pack does
     * not declare is a warning. Used by the deploy check and {@code sutra lint}.
     */
    public static List<String> check(Map<String, Object> manifest, int maxDays) {
        List<String> problems = new ArrayList<>();
        Object raw = manifest.get("history");
        if (raw == null) {
            return problems;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add("error: history: expected a map of kind to settings");
            return problems;
        }
        List<String> kinds = manifest.get("kinds") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        m.forEach((k, v) -> {
            String kind = String.valueOf(k);
            if (!kinds.isEmpty() && !kinds.contains(kind)) {
                problems.add("warning: history." + kind + ": the pack declares no kind " + kind);
            }
            HistorySpec.parse(kind, v, maxDays, problems);
        });
        return problems;
    }
}
