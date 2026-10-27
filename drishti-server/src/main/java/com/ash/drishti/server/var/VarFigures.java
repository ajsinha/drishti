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
package com.ash.drishti.server.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.ViewAugmenter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The VaR engine's figures on the pages of the position, portfolio and desk (VAR_GUIDE.md section 21.3): none of these kinds stores a VaR any
 * more, so when the page is opened the engine computes them as the caller (their entitlements by node, their field masks) and the view
 * reads them under {@code VAR_ENGINE}: a position's VaR and ES, its component in its book and its trades' own VaR and components; a
 * portfolio's VaR, ES, limit use, positions with their components, the split by product, currency and direction, and its P&amp;L over
 * the scenario days; a desk's VaR, ES and limit use; a legal entity's, with each of its books' VaR beside the record's book rows. When the engine cannot answer (off, no vectors, the caller may not see VaR) it
 * says why in {@code VAR_ENGINE.NOTE} and the page shows the rest.
 */
final class VarFigures implements ViewAugmenter {

    static final String FIELD = "VAR_ENGINE";
    private static final String LEGAL_ENTITY = "legal-entity";
    private static final int TOP = 10;
    private final VarService svc;
    private final VarViews views;

    VarFigures(VarService svc, VarViews views) {
        this.svc = svc;
        this.views = views;
    }

    VarService service() {
        return svc;
    }

    @Override
    public boolean augments(String kind) {
        return Set.of(svc.props().kinds().position(), svc.props().kinds().portfolio(), "desk", LEGAL_ENTITY).contains(kind);
    }

    @Override
    public EntityDocument augment(EntityDocument doc, AsOf asOf) {
        if (!(doc.data() instanceof DataNode.Obj o) || doc.deleted()) {
            return doc;
        }
        Map<String, Object> figures;
        Optional<Principal> who = caller();
        if (who.isEmpty()) {
            figures = Map.of("STATUS", "unavailable", "NOTE", "the VaR figures are computed for a signed-in viewer");
        } else {
            LocalDate date = asOf == null ? null : asOf.businessDate();
            if (date == null) {
                date = doc.provenance().businessDate();
            }
            try {
                String kind = doc.ref().kind();
                figures = kind.equals(svc.props().kinds().position()) ? position(who.get(), doc.ref().id(), date)
                        : kind.equals(svc.props().kinds().portfolio()) ? portfolio(who.get(), doc.ref().id(), date)
                        : kind.equals(LEGAL_ENTITY) ? legalEntity(who.get(), doc.ref().id(), o, date)
                        : desk(who.get(), doc.ref().id(), date);
            } catch (DrishtiException | IllegalArgumentException e) {
                figures = Map.of("STATUS", "unavailable", "NOTE", String.valueOf(e.getMessage()));
            }
        }
        Map<String, DataNode> fields = new LinkedHashMap<>(o.fields());
        fields.put(FIELD, DataNode.of(figures));
        return new EntityDocument(doc.ref(), new DataNode.Obj(fields), doc.provenance(), doc.deleted());
    }

    /** The request's caller (the token's user), or the viewer a live view acts for; empty outside both. */
    private static Optional<Principal> caller() {
        Optional<Principal> p = NetCaller.current().map(NetCaller.Caller::principal).filter(java.util.Objects::nonNull);
        return p.isPresent() ? p : Viewer.current().map(v -> new Principal(v.user(), v.roles()));
    }

    private Map<String, Object> head(Principal p, String scope, LocalDate date) {
        Map<String, Object> h = views.headline(p, scope, date);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("STATUS", "ok");
        m.put("SCOPE", h.get("canonical"));
        m.put("BUSINESS_DATE", h.get("businessDate"));
        m.put("VAR", h.get("var"));
        m.put("ES", h.get("es"));
        m.put("CONFIDENCE", h.get("confidence"));
        m.put("ES_CONFIDENCE", h.get("esConfidence"));
        m.put("LIMIT", h.get("limit"));
        m.put("LIMIT_USE", h.get("limitUse"));
        m.put("NOTE", "computed by the VaR engine as you opened the page (" + h.get("businessDate") + "); VAR " + h.get("canonical") + " <GO>");
        return m;
    }

