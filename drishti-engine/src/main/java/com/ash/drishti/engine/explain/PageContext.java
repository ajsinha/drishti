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
package com.ash.drishti.engine.explain;

import com.ash.drishti.engine.view.ViewModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * The answer to "can I trust this page and why does it look like this" for one page (layers 3 and 4 of
 * docs/architecture/CONTEXT_HELP.md). Every block is omitted when empty. Derived only from the view the caller got, so it
 * says nothing their view does not.
 *
 * @param ref the entity
 * @param mnemonic the kind's command code
 * @param locale the language of the text (English only for now)
 * @param generation the source generation explained
 * @param newer true when the server holds a newer generation than the one the caller said it shows
 * @param data where the data came from and how fresh it is
 * @param layout why the page looks like this
 * @param next where to go from here
 * @param timings milliseconds spent
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record PageContext(ViewModel.Ref ref, String mnemonic, String locale, long generation, Boolean newer, Data data, Layout layout,
        Next next, Map<String, Double> timings) {

    /** Provenance, freshness and the connector's health as one word. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Data(String source, long generation, String fetchedAt, String businessDate, boolean current, boolean live,
            String updatedAt, String staleAfter, boolean stale, String health, Linked linked) {}

    /** Linked entities: fetched within the budget, still pending, refused to the caller. */
    public record Linked(int fetched, int pending, int denied, long budgetMs) {}

    /**
     * @param label how the layout was built, as the page's provenance says
     * @param fingerprint the document shape's short fingerprint
     * @param sutra the Sutra chosen, absent when the layout is wholly inferred
     * @param inferred whether inference contributed anything
     * @param candidates the Sutras of the kind that were not chosen and what their {@code where} gave
     * @param inferredPanels panels inference added or completed, with the rule that did
     * @param noData panels with nothing to show, and why
     * @param errors panels that failed to bind
     * @param masked fields shown hidden for this caller, with the panels they are in
     * @param noAccess panels the caller may not open (title and the kind they name, nothing else)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Layout(String label, String fingerprint, Chosen sutra, boolean inferred, List<Candidate> candidates,
            List<InferredPanel> inferredPanels, List<NoData> noData, List<PanelError> errors, List<MaskedField> masked,
            List<NoAccess> noAccess) {}

    /** The Sutra the page is built from: its {@code where} (source text) and priority explain the choice. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Chosen(String name, int version, int priority, String where, String description) {}

    /** A Sutra of the kind that was not chosen: {@code result} is true, false, error or masked. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Candidate(String name, int version, int priority, String where, String result) {}

    public record InferredPanel(String id, String title, String reason) {}

    /** {@code why}: missing, null, empty list, masked, no values or no data; {@code path} the document path it looked at. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record NoData(String id, String title, String why, String path) {}

    public record PanelError(String id, String title, String message) {}

    /** {@code key} is the document path of the field when the page knows it, else its label. */
    public record MaskedField(String key, String label, List<String> panels) {}

    /** Only what the page already shows: the panel's title and the kind of entity it may not open. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record NoAccess(String title, String kind) {}

    /** The page's own function keys (links already restricted for the caller) and the panel kinds on it. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Next(List<ViewModel.KeyView> keys, List<String> panelKinds) {}
}
