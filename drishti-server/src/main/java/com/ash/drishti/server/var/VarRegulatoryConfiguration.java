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

import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.loads.LoadService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of regulatory VaR ({@link VarRegulatory}) and its settings, {@code drishti.regulatory}. */
@Configuration
@EnableConfigurationProperties(RegulatoryProperties.class)
public class VarRegulatoryConfiguration {

    @Bean
    VarRegulatory varRegulatory(VarService svc, RegulatoryProperties props, SourceRouter router, VarOfficial official,
            ObjectProvider<LoadService> loads) {
        official.backtestConfidences(props.backtesting().confidences());       // each node's record keeps its VaR at them
        VarDeskPnl pnl = new VarDeskPnl(router, props.pnlKind());
        VarBacktesting bt = new VarBacktesting(svc.official(), pnl, props, svc.props().confidence());
        VarPeriodStats stats = new VarPeriodStats(svc.official(), props, svc.props().stress().averageWeeks(), pnl);
        VarReports reports = new VarReports(svc.official(), stats, bt, props);
        LoadService l = loads.getIfAvailable();
        if (l != null) {
            l.onReady(r -> {
                if (props.pnlKind().equals(r.kind())) {
                    pnl.clear();                                              // a new or restated day of desk P&L read again
                }
            });
        }
        return new VarRegulatory(pnl, bt, stats, reports, new VarReportSignoff(reports), new VarSyntheticHistory(svc, props));
    }
}
