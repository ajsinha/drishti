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
package com.ash.drishti.server.engines;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every compute engine of this node and its state (docs/architecture/COMPUTE_ENGINES.md sections 5 to 8): the engines the beans register
 * ({@link ComputeEngine}), each matched to its catalogue entry; started at start in the order of their needs (one whose need is off is
 * {@code blocked}, DRS-1022, and nothing else fails); turned on and off live with the dependencies enforced (DRS-1021), an off draining the
 * requests in flight (up to {@code drishti.engines.drain}) before it releases what it holds; the change written to the engine's file, kept in
 * History and the audit log. The in-memory lake turned on says "restart needed" (its direct memory is sized at start).
 */
public final class EngineRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EngineRegistry.class);

    /** An engine's state on this node. */
    public enum State {
        RUNNING("running"), OFF("off"), STARTING("starting"), STOPPING("stopping"), BLOCKED("blocked"), NOT_INSTALLED("not installed");

        private final String word;

        State(String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }
    }

    /** One change asked for: the engine and on or off. */
    public record Change(String engine, boolean on) {}

    /** The outcome of one change: {@code live}, {@code restart needed}, {@code written} (an override above the file decides), {@code unchanged}. */
    public record Outcome(String engine, boolean on, String outcome, String detail) {}

    private final EngineCatalogue catalogue;
    private final EngineSwitches switches;
    private final EngineFiles files;
    private final EngineHistory history;
    private final AuditLog audit;
    private final Supplier<String> node;
    private final Duration drain;
    private final Map<String, ComputeEngine> engines = new LinkedHashMap<>();
    private final Map<String, EngineCatalogue.Entry> entries = new LinkedHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, String> reasons = new ConcurrentHashMap<>();
    private final Map<String, Boolean> desired = new ConcurrentHashMap<>();
    private final Map<String, String> restart = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<String, String> sums = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object changing = new Object();
    private volatile Thread watcher;

    public EngineRegistry(EngineCatalogue catalogue, EngineSwitches switches, EngineFiles files, EngineHistory history, AuditLog audit,
            Supplier<String> node, Duration drain, List<ComputeEngine> found) {
        this.catalogue = catalogue;
        this.switches = switches;
        this.files = files;
        this.history = history;
        this.audit = audit;
        this.node = node;
        this.drain = drain;
        for (ComputeEngine e : found) {
            engines.put(e.id(), e);
        }
        catalogue.all().forEach(e -> entries.put(e.id(), e));
        for (ComputeEngine e : found) {
            // an engine whose build has not added its catalogue entry yet still runs and is listed: no needs, nothing hidden by name
            entries.computeIfAbsent(e.id(), id -> new EngineCatalogue.Entry(id, id, "", id + ".yaml", "drishti.engines." + id + ".enabled", "", List.of(),
                    true, true, true, Map.of()));
        }
        entries.keySet().forEach(id -> inFlight.put(id, new AtomicInteger()));
        switches.warnings().forEach(w -> LOG.warn("{}", w));
        startAll();
    }

    /** The engines in the order their needs allow (a need before the engine that needs it). */
    List<String> order() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : entries.keySet()) {
            visit(id, seen, out);
        }
        return out;
    }

    private void visit(String id, Set<String> seen, List<String> out) {
        if (!seen.add(id)) {
            return;
        }
        for (String n : entry(id).needs()) {
            visit(n, seen, out);
        }
        out.add(id);
    }

    private void startAll() {
        for (String id : order()) {
            ComputeEngine eng = engines.get(id);
            if (eng == null) {
                states.put(id, State.NOT_INSTALLED);
                desired.put(id, false);
                continue;
            }
            boolean on = switches.of(id).on();
            desired.put(id, on);
            sums.put(id, files.checksum(entry(id)));
            if (!on) {
                states.put(id, State.OFF);
                eng.running(false);
                continue;
            }
            List<String> missing = entry(id).needs().stream().filter(n -> states.get(n) != State.RUNNING).toList();
            if (!missing.isEmpty()) {
                block(id, missing);
                continue;
            }
            start(id, eng);
        }
        String off = states.entrySet().stream().filter(e -> e.getValue() != State.RUNNING && e.getValue() != State.NOT_INSTALLED)
                .map(e -> e.getKey() + " " + e.getValue().word()).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
        LOG.info("engines: {} running here ({}); not running: {}", running().size(), String.join(", ", running()), off);
    }

    private void block(String id, List<String> missing) {
        String why = title(id) + " needs " + titles(missing) + " on this server, which " + (missing.size() == 1 ? "is" : "are") + " off";
        states.put(id, State.BLOCKED);
        reasons.put(id, why);
        engines.get(id).running(false);
        LOG.warn("engines: {} is not started: {} (DRS-1022); turn {} on, or {} off, in config/engines/", id, why, String.join(" and ", missing), id);
    }

    private void start(String id, ComputeEngine eng) {
        states.put(id, State.STARTING);
        try {
            eng.start();
            eng.running(true);
            states.put(id, State.RUNNING);
            reasons.remove(id);
        } catch (RuntimeException e) {
            eng.running(false);
            states.put(id, State.BLOCKED);
            reasons.put(id, "did not start: " + e.getMessage());
            LOG.warn("engines: {} did not start: {}", id, e.toString());
        }
    }

    /** Off: no new request is taken (404 or routed), those in flight finish within the drain, then the engine releases what it holds. */
    private void stop(String id, ComputeEngine eng) {
        states.put(id, State.STOPPING);
        eng.running(false);
        long until = System.nanoTime() + drain.toNanos();
        AtomicInteger n = inFlight.get(id);
        while (n != null && n.get() > 0 && System.nanoTime() < until) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (n != null && n.get() > 0) {
            LOG.warn("engines: {} stopped with {} request(s) still in flight after {} (they are cancelled with a clear message)", id, n.get(), drain);
        }
        try {
            eng.stop();
        } catch (RuntimeException e) {
            LOG.warn("engines: {} did not release everything: {}", id, e.toString());
        }
        states.put(id, State.OFF);
    }

    // ------------------------------------------------------------------------------------------------------------- what others ask

    public EngineCatalogue catalogue() {
        return catalogue;
    }

    public EngineCatalogue.Entry entry(String id) {
        EngineCatalogue.Entry e = entries.get(id);
        if (e == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no engine '" + id + "'; the engines: " + entries.keySet());
        }
        return e;
    }

    public List<EngineCatalogue.Entry> entries() {
        return List.copyOf(entries.values());
    }

    public boolean known(String id) {
        return entries.containsKey(id);
    }

    public State state(String id) {
        return states.getOrDefault(id, State.NOT_INSTALLED);
    }

    public boolean running(String id) {
        return states.get(id) == State.RUNNING;
    }

    /** The ids of the engines running here. */
    public List<String> running() {
        return order().stream().filter(this::running).toList();
    }

    public String reason(String id) {
        return reasons.getOrDefault(id, "");
    }

    public String restartNeeded(String id) {
        return restart.getOrDefault(id, "");
    }

    public boolean desired(String id) {
        return desired.getOrDefault(id, false);
    }

    public ComputeEngine engine(String id) {
        return engines.get(id);
    }

    public EngineSwitches.Switch switchOf(String id) {
        return switches.of(id);
    }

    public EngineFiles files() {
        return files;
    }

    public EngineHistory history() {
        return history;
    }

    public String node() {
        return node.get();
    }

    public String title(String id) {
        return entries.containsKey(id) ? entries.get(id).title() : id;
    }

    String titles(List<String> ids) {
        List<String> t = ids.stream().map(this::title).toList();
        return t.size() <= 1 ? String.join("", t) : String.join(", ", t.subList(0, t.size() - 1)) + " and " + t.get(t.size() - 1);
    }

    /** Told after any engine changed state (the digest, the console's about). */
    public void onChange(Runnable r) {
        listeners.add(r);
    }

    /**
     * A request for the engine's API begins: counted for the drain. The caller closes it when the answer is written. Throws
     * {@link EngineOffException} when the engine does not run here (the filter routes it to another node first).
     */
    public AutoCloseable enter(String id) {
        AtomicInteger n = inFlight.get(id);
        if (n == null || !running(id)) {
            throw new EngineOffException(id, title(id));
        }
        n.incrementAndGet();
        if (!running(id)) {                                            // turned off between the check and the count
            n.decrementAndGet();
            throw new EngineOffException(id, title(id));
        }
        return n::decrementAndGet;
    }

    public int inFlight(String id) {
        AtomicInteger n = inFlight.get(id);
        return n == null ? 0 : n.get();
    }

    // ------------------------------------------------------------------------------------------------------------- changes

    /**
     * Applies a batch of changes with one reason, checked as a whole for the dependencies (DRS-1021: nothing is changed when one is
     * refused). {@code withNeeds} adds the engines the ones turned on need; {@code withDependents} adds the running engines that need the
     * ones turned off. Each change is written to its file ({@code expected}: the checksums the page read, refused with DRS-1023 when a file
     * changed since), then takes effect: live, or "restart needed" (the in-memory lake on), or "written" when an override above the file
     * decides (the environment, the local file, an old name).
     */
    public List<Outcome> apply(List<Change> asked, String reason, String actor, boolean withNeeds, boolean withDependents, Map<String, String> expected) {
        if (reason == null || reason.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a reason: it is kept in the audit log and History");
        }
        synchronized (changing) {
            Map<String, Boolean> want = expand(asked, withNeeds, withDependents);
            refuseUnsafe(want, reason, actor);
            List<Outcome> out = new ArrayList<>();
            List<String> offs = new ArrayList<>(order());
            java.util.Collections.reverse(offs);
            for (String id : offs) {                                    // offs first, the engines that need others before those they need
                if (Boolean.FALSE.equals(want.get(id))) {
                    out.add(change(id, false, reason, actor, expected));
                }
            }
            for (String id : order()) {
                if (Boolean.TRUE.equals(want.get(id))) {
                    out.add(change(id, true, reason, actor, expected));
                }
            }
            listeners.forEach(Runnable::run);
            return out;
        }
    }

    private Map<String, Boolean> expand(List<Change> asked, boolean withNeeds, boolean withDependents) {
        Map<String, Boolean> want = new LinkedHashMap<>();
        for (Change c : asked) {
            EngineCatalogue.Entry e = entry(c.engine());
            if (!engines.containsKey(e.id())) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, e.title() + " is not installed in this build: it cannot be turned on or off yet");
            }
            want.put(e.id(), c.on());
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, Boolean> w : new ArrayList<>(want.entrySet())) {
                if (w.getValue() && withNeeds) {
                    for (String n : entry(w.getKey()).needs()) {
                        if (!want.containsKey(n) && !running(n) && engines.containsKey(n)) {
                            want.put(n, true);
                            grew = true;
                        }
                    }
                }
                if (!w.getValue() && withDependents) {
                    for (String d : catalogue.neededBy(w.getKey())) {
                        if (!want.containsKey(d) && (running(d) || desired(d))) {
                            want.put(d, false);
                            grew = true;
                        }
                    }
                }
            }
        }
        return want;
    }

    /** The batch refused, whole, when an engine turned on lacks a need or one turned off is needed by an engine that stays on. */
    private void refuseUnsafe(Map<String, Boolean> want, String reason, String actor) {
        Set<String> on = new LinkedHashSet<>();
        for (String id : entries.keySet()) {
            boolean now = running(id) || state(id) == State.STARTING || (desired(id) && state(id) == State.BLOCKED);
            if (want.getOrDefault(id, now)) {
                on.add(id);
            }
        }
        for (Map.Entry<String, Boolean> w : want.entrySet()) {
            String id = w.getKey();
            if (w.getValue()) {
                List<String> missing = entry(id).needs().stream().filter(n -> !on.contains(n)).toList();
                if (!missing.isEmpty()) {
                    refuse(want, reason, actor, title(id) + " needs " + titles(missing) + " on this server" + because(id) + ". Turn on "
                            + titles(missing) + " first, or " + (missing.size() == 1 ? "both" : "all of them") + " together.");
                }
            } else {
                List<String> users = catalogue.neededBy(id).stream().filter(on::contains).toList();
                if (!users.isEmpty()) {
                    int n = users.size() + 1;
                    refuse(want, reason, actor, title(id) + " is needed by " + titles(users) + " on this server. Turn "
                            + (users.size() == 1 ? "it" : "them") + " off first, or " + (n == 2 ? "both" : "all " + words(n)) + " together.");
                }
            }
        }
    }

    private static String because(String id) {
        return switch (id) {
            case "frtb-sa" -> ": its scopes (desks, books, limit letters) are VaR's and its inputs are the position sensitivities";
            case "fva" -> ": it reads the EE and ENE profile";
            case "ccr-cube" -> ": it reads the netting sets' path matrices";
            case "simm" -> ": it reads the position sensitivities and the factor dictionary";
            default -> "";
        };
    }

    private static String words(int n) {
        return switch (n) {
            case 3 -> "three";
            case 4 -> "four";
            case 5 -> "five";
            default -> String.valueOf(n);
        };
    }

    private void refuse(Map<String, Boolean> want, String reason, String actor, String words) {
        String at = Instant.now().toString();
        for (Map.Entry<String, Boolean> w : want.entrySet()) {
            history.add(new EngineHistory.Change(at, w.getKey(), w.getValue() ? "on" : "off", actor, reason, node.get(), "refused", words));
            if (audit != null) {
                audit.record(actor, "engine-refused", "engine/" + w.getKey(), (w.getValue() ? "on" : "off") + ": " + words + " (reason: " + reason + ")");
            }
        }
        throw new DrishtiException(ErrorCode.ENGINE_DEPENDENCY, words);
    }

    private Outcome change(String id, boolean on, String reason, String actor, Map<String, String> expected) {
        EngineCatalogue.Entry e = entry(id);
        ComputeEngine eng = engines.get(id);
        boolean now = running(id) || (!e.liveOn() && desired(id));
        if (now == on && desired(id) == on) {
            return new Outcome(id, on, "unchanged", title(id) + " is already " + (on ? "on" : "off") + " on this server");
        }
        String at = Instant.now().toString();
        String[] sum = files.write(e, on, actor, at, reason, expected == null ? null : expected.get(id));
        sums.put(id, sum[1]);
        desired.put(id, on);
        String outcome;
        String detail;
        EngineSwitches.Switch sw = switches.of(id);
        if (sw.overridden()) {
            outcome = "written";
            detail = "written to " + files.file(e) + ", but " + sw.source() + " still decides until it is removed";
        } else {
            String[] r = effect(id, e, eng, on, at, actor);
            outcome = r[0];
            detail = r[1];
        }
        history.add(new EngineHistory.Change(at, id, on ? "on" : "off", actor, reason, node.get(), outcome, detail));
        if (audit != null) {
            audit.record(actor, on ? "engine-enabled" : "engine-disabled", "engine/" + id, reason + " | node " + node.get() + " | " + outcome + " | file "
                    + files.file(e) + " sha " + (sum[0].isEmpty() ? "(none)" : sum[0]) + " -> " + sum[1]);
        }
        return new Outcome(id, on, outcome, detail);
    }

    /** The change takes effect: live, or "restart needed" for an engine that cannot come on live. */
    private String[] effect(String id, EngineCatalogue.Entry e, ComputeEngine eng, boolean on, String at, String actor) {
        if (on && !e.liveOn()) {
            restart.put(id, "turned on at " + at + (actor == null || actor.isBlank() ? "" : " by " + actor));
            return new String[] {"restart needed", "written to " + files.file(e) + "; it starts at the next restart"};
        }
        if (!on && !e.liveOn()) {
            restart.remove(id);
        }
        if (on) {
            List<String> missing = e.needs().stream().filter(n -> !running(n)).toList();
            if (!missing.isEmpty()) {
                block(id, missing);
                return new String[] {"blocked", reason(id)};
            }
            start(id, eng);
            return running(id) ? new String[] {"live", title(id) + " is running"} : new String[] {"blocked", reason(id)};
        }
        long held = eng.memoryBytes();
        int flying = inFlight(id);
        stop(id, eng);
        return new String[] {"live", flying + " request(s) in flight finished; " + (held >> 20) + " MB released"};
    }

    // ------------------------------------------------------------------------------------------------------------- the files watched

    /** Watches the engines' files: an edit by hand takes effect as an edit from the page, and shows in History as "edited in the file". */
    public void watch(Duration every) {
        if (every.isZero() || every.isNegative() || watcher != null) {
            return;
        }
        Thread t = Thread.ofVirtual().name("drishti-engines-watch").unstarted(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(every.toMillis());
                    scan();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    LOG.warn("engines: watching the files failed: {}", e.toString());
                }
            }
        });
        watcher = t;
        t.start();
    }

    /** One look at every engine file (the watcher's round; a test calls it directly). */
    public void scan() {
        for (EngineCatalogue.Entry e : entries()) {
            String now = files.checksum(e);
            String before = sums.get(e.id());
            if (before == null || now.equals(before)) {
                continue;
            }
            synchronized (changing) {
                sums.put(e.id(), now);
                Boolean on = files.enabledInFile(e);
                if (on == null || on == desired(e.id()) || !engines.containsKey(e.id()) || switches.of(e.id()).overridden()) {
                    continue;
                }
                String at = Instant.now().toString();
                try {
                    Map<String, Boolean> want = Map.of(e.id(), on);
                    refuseUnsafe(want, "edited in the file", "");
                } catch (DrishtiException refused) {
                    continue;                                            // in History as refused, with the words
                }
                desired.put(e.id(), on);
                String[] r = effect(e.id(), e, engines.get(e.id()), on, at, "");
                history.add(new EngineHistory.Change(at, e.id(), "edited in the file (" + (on ? "on" : "off") + ")", "", "", node.get(), r[0], r[1]));
                if (audit != null) {
                    audit.record("", on ? "engine-enabled" : "engine-disabled", "engine/" + e.id(), "edited in the file " + files.file(e) + " | " + r[0]);
                }
                listeners.forEach(Runnable::run);
            }
        }
    }

    @Override
    public void close() {
        Thread t = watcher;
        if (t != null) {
            t.interrupt();
        }
    }
}
