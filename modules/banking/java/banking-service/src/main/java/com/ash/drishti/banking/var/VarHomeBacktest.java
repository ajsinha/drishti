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

import com.ash.drishti.kernel.api.home.HomeTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code home_backtest} (DATE, PNL, VAR): the firm's official history rows, the day's hypothetical P&amp;L against the day's VaR, as the VaR
 * service's history view answers them for the person (backtest light's input; the light itself is the fragment's).
 */
@Component
final class VarHomeBacktest implements HomeTable {

    private final VarService svc;
    private final VarViews views;

    VarHomeBacktest(VarService svc, VarViews views) {
        this.svc = svc;
        this.views = views;
    }

    @Override
    public String name() {
        return "home_backtest";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rows(Context c) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!svc.running()) {
            return out;
        }
        try {
            for (Object r : views.history(c.person(), "BANK", c.today()).get("rows") instanceof List<?> l ? l : List.of()) {
                Map<String, Object> m = (Map<String, Object>) r;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("DATE", m.get("date"));
                row.put("PNL", m.get("pnl"));
                row.put("VAR", m.get("var"));
                out.add(row);
            }
        } catch (RuntimeException e) {
            return List.of();                                     // no history for the date, or none the person may see
        }
        return out;
    }
}
