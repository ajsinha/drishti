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
package com.ash.drishti.packs.bindings;

import com.ash.drishti.kernel.api.data.BindingState;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How each kind finds its data (docs/architecture/KERNEL_AND_MODULES.md sections 5.5 and 5.6), first hit wins: the site's binding
 * ({@code config/bindings.yaml}), the distribution's default (today: a pack's {@code routes:}), a lake that holds the kind by convention
 * ({@code AUTO}), the demo lake ({@code DEMO}, only while demo is on and the need allows it), else {@code UNBOUND}. A bound kind whose last
 * load is older than its cadence is {@code STALE}; one whose source breaks the contract is {@code MISMATCH} (unbound for its needs). A kind a
 * module computes is {@code COMPUTED}, never bound. An unserved required need switches off exactly the functions that use it, with the reason.
 * Pure: every fact comes through {@link Facts}.
 */
public final class BindingResolver {

    /** Where a resolution came from, in the order they are tried. */
    public enum Origin { SITE, DEFAULT, CONVENTION, DEMO, COMPUTED, NONE }

    /** One running source of a kind as the kernel sees it. */
    public record Source(String name, String plugin, String lake, boolean demo, boolean computed) {}

    /** What the resolver asks the kernel. */
    public interface Facts {

        /** Every kind the loaded packs define. */
        Set<String> kinds();

        /** The running sources that may hold the kind (a source that surely holds none of it left out). */
        List<Source> sources(String kind);

        /** Every connector defined at the site (files and templates), running or not. */
        Set<String> connectors();

        /** The distribution's default connector of the kind (a pack's route), if any. */
        Optional<String> defaultRoute(String kind);

        /** The kind's newest business date at its sources. */
        default Optional<LocalDate> latest(String kind) {
            return Optional.empty();
        }

        /** The kind's cadence ({@code daily}, {@code weekly}, {@code monthly}, ...), from its contract. */
        default Optional<String> cadence(String kind) {
            return Optional.empty();
        }

        /** What breaks the kind's contract at its source (a required field missing), if anything. */
        default Optional<String> mismatch(String kind) {
            return Optional.empty();
        }

        /** The contract's {@code binding-hint.lake-table}. */
        default Optional<String> hint(String kind) {
            return Optional.empty();
        }

        boolean demoEnabled();

        LocalDate today();
    }

    /**
     * One kind's resolution.
     *
     * @param kind the kind
     * @param state its state
     * @param origin where it came from
     * @param read the sources that serve it, in read order
     * @param binding the site's binding, if any
     * @param neededBy the modules that need it
     * @param required true when some loaded module requires it
     * @param usedBy the functions that use it
     * @param lastLoad its newest business date, if known
     * @param reason what an administrator reads (why unbound, stale, mismatched; what to confirm)
     */
    public record Resolution(String kind, BindingState state, Origin origin, List<String> read, Bindings.Binding binding, List<String> neededBy,
            boolean required, List<String> usedBy, LocalDate lastLoad, String reason) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("state", state.name());
            m.put("origin", origin.name());
            m.put("read", read);
            m.put("binding", binding == null ? null : binding.toMap());
            m.put("neededBy", neededBy);
            m.put("required", required);
            m.put("usedBy", usedBy);
            m.put("lastLoad", lastLoad == null ? null : lastLoad.toString());
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * Every kind's resolution and what follows from it.
     *
     * @param kinds every kind, by name
     * @param off each function switched off by an unserved need, to the reason
     * @param problems bindings that name what does not exist, and other things to fix
     */
    public record Result(Map<String, Resolution> kinds, Map<String, String> off, List<String> problems) {

        public long count(BindingState s) {
            return kinds.values().stream().filter(r -> r.state() == s).count();
        }
    }

    private BindingResolver() {}

    /** Resolves every kind the packs define or a module needs. */
    public static Result resolve(Bindings site, DataNeeds needs, Facts facts) {
        Set<String> all = new TreeSet<>(facts.kinds());
        needs.needs().forEach(n -> all.add(n.kind()));
        all.addAll(needs.computed().keySet());
        all.addAll(site.byKind().keySet());
        Map<String, Resolution> out = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> defined = facts.connectors();
        for (Bindings.Binding b : site.byKind().values()) {
            for (String c : b.connectors()) {
                if (!defined.contains(c)) {
                    problems.add(b.kind() + " is bound to '" + c + "', which no connector file defines" + (dangling(b, defined)
                            ? ": the binding is set aside and the kind resolves without it" : ""));
                }
            }
            if (needs.computed().containsKey(b.kind())) {
                problems.add(b.kind() + " is computed by the " + needs.computed().get(b.kind()) + " module: its binding is ignored");
            }
        }
        for (String kind : all) {
            out.put(kind, one(kind, site, needs, facts));
        }
        return new Result(out, off(out, needs, offered(site, facts)), List.copyOf(problems));
    }

