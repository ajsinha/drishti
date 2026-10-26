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
package com.ash.drishti.compute.var;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * How Drishti derives positions from the trades of a business date when it loads the date (VAR_GUIDE.md section 15.6): configuration, never
 * code. A trade is live when its status is one of the live statuses (a trade with no status is live: the batch valued it); the first
 * rule whose product groups or product types hold the trade decides its position's key, the first of the rule's key fields the trade
 * carries (a security id, an ISIN, a ticker, an exchange contract). A trade with no such field, or matched by no rule, is a position of
 * its own (OTC). The position id is {@code POS-<TT_BOOK>~<key>}: trades of one security in one book are one fungible position, their
 * signed quantities netting.
 *
 * <p>Immutable; the same rules and trades always give the same positions, so a date rebuilt from its inputs has the same members (each
 * official capture records the rules' {@link #digest()}).
 */
public final class PositionRules {

    /** The statuses of a live trade when the configuration names none. */
    public static final List<String> LIVE = List.of("NEW", "VALIDATED", "AMENDED");
    /** The rule a trade no rule matches falls under: one position per trade. */
    public static final String OTC = "otc";

    /**
     * One rule.
     *
     * @param name what the rule is called (securities, listed, otc): the position's {@code POSITION_RULE}
     * @param groups the product groups it takes (any type of them), compared ignoring case
     * @param types the product types it takes (in any group), compared ignoring case
     * @param key the fields whose first present value keys the position; empty: one position per trade
     */
    public record Rule(String name, Set<String> groups, Set<String> types, List<String> key) {
        public Rule {
            groups = upper(groups);
            types = upper(types);
            key = key == null ? List.of() : List.copyOf(key);
        }

        /** True when the rule takes the trade: it names neither groups nor types, or the trade's group or type is among them. */
        public boolean matches(String group, String type) {
            if (groups.isEmpty() && types.isEmpty()) {
                return true;
            }
            return group != null && groups.contains(group.toUpperCase(Locale.ROOT)) || type != null && types.contains(type.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Where a trade belongs.
     *
     * @param positionId {@code POS-<TT_BOOK>~<key>}
     * @param instrumentId the key's value (a security id), or the trade id for a position of one trade
     * @param securityId the key's value, null for a position of one trade
     * @param rule the rule's name
     */
    public record Derived(String positionId, String instrumentId, String securityId, String rule) {}

    private final Set<String> live;
    private final List<Rule> rules;
    private final String digest;

    public PositionRules(Collection<String> liveStatuses, List<Rule> rules) {
        this.live = upper(liveStatuses == null || liveStatuses.isEmpty() ? LIVE : liveStatuses);
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.digest = sha(toString());
    }

    /** The default: the live statuses above, no rule (every trade its own position). */
    public static PositionRules defaults() {
        return new PositionRules(LIVE, List.of());
    }

    /**
     * Rules from a configuration section as YAML reads it: {@code live-statuses: [..]}, {@code rules: [{name, groups, types, key}]}.
     * Unknown entries are ignored; a rule without a name is called {@code rule-<n>}.
     */
    public static PositionRules of(Map<?, ?> section) {
        if (section == null) {
            return defaults();
        }
        List<Rule> out = new ArrayList<>();
        if (section.get("rules") instanceof List<?> l) {
            int n = 0;
            for (Object o : l) {
                n++;
                if (o instanceof Map<?, ?> m) {
                    Object name = m.get("name");
                    out.add(new Rule(name == null ? "rule-" + n : String.valueOf(name), strings(m.get("groups")), strings(m.get("types")),
                            List.copyOf(strings(m.get("key")))));
                }
            }
        }
        return new PositionRules(strings(section.get("live-statuses")), out);
    }

    private static Set<String> strings(Object o) {
        Set<String> out = new LinkedHashSet<>();
        if (o instanceof Collection<?> c) {
            c.forEach(x -> out.add(String.valueOf(x).trim()));
        } else if (o instanceof Map<?, ?> m) {                       // a list bound from properties: {0: a, 1: b}
            m.values().forEach(x -> out.add(String.valueOf(x).trim()));
        } else if (o != null && !String.valueOf(o).isBlank()) {
            for (String s : String.valueOf(o).split(",")) {
                out.add(s.trim());
            }
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    private static Set<String> upper(Collection<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            in.forEach(s -> out.add(s.trim().toUpperCase(Locale.ROOT)));
        }
        return Set.copyOf(out);
    }

    /** True when a trade of this status counts on its date (no status: the batch valued it, so it counts). */
    public boolean live(String status) {
        return status == null || status.isBlank() || live.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    public List<Rule> rules() {
        return rules;
    }

    public Set<String> liveStatuses() {
        return live;
    }

    /** Every field a rule keys positions by: the trade columns the loader reads besides its own. */
    public Set<String> keyFields() {
        Set<String> out = new LinkedHashSet<>();
        rules.forEach(r -> out.addAll(r.key()));
        return out;
    }

    /**
     * The position of one trade: the first rule that takes it, keyed by the first of its key fields the trade carries (non-blank);
     * none: a position of its own.
     *
     * @param field the trade's value of a key field, null when it has none
     */
    public Derived derive(String tradeId, String book, String group, String type, Function<String, String> field) {
        String b = book == null ? "" : book;
        for (Rule r : rules) {
            if (!r.matches(group, type)) {
                continue;
            }
            for (String k : r.key()) {
                String v = field.apply(k);
                if (v != null && !v.isBlank()) {
                    return new Derived("POS-" + b + "~" + v.trim(), v.trim(), v.trim(), r.name());
                }
            }
            return new Derived("POS-" + b + "~" + tradeId, tradeId, null, r.key().isEmpty() ? r.name() : OTC);
        }
        return new Derived("POS-" + b + "~" + tradeId, tradeId, null, OTC);
    }

    /** A short stable hash of the rules: what an official capture records so its positions can be derived again. */
    public String digest() {
        return digest;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("live=").append(new java.util.TreeSet<>(live));
        for (Rule r : rules) {
            sb.append("; ").append(r.name()).append(" groups=").append(new java.util.TreeSet<>(r.groups())).append(" types=")
                    .append(new java.util.TreeSet<>(r.types())).append(" key=").append(r.key());
        }
        return sb.toString();
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
