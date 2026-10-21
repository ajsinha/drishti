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
package com.ash.drishti.server.bi.plan;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.ash.drishti.server.bi.expr.DelPrinter;
import com.ash.drishti.server.bi.expr.Expressions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A reader's <em>effective</em> security on a model, as one opaque id (docs/architecture/BI_DESIGN.md section 5.4): for every dataset alias, the row
 * predicates that apply to them with {@code user.*} resolved to values, the masked and hidden columns, and the tables over kinds they may not open. Two readers
 * with the same desks, rules and masks get the same signature and share result caches and standing-query evaluations; the signature never reveals a predicate
 * (it is a hash; meta and audit carry only the id). Pure.
 */
public final class SecuritySignature {

    /**
     * What decides a reader's security on a model. {@code user} counts only for a model whose rows are another question's answer asked as the reader (a
     * composed or derived dataset, a dataset read from another model, a connector query or Python source): its security is the sources', so such a model's
     * signature is the reader's own.
     */
    public record Reader(String user, boolean enforce, Set<String> roles, Map<String, List<Object>> attributes, SqlCompiler.Masks masks, Set<String> denied,
            Set<String> hidden) {
        public Reader {
            roles = Set.copyOf(roles);
            attributes = attributes == null ? Map.of() : attributes;
            masks = masks == null ? SqlCompiler.Masks.NONE : masks;
            denied = denied == null ? Set.of() : Set.copyOf(denied);
            hidden = hidden == null ? Set.of() : Set.copyOf(hidden);
        }
    }

    /** One alias's side of it: the resolved predicates (one per layer; {@code filters} first), whether a layer shuts the reader out, the masked columns. */
    public record Alias(List<String> rules, boolean denied, List<String> masked, List<String> hidden) {}

    private SecuritySignature() {}

    /** The signature id ({@code s_} and 16 hex digits) of a reader on a flattened model or dataset. */
    public static String of(Dataset flat, Reader reader) {
        return of(flat, reader, false);
    }

    /** As {@link #of(Dataset, Reader)}; {@code personal}: the model reads answers asked as the reader, so the reader is part of the signature. */
    public static String of(Dataset flat, Reader reader, boolean personal) {
        Map<String, Alias> all = new TreeMap<>();
        for (String t : flat.tables().keySet()) {
            all.put(t, alias(flat, t, reader));
        }
        StringBuilder b = new StringBuilder("enforce=").append(reader.enforce()).append(';');
        if (reader.enforce() && !flat.security().rows().isEmpty()) {         // a version-1 dataset: rules joined into every query
            List<String> v1 = new ArrayList<>();
            flat.security().rows().stream().filter(r -> reader.roles().contains(r.role())).forEach(r -> v1.add(resolved(r.where(), reader.attributes())));
            b.append("v1=").append(new TreeSet<>(v1)).append(v1.isEmpty() ? "DENY" : "").append(';');
        }
        all.forEach((t, a) -> b.append(t).append('=').append(a).append(';'));
        b.append("deniedKinds=").append(new TreeSet<>(reader.denied()));
        if (personal || flat.framed()) {
            b.append(";reader=").append(reader.user()).append(new TreeSet<>(reader.roles())).append(new TreeMap<>(reader.attributes()));
        }
        return "s_" + sha(b.toString()).substring(0, 16);
    }

    /**
     * The <b>row part</b> of the signature (docs/architecture/BI_RUNS.md section 9.3): everything that decides which rows a reader sees, and nothing about
     * masks. Two readers with an equal row part read the same rows of every alias: their rule predicates, denied layers, hidden columns and denied kinds are
     * the same (and, for a model of personal answers, they are the same reader). Readable text, compared and never shown.
     */
    public static String rowPart(Dataset flat, Reader reader, boolean personal) {
        StringBuilder b = new StringBuilder("enforce=").append(reader.enforce()).append(';');
        if (reader.enforce() && !flat.security().rows().isEmpty()) {
            List<String> v1 = new ArrayList<>();
            flat.security().rows().stream().filter(r -> reader.roles().contains(r.role())).forEach(r -> v1.add(resolved(r.where(), reader.attributes())));
            b.append("v1=").append(new TreeSet<>(v1)).append(v1.isEmpty() ? "DENY" : "").append(';');
        }
        for (String t : new TreeSet<>(flat.tables().keySet())) {
            Alias a = alias(flat, t, reader);
            b.append(t).append("=rules").append(a.rules()).append(",denied=").append(a.denied()).append(",hidden=").append(a.hidden()).append(';');
        }
        b.append("deniedKinds=").append(new TreeSet<>(reader.denied()));
        if (personal || flat.framed()) {
            b.append(";reader=").append(reader.user()).append(new TreeSet<>(reader.roles())).append(new TreeMap<>(reader.attributes()));
        }
        return b.toString();
    }

