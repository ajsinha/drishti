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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.engine.view.ViewModel;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request and response bodies of the REST API that are not the {@link ViewModel} itself. */
public final class ApiDtos {

    private ApiDtos() {}

    /** @param text a command such as {@code TRD MX-20000001 <GO>} */
    public record CommandRequest(String text) {}

    /**
     * @param ref the entity the command opens; null when the command names several (or none)
     * @param mnemonic the kind's mnemonic
     * @param list the pick list to show instead ({@code /search?q=}), when {@code ref} is null
     * @param matched how many entities the pick list holds
     * @param pack the pack whose overview to show instead (its code or name was typed alone), or null
     * @param history the range the command ended with ({@code TRD 1000015 30}): open the entity's history, or carry the range
     *        on the pick list; null when there is none
     */
    public record CommandResponse(ViewModel.Ref ref, String mnemonic, String list, Integer matched, String pack, HistoryAsk history) {

        public CommandResponse(ViewModel.Ref ref, String mnemonic) {
            this(ref, mnemonic, null, null, null, null);
        }

        public CommandResponse(ViewModel.Ref ref, String mnemonic, String list, Integer matched) {
            this(ref, mnemonic, list, matched, null, null);
        }

        public CommandResponse(ViewModel.Ref ref, String mnemonic, String list, Integer matched, String pack) {
            this(ref, mnemonic, list, matched, pack, null);
        }

        /** The same answer with a history range. */
        public CommandResponse withHistory(HistoryAsk h) {
            return new CommandResponse(ref, mnemonic, list, matched, pack, h);
        }
    }

    /**
     * The range of a history command (DATA_ARCHITECTURE.md › History queries: an entity over time, section 4.3).
     *
     * @param range the range as a command writes it ({@code 30}, {@code 3M}, {@code FROM 2026-09-01 TO 2026-09-30})
     * @param mode {@code data-days}, {@code calendar} or {@code between}
     * @param days the number of data days, for a bare number
     * @param span the calendar span ({@code 3M}, {@code YTD}), for a calendar range
     * @param from the first date, for {@code FROM}
     * @param to the last date, for {@code TO}
     * @param diff whether {@code DIFF} ended the command
     * @param query the console's query string for it ({@code days=30&diff=1})
     */
    public record HistoryAsk(String range, String mode, Integer days, String span, String from, String to, boolean diff, String query) {

        public static HistoryAsk of(com.ash.drishti.engine.command.HistoryRange.Split s) {
            var r = s.range();
            return new HistoryAsk(r.render(), r.mode().wire(), r.days(), r.span(), r.from() == null ? null : r.from().toString(),
                    r.to() == null ? null : r.to().toString(), s.diff(), r.query() + (s.diff() ? "&diff=1" : ""));
        }
    }

    /**
     * @param ref the entity
     * @param provenance where it came from
     * @param data the document exactly as the source produced it
     */
    public record RawEntity(ViewModel.Ref ref, Provenance provenance, DataNode data) {}

    /**
     * @param name plugin name
     * @param version plugin version
     * @param kinds kinds served (empty = any)
     * @param live pushes updates
     * @param search answers type-ahead
     * @param reverseLookup answers reverse lookups
     * @param health {@code UP} or a reason
     */
    public record SourceInfo(String name, String version, Set<String> kinds, boolean live, boolean search, boolean reverseLookup, String health) {}

    /**
     * @param sources started plugins
     * @param failures plugins that failed to start, with the reason
     */
    public record SourcesResponse(List<SourceInfo> sources, Map<String, String> failures) {}

    /**
     * @param name Sutra name
     * @param latest latest version
     * @param versions all loaded versions
     * @param domain grouping
     * @param kind entity kind it matches
     * @param where its match predicate
     * @param priority match priority
     */
    public record SutraInfo(String name, int latest, List<Integer> versions, String domain, String kind, String where, int priority) {}
}
