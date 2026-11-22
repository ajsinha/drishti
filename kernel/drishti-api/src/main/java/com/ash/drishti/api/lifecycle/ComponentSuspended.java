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
package com.ash.drishti.api.lifecycle;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code 503 DRS-1037}: a request reached a suspended module, pack or connector. The words name the part, who suspended it and since when;
 * never anything of its data: {@code {"code": "DRS-1037", "detail": "pack market-risk is suspended (by ops at 2026-11-22T10:00:00Z)", ...}}.
 */
public final class ComponentSuspended extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final Lifecycle.Type type;
    private final String name;
    private final String by;
    private final Instant since;
    private final String node;

    public ComponentSuspended(Lifecycle.Type type, String name, String by, Instant since) {
        this(type, name, by, since, null);
    }

    /** As suspended on another node of the cluster ({@code node}): the owner node's suspend, seen by a peer. */
    public ComponentSuspended(Lifecycle.Type type, String name, String by, Instant since, String node) {
        super(ErrorCode.COMPONENT_SUSPENDED, type.word() + " " + name + " is suspended" + (node == null ? "" : " on " + node)
                + (by == null || by.isBlank() ? "" : " (by " + by + (since == null ? "" : " at " + since) + ")")
                + "; it answers again when an administrator resumes it");
        this.type = type;
        this.name = name;
        this.by = by == null ? "" : by;
        this.since = since;
        this.node = node;
    }

    public Lifecycle.Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    /** True when {@code t} or one of its causes is a suspension. */
    public static java.util.Optional<ComponentSuspended> in(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            if (x instanceof ComponentSuspended s) {
                return java.util.Optional.of(s);
            }
        }
        return java.util.Optional.empty();
    }

    /** The extra properties of the problem answer. */
    public Map<String, Object> properties() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", type.word());
        out.put("name", name);
        out.put("suspendedBy", by);
        out.put("since", since == null ? "" : since.toString());
        if (node != null) {
            out.put("node", node);
        }
        return out;
    }
}
