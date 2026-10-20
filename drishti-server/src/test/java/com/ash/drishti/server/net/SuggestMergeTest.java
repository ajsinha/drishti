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
package com.ash.drishti.server.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.command.Suggestion;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The type-ahead merged across Drishti*net (QA1-05): one entity's ranges stay four rows, first, and an entity stays one row. */
class SuggestMergeTest {

    private static Suggestion range(String r) {
        return new Suggestion("range", "TRD", "trade", "1000002", r, "about " + r, "TRD 1000002 " + r);
    }

    private static Suggestion entity(String id) {
        return new Suggestion("entity", "TRD", "trade", id, id, "Trade", "TRD " + id);
    }

    @Test
    void theRangesOfOneEntityAreKeptAndComeFirst() {
        List<Suggestion> local = List.of(entity("10000020"), range("30"), range("3M"), range("YTD"), range("30 DIFF"), entity("1000002"),
                entity("10000020"), range("3M"));
        List<Suggestion> merged = new SearchScatter(null, null, null).mergeSuggestions(local, List.of(), 10);
        assertThat(merged).extracting(Suggestion::complete).containsExactly("TRD 1000002 30", "TRD 1000002 3M", "TRD 1000002 YTD",
                "TRD 1000002 30 DIFF", "TRD 10000020", "TRD 1000002");
    }

    @Test
    void theExactIdFromThePeerThatHoldsItComesFirst() {
        // QA1-204: this node holds 10000020..10000029, the peer holds 1000002; ten rows asked
        List<Suggestion> local = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            local.add(entity("1000002" + i));
        }
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        SearchScatter.Share peer = new SearchScatter.Share("n1", json.valueToTree(List.of(entity("1000002"), entity("10000020"))), null, 3);
        SearchScatter sc = new SearchScatter(null, null, null);
        assertThat(sc.mergeSuggestions(local, List.of(peer), 10, "TRD 1000002")).extracting(Suggestion::id).startsWith("1000002", "10000020")
                .hasSize(10);
        List<Suggestion> withRanges = new java.util.ArrayList<>(local);
        SearchScatter.Share peerRanges = new SearchScatter.Share("n1", json.valueToTree(List.of(range("30"), range("3M"), range("YTD"),
                range("30 DIFF"), entity("1000002"))), null, 3);
        assertThat(sc.mergeSuggestions(withRanges, List.of(peerRanges), 10, "TRD 1000002 ")).extracting(Suggestion::complete)
                .startsWith("TRD 1000002 30", "TRD 1000002 3M", "TRD 1000002 YTD", "TRD 1000002 30 DIFF", "TRD 1000002");
    }
}
