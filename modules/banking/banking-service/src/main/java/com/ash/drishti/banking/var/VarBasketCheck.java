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

import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarLens;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The server's answer to a basket sheet (VAR_GUIDE.md section 3.5): every cell checked, as the viewer, on the date, with the reason in
 * words for any that is not found; and the live figures of the basket the sheet composes: VaR, ES, the sum of the parts (each cell
 * added, alone), the diversification and the trades. The console renders this answer and computes nothing.
 *
 * <p>The basket is everything added (or every trade the viewer may see, when <i>Start from the whole bank</i> is ticked), then everything
 * taken away. A cell that takes away nothing (none of its trades is in a part added) is flagged in words; with the whole bank ticked, a
 * cell that adds is flagged as adding nothing. A minus on a desk or a book takes away all its trades, whichever part added them.
 *
 * <p>Cost: a trade or a position cell is looked up directly (a run of ordinals, never a bitmap as wide as the date), so 10,000 ids in a
 * column cost a few lookups each; a part's standalone VaR is its own vector measured through the default lens.
 */
final class VarBasketCheck {

    /** A cell that needs a word: not found, or found but adding or taking away nothing. */
    static final String NOT_FOUND = "not-found";
    static final String ADDS_NOTHING = "adds-nothing";
    static final String TAKES_NOTHING = "takes-nothing";

    private final VarService svc;

    VarBasketCheck(VarService svc) {
        this.svc = svc;
    }

