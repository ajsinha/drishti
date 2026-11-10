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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.kernel.api.data.KindProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The computed {@code system-basket} kind (docs/architecture/KERNEL_AND_MODULES.md section 5.7): the system baskets (VAR_GUIDE.md section
 * 5.7) kept in the basket store, so {@code PORT <id> <GO>} opens a system basket's page (its definition; the VaR engine adds its figures as
 * the page opens) and {@code PORT <GO>} lists them. Dated: a basket is served on the business dates it is valid on; a read without a date
 * is the VaR engine's newest date. Nothing is stored here: a change in the store is seen at the next read (its data version follows the
 * store's sequence).
 */
public final class VarSystemBasketsProvider implements KindProvider {

    public static final String NAME = "var-system-baskets";
    private static final String KIND = "system-basket";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(KIND);
    }

    private static Optional<VarService> service() {
        return VarPositions.installed().map(VarPositions::service);
    }

    /** The business date read: the one asked, else the VaR engine's newest date, else today. */
    private static LocalDate date(VarService s, AsOf asOf) {
        LocalDate d = asOf == null ? null : asOf.businessDate();
        if (d != null) {
            return d;
        }
        try {
            LocalDate latest = s.running() ? s.loader().latest() : null;
            return latest != null ? latest : LocalDate.now(java.time.ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return LocalDate.now(java.time.ZoneOffset.UTC);
        }
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        if (!KIND.equals(ref.kind())) {
            return Optional.empty();
        }
        return service().flatMap(s -> {
            LocalDate d = date(s, asOf);
            VarSystemBaskets store = s.saved().system();
            return store.get(ref.id()).filter(b -> b.validOn(d)).map(b -> doc(ref.kind(), b, d, store.stamp()));
        });
    }

    private static EntityDocument doc(String kind, VarSystemBaskets.Basket b, LocalDate d, long stamp) {
        return new EntityDocument(EntityRef.of(kind, b.id()), DataNode.of(b.document(d)), new Provenance(NAME, stamp, Instant.now(), false, d));
    }

    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        if (!KIND.equals(kind)) {
            return false;
        }
        service().ifPresent(s -> {
            LocalDate d = date(s, asOf);
            VarSystemBaskets store = s.saved().system();
            long stamp = store.stamp();
            store.on(d).values().forEach(b -> sink.accept(doc(kind, b, d, stamp)));
        });
        return true;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        List<EntityHit> out = new ArrayList<>();
        if (!KIND.equals(kind)) {
            return out;
        }
        String q = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        service().ifPresent(s -> {
            for (VarSystemBaskets.Basket b : s.saved().system().on(date(s, asOf)).values()) {
                if (out.size() >= limit) {
                    break;
                }
                String hay = (b.id() + " " + b.name() + " " + b.description() + " " + b.owner()).toUpperCase(Locale.ROOT);
                if (q.isEmpty() || hay.contains(q)) {
                    out.add(new EntityHit(EntityRef.of(kind, b.id()), b.name(), b.type() + " · " + b.owner()));
                }
            }
        });
        return out;
    }

    @Override
    public Optional<LocalDate> latestDate(String kind) {
        return KIND.equals(kind) ? service().map(s -> date(s, AsOf.LATEST)) : Optional.empty();
    }

    @Override
    public Optional<String> dataVersion(String kind, LocalDate date) {
        return KIND.equals(kind) ? service().map(s -> "sb-" + s.saved().system().stamp()) : Optional.empty();
    }

    @Override
    public Map<String, Object> stats() {
        return Map.of("cached", "the system baskets of the basket store (GET /api/v1/risk/var/system-baskets)");
    }
}
