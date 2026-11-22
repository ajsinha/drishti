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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.engine.source.RowPredicate;
import com.ash.drishti.enginehost.RowRule;
import com.ash.drishti.enginehost.ViewerAccess;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.ash.engine.Filter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A caller as the engine host sees them: the server's one access model (Entitlements) asked for each kind an engine reads, never a second
 * set of rules. Made per request; never kept beyond the per-viewer session it keys.
 */
final class PrincipalAccess implements ViewerAccess {

    private final Principal principal;
    private final Entitlements entitlements;
    private final PackRegistry packs;
    private final Map<String, RowRule> rules = new java.util.concurrent.ConcurrentHashMap<>();

    PrincipalAccess(Principal principal, Entitlements entitlements, PackRegistry packs) {
        this.principal = principal;
        this.entitlements = entitlements;
        this.packs = packs;
    }

    @Override
    public String actor() {
        return principal.user();
    }

    @Override
    public Set<String> roles() {
        return new HashSet<>(principal.roles());
    }

    @Override
    public boolean admin() {
        return entitlements.isAdmin(principal);
    }

    @Override
    public boolean mayOpen(String kind) {
        return entitlements.mayOpen(principal, kind);
    }

    @Override
    public boolean mayOpenPack(String pack) {
        return packs != null && packs.packs().stream().filter(p -> p.name().equals(pack)).map(Pack::kinds).flatMap(List::stream).anyMatch(this::mayOpen);
    }

    @Override
    public RowRule rows(String kind) {
        return rules.computeIfAbsent(kind, k -> entitlements.rowRule(principal, k).<RowRule>map(Predicate::new).orElse(RowRule.ALL));
    }

    @Override
    public boolean masked(String field) {
        return entitlements.masked(principal, field);
    }

    /** A role's row rule ({@link RowPredicate}) as the host evaluates it over a row's fields. */
    static final class Predicate implements RowRule {

        private final RowPredicate p;

        Predicate(RowPredicate p) {
            this.p = p;
        }

        @Override
        public boolean all() {
            return p.all();
        }

        @Override
        public Set<String> fields() {
            return p.fields();
        }

        @Override
        public boolean test(Function<String, Object> row) {
            Map<String, Object> m = new HashMap<>();
            for (String f : p.fields()) {
                Object v = row.apply(f);
                if (v != null) {
                    m.put(f, v);
                }
            }
            return p.test(DataNode.of(m));
        }

        @Override
        public String text() {
            return p.text();
        }

        /** A rule of one field compared with constants ({@code DESK in $user.desks}, {@code SOURCE_SYSTEM = 'ENDUR'}) is a filter; others are not. */
        @Override
        public Optional<com.ash.engine.Filter> asFilter() {
            if (p.all()) {
                return Optional.of(Filter.ALL);
            }
            if (p.fields().size() != 1 || p.pushdown().size() != 1) {
                return Optional.empty();
            }
            ScanRequest.Filter f = p.pushdown().get(0);
            if (f.op() == ScanRequest.Op.EQ && f.values().size() == 1) {
                return Optional.of(new Filter.Eq(f.path(), String.valueOf(f.values().get(0))));
            }
            if (f.op() == ScanRequest.Op.IN) {
                return Optional.of(new Filter.In(f.path(), new HashSet<>(f.values().stream().map(String::valueOf).toList())));
            }
            return Optional.empty();
        }
    }
}
