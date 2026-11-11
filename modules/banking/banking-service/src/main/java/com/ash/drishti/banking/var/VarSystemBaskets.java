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
package com.ash.drishti.banking.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The system baskets (VAR_GUIDE.md section 5.7): the bank's own baskets, kept in the basket store beside people's baskets
 * (HOW_IT_FITS.md section D5), under the system scope: {@code var/system-baskets/<ID>.json} in the content store,
 * every version compare-and-set, so every server of a cluster reads the same definitions. Each is a node of a hierarchy
 * ({@code hierarchy}), a condition over the positions' columns ({@code rule}) or positions and trades someone picked ({@code basket}),
 * valid from a business date to another (open-ended while {@code validTo} is empty); its VaR limit is a {@code var-limit} row (HIERARCHY
 * {@code PORTFOLIO}, NODE_ID its id). They are read as {@code PORT:<id>}; nothing about them comes from data feeds.
 *
 * <p>Reads are cached and follow a sequence ({@link #SEQUENCE}) every write raises, so a change made on one server is seen on the next
 * read anywhere. A stored entry that does not read as a system basket is listed ({@link #skipped()}) and left out, never an error.
 */
final class VarSystemBaskets {

    /** The system scope of the basket store: a visible name, beside people's {@code var/baskets/<owner>/}. */
    static final String FOLDER = "var/system-baskets/";
    /** Raised at every write: the readers' cache key across a cluster. */
    static final String SEQUENCE = "var/system-baskets-seq";
    /** Written once the demo's seed was imported, so a server never seeds the system scope twice. */
    static final String SEEDED = "var/system-baskets-seeded";
    static final Pattern ID = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");
    static final List<String> TYPES = List.of("hierarchy", "rule", "basket");
    static final List<String> ROLLS_UP_KINDS = List.of("bank", "division", "legal-entity", "desk", "book");

    /** One member of a {@code basket}: a position or a trade. */
    record Member(String position, String trade) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (position != null) {
                m.put("position", position);
            } else {
                m.put("trade", trade);
            }
            return m;
        }
    }

    /**
     * A system basket.
     *
     * @param type {@code hierarchy}, {@code rule} or {@code basket}
     * @param node a hierarchy's node id ({@code MGMT~DESK-RATES})
     * @param rule a rule's condition over the positions' columns ({@code CURRENCY == 'EUR'})
     * @param rollsUpKind the kind of the node it reports under (bank, division, legal-entity, desk, book), or null
     * @param validTo the last business date it applies to, null while open-ended
     * @param version the store's version number (0 before it is saved)
     */
    record Basket(String id, String name, String description, String owner, String type, String node, String rule, List<Member> members,
            String rollsUpKind, String rollsUpId, LocalDate validFrom, LocalDate validTo, int version, String createdAt, String updatedAt,
            String updatedBy, String retiredBy) {

        /** Its definition in the scope grammar: {@code NODE:x}, {@code WHERE:rule}, {@code POS:... + TRD:...}. */
        String scope() {
            return switch (type) {
                case "hierarchy" -> "NODE:" + node;
                case "rule" -> "WHERE:" + rule;
                default -> {
                    List<String> pos = new ArrayList<>();
                    List<String> trd = new ArrayList<>();
                    members.forEach(m -> {
                        if (m.position() != null) {
                            pos.add(m.position());
                        } else {
                            trd.add(m.trade());
                        }
                    });
                    List<String> parts = new ArrayList<>();
                    if (!pos.isEmpty()) {
                        parts.add("POS:" + String.join(",", pos));
                    }
                    if (!trd.isEmpty()) {
                        parts.add("TRD:" + String.join(",", trd));
                    }
                    yield parts.isEmpty() ? "TRD:NONE" : String.join(" + ", parts);
                }
            };
        }

        boolean validOn(LocalDate d) {
            return d == null || (validFrom == null || !d.isBefore(validFrom)) && (validTo == null || !d.isAfter(validTo));
        }

        /** As the date's portfolio the engine resolves {@code PORT:<id>} by (its type upper-case, as the page shows it). */
        VarDate.Portfolio portfolio(double limit) {
            return new VarDate.Portfolio(id, name, type.toUpperCase(Locale.ROOT), owner, scope(), limit);
        }

        /** The definition alone (what an import compares and an export writes), in the API's names. */
        Map<String, Object> definition() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("owner", owner);
            m.put("type", type);
            if (node != null) {
                m.put("node", node);
            }
            if (rule != null) {
                m.put("rule", rule);
            }
            if (!members.isEmpty()) {
                m.put("members", members.stream().map(Member::toMap).toList());
            }
            if (rollsUpKind != null || rollsUpId != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("kind", rollsUpKind);
                r.put("id", rollsUpId);
                m.put("rollsUpTo", r);
            }
            m.put("validFrom", validFrom == null ? null : validFrom.toString());
            m.put("validTo", validTo == null ? null : validTo.toString());
            return m;
        }

        /** As the store keeps it and the API answers: the definition, its scope text and its history. */
        Map<String, Object> toMap() {
            Map<String, Object> m = definition();
            m.put("scope", scope());
            m.put("version", version);
            m.put("createdAt", createdAt);
            m.put("updatedAt", updatedAt);
            m.put("updatedBy", updatedBy);
            if (retiredBy != null) {
                m.put("retiredBy", retiredBy);
            }
            return m;
        }

        /** The page's document (the {@code system-basket} kind, PORT &lt;id&gt;): the same fields the portfolio page always read. */
        Map<String, Object> document(LocalDate businessDate) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("PORTFOLIO_ID", id);
            m.put("NAME", name);
            m.put("DESCRIPTION", description);
            m.put("OWNER", owner);
            m.put("TYPE", type.toUpperCase(Locale.ROOT));
            Map<String, Object> def = new LinkedHashMap<>();
            switch (type) {
                case "hierarchy" -> def.put("NODE", node);
                case "rule" -> def.put("RULE", rule);
                default -> def.put("MEMBERS", members.stream().map(x -> x.position() != null ? Map.of("POSITION_ID", x.position())
                        : Map.of("TRADE_ID", x.trade())).toList());
            }
            m.put("DEFINITION", def);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("KIND", rollsUpKind);
            r.put("ID", rollsUpId);
            m.put("ROLLS_UP_TO", r);
            m.put("BUSINESS_DATE", businessDate == null ? null : businessDate.toString());
            m.put("VALID_FROM", validFrom == null ? null : validFrom.toString());
            m.put("VALID_TO", validTo == null ? null : validTo.toString());
            m.put("VERSION", version);
            m.put("UPDATED_AT", updatedAt);
            m.put("UPDATED_BY", updatedBy);
            return m;
        }

        Basket withHistory(int v, String created, String updated, String by, String retired) {
            return new Basket(id, name, description, owner, type, node, rule, members, rollsUpKind, rollsUpId, validFrom, validTo, v, created,
                    updated, by, retired);
        }

        Basket retiredOn(LocalDate to) {
            return new Basket(id, name, description, owner, type, node, rule, members, rollsUpKind, rollsUpId, validFrom, to, version, createdAt,
                    updatedAt, updatedBy, retiredBy);
        }
    }

    /**
     * Reads a system basket from the API's or an import file's map (camelCase or kebab-case names alike), refusing in words
     * ({@code DRS-5220}) what does not make one: an id that is not {@code A-Z 0-9 . _ -}, a type that is not hierarchy, rule or basket,
     * a hierarchy without its node, a rule without its condition, a basket without members, a member that is neither a position nor a
     * trade, a date that is not YYYY-MM-DD, valid-to before valid-from.
     */
    @SuppressWarnings("unchecked")
    static Basket read(Map<?, ?> in) {
        if (in == null) {
            throw invalid("a system basket is a map of id, name, owner, type and its definition");
        }
        String id = text(in, "id");
        if (id == null || !ID.matcher(id).matches()) {
            throw invalid("a system basket's id is 1-64 upper-case letters, digits, dots, dashes or underscores (PF-RATES-DESK), not '" + id + "'");
        }
        String name = text(in, "name");
        if (name == null) {
            throw invalid(id + ": a system basket has a name");
        }
        String owner = text(in, "owner");
        if (owner == null) {
            throw invalid(id + ": a system basket has an owner (the person or team who keeps its definition)");
        }
        String type = text(in, "type");
        type = type == null ? null : type.toLowerCase(Locale.ROOT);
        if (type == null || !TYPES.contains(type)) {
            throw invalid(id + ": type is one of " + TYPES + ", not '" + text(in, "type") + "'");
        }
        String node = text(in, "node");
        String rule = text(in, "rule");
        List<Member> members = new ArrayList<>();
        Object ms = in.get("members");
        if (ms instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    String p = first(text(m, "position"), text(m, "POSITION_ID"));
                    String t = first(text(m, "trade"), text(m, "TRADE_ID"));
                    if (p == null && t == null || p != null && t != null) {
                        throw invalid(id + ": a member is a position or a trade ({position: POS-...} or {trade: ...}), not " + m);
                    }
                    members.add(new Member(p, t));
                } else if (o != null && !String.valueOf(o).isBlank()) {
                    String s = String.valueOf(o).trim();
                    members.add(s.startsWith("POS-") ? new Member(s, null) : new Member(null, s));
                }
            }
        } else if (ms != null) {
            throw invalid(id + ": members is a list of positions and trades");
        }
        switch (type) {
            case "hierarchy" -> {
                if (node == null) {
                    throw invalid(id + ": a hierarchy basket names its node (MGMT~DESK-RATES)");
                }
                rule = null;
                members = List.of();
            }
            case "rule" -> {
                if (rule == null) {
                    throw invalid(id + ": a rule basket has its condition (CURRENCY == 'EUR')");
                }
                try {
                    new com.ash.drishti.banking.compute.var.PositionRule(rule);
                } catch (IllegalArgumentException e) {
                    throw invalid(id + ": the rule does not read: " + e.getMessage());
                }
                node = null;
                members = List.of();
            }
            default -> {
                if (members.isEmpty()) {
                    throw invalid(id + ": a basket lists at least one position or trade");
                }
                node = null;
                rule = null;
            }
        }
        Object r = first(in.get("rollsUpTo"), in.get("rolls-up-to"));
        String rk = null;
        String ri = null;
        if (r instanceof Map<?, ?> rm) {
            rk = text(rm, "kind");
            ri = text(rm, "id");
            if (rk != null && !ROLLS_UP_KINDS.contains(rk.toLowerCase(Locale.ROOT))) {
                throw invalid(id + ": rolls-up-to's kind is one of " + ROLLS_UP_KINDS + ", not '" + rk + "'");
            }
            rk = rk == null ? null : rk.toLowerCase(Locale.ROOT);
        } else if (r != null) {
            throw invalid(id + ": rolls-up-to is {kind, id}");
        }
        LocalDate from = date(id, "valid-from", first(text(in, "validFrom"), text(in, "valid-from")));
        LocalDate to = date(id, "valid-to", first(text(in, "validTo"), text(in, "valid-to")));
        if (from != null && to != null && to.isBefore(from)) {
            throw invalid(id + ": valid-to " + to + " is before valid-from " + from);
        }
        String desc = text(in, "description");
        return new Basket(id, name, desc == null ? "" : desc, owner, type, node, rule, List.copyOf(members), rk, ri, from, to,
                in.get("version") instanceof Number n ? n.intValue() : 0, text(in, "createdAt"), text(in, "updatedAt"), text(in, "updatedBy"),
                text(in, "retiredBy"));
    }

    private static <T> T first(T a, T b) {
        return a != null ? a : b;
    }

    private static String text(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static LocalDate date(String id, String field, String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw invalid(id + ": " + field + " is a date YYYY-MM-DD, not '" + s + "'");
        }
    }

    static DrishtiException invalid(String why) {
        return new DrishtiException(ErrorCode.VAR_SYSTEM_BASKET_INVALID, why);
    }

    // ------------------------------------------------------------------------------------------------------------------------ the store

    private final ContentStore store;
    private final ContentDocs docs;
    private volatile Snapshot snapshot = new Snapshot(-1, Map.of(), List.of());

    /** What was read at one sequence number: the system baskets by id, and the entries that did not read as one. */
    private record Snapshot(long seq, Map<String, Basket> baskets, List<Map<String, Object>> skipped) {}

    VarSystemBaskets(ContentStore store) {
        this.store = store;
        this.docs = new ContentDocs(store);
    }

    private static String key(String id) {
        return FOLDER + id + ".json";
    }

    /** The sequence the cache follows (a change anywhere raises it). */
    long stamp() {
        return ContentDocs.current(store, SEQUENCE);
    }

    private Snapshot read() {
        long seq = stamp();
        Snapshot s = snapshot;
        if (s.seq() == seq) {
            return s;
        }
        Map<String, Basket> out = new TreeMap<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String k : store.list(FOLDER)) {
            try {
                Optional<ContentStore.Item> it = store.get(k);
                if (it.isEmpty()) {
                    continue;
                }
                Basket b = read(ContentDocs.decode(it.get().bytes(), Map.class));
                if (!k.equals(key(b.id()))) {
                    throw invalid("the entry " + k + " holds the system basket " + b.id());
                }
                out.put(b.id(), b);
            } catch (RuntimeException e) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", k);
                m.put("reason", e.getMessage());
                skipped.add(m);
            }
        }
        Snapshot fresh = new Snapshot(seq, Collections.unmodifiableMap(out), List.copyOf(skipped));
        snapshot = fresh;
        return fresh;
    }

    /** Every system basket, retired ones too, by id. */
    Map<String, Basket> all() {
        return read().baskets();
    }

    /** The stored entries that do not read as a system basket: the key and why, left out of everything. */
    List<Map<String, Object>> skipped() {
        return read().skipped();
    }

    Optional<Basket> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(all().get(id.toUpperCase(Locale.ROOT)));
    }

    /** The system baskets valid on a business date (every one when the date is null). */
    Map<String, Basket> on(LocalDate date) {
        Map<String, Basket> out = new LinkedHashMap<>();
        all().forEach((id, b) -> {
            if (b.validOn(date)) {
                out.put(id, b);
            }
        });
        return out;
    }

    /** The date's portfolios the engine resolves {@code PORT:<id>} by: the system baskets valid on it, each with its limit (or NaN). */
    Map<String, VarDate.Portfolio> portfolios(LocalDate date, Map<String, Double> limits) {
        Map<String, VarDate.Portfolio> out = new LinkedHashMap<>();
        on(date).forEach((id, b) -> {
            Double l = limits == null ? null : limits.get(id);
            out.put(id, b.portfolio(l == null ? Double.NaN : l));
        });
        return out;
    }

    boolean isEmpty() {
        return store.list(FOLDER).isEmpty();
    }

    /**
     * Writes a system basket: a new one ({@code create}: refused when the id exists, {@code DRS-5220}), or the next version of one
     * ({@code expected}, when given, the version the editor opened: changed since answers {@code 409}). Returns it as stored.
     */
    @SuppressWarnings("unchecked")
    Basket save(Basket b, boolean create, Integer expected, String actor) {
        String now = Instant.now().toString();
        Map<String, Object> saved = docs.update(key(b.id()), Map.class, old -> {
            if (create && old != null) {
                throw invalid("a system basket " + b.id() + " exists already (version " + old.get("version") + "): open it to edit it");
            }
            if (!create && old == null) {
                throw new DrishtiException(ErrorCode.VAR_SYSTEM_BASKET_UNKNOWN, "no system basket " + b.id() + ": create it first");
            }
            int was = old == null ? 0 : ((Number) old.getOrDefault("version", 0)).intValue();
            if (expected != null && was != expected) {
                throw new DrishtiException(ErrorCode.STALE_REVISION, "the system basket " + b.id() + " changed since you opened it (now version " + was
                        + "): open it again before saving");
            }
            Basket next = b.withHistory(was + 1, old == null ? now : String.valueOf(old.get("createdAt")), now, actor,
                    b.validTo() == null ? null : b.retiredBy());
            return next.toMap();
        });
        ContentDocs.next(store, SEQUENCE);
        return read(saved);
    }

    /** Retires a system basket: valid until {@code to} (inclusive), kept for the dates before; refused when it is retired already then. */
    Basket retire(String id, LocalDate to, Integer expected, String actor) {
        Basket b = get(id).orElseThrow(() -> new DrishtiException(ErrorCode.VAR_SYSTEM_BASKET_UNKNOWN, "no system basket " + id));
        if (b.validFrom() != null && to.isBefore(b.validFrom())) {
            throw invalid(id + ": retired on " + to + " is before it starts (valid from " + b.validFrom() + ")");
        }
        if (b.validTo() != null && !to.isBefore(b.validTo())) {
            throw invalid(id + ": already valid only until " + b.validTo());
        }
        Basket r = b.retiredOn(to);
        r = new Basket(r.id(), r.name(), r.description(), r.owner(), r.type(), r.node(), r.rule(), r.members(), r.rollsUpKind(), r.rollsUpId(),
                r.validFrom(), r.validTo(), r.version(), r.createdAt(), r.updatedAt(), r.updatedBy(), actor);
        return save(r, false, expected == null ? b.version() : expected, actor);
    }

    // ----------------------------------------------------------------------------------------------------------- import and export

    /** The kebab-case names a YAML file uses, from the API's: {@code rollsUpTo} to {@code rolls-up-to}. */
    static Map<String, Object> yamlOf(Basket b) {
        Map<String, Object> out = new LinkedHashMap<>();
        b.definition().forEach((k, v) -> out.put(switch (k) {
            case "rollsUpTo" -> "rolls-up-to";
            case "validFrom" -> "valid-from";
            case "validTo" -> "valid-to";
            default -> k;
        }, v));
        return out;
    }

    /** The system baskets in a file's form: {@code system-baskets: [...]} (retired ones too, with their valid-to). */
    Map<String, Object> export() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("system-baskets", all().values().stream().map(VarSystemBaskets::yamlOf).toList());
        return out;
    }

    /**
     * What importing {@code doc} ({@code system-baskets: [...]}) would do, or does when {@code apply}: per basket {@code add},
     * {@code change} (with each field's old and new value), {@code same} or {@code invalid} (the reason, never written); baskets in the
     * store the file does not name are kept as they are (an import never removes or retires one). {@code check} is the caller's check
     * against the date (an unknown node or position), null when none applies. Written baskets are listed with their new version.
     */
    Map<String, Object> importDoc(Object doc, boolean apply, String actor, java.util.function.Function<Basket, String> check,
            java.util.function.BiConsumer<String, Basket> written) {
        if (!(doc instanceof Map<?, ?> m) || !(m.get("system-baskets") instanceof List<?> list)) {
            throw invalid("an import file is a YAML map with the list system-baskets: [...] (drishti.py var baskets export --scope system)");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        int add = 0;
        int change = 0;
        int same = 0;
        int bad = 0;
        Map<String, Basket> current = all();
        for (Object o : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            Basket b;
            try {
                b = read(o instanceof Map<?, ?> om ? om : null);
                if (!seen.add(b.id())) {
                    throw invalid(b.id() + ": named twice in the file");
                }
                String why = check == null ? null : check.apply(b);
                if (why != null) {
                    throw invalid(why);
                }
            } catch (DrishtiException e) {
                row.put("id", o instanceof Map<?, ?> om && om.get("id") != null ? String.valueOf(om.get("id")) : null);
                row.put("action", "invalid");
                row.put("reason", e.getMessage());
                rows.add(row);
                bad++;
                continue;
            }
            row.put("id", b.id());
            Basket was = current.get(b.id());
            if (was == null) {
                row.put("action", "add");
                add++;
            } else {
                Map<String, Object> diff = diff(was.definition(), b.definition());
                if (diff.isEmpty()) {
                    row.put("action", "same");
                    same++;
                    rows.add(row);
                    continue;
                }
                row.put("action", "change");
                row.put("changes", diff);
                change++;
            }
            if (apply) {
                Basket s = save(b, was == null, was == null ? null : was.version(), actor);
                row.put("version", s.version());
                if (written != null) {
                    written.accept(was == null ? "create" : "edit", s);
                }
            }
            rows.add(row);
        }
        List<String> kept = current.keySet().stream().filter(id -> !seen.contains(id)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", apply);
        out.put("add", add);
        out.put("change", change);
        out.put("same", same);
        out.put("invalid", bad);
        out.put("keptNotInFile", kept);
        out.put("baskets", rows);
        return out;
    }

    /** Each field that differs: its old and new value. */
    static Map<String, Object> diff(Map<String, Object> was, Map<String, Object> now) {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(was.keySet());
        keys.addAll(now.keySet());
        for (String k : keys) {
            Object a = was.get(k);
            Object b = now.get(k);
            if (!java.util.Objects.equals(a, b)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("from", a);
                d.put("to", b);
                out.put(k, d);
            }
        }
        return out;
    }

    /**
     * Imports the seed file into an empty system scope, once per store ({@link #SEEDED} records it): the demo's system baskets at the
     * first start. Returns the number written; 0 when the scope holds baskets already, it was seeded before, or the file has none.
     */
    int seed(Object doc, String actor, java.util.function.BiConsumer<String, Basket> written) {
        if (!isEmpty() || store.exists(SEEDED)) {
            return 0;
        }
        if (!docs.create(SEEDED, Map.of("seededAt", Instant.now().toString(), "by", actor))) {
            return 0;                                                   // another server of the cluster is seeding it
        }
        Map<String, Object> r = importDoc(doc, true, actor, null, written);
        return ((Number) r.get("add")).intValue();
    }
}
