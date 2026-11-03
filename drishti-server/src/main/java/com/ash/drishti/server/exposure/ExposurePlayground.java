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
package com.ash.drishti.server.exposure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.CsaTerms;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.server.playground.ExportSizing;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Exposure Playground of one netting set (docs/guides/EXPOSURE_GUIDE.md section 29) as the person: the set resolved and its
 * visibility checked by the exposure engine ({@link ExposureService#scoped}), its trades' paths read from the lake, the first
 * {@code paths} paths taken, the largest trades by |EE contribution at the peak EE date| as their own columns and the others summed into
 * Rest of set, and every figure computed by the engine's code on exactly those paths ({@link ExposurePlaygroundModel}). {@code EXAMPLE}
 * is the hand-built case X1 of COUNTERPARTY_ENGINES.md section 9 (two trades, five paths), needing no data.
 */
final class ExposurePlayground {

    static final String EXAMPLE = "EXAMPLE";
    static final String REST = "REST-OF-SET";

    private final ExposureService svc;
    private final ExposurePlaygroundProperties props;

    ExposurePlayground(ExposureService svc, ExposurePlaygroundProperties props) {
        this.svc = svc;
        this.props = props;
    }

    ExposurePlaygroundProperties props() {
        return props;
    }

    /** What the person asked, besides the netting set and the date. */
    record Choices(double[] qs, ExposureMath.Rule rule, String collateral, String contributionDate, Integer paths, Integer trades) {

        static Choices of(ExposureService svc, ExposurePlaygroundProperties pp, String q, String rule, String collateral, String date, Integer paths,
                String trades, int defaultTrades) {
            double[] qs = svc.props().confidences();
            if (q != null && !q.isBlank()) {
                try {
                    qs = Arrays.stream(q.split(",")).map(String::trim).filter(x -> !x.isEmpty()).mapToDouble(Double::parseDouble).toArray();
                } catch (NumberFormatException e) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "q is one or more confidences (0.95,0.99), not " + q);
                }
                for (double x : qs) {
                    if (!(x > 0.5 && x < 1)) {
                        throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a confidence is above 0.5 and below 1, not " + x);
                    }
                }
                if (qs.length == 0 || qs.length > 4) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "q is one to four confidences, not " + q);
                }
            }
            ExposureMath.Rule r = svc.props().rule();
            if (rule != null && !rule.isBlank()) {
                String x = rule.trim().toLowerCase(Locale.ROOT);
                if (!x.equals("kth") && !x.startsWith("interp")) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "rule is kth or interpolated, not " + rule);
                }
                r = x.equals("kth") ? ExposureMath.Rule.KTH : ExposureMath.Rule.INTERPOLATED;
            }
            String c = collateral == null || collateral.isBlank() ? svc.props().collateralModel() : collateral.trim().toLowerCase(Locale.ROOT);
            try {
                ExposureMath.Model.of(c);
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
            }
            if (paths != null && (paths < 1 || paths > pp.maxPaths())) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "paths is 1 to " + pp.maxPaths() + " (drishti.ccr.exposure.playground.max-paths), not "
                        + paths);
            }
            int t;
            try {
                t = trades == null || trades.isBlank() ? defaultTrades : ExposurePlaygroundProperties.trades(trades);
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
            }
            return new Choices(qs, r, c, date == null || date.isBlank() ? pp.contributionDate() : date.trim(), paths, t);
        }
    }

    /** The playground of a netting set (or {@code EXAMPLE}) on a date, as the person sees it. */
    ExposurePlaygroundModel build(Principal p, String nettingSet, LocalDate date, Choices ch) {
        if (nettingSet == null || nettingSet.isBlank()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "give a netting set: nettingSet=NS-ALDERSHOT-FRA, or EXAMPLE for the hand-built case");
        }
        String id = nettingSet.trim();
        if (id.equalsIgnoreCase(EXAMPLE)) {
            return example(ch);
        }
        String scope = id.toUpperCase(Locale.ROOT).startsWith("NSET:") ? id : "NSET:" + id;
        ExposureService.Scoped s = svc.scoped(p, ExposureService.scopeText(scope), date, ExposureService.Ask.of(svc.props(), null, ch.collateral(), null, null));
        if (!"netting-set".equals(s.kind())) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "PLAY EXP takes one netting set, not " + s.kind() + " " + s.res().canonical());
        }
        if (!s.view().seesTrades(s.res().trades())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the playground shows the netting set trade by trade; your role sees it only in aggregate");
        }
        ExposureBook b = s.date().book();
        ExposureBook.SetInfo set = b.set(b.setOrdinal(s.res().id()));
        int p0 = b.paths();
        int n = Math.min(p0, ch.paths() == null ? props.maxPaths() : ch.paths());
        ExposureGrid grid = b.grid();
        List<String> ids = new ArrayList<>();
        for (int t = set.from(); t < set.to(); t++) {
            ids.add(b.trade(t).id());
        }
        Map<String, double[]> tp = svc.paths(s.date()).read(ids);
        List<ExposurePlaygroundModel.Column> all = new ArrayList<>();
        for (int t = set.from(); t < set.to(); t++) {
            ExposureBook.TradeInfo ti = b.trade(t);
            String label = ti.id() + (ti.product() == null ? "" : " · " + ti.product());
            all.add(new ExposurePlaygroundModel.Column(ti.id(), label, false, 1, ti.mtm0(), ti.lifeYears(), tp.get(ti.id()), p0));
        }
        double[] times = new double[grid.size()];
        for (int k = 0; k < times.length; k++) {
            times[k] = grid.time(k);
        }
        ExposureMath.Model model = ExposureMath.Model.of(ch.collateral());
        Object lb = b.run().get("LOOKBACK_DAYS");
        Map<String, Object> runFigures = new LinkedHashMap<>();
        if (n < p0) {
            ExposureMath.Stats st = s.result().stats;
            runFigures.put("EPE (all paths)", st.epe);
            runFigures.put("EEPE (all paths)", st.eepe);
            runFigures.put("Peak EE (all paths)", st.peakEe);
            for (int a = 0; a < st.qs.length; a++) {
                runFigures.put("Peak PFE " + Math.round(st.qs[a] * 1000) / 10.0 + "% (all paths, the engine's confidences)", st.peakPfe[a]);
            }
        }
        ExposurePlaygroundModel.About about = new ExposurePlaygroundModel.About(set.id(), svc.name(s.date(), set.counterparty(), p), s.date().date().toString(),
                b.runId(), str(b.run().get("CALIBRATION")), p0, set.to() - set.from(), set.csa().present() ? set.csa().mporDays() : null,
                set.csa().present() ? set.csa().frequencyDays() : null, lb instanceof Number num ? num.intValue() : lb == null ? null : parseInt(lb),
                provenance(s, set), runFigures);
        // the columns: every trade first (to rank them at the peak EE date), then the largest as their own and the rest summed
        ExposurePlaygroundModel first = new ExposurePlaygroundModel(about, grid, times, all, n, set.csa(), model, ch.rule(), ch.qs(), svc.props().alpha(), -1);
        Integer[] order = new Integer[all.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> -Math.abs(first.share[i])).thenComparing(i -> all.get(i).id()));
        int shown = ch.trades() == null || ch.trades() <= 0 || ch.trades() >= all.size() ? all.size() : ch.trades();
        List<ExposurePlaygroundModel.Column> cols = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            cols.add(all.get(order[i]));
        }
        if (shown < all.size()) {
            double[] rest = new double[grid.size() * p0];
            double mtm0 = 0;
            double life = 0;
            for (int i = shown; i < all.size(); i++) {
                ExposurePlaygroundModel.Column c = all.get(order[i]);
                if (c.values() != null) {
                    for (int x = 0; x < rest.length; x++) {
                        rest[x] += c.values()[x];
                    }
                }
                mtm0 += c.mtm0();
                life = Math.max(life, c.lifeYears());
            }
            int k = all.size() - shown;
            cols.add(new ExposurePlaygroundModel.Column(REST, "Rest of set (" + k + " trades)", true, k, mtm0, life, rest, p0));
        }
        return new ExposurePlaygroundModel(about, grid, times, cols, n, set.csa(), model, ch.rule(), ch.qs(), svc.props().alpha(),
                contributionAt(grid, ch.contributionDate()));
    }

    /** The sizes an estimate needs, without reading a path: the set's trades, the paths taken, the grid dates and the primary dates. */
    int[] sizes(Principal p, String nettingSet, LocalDate date, Integer paths) {
        if (nettingSet != null && nettingSet.trim().equalsIgnoreCase(EXAMPLE)) {
            return new int[] {2, 5, 8, 4};
        }
        if (nettingSet == null || nettingSet.isBlank()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "give a netting set: nettingSet=NS-ALDERSHOT-FRA, or EXAMPLE for the hand-built case");
        }
        String id = nettingSet.trim();
        String scope = id.toUpperCase(Locale.ROOT).startsWith("NSET:") ? id : "NSET:" + id;
        ExposureService.Scoped s = svc.scoped(p, ExposureService.scopeText(scope), date, ExposureService.Ask.of(svc.props(), null, null, null, null));
        if (!"netting-set".equals(s.kind())) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "PLAY EXP takes one netting set, not " + s.kind() + " " + s.res().canonical());
        }
        ExposureBook b = s.date().book();
        ExposureBook.SetInfo set = b.set(b.setOrdinal(s.res().id()));
        int n = Math.min(b.paths(), paths == null ? props.maxPaths() : Math.max(1, Math.min(paths, props.maxPaths())));
        return new int[] {set.to() - set.from(), n, b.grid().size(), b.grid().primaryCount()};
    }

    /** The primary point of a tenor or date ({@code peak}: -1, the peak EE date). */
    static int contributionAt(ExposureGrid grid, String date) {
        if (date == null || date.isBlank() || date.equalsIgnoreCase("peak")) {
            return -1;
        }
        for (int i = 0; i < grid.primaryCount(); i++) {
            int k = grid.primaryAt(i);
            if (date.equalsIgnoreCase(grid.tenor(k)) || date.equals(grid.date(k).toString())) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the contribution date is a primary date of the run (a tenor such as 1Y, or its date) or "
                + "peak, not " + date);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer parseInt(Object o) {
        try {
            return (int) Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> provenance(ExposureService.Scoped s, ExposureBook.SetInfo set) {
        ExposureBook b = s.date().book();
        Map<String, Object> run = b.run();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Business date", s.date().date().toString());
        m.put("Run", b.runId() + " (batch " + b.batchId() + ")");
        m.put("Model and version", run.get("MODEL") + " " + run.get("MODEL_VERSION"));
        m.put("Seed", run.get("SEED"));
        m.put("Calibration", run.get("CALIBRATION"));
        m.put("Value basis", run.get("VALUE_BASIS"));
        m.put("Precision of the paths", run.get("PRECISION"));
        m.put("Input versions", s.date().inputs());
        m.put("Failed trades", s.date().intake().get("failed"));
        m.put("Proxied trades", s.date().intake().get("proxied"));
        m.put("Flags", set.flags());
        m.put("Warnings", s.date().warnings());
        m.put("Engine", "the exposure engine in the server (drishti-compute ExposureMath), on the paths of this workbook");
        return m;
    }

    // ---- the hand-built case X1 (COUNTERPARTY_ENGINES.md section 9) -------------------------------------------------------------------

    /** Case X1's grid: four quarterly primary dates, each with a look-back point 14 days (10 business days) before it. */
    static ExposureGrid exampleGrid() {
        LocalDate bd = LocalDate.of(2026, 9, 30);
        int[] primary = {91, 183, 274, 365};
        List<LocalDate> dates = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<Integer> lbOf = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            dates.add(bd.plusDays(primary[i] - 14));
            roles.add("LOOKBACK");
            lbOf.add(2 * i + 1);
            dates.add(bd.plusDays(primary[i]));
            roles.add("PRIMARY");
            lbOf.add(-1);
        }
        return ExposureGrid.of(bd, dates, roles, lbOf, List.of("", "3M", "", "6M", "", "9M", "", "1Y"));
    }

    /** Case X1: two trades A and B, five paths; amounts in USD millions; the CSA's thresholds 1, the counterparty's MTA 0.5. */
    static ExposurePlaygroundModel example(Choices ch) {
        double[][] a = {{2, 3, 4, 3}, {-1, 0, 1, 2}, {1, -1, -2, -1}, {3, 4, 2, 5}, {0, 1, 3, 1}};
        double[][] bv = {{-1, -1, 0, 1}, {1, 2, -2, -1}, {0, 2, 1, -1}, {-2, -1, 1, -2}, {1, 0, -1, 2}};
        double[][] l = {{0.5, 1.5, 3, 4}, {0, 1.5, 0, 0}, {1, 1, 0, -1.5}, {0.5, 2.5, 3.5, 2.5}, {1, 1, 1.5, 2.5}};
        int p = 5;
        int g = 8;
        double[] ma = new double[g * p];
        double[] mb = new double[g * p];
        for (int j = 0; j < p; j++) {
            for (int i = 0; i < 4; i++) {
                ma[(2 * i + 1) * p + j] = a[j][i];
                mb[(2 * i + 1) * p + j] = bv[j][i];
                mb[2 * i * p + j] = bv[j][i];                      // at the look-back point B is as at its primary date, A makes up L
                ma[2 * i * p + j] = l[j][i] - bv[j][i];
            }
        }
        ExposureGrid grid = exampleGrid();
        double[] times = new double[g];
        for (int k = 0; k < g; k++) {
            times[k] = grid.primary(k) ? (k + 1) / 8.0 : grid.time(k);   // case X1's exact quarters at the primary dates
        }
        CsaTerms csa = new CsaTerms("CSA-X1", 1, 1, 0.5, 0.5, 0, 0, 10, 1, true, 0, 0);
        List<ExposurePlaygroundModel.Column> cols = List.of(new ExposurePlaygroundModel.Column("A", "A · trade A of case X1", false, 1, 0.3, 2, ma, p),
                new ExposurePlaygroundModel.Column("B", "B · trade B of case X1", false, 1, 0.1, 2, mb, p));
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("Source", "the hand-built case X1 of docs/architecture/COUNTERPARTY_ENGINES.md section 9: every figure can be worked by hand");
        prov.put("Units", "USD millions");
        ExposurePlaygroundModel.About about = new ExposurePlaygroundModel.About("EXAMPLE-X1", "CP-EXAMPLE", "2026-09-30", "X1", "hand-built", p, 2, 10, 1, 10, prov,
                Map.of());
        double[] qs = ch == null || ch.qs() == null ? new double[] {0.8, 0.95} : ch.qs();
        ExposureMath.Rule rule = ch == null ? ExposureMath.Rule.KTH : ch.rule();
        ExposureMath.Model model = ch == null ? ExposureMath.Model.LAGGED : ExposureMath.Model.of(ch.collateral());
        int at = ch == null ? -1 : contributionAt(grid, ch.contributionDate());
        return new ExposurePlaygroundModel(about, grid, times, cols, p, csa, model, rule, qs, 1.4, at);
    }

    // ---- the sizes -------------------------------------------------------------------------------------------------------------------

    /** The export's size for each trade choice, before it is made (the trades the set holds, the paths taken). */
    List<Map<String, Object>> estimates(int setTrades, int paths, int grid, int primaries, int qs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : props.exportChoices()) {
            int t = ExposurePlaygroundProperties.trades(c);
            int cols = t <= 0 || t >= setTrades ? setTrades : t + 1;
            Map<String, Object> m = new LinkedHashMap<>(ExposurePlaygroundBook.estimate(paths, cols, grid, primaries, qs, props.exportMaxCells()).describe());
            m.put("choice", c);
            m.put("tradeColumns", Math.min(setTrades, t <= 0 ? setTrades : t));
            m.put("restOfSet", t > 0 && t < setTrades);
            out.add(m);
        }
        return out;
    }

    ExportSizing.Estimate estimate(ExposurePlaygroundModel m) {
        return ExposurePlaygroundBook.estimate(m.n, m.columns.size(), m.grid.size(), m.primaryCount(), m.qs.length, props.exportMaxCells());
    }
}
