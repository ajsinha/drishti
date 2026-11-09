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
package com.ash.drishti.server.explain;

import com.ash.drishti.kernel.api.explain.ExplainRequest;
import com.ash.drishti.kernel.api.explain.Explainer;
import com.ash.drishti.kernel.api.explain.Explanation;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.UnderConstruction;
import com.ash.drishti.server.engines.EngineOffException;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * One Explain for every number (docs/architecture/SHARED_SCREENS.md section 3; USER_GUIDE.md, "Explain"): the registered
 * {@link Explainer}s by kind, asked as the person. The core knows no kind: each engine registers its own (banking's VaR, ES, parametric
 * and exposure explainers live with it), and a kind whose engine is off is refused in words ({@code DRS-1020}), never half-answered.
 * A part an explainer has not built yet carries its under-construction registry entry (what, and the release that builds it).
 */
public final class NumberExplainService {

    /** The registry ids of the Explain work not built yet start with this (config/under-construction.yaml). */
    public static final String PLANNED = "explain-";

    private final Map<String, Explainer> byKind = new TreeMap<>();
    private final ObjectProvider<EngineRegistry> engines;
    private final ObjectProvider<UnderConstruction> registry;

    public NumberExplainService(List<Explainer> explainers, ObjectProvider<EngineRegistry> engines, ObjectProvider<UnderConstruction> registry) {
        for (Explainer e : explainers) {
            if (byKind.put(e.kind(), e) != null) {
                throw new IllegalStateException("two explainers of the kind " + e.kind());
            }
        }
        this.engines = engines;
        this.registry = registry;
    }

    /** The kinds that can be explained here (their engines running), their measures, and the Explain work registered as not built. */
    public Map<String, Object> kinds() {
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (Explainer e : byKind.values()) {
            if (!running(e)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.kind());
            m.put("title", e.title());
            m.put("measures", e.measures());
            m.put("engine", e.engine());
            kinds.add(m);
        }
        List<Map<String, Object>> planned = new ArrayList<>();
        UnderConstruction uc = registry.getIfAvailable();
        if (uc != null) {
            for (UnderConstruction.Item i : uc.items()) {
                if (i.id().startsWith(PLANNED)) {
                    planned.add(item(i));
                }
            }
        }
        return Map.of("kinds", kinds, "underConstruction", planned);
    }

    /** The explanation of one number, as the person; the parts asked for ({@link ExplainRequest#part()}). */
    public Map<String, Object> explain(Principal p, ExplainRequest r) {
        if (r.kind().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give the kind of number: kind=" + String.join(", kind=", byKind.keySet()));
        }
        Explainer e = byKind.get(r.kind());
        if (e == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no explainer of the kind " + r.kind() + " on this server (the kinds: "
                    + String.join(", ", byKind.keySet()) + ")");
        }
        if (!running(e)) {
            EngineRegistry reg = engines.getIfAvailable();
            throw new EngineOffException(e.engine(), reg == null ? e.engine() : reg.title(e.engine()));
        }
        if (r.measure() != null && !e.measures().contains(r.measure())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the " + r.kind() + " explainer explains " + String.join(", ", e.measures())
                    + " (not " + r.measure() + ")");
        }
        Explanation x = e.explain(p, r);
        Map<String, Object> out = x.toMap(r);
        x.unbuilt().forEach((part, id) -> {
            if (out.get(part) instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) m);
                copy.put("underConstruction", entry(id));
                out.put(part, copy);
            } else if (!r.part().equals("preview")) {
                out.computeIfAbsent("underConstruction", k -> new ArrayList<Map<String, Object>>());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> l = (List<Map<String, Object>>) out.get("underConstruction");
                Map<String, Object> m = new LinkedHashMap<>(entry(id));
                m.put("part", part);
                l.add(m);
            }
        });
        return out;
    }

    private boolean running(Explainer e) {
        if (e.engine() == null) {
            return true;
        }
        EngineRegistry reg = engines.getIfAvailable();
        return reg == null || !reg.known(e.engine()) || reg.running(e.engine());
    }

    private Map<String, Object> entry(String id) {
        UnderConstruction uc = registry.getIfAvailable();
        if (uc != null) {
            for (UnderConstruction.Item i : uc.items()) {
                if (i.id().equals(id)) {
                    return item(i);
                }
            }
        }
        return Map.of("id", id, "text", UnderConstruction.TEXT);
    }

    private static Map<String, Object> item(UnderConstruction.Item i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("text", UnderConstruction.TEXT);
        m.put("about", i.about());
        m.put("release", i.release());
        return m;
    }
}