    /** The <b>mask part</b>: every column masked for the reader, as {@code alias.column}, sorted. */
    public static Set<String> maskPart(Dataset flat, Reader reader) {
        TreeSet<String> out = new TreeSet<>();
        for (String t : flat.tables().keySet()) {
            alias(flat, t, reader).masked().forEach(c -> out.add(t + "." + c));
        }
        return out;
    }

    /** One alias's effective security for the reader. */
    public static Alias alias(Dataset flat, String table, Reader reader) {
        List<String> rules = new ArrayList<>();
        boolean denied = false;
        Dataset.TableSecurity s = flat.security().tables().get(table);
        if (s != null) {
            s.filters().forEach(f -> rules.add("filter " + resolved(f, reader.attributes())));
            if (reader.enforce()) {
                for (List<Dataset.RowRule> layer : s.layers()) {
                    if (layer.isEmpty()) {
                        continue;
                    }
                    TreeSet<String> or = new TreeSet<>();
                    layer.stream().filter(r -> reader.roles().contains(r.role())).forEach(r -> or.add(resolved(r.where(), reader.attributes())));
                    if (or.isEmpty()) {
                        denied = true;
                    } else {
                        rules.add(String.join(" or ", or));
                    }
                }
            }
        }
        if (reader.enforce() && !flat.security().perTable() && !flat.security().rows().isEmpty()) {
            TreeSet<String> or = new TreeSet<>();                               // a version-1 dataset: the rules that name this table
            for (Dataset.RowRule r : flat.security().rows()) {
                if (reader.roles().contains(r.role()) && names(r.where(), table)) {
                    or.add(resolved(r.where(), reader.attributes()));
                }
            }
            if (!or.isEmpty()) {
                rules.add(String.join(" or ", or));
            }
            denied = flat.security().rows().stream().noneMatch(r -> reader.roles().contains(r.role()));
        }
        List<String> masked = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        Dataset.Table t = flat.tables().get(table);
        if (t != null) {
            for (String c : t.columns().keySet()) {
                if (reader.masks().masked(table, c)) {
                    masked.add(c);
                }
                if (reader.hidden().contains(table + "." + c)) {
                    hidden.add(c);
                }
            }
        }
        return new Alias(rules, denied, masked, hidden);
    }

    private static boolean names(String where, String table) {
        try {
            return Node.anyMatch(Expressions.parse(where), x -> x instanceof Node.Ref r && r.parts().size() >= 2 && r.parts().get(0).equals(table));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A predicate with the reader's attributes written as values (sorted where it is a list), in canonical DEL. */
    static String resolved(String where, Map<String, List<Object>> attributes) {
        Node n;
        try {
            n = Expressions.parse(where);
        } catch (RuntimeException e) {
            return where;
        }
        try {
            return DelPrinter.print(bind(n, attributes));
        } catch (RuntimeException e) {
            return n.toString();
        }
    }

    private static Node bind(Node n, Map<String, List<Object>> attrs) {
        return switch (n) {
            case Node.UserRef u -> value(attrs.getOrDefault(u.attribute(), List.of()).isEmpty() ? "" : attrs.get(u.attribute()).get(0));
            case Node.In i -> {
                List<Node> items = new ArrayList<>();
                for (Node item : i.items()) {
                    if (item instanceof Node.UserRef u) {
                        new TreeSet<>(attrs.getOrDefault(u.attribute(), List.of()).stream().map(String::valueOf).toList()).forEach(v -> items.add(value(v)));
                    } else {
                        items.add(bind(item, attrs));
                    }
                }
                yield items.isEmpty() ? new Node.Bool(i.negated()) : new Node.In(bind(i.value(), attrs), items, i.negated());
            }
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> bind(a, attrs)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), bind(b.left(), attrs), bind(b.right(), attrs));
            case Node.Un u -> new Node.Un(u.op(), bind(u.operand(), attrs));
            case Node.Cond c -> new Node.Cond(bind(c.test(), attrs), bind(c.yes(), attrs), bind(c.no(), attrs));
            default -> n;
        };
    }

    private static Node value(Object v) {
        if (v instanceof Number num) {
            return new Node.Num(num.doubleValue(), num.doubleValue() == Math.rint(num.doubleValue()));
        }
        if (v instanceof Boolean bo) {
            return new Node.Bool(bo);
        }
        return new Node.Str(String.valueOf(v));
    }

    public static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
