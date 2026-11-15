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
package com.ash.drishti.server.live;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.live.FeedState;
import com.ash.drishti.live.LiveSource;
import com.ash.drishti.packs.Companions;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.server.connectors.ConnectorManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Every live feed as Health, Admin and the CLI show it (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 7.9 and 9.1): one row per
 * live connector (a {@code kafka}, {@code activemq} or {@code rabbitmq} connector, a companion or not), its state in the one list of
 * words ({@link FeedState}), its rate, counts, rows, the last message, the last reconcile in a line, its window when it lands; and the
 * administrator's pause, resume and apply now, each audited. Off, not configured and failed come from the connector's status; the rest
 * from the kernel's live source.
 */
@Component
public final class LiveFeeds {

    private final SourceRegistry registry;
    private final ConnectorManager connectors;
    private final ObjectProvider<com.ash.drishti.identity.AuditLog> audit;
    private final double rejectRatio;

    public LiveFeeds(SourceRegistry registry, ConnectorManager connectors, ObjectProvider<com.ash.drishti.identity.AuditLog> audit, Environment env,
            ObjectProvider<com.ash.drishti.server.metrics.PlatformMetrics> metrics) {
        this.registry = registry;
        metrics.ifAvailable(m -> m.liveFeeds(this::list));             // the feeds' gauges and the platform alerts on them
        this.connectors = connectors;
        this.audit = audit;
        this.rejectRatio = env.getProperty("drishti.live.degraded.reject-ratio", Double.class, 0.05);
    }

    /** Every live feed, by connector name. */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, SourceRegistry.ConnectorStatus> statuses = registry.connectorStatuses();
        for (String name : new java.util.TreeSet<>(connectors.names())) {
            connectors.effective(name).filter(d -> Companions.TRANSPORTS.contains(d.plugin()))
                    .ifPresent(d -> out.add(row(d, statuses.get(name))));
        }
        return out;
    }

    /** One feed with its detail: the row, each kind's rules in words, the newest rejects, how it flows. Empty: no such live connector. */
    public Optional<Map<String, Object>> detail(String name) {
        return connectors.effective(name).filter(d -> Companions.TRANSPORTS.contains(d.plugin())).map(d -> {
            Map<String, Object> m = row(d, registry.connectorStatuses().get(name));
            Map<String, Object> rules = new TreeMap<>();
            for (Object k : (List<?>) m.get("kinds")) {
                rules.put(String.valueOf(k), registry.liveRules(String.valueOf(k)).inWords());
            }
            m.put("rules", rules);
            registry.live(name).map(LiveSource::feed).ifPresent(f -> {
                m.put("rejects", f.rejects() == null ? List.of() : f.rejects().newest().stream().map(Object::toString).toList());
                m.put("flow", List.of(d.plugin() + (d.companionOf() == null ? "" : " (the companion of " + d.companionOf() + ")"),
                        "the contract's checks", "the live state" + (registry.live(name).map(LiveSource::replayable).orElse(true) ? "" : " on disk"),
                        "the next lake load absorbs it"));
            });
            return m;
        });
    }

    /** The rows of the feeds that serve {@code kind}, for the bindings' Live column. */
    public List<Map<String, Object>> ofKind(String kind) {
        return list().stream().filter(r -> ((List<?>) r.get("kinds")).contains(kind)).toList();
    }

    private Map<String, Object> row(ConnectorFiles.Definition d, SourceRegistry.ConnectorStatus status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connector", d.name());
        m.put("plugin", d.plugin());
        m.put("companionOf", d.companionOf());
        Optional<LiveSource> live = registry.live(d.name());
        List<String> kinds = live.map(l -> List.copyOf(new java.util.TreeSet<>(l.manifest().kinds()))).orElse(List.copyOf(d.kinds()));
        m.put("kinds", kinds);
        String s = status == null ? null : status.state();
        FeedState state;
        String why = status == null ? null : status.problem();
        if (live.isEmpty() || "DISABLED".equals(s) || "IDLE".equals(s) || "FAILED".equals(s) || "OFFLINE".equals(s)) {
            state = "IDLE".equals(s) ? FeedState.NOT_CONFIGURED : "FAILED".equals(s) ? FeedState.DOWN : FeedState.OFF;
            if ("OFFLINE".equals(s)) {
                why = "offline mode is on";
            }
        } else {
            LiveSource l = live.get();
            state = l.state(registry.freshness(d.name()).stale(), rejectRatio);
            m.put("health", l.health());
            if (l.feed() != null) {
                Map<String, Object> st = l.feed().stats();
                m.put("perMinute", st.get("perMinute"));
                m.put("rows", st.get("rows"));
                m.put("diskMb", st.get("diskMb"));
                m.put("counts", st.get("counts"));
                m.put("lastMessage", st.get("lastMessage"));
                m.put("liveDate", st.get("liveDate"));
                m.put("paused", st.get("paused"));
                m.put("landing", st.get("landing"));
                if (st.get("lastReconcile") instanceof Map<?, ?> rc) {
                    m.put("lastReconcile", rc);
                    m.put("reconcileLine", "load of " + rc.get("load") + " (" + rc.get("kind") + "): " + rc.get("absorbed") + " absorbed, "
                            + rc.get("carried") + " carried, " + rc.get("orphans") + " orphans");
                }
            }
        }
        m.put("state", state.words());
        m.put("attention", state.attention());
        m.put("why", why);
        return m;
    }

    /** Pauses a feed; false when it is not running. */
    public boolean pause(String name, String by) {
        boolean ok = registry.live(name).map(l -> l.pause(by)).orElse(false);
        if (ok) {
            record(by, "live-pause", name, "paused" + (registry.live(name).map(LiveSource::replayable).orElse(false)
                    ? ": messages are skipped and read again at the next start" : ": messages wait at the broker"));
        }
        return ok;
    }

    /** Resumes a paused feed; false when it is not running. */
    public boolean resume(String name, String by) {
        boolean ok = registry.live(name).map(LiveSource::resume).orElse(false);
        if (ok) {
            record(by, "live-resume", name, "resumed");
        }
        return ok;
    }

    /** Lands the feed's waiting window now; its window after, or empty when it lands nothing. */
    public Optional<Map<String, Object>> applyNow(String name, String by) {
        Optional<Map<String, Object>> w = registry.landNow(name);
        w.ifPresent(x -> record(by, "live-apply-now", name, "the waiting window landed at once"));
        return w;
    }

    private void record(String by, String action, String name, String words) {
        com.ash.drishti.identity.AuditLog a = audit.getIfAvailable();
        if (a != null) {
            a.record(by, action, name, words);
        }
    }
}
