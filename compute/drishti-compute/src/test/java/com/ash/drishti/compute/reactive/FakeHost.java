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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.ComputeService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/** A host for tests: documents per kind in memory, a version per kind, a field masked for viewers without the role {@code raw}. */
final class FakeHost implements ReactiveHost {

    final Map<String, Map<String, Map<String, Object>>> data = new ConcurrentHashMap<>();
    final Map<String, String> versions = new ConcurrentHashMap<>();
    final List<String> audits = new CopyOnWriteArrayList<>();
    final Map<String, LongConsumer> live = new ConcurrentHashMap<>();
    volatile ComputeService compute;
    volatile String denyKind;

    void put(String kind, String id, Map<String, Object> doc) {
        data.computeIfAbsent(kind, k -> new ConcurrentHashMap<>()).put(id, doc);
    }

    @Override
    public Optional<Viewer> viewer() {
        return Viewer.current();
    }

    @Override
    public Optional<String> refusal(Viewer viewer, String connector, String kind) {
        return kind.equals(denyKind) ? Optional.of("your role cannot open " + kind) : Optional.empty();
    }

    @Override
    public List<DataNode> read(Viewer viewer, String kind, List<String> ids, AsOf asOf, int limit) {
        Map<String, Map<String, Object>> m = data.getOrDefault(kind, Map.of());
        List<DataNode> out = new ArrayList<>();
        List<String> want = ids == null ? new ArrayList<>(new java.util.TreeSet<>(m.keySet())) : ids;
        for (String id : want) {
            Map<String, Object> d = m.get(id);
            if (d != null && out.size() < limit) {
                Map<String, Object> seen = new LinkedHashMap<>(d);
                if (seen.containsKey("SECRET") && !viewer.roles().contains("raw")) {
                    seen.put("SECRET", "•••");
                }
                out.add(DataNode.of(seen));
            }
        }
        return out;
    }

    @Override
    public Optional<String> dataVersion(String kind, AsOf asOf) {
        return Optional.ofNullable(versions.get(kind));
    }

    @Override
    public LocalDate today() {
        return LocalDate.of(2026, 10, 23);
    }

    @Override
    public ComputeService compute() {
        return compute;
    }

    @Override
    public boolean inProcessJava() {
        return false;
    }

    @Override
    public void audit(String actor, String action, String subject, String detail) {
        audits.add(actor + " " + action + " " + subject);
    }

    @Override
    public Optional<Instant> nextCron(String cron, String zone, Instant after) {
        return Optional.empty();
    }

    @Override
    public AutoCloseable subscribe(String kind, String id, LongConsumer onChange) {
        live.put(kind + "/" + id, onChange);
        return () -> live.remove(kind + "/" + id);
    }
}
