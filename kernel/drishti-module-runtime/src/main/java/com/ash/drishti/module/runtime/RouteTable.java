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
package com.ash.drishti.module.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * The live route table (KERNEL_AND_MODULES.md section 9.8): every module's routes in one immutable {@link Snapshot}, looked up per request
 * without a lock and replaced atomically (copy-on-write) when a module is loaded, switched off or on, or removed. Each entry carries its
 * module, function, permission and state, so a lookup answers: draw it (200), moved (308), not available (404, the module is off) or gone
 * (410, a tombstone of a removed module). Nothing per person is held here. The console's dispatcher for {@code /m/{id}/{path}} and the
 * kernel's API mount ask the same table.
 */
public final class RouteTable {

    /** What a route draws. */
    public enum Kind { PAGE, SUTRA, MOVED, API }

    /** A route's state. */
    public enum State { ACTIVE, OFF, TOMBSTONE }

    /**
     * One route.
     *
     * @param module     the module's id
     * @param version    the module's version
     * @param id         the route's id, unique in the module (what links, menus and words use)
     * @param path       the canonical path ({@code /m/<id>/variants/{cohort}}, {@code /api/v1/<prefix>/{rest...}})
     * @param kind       what draws it
     * @param target     the page template, Sutra, or the route id it moved to
     * @param function   the function that gates it, or null
     * @param permission the permission it needs, or null
     * @param alias      a short path answering the same route ({@code /var}), or null
     * @param state      active, off or tombstone
     */
    public record Route(String module, String version, String id, String path, Kind kind, String target, String function, String permission,
            String alias, State state) {

        Route with(State s) {
            return new Route(module, version, id, path, kind, target, function, permission, alias, s);
        }
    }

    /** A lookup's answer: the route, the path's parameters, and the status the dispatcher returns. */
    public record Match(Route route, Map<String, String> params, int status) {
    }

    /** One immutable version of the table. */
    public record Snapshot(long version, List<Route> routes) {

        /** The route a path reaches: canonical paths first, then aliases (consulted only after the host's own routes miss). */
        public Optional<Match> lookup(String path) {
            String p = path == null ? "" : path.split("[?#]", 2)[0];
            for (boolean alias : new boolean[] {false, true}) {
                for (Route r : routes) {
                    String pattern = alias ? r.alias() : r.path();
                    if (pattern == null) {
                        continue;
                    }
                    Map<String, String> params = match(pattern, p);
                    if (params != null) {
                        int status = switch (r.state()) {
                            case OFF -> 404;
                            case TOMBSTONE -> 410;
                            case ACTIVE -> r.kind() == Kind.MOVED ? 308 : 200;
                        };
                        return Optional.of(new Match(r, params, status));
                    }
                }
            }
            return Optional.empty();
        }

        /** The routes of one module. */
        public List<Route> of(String module) {
            return routes.stream().filter(r -> r.module().equals(module)).toList();
        }
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(0, List.of()));

    /** The table now (immutable; keep it for one request). */
    public Snapshot snapshot() {
        return current.get();
    }

    /** Looks a path up in the current snapshot. */
    public Optional<Match> lookup(String path) {
        return current.get().lookup(path);
    }

    /** Puts a module's routes, replacing whatever it had (a tombstone included: a reinstall restores the paths). */
    public Snapshot register(String module, List<Route> routes) {
        for (Route r : routes) {
            if (!r.module().equals(module)) {
                throw new IllegalArgumentException("route " + r.id() + " belongs to " + r.module() + ", not " + module);
            }
        }
        return swap(all -> {
            List<Route> out = new ArrayList<>(all.stream().filter(r -> !r.module().equals(module)).toList());
            out.addAll(routes);
            return out;
        });
    }

    /** Sets every route of a module to a state: off (switched off), active (on again), tombstone (removed). */
    public Snapshot state(String module, State state) {
        return swap(all -> all.stream().map(r -> r.module().equals(module) ? r.with(state) : r).toList());
    }

    /** Drops a module's routes entirely (a tombstone that expired, or a module that never loaded). */
    public Snapshot remove(String module) {
        return swap(all -> all.stream().filter(r -> !r.module().equals(module)).toList());
    }

    private Snapshot swap(UnaryOperator<List<Route>> change) {
        return current.updateAndGet(s -> new Snapshot(s.version() + 1, List.copyOf(change.apply(s.routes()))));
    }

    /**
     * Matches a path pattern ({@code /m/genomics/variants/{cohort}}, {@code /var/{scope...}}) against a path; the parameters, or null when it
     * does not match. {@code {name}} takes one segment, {@code {name...}} the rest (possibly empty).
     */
    static Map<String, String> match(String pattern, String path) {
        String[] ps = trim(pattern).split("/", -1);
        String[] xs = trim(path).split("/", -1);
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < ps.length; i++) {
            String seg = ps[i];
            if (seg.startsWith("{") && seg.endsWith("...}")) {
                params.put(seg.substring(1, seg.length() - 4), i < xs.length ? String.join("/", List.of(xs).subList(i, xs.length)) : "");
                return params;
            }
            if (i >= xs.length) {
                return null;
            }
            if (seg.startsWith("{") && seg.endsWith("}")) {
                if (xs[i].isEmpty()) {
                    return null;
                }
                params.put(seg.substring(1, seg.length() - 1), xs[i]);
            } else if (!seg.equals(xs[i])) {
                return null;
            }
        }
        return ps.length == xs.length ? params : null;
    }

    private static String trim(String s) {
        String t = s.startsWith("/") ? s.substring(1) : s;
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }
}