    private static Resolution one(String kind, Bindings site, DataNeeds needs, Facts facts) {
        List<DataNeeds.Need> mine = needs.needs().stream().filter(n -> n.kind().equals(kind)).toList();
        List<String> neededBy = mine.stream().map(DataNeeds.Need::module).distinct().toList();
        boolean required = mine.stream().anyMatch(n -> n.required() || !n.requiredFor().isEmpty());
        List<String> usedBy = mine.stream().flatMap(n -> n.usedBy().stream()).distinct().toList();
        boolean demoAllowed = mine.isEmpty() || mine.stream().allMatch(DataNeeds.Need::demo);
        List<Source> sources = facts.sources(kind);
        Bindings.Binding b = site.of(kind).filter(x -> !dangling(x, facts.connectors())).orElse(null);
        if (needs.computed().containsKey(kind) || sources.stream().anyMatch(Source::computed)) {
            String by = needs.computed().getOrDefault(kind, sources.stream().filter(Source::computed).map(Source::name).findFirst().orElse(""));
            return new Resolution(kind, BindingState.COMPUTED, Origin.COMPUTED, names(sources.stream().filter(Source::computed).toList()), null,
                    neededBy, required, usedBy, null, "computed by " + by + "; not bindable");
        }
        List<Source> real = sources.stream().filter(s -> !s.demo()).toList();
        List<Source> demo = sources.stream().filter(Source::demo).toList();
        Resolution r;
        if (b != null) {
            if (b.demo()) {
                r = facts.demoEnabled() && !demo.isEmpty()
                        ? new Resolution(kind, BindingState.DEMO, Origin.SITE, names(demo), b, neededBy, required, usedBy, null, "bound to the demo lake")
                        : unbound(kind, b, neededBy, required, usedBy, facts.demoEnabled() ? "bound to the demo lake, which holds none of it"
                                : "bound to the demo lake, and demo is off (drishti.demo.enabled)");
            } else if (b.read().isEmpty()) {
                r = new Resolution(kind, BindingState.BOUND, Origin.SITE, List.of(), b, neededBy, required, usedBy, null,
                        b.writes() ? "written to " + b.writeLake() + " · " + b.writeTable() : "live from " + b.live());
            } else {
                List<String> running = b.read().stream().filter(c -> sources.stream().anyMatch(s -> s.name().equals(c))).toList();
                r = running.isEmpty()
                        ? unbound(kind, b, neededBy, required, usedBy, "bound to " + String.join(", ", b.read()) + ", none of which is running")
                        : new Resolution(kind, BindingState.BOUND, Origin.SITE, running, b, neededBy, required, usedBy, null,
                                running.size() < b.read().size() ? "not running: " + b.read().stream().filter(c -> !running.contains(c)).toList() : "");
            }
        } else {
            Optional<String> route = facts.defaultRoute(kind).filter(c -> real.stream().anyMatch(s -> s.name().equals(c)));
            if (route.isPresent()) {
                List<String> read = new ArrayList<>(List.of(route.get()));
                real.stream().map(Source::name).filter(n -> !read.contains(n)).forEach(read::add);
                r = new Resolution(kind, BindingState.BOUND, Origin.DEFAULT, read, null, neededBy, required, usedBy, null,
                        "the distribution's default (the pack's route to " + route.get() + ")");
            } else if (!real.isEmpty()) {
                String lakes = String.join(", ", new LinkedHashSet<>(real.stream().map(s -> s.lake() == null ? s.name() : s.lake()).toList()));
                r = new Resolution(kind, BindingState.AUTO, Origin.CONVENTION, names(real), null, neededBy, required, usedBy, null,
                        "found by convention in " + lakes + facts.hint(kind).map(h -> " (table " + h + ")").orElse("") + ": confirm");
            } else if (!demo.isEmpty() && facts.demoEnabled() && demoAllowed) {
                r = new Resolution(kind, BindingState.DEMO, Origin.DEMO, names(demo), null, neededBy, required, usedBy, null, "demo data");
            } else {
                String why = !demo.isEmpty() && !facts.demoEnabled() ? "only the demo lake holds it, and demo is off"
                        : "no source is bound" + facts.hint(kind).map(h -> " (hint: a lake table " + h + ")").orElse("");
                r = unbound(kind, null, neededBy, required, usedBy, why);
            }
        }
        if (r.state() == BindingState.BOUND || r.state() == BindingState.AUTO) {
            Optional<String> bad = facts.mismatch(kind);
            if (bad.isPresent()) {
                return new Resolution(kind, BindingState.MISMATCH, r.origin(), r.read(), b, neededBy, required, usedBy, null, bad.get());
            }
            LocalDate last = facts.latest(kind).orElse(null);
            int days = cadenceDays(facts.cadence(kind).orElse(""));
            if (last != null && days > 0 && ChronoUnit.DAYS.between(last, facts.today()) > days) {
                return new Resolution(kind, BindingState.STALE, r.origin(), r.read(), b, neededBy, required, usedBy, last,
                        "last loaded " + last + " (cadence " + facts.cadence(kind).orElse("") + ")");
            }
            return new Resolution(kind, r.state(), r.origin(), r.read(), b, neededBy, required, usedBy, last, r.reason());
        }
        return r;
    }

