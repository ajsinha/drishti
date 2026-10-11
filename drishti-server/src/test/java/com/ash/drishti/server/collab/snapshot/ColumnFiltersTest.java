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
package com.ash.drishti.server.collab.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.engine.control.ViewState;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The view state a share carries: parsed and bounded ({@link ViewState}), and the column filters applied to a table on the server with
 * the console's meaning (the grammar of static/js/colfilter.js), so a picture shows the rows the sender had left.
 */
class ColumnFiltersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);       // a Wednesday

    private static PanelData.Row row(String... cells) {
        return new PanelData.Row(java.util.Arrays.stream(cells).map(c -> Cell.of(null, c)).toList(), false, null);
    }

    /** One table panel {@code cf} with a text, a number and a date column. */
    private static ViewModel view(PanelData.Row... rows) {
        PanelData.Table t = new PanelData.Table(List.of("Leg", "Notional", "Pay date"), List.of(false, true, false), List.of(rows), null, null);
        PanelView p = new PanelView("cf", "table", "Cashflows", null, null, null, false, null, t, null, false);
        return new ViewModel(null, null, null, List.of(), List.of(p), List.of(), null, null, List.of(), List.of());
    }

    private static List<String> legs(ViewModel v) {
        return ((PanelData.Table) v.panels().get(0).data()).rows().stream().map(r -> r.cells().get(0).text()).toList();
    }

    private static final ViewModel DATA = view(row("A", "1,000,000", "2026-10-07"), row("B", "250k", "2026-10-06"), row("C", "5.5m", "2026-09-01"),
            row("D", "•••", "2026-10-09"), row("", "2m", ""));

    private static List<String> filtered(String query) {
        return legs(ColumnFilters.apply(DATA, ViewState.parse(query, 4000), TODAY));
    }

    @Test
    void viewStateKeepsOnlyControlsAndFiltersAndIsBounded() {
        ViewState s = ViewState.parse("?ctl.legs=2&utm=x&f.cf.Leg=in%3AA&asOf=2020-01-01&ctl.legs=3&f.nodot=1", 4000);
        assertThat(s.params()).containsOnlyKeys("ctl.legs", "f.cf.Leg").containsEntry("ctl.legs", "3");
        assertThat(s.text()).isEqualTo("ctl.legs=3&f.cf.Leg=in%3AA");
        assertThat(s.controls().get("legs")).isEqualTo("3");
        assertThat(s.filtersOf("cf")).containsEntry("Leg", "in:A");
        assertThat(ViewState.parse(null, 10)).isSameAs(ViewState.NONE);
        assertThat(ViewState.parse("utm=1", 10).text()).isNull();
        assertThatThrownBy(() -> ViewState.parse("ctl.a=" + "x".repeat(50), 20)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit is 20");
    }

    @Test
    void aCheckedListKeepsTheTickedValuesAndBlanksAndMasksOnlyWhenTicked() {
        assertThat(filtered("f.cf.Leg=in:A,B")).containsExactly("A", "B");
        assertThat(filtered("f.cf.Leg=in:A,~b")).containsExactly("A", "");
        assertThat(filtered("f.cf.Notional=in:250k,~m")).containsExactly("B", "D");
    }

    @Test
    void numberConditionsReadAmountsAsTheConsoleDoes() {
        assertThat(filtered("f.cf.Notional=gt:1m")).containsExactly("C", "");
        assertThat(filtered("f.cf.Notional=bt:200k~1m")).containsExactly("A", "B");
        assertThat(filtered("f.cf.Notional=top:2")).containsExactly("C", "");
        assertThat(filtered("f.cf.Notional=avga")).containsExactly("C");
        assertThat(filtered("f.cf.Notional=lt:300000")).containsExactly("B");
    }

    @Test
    void textAndDateConditionsAndAMaskNeverMatching() {
        assertThat(filtered("f.cf.Leg=ct:b")).containsExactly("B");
        assertThat(filtered("f.cf.Leg=nc:b")).containsExactly("A", "C", "D", "");
        assertThat(filtered("f.cf.Pay date=today")).containsExactly("A");
        assertThat(filtered("f.cf.Pay date=thisweek")).containsExactly("A", "B", "D");
        assertThat(filtered("f.cf.Pay date=bf:2026-10-01")).containsExactly("C");
        assertThat(filtered("f.cf.Notional=eq:0")).as("a masked amount matches no condition").isEmpty();
    }

    @Test
    void filtersCombineAcrossColumnsAndUnknownColumnsAndPanelsAreIgnored() {
        assertThat(filtered("f.cf.Leg=in:A,B,C&f.cf.Notional=gt:1m")).containsExactly("C");
        assertThat(filtered("f.cf.Nope=in:A")).hasSize(5);
        assertThat(filtered("f.other.Leg=in:A")).hasSize(5);
        assertThat(ColumnFilters.apply(DATA, ViewState.NONE, TODAY)).isSameAs(DATA);
    }

    @Test
    void aParentStaysWhenARowBelowItMatches() {
        PanelData.Row child = row("A-kid", "9", "");
        PanelData.Row parent = new PanelData.Row(row("A", "1", "").cells(), false, null, List.of(child));
        PanelData.Row other = row("Z", "1", "");
        ViewModel v = view(parent, other);
        ViewModel out = ColumnFilters.apply(v, ViewState.parse("f.cf.Leg=in:A-kid", 4000), TODAY);
        assertThat(legs(out)).containsExactly("A");
        assertThat(((PanelData.Table) out.panels().get(0).data()).rows().get(0).children()).hasSize(1);
    }
}