    private List<Long> pnl(Principal p, String scope, LocalDate date) {
        Object s = views.path(p, scope, date, 0, 1).get("series");
        List<Long> out = new ArrayList<>();
        if (s instanceof long[] a) {
            for (long v : a) {
                out.add(v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Principal p, String scope, LocalDate date, String by) {
        try {
            return (List<Map<String, Object>>) views.contributions(p, scope, date, by, null).getOrDefault("rows", List.of());
        } catch (DrishtiException e) {
            return List.of();                                            // an aggregate-only viewer: no positions or trades
        }
    }

    private Map<String, Object> position(Principal p, String id, LocalDate date) {
        String scope = "POS:" + id;
        Map<String, Object> m = head(p, scope, date);
        List<Map<String, Object>> trades = new ArrayList<>();
        for (Map<String, Object> r : rows(p, scope, date, "trade")) {
            trades.add(row("TRADE_ID", r, "standaloneVar", "componentVar"));
        }
        trades.sort(Comparator.comparing(r -> String.valueOf(r.get("TRADE_ID"))));
        m.put("TRADES", trades);
        VarService.Scoped s = svc.scoped(p, scope, date);
        if (s.res().position() >= 0) {
            String book = s.date().book().positions().text("TT_BOOK", s.res().position());
            String bookScope = "BOOK:" + book;
            Map<String, Object> bh = views.headline(p, bookScope, date);
            m.put("BOOK_VAR", bh.get("var"));
            for (Map<String, Object> r : rows(p, bookScope, date, "position")) {
                if (s.date().book().positionId(s.res().position()).equals(r.get("key"))) {
                    m.put("COMPONENT_VAR", r.get("componentVar"));
                    m.put("PCT_OF_BOOK_VAR", r.get("pct"));
                }
            }
        }
        m.put("PNL", pnl(p, scope, date));
        return m;
    }

    private Map<String, Object> portfolio(Principal p, String id, LocalDate date) {
        String scope = "PORT:" + id;
        Map<String, Object> m = head(p, scope, date);
        List<Map<String, Object>> positions = new ArrayList<>();
        for (Map<String, Object> r : rows(p, scope, date, "position")) {
            Map<String, Object> x = row("POSITION_ID", r, "standaloneVar", "componentVar");
            x.put("INSTRUMENT", r.get("instrument"));
            x.put("DIRECTION", r.get("direction"));
            x.put("QUANTITY", r.get("quantity"));
            x.put("MARKET_VALUE", r.get("marketValue"));
            x.put("CURRENCY", r.get("currency"));
            x.put("TT_BOOK", r.get("book"));
            x.put("PRODUCT_TYPE", r.get("productType"));
            x.put("TRADES", r.get("trades"));
            x.put("PARTIAL", r.get("partial"));
            x.put("PCT_OF_VAR", r.get("pct"));
            positions.add(x);
        }
        m.put("POSITIONS", positions);
        m.put("POSITION_COUNT", positions.size());
        m.put("LONG_COUNT", positions.stream().filter(x -> "LONG".equals(x.get("DIRECTION"))).count());
        m.put("SHORT_COUNT", positions.stream().filter(x -> "SHORT".equals(x.get("DIRECTION"))).count());
        m.put("GROSS_MV", Math.round(positions.stream().mapToDouble(x -> Math.abs(num(x.get("MARKET_VALUE")))).sum()));
        m.put("NET_MV", Math.round(positions.stream().mapToDouble(x -> num(x.get("MARKET_VALUE"))).sum()));
        m.put("TOP_CONTRIBUTORS", positions.subList(0, Math.min(TOP, positions.size())));
        m.put("BY_PRODUCT", by(positions, "PRODUCT_TYPE"));
        m.put("BY_CURRENCY", by(positions, "CURRENCY"));
        m.put("BY_DIRECTION", by(positions, "DIRECTION"));
        m.put("PNL", pnl(p, scope, date));
        return m;
    }

    private Map<String, Object> desk(Principal p, String id, LocalDate date) {
        return head(p, "DESK:" + id, date);
    }

    /**
     * A legal entity's VaR (LE:&lt;id&gt;) and each of its books' (BOOK:&lt;id&gt;) beside the record's own book rows (BOOK, DESK,
     * PNL, MTM, TRADES): the record stores no VaR since 2.9.2. A book the engine cannot answer for keeps its row with no VAR.
     */
    private Map<String, Object> legalEntity(Principal p, String id, DataNode.Obj doc, LocalDate date) {
        Map<String, Object> m = head(p, "LE:" + id, date);
        List<Map<String, Object>> books = new ArrayList<>();
        DataNode rows = doc.get("BOOKS");
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i).unwrap() instanceof Map<?, ?> r)) {
                continue;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            r.forEach((k, v) -> b.put(String.valueOf(k), v));
            try {
                b.put("VAR", views.headline(p, "BOOK:" + r.get("BOOK"), date).get("var"));
            } catch (DrishtiException | IllegalArgumentException e) {
                b.put("VAR", null);
            }
            books.add(b);
        }
        m.put("BOOKS", books);
        return m;
    }

    private static Map<String, Object> row(String key, Map<String, Object> r, String own, String component) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put(key, r.get("key"));
        x.put("QUANTITY", r.get("quantity"));
        x.put("DIRECTION", r.get("direction"));
        x.put("VAR", r.get(own));
        x.put("COMPONENT_VAR", r.get(component));
        if (r.get("tradeCurrency") != null) {
            x.put("TRADE_CURRENCY", r.get("tradeCurrency"));        // the currency its PVs came in, else its own (VAR_GUIDE.md section 15.11)
        }
        return x;
    }

    /** Component VaR and market value summed by one column of the positions, largest component first. */
    private static List<Map<String, Object>> by(List<Map<String, Object>> positions, String column) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        for (Map<String, Object> x : positions) {
            double[] s = sums.computeIfAbsent(String.valueOf(x.get(column)), k -> new double[3]);
            s[0] += num(x.get("COMPONENT_VAR"));
            s[1] += num(x.get("MARKET_VALUE"));
            s[2]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        sums.forEach((k, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("KEY", k);
            m.put("POSITIONS", (long) s[2]);
            m.put("COMPONENT_VAR", Math.round(s[0]));
            m.put("NET_MV", Math.round(s[1]));
            out.add(m);
        });
        out.sort(Comparator.comparingDouble(m -> -num(m.get("COMPONENT_VAR"))));
        return out;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