    private static Resolution unbound(String kind, Bindings.Binding b, List<String> neededBy, boolean required, List<String> usedBy, String why) {
        return new Resolution(kind, BindingState.UNBOUND, b == null ? Origin.NONE : Origin.SITE, List.of(), b, neededBy, required, usedBy, null, why);
    }

    private static List<String> names(List<Source> s) {
        return s.stream().map(Source::name).distinct().toList();
    }

    /** The calendar days after which a load is stale: a daily kind four (a long weekend), weekly ten, monthly forty; others never. */
    static int cadenceDays(String cadence) {
        return switch (cadence.toLowerCase(Locale.ROOT)) {
            case "daily" -> 4;
            case "weekly" -> 10;
            case "monthly" -> 40;
            case "quarterly" -> 100;
            default -> 0;
        };
    }

    /**
     * A binding that names only connectors the site does not define (their files removed, their pack not loaded): Health lists it as a
     * problem and the kind resolves as if it were not bound (section 5.5), so a binding left behind does not switch functions off. A
     * binding to a connector that is defined but not running stays: that is the site's choice, and UNBOUND says so.
     */
    static boolean dangling(Bindings.Binding b, Set<String> defined) {
        return !b.demo() && !b.writes() && !b.connectors().isEmpty() && b.connectors().stream().noneMatch(defined::contains);
    }

    /**
     * The kinds the site offers: those its loaded packs define and those it binds. A need on a kind the site offers in no way (its pack is
     * not loaded, nothing binds it) is outside the site's content (the pack switch decides those functions, ACCESS_MODEL.md), so it
     * switches nothing off; it still resolves UNBOUND in Health.
     */
    static Set<String> offered(Bindings site, Facts facts) {
        Set<String> out = new java.util.HashSet<>(facts.kinds());
        out.addAll(site.byKind().keySet());
        return out;
    }

    /**
     * The functions an unserved need switches off: a required need, a {@code for:} need, an any-of group with no member served; only for
     * kinds the site offers ({@link #offered}).
     */
    static Map<String, String> off(Map<String, Resolution> kinds, DataNeeds needs, Set<String> offered) {
        Map<String, String> out = new TreeMap<>();
        Map<String, List<DataNeeds.Need>> groups = new LinkedHashMap<>();
        for (DataNeeds.Need n : needs.needs()) {
            if (n.writeOnly()) {
                continue;                                       // a produced kind: unbound means not published, nothing switched off
            }
            if (n.anyOf() != null) {
                groups.computeIfAbsent(n.module() + "/" + n.anyOf(), g -> new ArrayList<>()).add(n);
                continue;
            }
            if (n.requiredWith() != null && !usable(kinds, n.requiredWith())) {
                continue;
            }
            if (offered.contains(n.kind()) && !usable(kinds, n.kind())) {
                for (String f : n.gates()) {
                    out.putIfAbsent(f, f + " needs data: " + unserved(n.kind(), kinds.get(n.kind()).reason()) + "; ask your data administrator");
                }
            }
        }
        groups.values().forEach(g -> {
            if (g.stream().anyMatch(n -> offered.contains(n.kind())) && g.stream().noneMatch(n -> usable(kinds, n.kind()))) {
                String names = String.join(" or ", g.stream().map(DataNeeds.Need::kind).toList());
                g.stream().flatMap(n -> n.gates().stream()).distinct().forEach(f -> out.putIfAbsent(f, f + " needs data: " + names
                        + " is not bound; ask your data administrator"));
            }
        });
        return out;
    }

    /** {@code exposure-path is not bound}, or {@code ... is not served (only the demo lake holds it, and demo is off)}. */
    private static String unserved(String kind, String reason) {
        return reason == null || reason.isBlank() || reason.startsWith("no source is bound") ? kind + " is not bound" : kind + " is not served (" + reason + ")";
    }

    private static boolean usable(Map<String, Resolution> kinds, String kind) {
        Resolution r = kinds.get(kind);
        return r != null && r.state().usable();
    }
}
