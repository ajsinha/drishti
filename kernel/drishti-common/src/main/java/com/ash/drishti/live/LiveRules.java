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
package com.ash.drishti.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a live message means for one kind: the {@code live:} block of the kind's contract
 * ({@code contracts/<kind>.contract.yaml}, docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.3). The contract owns these rules; a live
 * companion (the transport) names none of them. A contract without the block may still be fed, with the safe defaults: arrival order,
 * whole rows, overlay, cleared at the next load, strict validation. A computed kind never takes a feed.
 *
 * <p>{@link #parse} reads the block and checks it against the contract (every field it names is one of the contract's fields; each value
 * is one of its words); the problems are in words, for Health, with {@link #CODE}. Pure: no file, no setting is read here.
 *
 * @param kind          the kind
 * @param allowed       false: the kind never takes a live feed (official results, computed kinds, decision Q5 for positions)
 * @param key           the contract's {@code key:} fields (joined with {@code |} for a live key)
 * @param order         what makes one message newer than another, most significant first; empty: arrival
 * @param changedAt     the event-time field, or null
 * @param changedBy     the field naming who changed the row, or null
 * @param asOf          the field carrying the business date, or null (the live date)
 * @param partial       whole rows, or JSON Merge Patch over the row held
 * @param deleteWhen    a condition in the search grammar that makes a row a delete, or null
 * @param apply         overlay (the live state) or land (windows appended to the lake); {@code both} only from a site's binding (Q7)
 * @param conflate      keep the latest per key per period before applying; {@link Duration#ZERO}: none
 * @param absorb        how the next lake load absorbs the live state
 * @param ttl           how long a live row is kept unabsorbed
 * @param maxRows       the bound of the kind's live state
 * @param validate      when a message is checked against the contract
 * @param effective     when a change is shown in trees, pivots and scopes (reference data, section 8.5)
 * @param closedDates   whether a landed feed may restate a closed, not signed-off date (decision Q12)
 * @param keepVersions  the versions per key the live state keeps for a record's Changes today
 * @param reorderWindow how long a merge-patch gap is held for its missing versions
 * @param maxMessageKb  the largest message accepted, 0: the site's {@code drishti.live.max-message-kb}
 * @param landKey       a landed kind's window key: the first of these fields a row has (a trade's PVs by {@code TRADE_ID}, a position's
 *                      by {@code POSITION_ID}); empty: the contract's key
 */
public record LiveRules(String kind, boolean allowed, List<String> key, List<String> order, String changedAt, String changedBy, String asOf,
        Partial partial, String deleteWhen, Apply apply, Duration conflate, Absorb absorb, Duration ttl, long maxRows, Validate validate,
        Effective effective, ClosedDates closedDates, int keepVersions, Duration reorderWindow, int maxMessageKb, List<String> landKey) {

    /** The code of every problem {@link #parse} reports. */
    public static final String CODE = "DRS-1060";
    /** The kinds that may restate a closed date when {@code drishti.live.restate-kinds} is not set (decision Q12). */
    public static final Set<String> RESTATE_KINDS = Set.of("scenario-pv", "pnl-vector");
    /** The keys of a {@code live:} block. */
    public static final Set<String> KEYS = Set.of("allowed", "order", "changed-at", "changed-by", "as-of", "partial", "delete", "apply", "conflate",
            "absorb", "retention", "validate", "effective", "closed-dates", "keep-versions", "reorder-window", "max-message-kb", "land-key");

    /** {@code partial:}: whole rows, or a JSON Merge Patch (RFC 7386) over the row held. */
    public enum Partial { WHOLE, MERGE }

    /** {@code apply:}: the live state; windows landed in the lake; both (a site's binding only, decision Q7). */
    public enum Apply { OVERLAY, LAND, BOTH }

    /** {@code absorb:} (section 6): per key by order; by the load's cut-off; the live state cleared. */
    public enum Absorb { BY_ORDER, BY_CUTOFF, RESET }

    /** {@code validate:} (section 3.4); strict is the default for every kind (decision Q8). */
    public enum Validate { STRICT, LENIENT, DEFERRED }

    /** {@code effective:} (section 8.5): shown everywhere at once, or in trees, pivots and scopes from the next load (decision Q10). */
    public enum Effective { AT_ONCE, NEXT_LOAD }

    /** {@code closed-dates:} (section 5): an earlier date is rejected, or (landed kinds, decision Q12) appended as a restatement. */
    public enum ClosedDates { REJECT, RESTATE }

    /** The default bound of a kind's live state (section 7.6). */
    public static final Duration DEFAULT_TTL = Duration.ofDays(2);
    public static final long DEFAULT_MAX_ROWS = 2_000_000L;
    public static final Duration DEFAULT_REORDER = Duration.ofSeconds(2);

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public LiveRules {
        key = key == null ? List.of() : List.copyOf(key);
        order = order == null ? List.of() : List.copyOf(order);
        landKey = landKey == null ? List.of() : List.copyOf(landKey);
    }

    /**
     * A block read, with what is wrong with it.
     *
     * @param rules    the rules (the defaults where the block is wrong or silent; {@code allowed} false when a problem makes it unusable)
     * @param problems each problem in words, naming the kind and the key; empty when the block is good
     */
    public record Parsed(LiveRules rules, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** True when the kind takes a live feed at all. */
    public boolean live() {
        return allowed;
    }

    /** True when the order is arrival only (no field of the row says which message is newer). */
    public boolean byArrival() {
        return order.isEmpty();
    }

    /** The defaults of a kind with no {@code live:} block (section 2.3): the safe ones. */
    /**
     * The rules in words, as the Admin drawer and the CLI show them (LIVE_CONNECTORS_DESIGN.md section 9.1, "The kind's rules"): applied,
     * newer means, messages, at the next load, kept. One line each, by label.
     */
    public java.util.Map<String, String> inWords() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        if (!allowed) {
            m.put("Live", "not allowed: the contract says end of day only");
            return m;
        }
        m.put("Applied", switch (apply) {
            case OVERLAY -> "at once over the lake's rows";
            case LAND -> "landed in the lake by window, as one batch per kind and date";
            case BOTH -> "at once over the lake's rows, and landed in the lake by window (the site's binding)";
        });
        m.put("Newer means", order.isEmpty() ? "arrived later (the contract names no order field)"
                : "a higher " + String.join(", then a later ", order));
        m.put("Messages", (partial == Partial.MERGE ? "changed fields merged into the row" : "whole records") + ", checked "
                + switch (validate) {
                    case STRICT -> "against the contract (a bad one is rejected)";
                    case LENIENT -> "leniently (unknown fields kept, a wrong type rejected)";
                    case DEFERRED -> "by the engine that uses them";
                });
        m.put("At the next load", switch (absorb) {
            case BY_ORDER -> "absorbed by " + (order.isEmpty() ? "order" : order.get(0)) + "; later changes carried";
            case BY_CUTOFF -> "absorbed when changed before the load's cut-off; later changes carried";
            case RESET -> "the live rows of the date are dropped; the lake's stand";
        });
        m.put("Kept", humanDuration(ttl) + ", " + String.format(Locale.ROOT, "%,d", maxRows) + " rows at most");
        if (effective == Effective.NEXT_LOAD) {
            m.put("Effective", "from the next load (reference data)");
        }
        if (!conflate.isZero()) {
            m.put("Conflated", "one change per key every " + humanDuration(conflate));
        }
        return m;
    }

    private static String humanDuration(Duration d) {
        long s = d.toSeconds();
        return s % 86_400 == 0 ? s / 86_400 + (s == 86_400 ? " day" : " days") : s % 3_600 == 0 ? s / 3_600 + " h" : s % 60 == 0 ? s / 60 + " min" : s + " s";
    }

    /** These rules with {@code apply} as the site's binding asks ({@code both}: overlaid at once and landed by window too). */
    public LiveRules withApply(Apply a) {
        return a == apply ? this : new LiveRules(kind, allowed, key, order, changedAt, changedBy, asOf, partial, deleteWhen, a, conflate, absorb,
                ttl, maxRows, validate, effective, closedDates, keepVersions, reorderWindow, maxMessageKb, landKey);
    }

    public static LiveRules defaults(String kind, List<String> key, boolean computed) {
        return new LiveRules(kind, !computed, key, List.of(), null, null, null, Partial.WHOLE, null, Apply.OVERLAY, Duration.ZERO, Absorb.RESET,
                DEFAULT_TTL, DEFAULT_MAX_ROWS, Validate.STRICT, Effective.AT_ONCE, ClosedDates.REJECT, 1, DEFAULT_REORDER, 0, List.of());
    }

    /**
     * Reads the {@code live:} block of a contract and checks it.
     *
     * @param contract     the contract as read (its {@code kind}, {@code key}, {@code class}, {@code binding-hint}, {@code fields}, {@code nested}, {@code live})
     * @param restateKinds the kinds that may restate closed dates ({@code drishti.live.restate-kinds}; {@link #RESTATE_KINDS} by default)
     */
    public static Parsed parse(Map<String, Object> contract, Collection<String> restateKinds) {
        String kind = String.valueOf(contract.getOrDefault("kind", "?"));
        List<String> key = strings(contract.get("key"));
        boolean computed = "computed".equals(String.valueOf(contract.get("class")))
                || contract.get("binding-hint") instanceof Map<?, ?> h && Boolean.TRUE.equals(h.get("computed"));
        LiveRules d = defaults(kind, key, computed);
        Object raw = contract.get("live");
        if (raw == null) {
            return new Parsed(d, List.of());
        }
        List<String> problems = new ArrayList<>();
        String at = "the live: block of the " + kind + " contract";
        if (!(raw instanceof Map<?, ?> block)) {
            return new Parsed(off(d), List.of(at + " is a mapping (" + CODE + ")"));
        }
        for (Object k : block.keySet()) {
            if (!KEYS.contains(String.valueOf(k))) {
                problems.add(at + ": unknown key '" + k + "' (allowed: " + String.join(", ", new java.util.TreeSet<>(KEYS)) + ")");
            }
        }
        Set<String> fields = fields(contract);
        boolean allowed = bool(block.get("allowed"), !computed, at + ": allowed", problems);
        if (computed && allowed) {
            problems.add(at + ": a computed kind is live by derivation from its inputs and takes no feed; write allowed: false or remove the block");
        }
        List<String> order = strings(block.get("order"));
        for (String f : order) {
            field(f, fields, at + ": order", problems);
        }
        String changedAt = field(text(block.get("changed-at")), fields, at + ": changed-at", problems);
        String changedBy = field(text(block.get("changed-by")), fields, at + ": changed-by", problems);
        String asOf = field(text(block.get("as-of")), fields, at + ": as-of", problems);
        Partial partial = word(block.get("partial"), Partial.class, Partial.WHOLE, at + ": partial", problems);
        String deleteWhen = null;
        if (block.get("delete") instanceof Map<?, ?> del) {
            deleteWhen = text(del.get("when"));
        } else if (block.get("delete") != null) {
            problems.add(at + ": delete is a mapping ({when: <a condition>})");
        }
        Apply apply = word(block.get("apply"), Apply.class, Apply.OVERLAY, at + ": apply", problems);
        if (apply == Apply.BOTH) {
            problems.add(at + ": apply: both is a site's choice in the kind's binding (live: {apply: both}), never the contract's: write overlay or land");
            apply = Apply.OVERLAY;
        }
        Object conflateRaw = block.get("conflate");
        Duration conflate = conflateRaw == null || "none".equalsIgnoreCase(String.valueOf(conflateRaw).strip()) ? Duration.ZERO
                : duration(conflateRaw, Duration.ZERO, at + ": conflate", problems);
        Absorb absorb = word(block.get("absorb"), Absorb.class, Absorb.RESET, at + ": absorb", problems);
        if (absorb == Absorb.BY_ORDER && order.isEmpty()) {
            problems.add(at + ": absorb: by-order needs an order: field the lake keeps (a version or an event time); write order: or absorb: reset");
        }
        Duration ttl = DEFAULT_TTL;
        long maxRows = DEFAULT_MAX_ROWS;
        if (block.get("retention") instanceof Map<?, ?> ret) {
            for (Object k : ret.keySet()) {
                if (!Set.of("ttl", "max-rows").contains(String.valueOf(k))) {
                    problems.add(at + ": retention has ttl and max-rows, not '" + k + "'");
                }
            }
            ttl = ret.get("ttl") == null ? ttl : duration(ret.get("ttl"), ttl, at + ": retention.ttl", problems);
            maxRows = ret.get("max-rows") == null ? maxRows : number(ret.get("max-rows"), maxRows, at + ": retention.max-rows", problems);
        } else if (block.get("retention") != null) {
            problems.add(at + ": retention is a mapping ({ttl: 2d, max-rows: 2000000})");
        }
        Validate validate = word(block.get("validate"), Validate.class, Validate.STRICT, at + ": validate", problems);
        Effective effective = word(block.get("effective"), Effective.class, Effective.AT_ONCE, at + ": effective", problems);
        ClosedDates closed = word(block.get("closed-dates"), ClosedDates.class, ClosedDates.REJECT, at + ": closed-dates", problems);
        Collection<String> restate = restateKinds == null ? RESTATE_KINDS : restateKinds;
        if (closed == ClosedDates.RESTATE && apply != Apply.LAND) {
            problems.add(at + ": closed-dates: restate is for a landed kind (apply: land)");
        }
        if (closed == ClosedDates.RESTATE && !restate.contains(kind)) {
            problems.add(at + ": closed-dates: restate is allowed only for " + String.join(", ", new java.util.TreeSet<>(restate))
                    + " (drishti.live.restate-kinds)");
        }
        int keep = (int) (block.get("keep-versions") == null ? 1 : number(block.get("keep-versions"), 1, at + ": keep-versions", problems));
        Duration reorder = block.get("reorder-window") == null ? DEFAULT_REORDER
                : duration(block.get("reorder-window"), DEFAULT_REORDER, at + ": reorder-window", problems);
        int maxKb = (int) (block.get("max-message-kb") == null ? 0 : number(block.get("max-message-kb"), 0, at + ": max-message-kb", problems));
        List<String> landKey = strings(block.get("land-key"));
        for (String f : landKey) {
            field(f, fields, at + ": land-key", problems);
        }
        if (!landKey.isEmpty() && apply != Apply.LAND) {
            problems.add(at + ": land-key is a landed kind's window key (apply: land)");
        }
        if (allowed && key.isEmpty() && landKey.isEmpty()) {
            problems.add(at + ": the kind has no key: (a live row replaces the lake's row by key); give the contract its key or write allowed: false");
        }
        LiveRules rules = new LiveRules(kind, allowed, key, order, changedAt, changedBy, asOf, partial, deleteWhen, apply, conflate, absorb, ttl,
                maxRows, validate, effective, closed, Math.max(1, keep), reorder, maxKb, landKey);
        List<String> worded = problems.stream().map(p -> p + " (" + CODE + ")").toList();
        return new Parsed(worded.isEmpty() ? rules : off(rules), worded);
    }

    /** A kind whose rules cannot be used takes no feed until they are fixed (never a guess at what the contract meant). */
    private static LiveRules off(LiveRules r) {
        return new LiveRules(r.kind, false, r.key, r.order, r.changedAt, r.changedBy, r.asOf, r.partial, r.deleteWhen, r.apply, r.conflate,
                r.absorb, r.ttl, r.maxRows, r.validate, r.effective, r.closedDates, r.keepVersions, r.reorderWindow, r.maxMessageKb, r.landKey);
    }

    /** The contract's field names: {@code fields} and the paths of {@code nested} with their fields ({@code LEGS[].NOTIONAL}). */
    public static Set<String> fields(Map<String, Object> contract) {
        Set<String> out = new LinkedHashSet<>();
        if (contract.get("fields") instanceof List<?> l) {
            l.forEach(f -> {
                if (f instanceof Map<?, ?> m && m.get("name") != null) {
                    out.add(String.valueOf(m.get("name")));
                }
            });
        }
        if (contract.get("nested") instanceof Map<?, ?> nested) {
            nested.forEach((path, fs) -> {
                if (fs instanceof List<?> l) {
                    l.forEach(f -> {
                        if (f instanceof Map<?, ?> m && m.get("name") != null) {
                            out.add(path + "." + m.get("name"));
                        }
                    });
                }
            });
        }
        return out;
    }

    /** {@code 1s}, {@code 500ms}, {@code 5m}, {@code 2d}; the default and a problem when it is not one. */
    public static Duration duration(Object v, Duration dflt, String where, List<String> problems) {
        Matcher m = DURATION.matcher(String.valueOf(v).strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            problems.add(where + " is a duration such as 500ms, 1s, 5m, 2d, not '" + v + "'");
            return dflt;
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    private static String field(String name, Set<String> fields, String where, List<String> problems) {
        if (name == null) {
            return null;
        }
        if (!FIELD.matcher(name).matches() && !name.contains(".")) {
            problems.add(where + " names '" + name + "', which is not a field name");
        } else if (!fields.isEmpty() && !fields.contains(name)) {
            problems.add(where + " names " + name + ", which is not a field of the contract");
        }
        return name;
    }

    private static <E extends Enum<E>> E word(Object v, Class<E> type, E dflt, String where, List<String> problems) {
        if (v == null) {
            return dflt;
        }
        String w = String.valueOf(v).strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(w)) {
                return e;
            }
        }
        List<String> words = java.util.Arrays.stream(type.getEnumConstants()).map(e -> e.name().toLowerCase(Locale.ROOT).replace('_', '-')).toList();
        problems.add(where + " is one of " + String.join(", ", words) + ", not '" + v + "'");
        return dflt;
    }

    private static boolean bool(Object v, boolean dflt, String where, List<String> problems) {
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).strip();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s);
        }
        problems.add(where + " is true or false, not '" + v + "'");
        return dflt;
    }

    private static long number(Object v, long dflt, String where, List<String> problems) {
        String s = String.valueOf(v).strip().replace("_", "");
        if (s.matches("\\d{1,15}")) {
            return Long.parseLong(s);
        }
        problems.add(where + " is a whole number, not '" + v + "'");
        return dflt;
    }

    private static String text(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
    }

    private static List<String> strings(Object v) {
        if (v instanceof Collection<?> c) {
            return c.stream().filter(java.util.Objects::nonNull).map(x -> String.valueOf(x).strip()).filter(s -> !s.isEmpty()).toList();
        }
        return v == null || String.valueOf(v).isBlank() ? List.of() : List.of(String.valueOf(v).strip());
    }
}
