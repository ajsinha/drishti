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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.CurrencyBook;
import com.ash.drishti.compute.var.ScenarioFx;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.ParallelSums;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A business date in another reporting currency (VAR_GUIDE.md section 15.10): built from the date's USD book the first time the currency
 * is asked, every trade's vector converted through USD with the scenario FX of the date ({@link CurrencyBook}), then kept like the USD
 * book (mapped from its own hot file under {@code <hot>/<date>-<CCY>/} with the tiered memory, on the heap otherwise). The currency move
 * is part of the risk: the same trades give a different VaR in each currency. A currency without scenario FX on the date is refused
 * ({@code DRS-5207}).
 */
final class VarCurrencies {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final VarLoader loader;
    private final VarProperties props;
    private final ParallelSums sums;

    VarCurrencies(VarLoader loader, VarProperties props, ParallelSums sums) {
        this.loader = loader;
        this.props = props;
        this.sums = sums;
    }

    /** A reporting currency as asked ({@code ccy=eur}): an ISO code in upper case; none is USD. */
    static String normalise(String ccy) {
        if (ccy == null || ccy.isBlank()) {
            return VarDate.USD;
        }
        String c = ccy.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z]{3}")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "ccy is a currency's ISO code (USD, EUR, CAD ...), not " + ccy);
        }
        return c;
    }

    /** The date's book in {@code ccy}, built from its USD book {@code usd} (or opened from its hot file when built from the same inputs). */
    VarDate convert(VarDate usd, String ccy) {
        VarDate out = convertMorning(usd, ccy);
        return usd.intraday() == null || usd.intraday().isEmpty() ? out : apply(out, usd, usd.intraday().rows());
    }

    /**
     * The morning run of the date in {@code ccy}, without the day's updates: a pure function of the USD date's inputs, so it is kept per date,
     * currency and data version, and each USD intraday layer is applied on it ({@link #apply}).
     */
    VarDate convertMorning(VarDate usd, String ccy) {
        long t0 = System.nanoTime();
        LocalDate date = usd.date();
        VarBook ub = usd.book().morning();                           // the morning run: the day's updates are applied after
        ScenarioFx fx = loader.fx(date, ub.scenarioDates());
        ScenarioFx.Rates rep = fx.rates(ccy).orElseThrow(() -> new DrishtiException(ErrorCode.VAR_NO_FX, "no " + ccy + " FX for the scenarios of "
                + date + ": " + fx.notes().stream().filter(n -> n.startsWith(ccy + ":")).findFirst().orElse(ccy + ": neither supplied ("
                + props.kinds().fxScenario() + ") nor in the FX history (" + props.kinds().fxSpot() + ")") + "; the currencies with FX on the date: "
                + String.join(", ", fx.currencies())));
        VarTier tier = loader.tier();
        String fxVersions = loader.fxVersions(date);
        String digest = tier.mapped() && ub.hot() != null ? VarTier.digest(date, ub.hot().digest() + "|ccy=" + ccy + "|fx=" + fxVersions, "", "", "") : "";
        int withoutPv = 0;
        VarBook book = null;
        Optional<java.nio.file.Path> hot = tier.mapped() ? tier.existing(date, ccy, digest) : Optional.empty();
        if (hot.isPresent()) {
            try {
                book = tier.open(hot.get(), t0);
                withoutPv = JSON.readTree(book.hot().meta()).path("withoutPv").asInt();
            } catch (RuntimeException | java.io.IOException e) {
                if (book != null) {
                    book.close();
                }
                book = null;                                           // built again below
            }
        }
        if (book == null) {
            for (int o = 0; o < ub.tradeCount(); o++) {
                withoutPv += Double.isNaN(ub.trades().number("PV", o)) ? 1 : 0;
            }
            long budget = props.memoryBudgetMb() * 1024 * 1024;
            VarBook.Builder b = tier.mapped() ? new VarBook.Builder(date, ub.scenarioSet(), ub.scenarioDates(), budget, tier.target(date, ccy, digest))
                    : new VarBook.Builder(date, ub.scenarioSet(), ub.scenarioDates(), props.rowsPerChunk(), budget);
            b.batchId(usd.intake().batchIds().isEmpty() ? "" : usd.intake().batchIds().get(0));
            b.meta("{\"currency\":\"" + ccy + "\",\"withoutPv\":" + withoutPv + "}");
            try {
                book = CurrencyBook.convert(ub, rep, b, sums).book();
            } catch (RuntimeException e) {
                b.discard();
                throw e;
            }
        }
        tier.opened(book);
        Map<String, VarDate.Portfolio> portfolios = new LinkedHashMap<>();
        usd.portfolios().forEach((id, p) -> portfolios.put(id, new VarDate.Portfolio(p.id(), p.name(), p.type(), p.owner(), p.scope(),
                Double.isNaN(p.limit()) ? Double.NaN : p.limit() / rep.base())));
        Map<String, Object> fxUsed = new LinkedHashMap<>(usd.fx());
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("source", rep.source());
        mine.put("base", rep.base());
        mine.put("reporting", true);
        if (withoutPv > 0) {
            mine.put("tradesWithoutPv", withoutPv);
        }
        fxUsed.put(ccy, mine);
        Map<String, String> versions = new LinkedHashMap<>(usd.versions());
        versions.put("fx", fxVersions);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new VarDate(date, book, portfolios, usd.version(), java.time.Instant.now(), ms, 0, usd.intake(), versions, List.of(), ccy, fxUsed);
    }

    /**
     * Intraday updates (USD) applied on a book in this currency (VAR_GUIDE.md section 8.4): each trade reconverted alone, its difference
     * into its book and nodes; the morning P&amp;L it replaces is the one this book's aggregates were summed from (the USD morning row as
     * the conversion read it).
     */
    VarDate apply(VarDate cur, VarDate usd, List<VarIntradayRows.Row> rows) {
        ScenarioFx fx = loader.fx(usd.date(), usd.book().scenarioDates());
        ScenarioFx.Rates rep = fx.rates(cur.currency()).orElse(null);
        if (rep == null || rows.isEmpty()) {
            return cur.withBook(cur.book(), usd.intraday());
        }
        int w = cur.book().width();
        VarBook um = usd.book().morning();
        List<com.ash.drishti.compute.var.VarIntraday.Update> ups = new java.util.ArrayList<>();
        String last = null;
        int pvAt = VarBook.TRADE_NUMBERS.indexOf("PV");
        for (VarIntradayRows.Row r : rows) {
            com.ash.drishti.compute.var.VarIntraday.Update u = r.usd();
            last = VarIntradayRows.compare(r.computedAt(), last) > 0 ? r.computedAt() : last;
            double[] pnl = null;
            double pvUsd = u.pvUsd();
            if (u.pnl() != null) {
                pnl = new double[w];
                ScenarioFx.convert(Double.isNaN(pvUsd) ? 0 : pvUsd, u.pnl(), w, rep, pnl);
            }
            double pv = Double.isNaN(pvUsd) ? Double.NaN : pvUsd / rep.base();
            double[] nums = u.numbers() == null ? null : u.numbers().clone();
            if (nums != null) {
                nums[pvAt] = pv;
            }
            ups.add(new com.ash.drishti.compute.var.VarIntraday.Update(u.tradeId(), pnl, pv, u.texts(), nums, pvUsd, u.pnlUsd()));
        }
        com.ash.drishti.compute.var.VarIntraday.Morning exact = o -> {
            double[] row = new double[w];
            um.store().copy(o, row);
            double p = um.trades().number("PV", o);
            double[] out = new double[w];
            ScenarioFx.convert(Double.isNaN(p) ? 0 : p, row, w, rep, out);
            return out;
        };
        VarBook b = com.ash.drishti.compute.var.VarIntraday.apply(cur.book(), ups, exact, c -> fx.rates(c).orElse(null), VarIntradayRows.batchAt(rows), last);
        return cur.withBook(b, usd.intraday());
    }
}
