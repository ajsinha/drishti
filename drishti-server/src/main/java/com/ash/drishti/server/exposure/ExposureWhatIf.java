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
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Incremental exposure before dealing (EXPOSURE_GUIDE.md section 18.7). The {@code addon} method, a conservative proxy computed here: each candidate
 * is its own netting set (no netting benefit, no collateral) whose PFE at a primary date is
 * {@code max(MTM, 0) + z_q SF d delta sqrt(min(t, M))} and whose EE is {@code max(MTM, 0) + SF d sqrt(min(t, M)) / sqrt(2 pi)} (the mean
 * of the positive part of a normal of that spread), added to the scope's profile; SF is SA-CCR's supervisory factor of the candidate's
 * asset class, d its adjusted notional (an interest-rate trade's notional times its supervisory duration), delta its sign. The exact
 * {@code paths} method (the candidate's paths on the date's run added to its netting set per path, collateral and exposure computed
 * again) runs in the Python exposure engine ({@code exposure-whatif}, docs/guides/EXPOSURE_GUIDE.md section 19.5), asked by
 * {@link ExposureWhatIfPaths} as the person (DRS-5446 when it is not running); a candidate's paths of another run are refused (DRS-5442)
 * either way.
 */
final class ExposureWhatIf {

    /** SA-CCR's supervisory factors by asset class (CRE52; single-name credit at an A rating). */
    static final Map<String, Double> SF = Map.of("rates", 0.005, "fx", 0.04, "credit", 0.0054, "equity", 0.32, "commodity", 0.18);
    private final ExposureService svc;
    private final ExposureWhatIfPaths paths;

    ExposureWhatIf(ExposureService svc) {
        this.svc = svc;
        this.paths = new ExposureWhatIfPaths(svc);
    }

    /** The factor class of an asset class as the trades name it (Rates, FX, Credit, Equity, Commodity and their kin). */
    static String factor(String assetClass) {
        String a = assetClass == null ? "" : assetClass.trim().toLowerCase(Locale.ROOT);
        if (a.contains("fx") || a.contains("foreign")) {
            return "fx";
        }
        if (a.contains("credit")) {
            return "credit";
        }
        if (a.contains("equity") || a.contains("structured")) {
            return "equity";
        }
        if (a.contains("commodity")) {
            return "commodity";
        }
        return "rates";
    }

    /** The standard normal quantile (Acklam's approximation; |error| below 1.2e-9 over (0, 1)). */
    static double z(double q) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01,
            2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00,
            2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double lo = 0.02425;
        if (q < lo) {
            double x = Math.sqrt(-2 * Math.log(q));
            return (((((c[0] * x + c[1]) * x + c[2]) * x + c[3]) * x + c[4]) * x + c[5]) / ((((d[0] * x + d[1]) * x + d[2]) * x + d[3]) * x + 1);
        }
        if (q > 1 - lo) {
            return -z(1 - q);
        }
        double x = q - 0.5;
        double r = x * x;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * x
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    /**
     * A what-if: {@code {scope, method: addon|paths, q, candidates: [{tradeId, nettingSet, assetClass, notional, maturityDate, mtm,
     * delta, paths: {runId, ...}}]}}.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> whatIf(Principal p, Map<String, Object> body, LocalDate date) {
        String scope = String.valueOf(body.getOrDefault("scope", "")).trim();
        if (scope.isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a what-if names its scope: {\"scope\": \"CPTY:CP-ALDERSHOT\", ...}");
        }
        String method = String.valueOf(body.getOrDefault("method", "addon")).trim().toLowerCase(Locale.ROOT);
        Object qq = body.get("q");
        Object cc = body.get("collateral");
        ExposureService.Ask ask = ExposureService.Ask.of(svc.props(), qq == null ? null : String.valueOf(qq), cc == null ? null : String.valueOf(cc),
                null, null);
        List<Map<String, Object>> cands = body.get("candidates") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (cands.isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a what-if has candidates: [{tradeId, assetClass, notional, maturityDate, mtm}]");
        }
        ExposureService.Scoped base = svc.scoped(p, scope, date, ask);
        ExposureBook b = base.date().book();
        for (Map<String, Object> c : cands) {
            if (c.get("paths") instanceof Map<?, ?> pm && pm.get("runId") != null && !b.runId().equals(String.valueOf(pm.get("runId")))) {
                throw new DrishtiException(ErrorCode.EXPOSURE_WHATIF_RUN, "the candidate's paths are not of the run " + b.runId() + " of " + b.businessDate()
                        + ": they are of " + pm.get("runId"));
            }
        }
        if (method.equals("paths")) {
            return paths.whatIf(p, scope, base, ask, cands, date);
        }
        if (!method.equals("addon")) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "method is addon or paths, not " + method);
        }
        ExposureGrid g = b.grid();
        ExposureMath.Stats st = base.result().stats;
        int qa = st.at(ask.q());
        double zq = z(ask.q());
        double[] times = com.ash.drishti.compute.ccr.ExposureCalc.times(g);
        double[] addPfe = new double[times.length];
        double[] addEe = new double[times.length];
        for (Map<String, Object> c : cands) {
            double mtm = num(c.get("mtm"));
            double notional = Math.abs(num(c.get("notional")));
            double delta = c.get("delta") == null ? 1 : num(c.get("delta"));
            String cls = factor(String.valueOf(c.getOrDefault("assetClass", "")));
            double m = maturity(b.businessDate(), c.get("maturityDate"));
            double dAdj = cls.equals("rates") ? notional * (1 - Math.exp(-0.05 * m)) / 0.05 : notional;
            for (int i = 0; i < times.length; i++) {
                double alive = times[i] <= m ? 1 : 0;
                double spread = SF.get(cls) * dAdj * Math.abs(delta) * Math.sqrt(Math.min(times[i], m));
                addPfe[i] += alive * (Math.max(mtm, 0) + zq * spread);
                addEe[i] += alive * (Math.max(mtm, 0) + spread / Math.sqrt(2 * Math.PI));
            }
        }
        List<Map<String, Object>> profile = new ArrayList<>();
        double peakWith = 0;
        double eeWithMax = 0;
        double epeWith = 0;
        double prev = 0;
        double horizon = st.horizon;
        double longest = cands.stream().mapToDouble(c -> maturity(b.businessDate(), c.get("maturityDate"))).max().orElse(0);
        double hWith = Math.min(1.0, Math.max(horizon, longest));
        for (int i = 0; i < times.length; i++) {
            double pfeWith = st.pfe[qa][i] + addPfe[i];
            double eeWith = st.ee[i] + addEe[i];
            peakWith = Math.max(peakWith, pfeWith);
            eeWithMax = Math.max(eeWithMax, eeWith);
            if (hWith > 0 && prev < hWith - 1e-9) {
                epeWith += eeWith * (Math.min(times[i], hWith) - prev);
                prev = times[i];
            }
            Map<String, Object> r = new LinkedHashMap<>();
            int k = g.primaryAt(i);
            r.put("tenor", g.tenor(k));
            r.put("date", g.date(k).toString());
            r.put("t", ExposureViews.round4(times[i]));
            r.put("eeBase", Math.round(st.ee[i]));
            r.put("ee", Math.round(eeWith));
            r.put("pfeBase", Math.round(st.pfe[qa][i]));
            r.put("pfe", Math.round(pfeWith));
            r.put("incrementalPfe", Math.round(addPfe[i]));
            profile.add(r);
        }
        epeWith = hWith > 0 ? epeWith / hWith : 0;
        Map<String, Object> baseFig = new LinkedHashMap<>();
        baseFig.put("epe", Math.round(st.epe));
        baseFig.put("peakPfe", Math.round(st.peakPfe[qa]));
        baseFig.put("peakEe", Math.round(st.peakEe));
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("epe", Math.round(epeWith));
        with.put("peakPfe", Math.round(peakWith));
        with.put("peakEe", Math.round(eeWithMax));
        Map<String, Object> inc = new LinkedHashMap<>();
        inc.put("epe", Math.round(epeWith - st.epe));
        inc.put("peakPfe", Math.round(peakWith - st.peakPfe[qa]));
        inc.put("profile", profile);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", base.res().canonical());
        out.put("businessDate", b.businessDate().toString());
        out.put("runId", b.runId());
        out.put("confidence", ask.q());
        out.put("method", "addon");
        out.put("base", baseFig);
        out.put("with", with);
        out.put("incremental", inc);
        out.put("candidates", cands.size());
        out.put("flags", List.of("proxy"));
        out.put("note", "a conservative proxy: each candidate a netting set of its own, uncollateralised, with SA-CCR's supervisory factor; the "
                + "exact method (paths) runs in the Python exposure engine");
        svc.audit(p, "ccr-whatif", base, "method=addon candidates=" + cands.size());
        return out;
    }

    private static double maturity(LocalDate bd, Object d) {
        if (d == null) {
            return 1;
        }
        try {
            return Math.max(1 / 365.0, ChronoUnit.DAYS.between(bd, LocalDate.parse(String.valueOf(d).substring(0, 10))) / 365.0);
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's maturityDate is a date (YYYY-MM-DD), not " + d);
        }
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's amount is a number, not " + o);
        }
    }
}
