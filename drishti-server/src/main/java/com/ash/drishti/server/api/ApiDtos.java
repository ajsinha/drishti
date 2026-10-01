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

    /** @param text a command such as {@code TRD IRS-48213 <GO>} */
    public record CommandRequest(String text) {}

    /**
     * @param ref the entity the command opens; null when the command names several (or none)
     * @param mnemonic the kind's mnemonic
     * @param list the pick list to show instead ({@code /search?q=}), when {@code ref} is null
     * @param matched how many entities the pick list holds
     * @param pack the pack whose overview to show instead (its code or name was typed alone), or null
     */
    public record CommandResponse(ViewModel.Ref ref, String mnemonic, String list, Integer matched, String pack) {

        public CommandResponse(ViewModel.Ref ref, String mnemonic) {
            this(ref, mnemonic, null, null, null);
        }

        public CommandResponse(ViewModel.Ref ref, String mnemonic, String list, Integer matched) {
            this(ref, mnemonic, list, matched, null);
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
