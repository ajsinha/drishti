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

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.rachana.live.spi.Authoriser;
import com.ash.rachana.live.spi.ReaderView;
import com.ash.rachana.table.LiveTableSpec;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Drishti's rights over its live tables (LIVE_TABLES 5, i8 step 3): the caller is the request's {@link Principal}; a table is read by
 * whoever may open its kind (else answered as unknown, so its existence does not leak); their row rule is a test of the row as stored
 * ({@code rowTest}: Drishti's rules read nested fields and the person's attributes, so they are not pushed down); their masks are the
 * entitlements' redactor ({@code redact}) and the declared columns they may not see ({@code maskedColumns}); they may edit the columns
 * their roles may change while the site's switches and their edit power allow it (else read only, with the reason). The audience is
 * everything that decides what they see (security signature, masks, row rule): two people with equal audiences share an index, no
 * one else reads it.
 */
public final class DrishtiAuthoriser implements Authoriser {

    /** What Drishti's entitlements and edit settings answer for one person. */
    public interface Rights {

        boolean mayOpen(Principal p, String kind);

        /** The person's security signature (roles, embedding app) with their masks and administrator view: part of the audience. */
        String signature(Principal p);

        /** Their row rule on the kind: its text (part of the audience) and test; null when they see every row. */
        RowRule rows(Principal p, String kind);

        /** How a record is shown to them (masked values); null when nothing is masked. */
        UnaryOperator<Map<String, Object>> redact(Principal p);

        /** Whether a field is masked for them. */
        boolean masked(Principal p, String field);

        /** Why they may not edit the table (the site's switches, their edit power, the kind), or null when they may. */
        String readOnly(Principal p, LiveTableSpec spec);

        /** Their roles as the declaration's {@code roles} and {@code editable-by} name them. */
        List<String> formRoles(Principal p);
    }

    /** A row rule: its text and its test of the row as stored. */
    public record RowRule(String text, Predicate<Map<String, Object>> test) {}

    private final Function<String, LiveTableSpec> specs;
    private final Rights rights;

    public DrishtiAuthoriser(Function<String, LiveTableSpec> specs, Rights rights) {
        this.specs = specs;
        this.rights = rights;
    }

    /** The kind a table holds: its profile's {@code kind}, else its name. */
    public static String kind(LiveTableSpec spec) {
        Object k = spec.profile().get("kind");
        return k == null ? spec.name() : String.valueOf(k);
    }

    private static Principal principal(Object caller) throws Refusal {
        if (caller instanceof Principal p) {
            return p;
        }
        throw new Refusal("no signed-in caller", true);
    }

    @Override
    public ReaderView view(Object caller, String table) throws Refusal {
        Principal p = principal(caller);
        LiveTableSpec spec = specs.apply(table);
        if (spec == null) {
            throw new Refusal("no table " + table, true);
        }
        String kind = kind(spec);
        if (!rights.mayOpen(p, kind)) {
            throw new Refusal(p.user() + " may not open " + kind + " records", true);
        }
        RowRule rule = rights.rows(p, kind);
        UnaryOperator<Map<String, Object>> redact = rights.redact(p);
        Set<String> masked = new LinkedHashSet<>();
        if (redact != null) {
            spec.columns().forEach(c -> {
                if (rights.masked(p, c.name())) {
                    masked.add(c.name());
                }
            });
        }
        String audience = rights.signature(p) + (rule == null ? "" : "|rows=" + rule.text() + "|" + p.user());
        String no = spec.edit() == null ? "the table is read only" : rights.readOnly(p, spec);
        Set<String> editable = no == null ? editable(spec, rights.formRoles(p)) : Set.of();
        if (no == null && editable.isEmpty()) {
            no = "no column of " + table + " is editable for " + p.user() + "'s roles";
        }
        return new ReaderView(audience, null, masked, rule == null ? null : rule.test(), redact, editable, no);
    }

    /** The columns of a declaration the roles may change: each field's {@code editable-by}, else the form's {@code roles}. */
    @SuppressWarnings("unchecked")
    static Set<String> editable(LiveTableSpec spec, List<String> held) {
        Set<String> out = new LinkedHashSet<>();
        Object roles = spec.edit().get("roles");
        if (spec.edit().get("fields") instanceof List<?> fields) {
            for (Object o : fields) {
                if (!(o instanceof Map<?, ?> f) || Boolean.TRUE.equals(f.get("readonly")) || f.get("bind") == null) {
                    continue;
                }
                Object by = f.get("editable-by") != null ? f.get("editable-by") : roles;
                if (by == null || by instanceof List<?> l && l.stream().map(String::valueOf).anyMatch(held::contains)) {
                    out.add(String.valueOf(f.get("bind")));
                }
            }
        }
        out.retainAll(spec.editableColumns());
        return out;
    }

    @Override
    public Decision mayEdit(Object caller, String table, Map<String, Object> change, Map<String, Object> row) {
        try {
            Principal p = principal(caller);
            LiveTableSpec spec = specs.apply(table);
            String no = spec == null ? "no table " + table : rights.readOnly(p, spec);
            return no == null ? new Decision.Allow() : new Decision.Refuse(no);
        } catch (Refusal r) {
            return new Decision.Refuse(r.getMessage());
        }
    }

    @Override
    public String who(Object caller) {
        return caller instanceof Principal p ? p.user() : null;
    }
}
