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
package com.ash.drishti.server.api;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.history.HistoryProperties;
import com.ash.drishti.engine.history.HistoryService;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.server.design.HistoryDefinitions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** History queries' service (DATA_ARCHITECTURE.md › History queries: an entity over time, section 7): the packs' {@code history:} entries and the kinds' mnemonics wired in. */
@Configuration(proxyBeanMethods = false)
public class HistoryConfiguration {

    @Bean
    public HistoryService historyService(SourceRouter router, BusinessDates dates, HistoryProperties props, SearchProperties search, Formats formats,
            ElCompiler el, HistoryDefinitions definitions, Mnemonics mnemonics) {
        return new HistoryService(router, dates, props, search, formats, el, definitions::of, kind -> {
            String code = mnemonics.codeFor(kind);
            return code == null || code.isBlank() ? null : code;
        });
    }
}
