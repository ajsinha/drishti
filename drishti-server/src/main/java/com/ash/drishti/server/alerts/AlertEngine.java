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
package com.ash.drishti.server.alerts;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates every user's alert rules on every tick of the entities they watch, whether or not anyone has a
 * browser open. A rule fires on the transition from false to true and re-arms when it turns false again,
 * so a condition that stays true alerts once. Rules are compiled when saved; events are kept per user
 * (the most recent 200) and pushed to that user's open streams.
 */
public final class AlertEngine implements AutoCloseable {

    static final String NS = "alerts";
    static final Set<String> SEVERITIES = Set.of("info", "warn", "critical");
    private static final Logger LOG = LoggerFactory.getLogger(AlertEngine.class);
    private static final int KEEP = 200;

    private final PreferenceStore store;
    private final TopicHub hub;
    private final SourceRouter router;
    private final ElCompiler el;
    private final Formats formats;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, List<AlertRule>> rulesByUser = new ConcurrentHashMap<>();
    private final Map<EntityRef, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> state = new ConcurrentHashMap<>();
    private final Map<String, Deque<AlertEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<AlertEvent>>> listeners = new ConcurrentHashMap<>();

    public AlertEngine(PreferenceStore store, TopicHub hub, SourceRouter router, ElCompiler el, Formats formats) {
        this.store = store;
        this.hub = hub;
        this.router = router;
        this.el = el;
        this.formats = formats;
        for (String user : store.users()) {
            rulesByUser.put(user, read(user));
        }
        resubscribe();
    }

    // ---- rules ---------------------------------------------------------------------------------

    public List<AlertRule> rules(String user) {
        return rulesByUser.getOrDefault(user, List.of());
    }

    public AlertRule save(String user, AlertRule rule) {
        if (!SEVERITIES.contains(rule.severity())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "severity must be one of " + SEVERITIES);
        }
        try {
            el.compile(rule.when());
            if (rule.message() != null) {
                el.template(rule.message());
            }
        } catch (ElException e) {
            throw new DrishtiException(ErrorCode.EL_SYNTAX, "alert expression: " + e.getMessage());
        }
        ObjectNode n = json.createObjectNode();
        n.putObject("ref").put("kind", rule.ref().kind()).put("id", rule.ref().id());
        n.put("when", rule.when()).put("severity", rule.severity()).put("message", rule.message() == null ? "" : rule.message())
                .put("enabled", rule.enabled());
        store.put(user, NS, rule.name(), n);
        state.remove(user + "\u0000" + rule.name());
        rulesByUser.put(user, read(user));
        resubscribe();
        router.fetch(rule.ref()).thenAccept(this::evaluate);
        return rule;
    }

    public boolean delete(String user, String name) {
        boolean removed = store.delete(user, NS, name);
        rulesByUser.put(user, read(user));
        resubscribe();
        return removed;
    }

    private List<AlertRule> read(String user) {
        List<AlertRule> out = new ArrayList<>();
        for (String name : store.keys(user, NS)) {
            store.get(user, NS, name).ifPresent(n -> out.add(new AlertRule(name,
                    EntityRef.of(n.path("ref").path("kind").asText(), n.path("ref").path("id").asText()), n.path("when").asText(),
                    n.path("severity").asText("warn"), n.path("message").asText(""), n.path("enabled").asBoolean(true))));
        }
        return out;
    }

    /** Subscribes to every entity some enabled rule watches, and drops subscriptions nobody needs. */
    private synchronized void resubscribe() {
        Set<EntityRef> wanted = ConcurrentHashMap.newKeySet();
        rulesByUser.values().forEach(rs -> rs.stream().filter(AlertRule::enabled).forEach(r -> wanted.add(r.ref())));
        subscriptions.keySet().removeIf(ref -> {
            if (!wanted.contains(ref)) {
                subscriptions.get(ref).close();
                return true;
            }
            return false;
        });
        for (EntityRef ref : wanted) {
            subscriptions.computeIfAbsent(ref, r -> hub.subscribe(r, this::evaluate));
        }
    }

    // ---- evaluation ----------------------------------------------------------------------------

    void evaluate(EntityDocument doc) {
        EvalContext ctx = EvalContext.of(doc.data(), formats);
        rulesByUser.forEach((user, rules) -> {
            for (AlertRule r : rules) {
                if (!r.enabled() || !r.ref().equals(doc.ref())) {
                    continue;
                }
                boolean now;
                try {
                    now = Values.truthy(el.compile(r.when()).eval(ctx));
                } catch (RuntimeException e) {
                    LOG.debug("alert {} of {} could not evaluate: {}", r.name(), user, e.getMessage());
                    continue;
                }
                Boolean before = state.put(user + "\u0000" + r.name(), now);
                if (now && !Boolean.TRUE.equals(before)) {
                    String msg = r.message() == null || r.message().isBlank() ? r.name() + ": " + r.when() : el.template(r.message()).render(ctx);
                    fire(new AlertEvent(seq.incrementAndGet(), Instant.now(), user, r.name(), doc.ref().kind(), doc.ref().id(),
                            r.severity(), msg, doc.provenance().generation()));
                }
            }
        });
    }

    private void fire(AlertEvent e) {
        Deque<AlertEvent> d = events.computeIfAbsent(e.user(), u -> new ArrayDeque<>());
        synchronized (d) {
            d.addFirst(e);
            while (d.size() > KEEP) {
                d.removeLast();
            }
        }
        listeners.getOrDefault(e.user(), List.of()).forEach(l -> {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // a closed stream is removed by its own writer
            }
        });
    }

    public List<AlertEvent> events(String user, int limit) {
        Deque<AlertEvent> d = events.get(user);
        if (d == null) {
            return List.of();
        }
        synchronized (d) {
            return d.stream().limit(Math.max(1, limit)).toList();
        }
    }

    /** Receives this user's alerts as they fire, until the returned handle is closed. */
    public Subscription listen(String user, Consumer<AlertEvent> l) {
        List<Consumer<AlertEvent>> ls = listeners.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>());
        ls.add(l);
        return () -> ls.remove(l);
    }

    /** Suggested rules for a kind, from the enabled packs ({@code alerts:} in pack.yaml). */
    public static List<Map<String, Object>> suggestions(List<Map<String, Object>> fromPacks, String kind) {
        return fromPacks.stream().filter(s -> kind.equals(s.get("kind"))).toList();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("rules", rulesByUser.values().stream().mapToInt(List::size).sum());
        m.put("watched", subscriptions.size());
        m.put("fired", seq.get());
        return m;
    }


    @Override
    public void close() {
        subscriptions.values().forEach(Subscription::close);
    }

}
