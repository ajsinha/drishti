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
import com.ash.drishti.common.DrishtiException;
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
 * The computed {@code position} kind (docs/architecture/KERNEL_AND_MODULES.md section 5.7): the positions Drishti derives from the trades
 * when a date loads (VAR_GUIDE.md section 15.6), served from the VaR engine's memory (never stored: rebuilt from the date's trades and the
 * rules, reproducible). Dated: a position is read on a business date, the newest with vectors when it is later. A read when the engine is
 * off or has no vectors finds nothing. Not bindable: the kernel serves {@code position} from this provider, shown as COMPUTED.
 */
public final class VarPositionsProvider implements KindProvider {

    public static final String NAME = "var-positions";
    private static final String KIND = "position";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(KIND);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        if (!KIND.equals(ref.kind())) {
            return Optional.empty();
        }
        return positions().flatMap(p -> {
            try {
                LocalDate d = asOf == null ? null : asOf.businessDate();
                return p.position(ref.id(), d).map(m -> new EntityDocument(ref, DataNode.of(m),
                        new Provenance(NAME, generation(p, d), Instant.now(), false, LocalDate.parse(String.valueOf(m.get("BUSINESS_DATE"))))));
            } catch (DrishtiException e) {
                return Optional.empty();                                // no vectors, the engine off, a date that cannot load: nothing
            }
        });
    }

    private static long generation(VarPositions p, LocalDate d) {
        return p.version(d).hashCode() & 0xffffffffL;
    }

    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        if (!KIND.equals(kind)) {
            return false;
        }
        Optional<VarPositions> p = positions();
        if (p.isEmpty()) {
            return true;
        }
        try {
            LocalDate d = asOf == null ? null : asOf.businessDate();
            long gen = generation(p.get(), d);
            p.get().positions(d, m -> sink.accept(new EntityDocument(EntityRef.of(kind, String.valueOf(m.get("POSITION_ID"))), DataNode.of(m),
                    new Provenance(NAME, gen, Instant.now(), false, LocalDate.parse(String.valueOf(m.get("BUSINESS_DATE")))))));
        } catch (DrishtiException e) {
            // nothing loaded: an empty listing
        }
        return true;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        List<EntityHit> out = new ArrayList<>();
        if (!KIND.equals(kind)) {
            return out;
        }
        String q = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        documents(kind, asOf, d -> {
            if (out.size() < limit) {
                String id = d.ref().id();
                String ins = d.data().get("INSTRUMENT") == null ? "" : d.data().get("INSTRUMENT").asText();
                if (q.isEmpty() || id.toUpperCase(Locale.ROOT).contains(q) || ins.toUpperCase(Locale.ROOT).contains(q)) {
                    out.add(new EntityHit(d.ref(), id, ins));
                }
            }
        });
        return out;
    }

    @Override
    public Optional<LocalDate> latestDate(String kind) {
        return KIND.equals(kind) ? positions().map(VarPositions::latest) : Optional.empty();
    }

    @Override
    public Optional<String> dataVersion(String kind, LocalDate date) {
        if (!KIND.equals(kind)) {
            return Optional.empty();
        }
        try {
            return positions().map(p -> p.version(date));
        } catch (DrishtiException e) {
            return Optional.empty();
        }
    }

    /** Positions follow their trades' vectors and the hierarchy: a load of those makes them stale. */
    @Override
    public boolean dependsOn(String kind) {
        return positions().map(p -> p.service().props().kinds().inputs().contains(kind)).orElse(false);
    }

    @Override
    public Map<String, Object> stats() {
        return Map.of("cached", "the VaR engine's loaded dates (GET /api/v1/risk/var/status)");
    }

    private static Optional<VarPositions> positions() {
        return VarPositions.installed().filter(p -> p.service().running());
    }
}