    /** The checks and figures of {@code sheet} as {@code p} on the date asked (null: the latest), in {@code ccy} (null: USD). */
    Map<String, Object> check(Principal p, VarBasketSheet sheet, LocalDate asked, String ccy) {
        long t0 = System.nanoTime();
        VarDate d = svc.date(asked, ccy);
        String date = d.date().toString();
        VarBook book = d.book();
        VarAccess.View view = svc.access().view(p, d);
        Map<String, String> words = svc.words();
        ScopeResolver resolver = new ScopeResolver(book, svc.definitions(p, d), words);
        boolean cptyMasked = svc.access().masked(p, "COUNTERPARTY");
        VarLens lens = svc.props().defaultLens();

        List<VarBasketSheet.Cell> cells = sheet.cells();
        List<Map<String, Object>> flagged = new ArrayList<>();
        List<VarBasketSheet.Cell> usable = new ArrayList<>();
        List<VarBasketSheet.Cell> minusCells = new ArrayList<>();
        List<OrdinalSet> minusSets = new ArrayList<>();
        IntPairs plusRuns = new IntPairs();
        OrdinalSet plusGroups = OrdinalSet.empty();
        double sumParts = 0;
        int parts = 0;
        int found = 0;
        int notFound = 0;
        for (VarBasketSheet.Cell c : cells) {
            String why = c.problem();
            OrdinalSet set = null;
            if (why == null && c.word().equals("CPTY") && cptyMasked) {
                why = "cannot be used: the counterparty is masked for your role, so a basket may not select trades by it";
            }
            if (why == null) {
                Found f = resolve(c, book, resolver, date, d, words);
                why = f.why();
                set = f.set();
            }
            if (why == null) {
                OrdinalSet visible = view.full() ? set : set.and(view.visible());
                if (visible.isEmpty()) {
                    why = "is outside your view: your role does not see its trades";
                } else {
                    set = visible;
                }
            }
            if (why != null) {
                notFound++;
                flagged.add(flag(c, NOT_FOUND, c.ref() + (c.problem() != null ? ": " : " ") + why));
                continue;
            }
            found++;
            usable.add(c);
            if (c.minus()) {
                minusCells.add(c);
                minusSets.add(set);
                continue;
            }
            if (sheet.wholeBank()) {
                flagged.add(flag(c, ADDS_NOTHING, c.ref() + " adds nothing: Start from the whole bank already holds every trade you may see"));
                continue;
            }
            if (set.isRuns()) {
                set.forEachRun(plusRuns::add);
            } else {
                plusGroups = plusGroups.or(set);
            }
            double[] oneDay = book.vector(set, svc.sums());
            VarCalc.Measures alone = lens.measures(svc.calc(), oneDay, lens.apply(oneDay));
            sumParts += alone.var();
            parts++;
        }
        OrdinalSet added;
        if (sheet.wholeBank()) {
            OrdinalSet bank = resolver.resolve("BANK").set();
            added = view.full() ? bank : bank.and(view.visible());
        } else {
            added = OrdinalSet.ofRuns(plusRuns.toArray()).or(plusGroups);
        }
        int takesNothing = 0;
        for (int i = 0; i < minusCells.size(); i++) {
            OrdinalSet m = minusSets.get(i);
            if (!m.intersects(added)) {
                takesNothing++;
                VarBasketSheet.Cell c = minusCells.get(i);
                flagged.add(flag(c, TAKES_NOTHING, c.ref() + " takes nothing away: none of its " + m.size() + " trade" + (m.size() == 1 ? "" : "s")
                        + " is in a part added"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", date);
        out.put("currency", d.currency());
        out.put("wholeBank", sheet.wholeBank());
        out.put("rows", sheet.rows());
        out.put("columns", columns(words));
        out.put("parts", cells.size());
        out.put("found", found);
        out.put("notFound", notFound);
        out.put("flagged", flagged.size() - notFound);
        out.put("cells", flagged);
        String figures = VarBasketSheet.compose(usable, sheet.wholeBank());
        String scope = sheet.scope();
        out.put("scope", scope);
        out.put("confidence", lens.confidence());
        out.put("esConfidence", lens.esConfidence());
        if (figures.isEmpty()) {
            out.put("empty", true);
            out.put("trades", 0);
            out.put("var", null);
            out.put("es", null);
            out.put("sumOfParts", null);
            out.put("diversification", null);
            out.put("notice", cells.isEmpty() && !sheet.wholeBank() ? "the basket is empty: add a part, paste a column, or tick Start from the whole bank"
                    : "nothing is added: every part added is not found (or the sheet only takes away); tick Start from the whole bank to take away from it");
        } else {
            VarService.Scoped s = svc.scoped(p, figures, asked, ccy);          // the headline's own path: the same VaR the explorer shows
            out.put("empty", s.set().isEmpty());
            out.put("trades", s.set().size());
            out.put("takenAway", Math.max(0, added.size() - s.set().size()));
            out.put("var", VarViews.round(s.measures().var()));
            out.put("es", VarViews.round(s.measures().es()));
            out.put("sumOfParts", sheet.wholeBank() || parts == 0 ? null : VarViews.round(sumParts));
            out.put("diversification", sheet.wholeBank() || parts < 2 ? null : VarViews.round(sumParts - s.measures().var()));
            out.put("partsAdded", parts);
            if (s.set().isEmpty()) {
                out.put("notice", "no trades: this basket holds no live trade with a P&L vector on " + date + ", so it has no VaR");
            }
        }
        out.put("takesNothing", takesNothing);
        out.put("computeMs", (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    /** The sheet's columns with their headings and the kind each holds. */
    List<Map<String, Object>> columns(Map<String, String> words) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : VarBasketSheet.columns(words.keySet())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c);
            m.put("title", VarBasketSheet.title(c, w -> words.containsKey(w) ? svc.hierarchies().title(words.get(w)) : null));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> flag(VarBasketSheet.Cell c, String status, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", c.column());
        m.put("row", c.row());
        m.put("text", c.text());
        m.put("word", c.word());
        m.put("id", c.id());
        m.put("op", c.minus() ? "-" : "+");
        m.put("status", status);
        m.put("reason", reason);
        return m;
    }

    /** A cell's trades on the date, or why it has none. */
    private record Found(OrdinalSet set, String why) {}

    private Found resolve(VarBasketSheet.Cell c, VarBook book, ScopeResolver resolver, String date, VarDate d, Map<String, String> words) {
        switch (c.word()) {
            case "TRD" -> {
                int o = book.tradeOrdinalAnyCase(c.id());
                if (o < 0) {
                    String pos = d.intake().positionOf(c.id());
                    if (pos != null) {
                        return new Found(null, "is in the position " + pos + ", which its source system sends whole: put the position in the Position column");
                    }
                    String why = d.intake().notLiveWhy(c.id());
                    return new Found(null, why != null ? "is not live on " + date + ": " + why + " (no P&L vector)"
                            : "is not a live trade on " + date + " (no P&L vector): unknown to the day's vectors, matured or cancelled");
                }
                return new Found(OrdinalSet.range(o, o + 1), null);
            }
            case "POS" -> {
                int pi = book.positionIndexAnyCase(c.id());
                if (pi < 0) {
                    return new Found(null, "matches no position on " + date + " (no live trade of it has a P&L vector)");
                }
                IntPairs runs = new IntPairs();
                book.forEachPositionRun(pi, runs::add);
                return new Found(OrdinalSet.ofRuns(runs.toArray()), null);
            }
            default -> {
                ScopeResolver.Resolution r;
                try {
                    r = resolver.resolve(c.ref());
                } catch (IllegalArgumentException e) {
                    return new Found(null, "cannot be read: " + e.getMessage());
                }
                if (!r.unknown().isEmpty()) {
                    return new Found(null, unknown(c, date, words));
                }
                if (r.set().isEmpty()) {
                    return new Found(null, "holds no live trade with a P&L vector on " + date);
                }
                return new Found(r.set(), null);
            }
        }
    }

    private String unknown(VarBasketSheet.Cell c, String date, Map<String, String> words) {
        return switch (c.word()) {
            case "PORT" -> "is not a system basket on " + date + " (PORT <GO> lists them)";
            case "BASKET" -> "is not a basket you may read (deleted, or not shared with you)";
            case "BOOK" -> "is not a book of the hierarchy on " + date;
            case "DESK" -> "is not a desk of the hierarchy on " + date;
            case "DIV" -> "is not a division of the hierarchy on " + date;
            case "NODE" -> "is not a node of a hierarchy on " + date;
            case "LE" -> "is not a legal entity on " + date;
            case "CPTY" -> "has no live trade with a P&L vector on " + date;
            default -> {
                String t = words.containsKey(c.word()) ? svc.hierarchies().title(words.get(c.word())) : c.word();
                yield "is not in " + (t == null ? c.word() : t.toLowerCase(Locale.ROOT)) + " on " + date;
            }
        };
    }

    /** A growable list of {@code from, to} pairs. */
    private static final class IntPairs {
        private int[] a = new int[16];
        private int n;

        void add(int from, int to) {
            if (n + 2 > a.length) {
                a = java.util.Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = from;
            a[n++] = to;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
