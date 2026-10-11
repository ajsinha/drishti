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
package com.ash.drishti.server.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ash.drishti.engine.control.ViewState;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A panel is named in an email as the sender's view showed it: the title that follows a control (the filtered title) is the filtered one. */
class PanelTitlesViewStateTest {

    private final PanelTitles.Source source = new PanelTitles.Source() {
        @Override
        public Map<String, String> titles(String kind, String entityId, Principal reader) {
            return Map.of("cashflows", "Cashflows");
        }

        @Override
        public Map<String, String> titles(String kind, String entityId, Principal reader, ViewState state) {
            return Map.of("cashflows", state.isEmpty() ? "Cashflows" : "Cashflows (" + state.params().values().iterator().next() + ")");
        }
    };

    @Test
    void theTitleFollowsTheViewStateAndTheCacheKeepsThemApart() {
        PanelTitles titles = new PanelTitles(source, mock(Entitlements.class));
        Principal bob = new Principal("bob", List.of("risk"));
        assertThat(titles.title("TRD", "T-1", "cashflows", bob)).isEqualTo("Cashflows");
        assertThat(titles.title("TRD", "T-1", "cashflows", bob, ViewState.parse("ctl.currency=EUR", 1000))).isEqualTo("Cashflows (EUR)");
        assertThat(titles.title("TRD", "T-1", "cashflows", bob, ViewState.parse("ctl.currency=USD", 1000))).isEqualTo("Cashflows (USD)");
        assertThat(titles.title("TRD", "T-1", "cashflows", bob, ViewState.NONE)).isEqualTo("Cashflows");
    }

    @Test
    void aSourceThatKnowsNothingOfStatesStillAnswersWithTheUnfilteredTitle() {
        PanelTitles titles = new PanelTitles((kind, id, who) -> Map.of("cashflows", "Cashflows"), mock(Entitlements.class));
        assertThat(titles.title("TRD", "T-1", "cashflows", new Principal("bob", List.of()), ViewState.parse("ctl.currency=EUR", 1000))).isEqualTo("Cashflows");
        assertThat(titles.title("TRD", "T-1", "other", new Principal("bob", List.of()))).isEqualTo("other");
    }
}
