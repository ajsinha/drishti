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
package com.ash.drishti.compute.ccr;

import com.ash.drishti.compute.var.PositionRule;
import com.ash.drishti.compute.var.ScopeExpr;
import com.ash.drishti.compute.vector.OrdinalSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a scope (the scope grammar of {@link ScopeExpr}, VAR_GUIDE.md section 5, with the counterparty terms of EXPOSURE_GUIDE.md section 4) on one
 * business date of the exposure engine to the trades it means: {@code NSET:} netting sets, {@code CPTY:} a counterparty's trades,
 * {@code GROUP:} the counterparties of a connected group, {@code LE:} the trades booked by a legal entity (the trade's booking entity),
 * {@code DESK:} and {@code BOOK:} by the trades' desk and book, {@code TRD:} trades, {@code BANK} every trade, {@code WHERE:} a rule over
 * the trades' columns; joined with {@code +}, {@code -} and {@code &}. A scope that names only trades ({@code TRD:}) is <em>standalone</em>:
 * its trades are valued as if alone, without collateral (EXPOSURE_GUIDE.md section 4). Ids the date does not hold are reported, never dropped
 * silently.
 */
public final class ExposureScopes {

    /** The connected groups (GRP.md): a group's member counterparties. */
    @FunctionalInterface
    public interface Groups {
        Optional<List<String>> members(String group);
    }

    /**
     * What a scope resolved to.
     *
     * @param kind {@code netting-set}, {@code counterparty}, {@code group}, {@code legal-entity}, {@code desk}, {@code book},
     *     {@code bank}, {@code trade}, {@code trades} or {@code adhoc}
     * @param id the one netting set, counterparty, group, legal entity, desk or book it names, else null
     * @param standalone only {@code TRD:} terms: the trades valued alone, uncollateralised
     */
    public record Resolution(OrdinalSet trades, String canonical, String kind, String id, boolean standalone, List<String> unknown,
            List<String> notes) {}

    /** The words of the scope grammar the exposure engine answers. */
    public static final List<String> WORDS = List.of("BANK", "NSET", "CPTY", "GROUP", "LE", "DESK", "BOOK", "TRD", "WHERE");
    /** The trade columns a {@code WHERE:} rule may read. */
    public static final List<String> COLUMNS = List.of("NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "DESK", "TT_BOOK", "ASSET_CLASS", "PRODUCT",
            "SOURCE_SYSTEM", "MATURITY_DATE", "NOTIONAL", "MTM");

    private final ExposureBook book;
    private final Groups groups;

    public ExposureScopes(ExposureBook book, Groups groups) {
        this.book = book;
        this.groups = groups == null ? g -> Optional.empty() : groups;
    }

    public Resolution resolve(String text) {
        ScopeExpr e = ScopeExpr.parse(text);
        List<String> unknown = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        OrdinalSet set = eval(e, unknown, notes);
        boolean standalone = e.refs().stream().allMatch(r -> r.word().equals("TRD"));
        String kind = "adhoc";
        String id = null;
        if (e instanceof ScopeExpr.Ref r) {
            List<String> ids = ScopeExpr.LISTS.contains(r.word()) ? r.ids() : List.of(r.arg());
            boolean one = ids.size() == 1;
            switch (r.word()) {
                case "BANK" -> kind = "bank";
                case "NSET" -> {
                    kind = one ? "netting-set" : "adhoc";
                    id = one ? setId(ids.get(0)) : null;
                }
                case "CPTY" -> {
                    kind = one ? "counterparty" : "adhoc";
                    id = one ? cptyId(ids.get(0)) : null;
                }
                case "GROUP" -> {
                    kind = "group";
                    id = r.arg();
                }
                case "LE" -> {
                    kind = "legal-entity";
                    id = prefixed(r.arg(), "LE-", t -> t.legalEntity());
                }
                case "DESK" -> {
                    kind = "desk";
                    id = prefixed(r.arg(), "DESK-", t -> t.desk());
                }
                case "BOOK" -> {
                    kind = "book";
                    id = prefixed(r.arg(), "BOOK-", t -> t.book());
                }
                case "TRD" -> kind = one ? "trade" : "trades";
                default -> kind = "adhoc";
            }
        }
        return new Resolution(set, e.toString(), kind, id, standalone, List.copyOf(unknown), List.copyOf(notes));
    }

    private OrdinalSet eval(ScopeExpr e, List<String> unknown, List<String> notes) {
        return switch (e) {
            case ScopeExpr.Union u -> {
                OrdinalSet s = OrdinalSet.empty();
                for (ScopeExpr t : u.terms()) {
                    s = s.or(eval(t, unknown, notes));
                }
                yield s;
            }
            case ScopeExpr.Minus m -> eval(m.left(), unknown, notes).andNot(eval(m.right(), unknown, notes));
            case ScopeExpr.And a -> eval(a.left(), unknown, notes).and(eval(a.right(), unknown, notes));
            case ScopeExpr.Ref r -> ref(r, unknown, notes);
        };
    }

    private OrdinalSet ref(ScopeExpr.Ref r, List<String> unknown, List<String> notes) {
        int n = book.trades().size();
        switch (r.word()) {
            case "BANK" -> {
                return OrdinalSet.range(0, n);
            }
            case "NSET" -> {
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                for (String id : r.ids()) {
                    String s = setId(id);
                    if (s == null) {
                        unknown.add(id);
                    } else {
                        ExposureBook.SetInfo info = book.set(book.setOrdinal(s));
                        b.addRange(info.from(), info.to());
                    }
                }
                return b.build();
            }
            case "CPTY" -> {
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                for (String id : r.ids()) {
                    String c = cptyId(id);
                    if (c == null) {
                        unknown.add(id);
                    } else {
                        addWhere(b, t -> c.equals(t.counterparty()));
                    }
                }
                return b.build();
            }
            case "GROUP" -> {
                Optional<List<String>> members = groups.members(r.arg());
                if (members.isEmpty() && !r.arg().startsWith("GRP-")) {
                    members = groups.members("GRP-" + r.arg());
                }
                if (members.isEmpty()) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                List<String> cps = members.get();
                notes.add("group " + r.arg() + ": " + String.join(", ", cps));
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                addWhere(b, t -> cps.contains(t.counterparty()));
                return b.build();
            }
            case "LE", "DESK", "BOOK" -> {
                String prefix = r.word().equals("LE") ? "LE-" : r.word().equals("DESK") ? "DESK-" : "BOOK-";
                java.util.function.Function<ExposureBook.TradeInfo, String> col = r.word().equals("LE") ? ExposureBook.TradeInfo::legalEntity
                        : r.word().equals("DESK") ? ExposureBook.TradeInfo::desk : ExposureBook.TradeInfo::book;
                String id = prefixed(r.arg(), prefix, col);
                if (id == null) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                addWhere(b, t -> id.equals(col.apply(t)));
                return b.build();
            }
            case "TRD" -> {
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                for (String id : r.ids()) {
                    int o = book.tradeOrdinal(id);
                    if (o < 0) {
                        unknown.add(id);
                    } else {
                        b.add(o);
                    }
                }
                return b.build();
            }
            case "WHERE" -> {
                PositionRule rule = new PositionRule(r.arg());
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                int[] matched = {0};
                addWhere(b, t -> {
                    boolean m = rule.matches(f -> column(t, f));
                    matched[0] += m ? 1 : 0;
                    return m;
                });
                notes.add("rule matched " + matched[0] + " trades");
                return b.build();
            }
            default -> throw new IllegalArgumentException(r.word() + ": is not a scope of exposure; use " + String.join(", ", WORDS)
                    + " (NSET:<netting set>, CPTY:<counterparty>, GROUP:<group>, LE:<legal entity>, TRD:<trades>)");
        }
    }

    private void addWhere(OrdinalSet.Builder b, java.util.function.Predicate<ExposureBook.TradeInfo> p) {
        List<ExposureBook.TradeInfo> ts = book.trades();
        for (int t = 0; t < ts.size(); t++) {
            if (p.test(ts.get(t))) {
                b.add(t);
            }
        }
    }

    /** A trade's column for a rule ({@link #COLUMNS}); numbers as numbers. */
    Object column(ExposureBook.TradeInfo t, String field) {
        return switch (field.toUpperCase(Locale.ROOT)) {
            case "NETTING_SET" -> t.set() < 0 ? null : book.set(t.set()).id();
            case "COUNTERPARTY" -> t.counterparty();
            case "LEGAL_ENTITY" -> t.legalEntity();
            case "DESK" -> t.desk();
            case "TT_BOOK", "BOOK" -> t.book();
            case "ASSET_CLASS" -> t.assetClass();
            case "PRODUCT" -> t.product();
            case "SOURCE_SYSTEM" -> t.sourceSystem();
            case "MATURITY_DATE" -> t.maturity();
            case "NOTIONAL" -> t.notional();
            case "MTM" -> t.mtm0();
            default -> null;
        };
    }

    /** A netting set the date holds, by its id or without its {@code NS-} prefix; null when none. */
    String setId(String id) {
        int s = book.setOrdinal(id);
        if (s < 0 && !id.toUpperCase(Locale.ROOT).startsWith("NS-")) {
            s = book.setOrdinal("NS-" + id);
        }
        return s < 0 ? null : book.set(s).id();
    }

    private String cptyId(String id) {
        String a = id.toUpperCase(Locale.ROOT);
        for (String c : List.of(a, "CP-" + a)) {
            if (book.trades().stream().anyMatch(t -> c.equals(t.counterparty()))) {
                return c;
            }
        }
        return null;
    }

    private String prefixed(String arg, String prefix, java.util.function.Function<ExposureBook.TradeInfo, String> col) {
        String a = arg.toUpperCase(Locale.ROOT);
        for (String c : List.of(a, prefix + a)) {
            if (book.trades().stream().anyMatch(t -> c.equals(col.apply(t)))) {
                return c;
            }
        }
        return null;
    }
}
