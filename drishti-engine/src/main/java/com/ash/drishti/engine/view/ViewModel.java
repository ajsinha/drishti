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
package com.ash.drishti.engine.view;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A bound view: everything the console renders, and nothing it must compute. Immutable, serialised as
 * JSON. Every value is pre-formatted text plus a tone, so all clients render numbers identically.
 *
 * @param ref the entity
 * @param mnemonic the command mnemonic for the kind ({@code TRD})
 * @param title the title line
 * @param strip header key figures
 * @param panels panels in layout order; {@link PanelView#area()} says which column
 * @param keys function keys
 * @param provenance how the view was built
 * @param timings stage timings in milliseconds (fetch, layout, bind, links, total)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewModel(
        Ref ref,
        String mnemonic,
        TitleView title,
        List<Cell> strip,
        List<PanelView> panels,
        List<KeyView> keys,
        Provenance provenance,
        Map<String, Double> timings) {

    /** An entity address as sent to clients. */
    public record Ref(String kind, String id) {}

    /**
     * A navigable link.
     *
     * @param kind target kind
     * @param id target id
     * @param mnemonic command mnemonic for the target kind, when one exists
     */
    public record LinkView(String kind, String id, String mnemonic) {}

    /**
     * One formatted value.
     *
     * @param label label (strip, kv) or null (table cell)
     * @param text formatted text
     * @param tone theme tone ({@code pos}, {@code neg}, ...) or null
     * @param link when the value opens another entity
     * @param emphasis draw highlighted
     * @param path document path the value came from, for live patches
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public record Cell(String label, String text, String tone, LinkView link, boolean emphasis, String path) {

        public static Cell of(String label, String text) {
            return new Cell(label, text, null, null, false, null);
        }
    }

    /**
     * @param pill pill text
     * @param id the identifier
     * @param with counterparty text and link, or null
     */
    public record TitleView(String pill, String id, Cell with) {}

    /**
     * @param key {@code F2}
     * @param label what it does ({@code Legs}, {@code Netting set}, {@code Raw JSON})
     * @param action {@code panel}, {@code link}, {@code raw} or {@code impact}
     * @param panel target panel id for {@code panel}
     * @param link target for {@code link}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KeyView(String key, String label, String action, String panel, LinkView link) {}

    /**
     * @param layout {@code Sutra irs-vanilla v3 + inference}
     * @param fingerprint short shape fingerprint
     * @param source source system
     * @param generation source generation
     * @param fetchedAt ISO instant
     * @param live whether the source pushes updates
     */
    /** @param businessDate the business date the data is for, or {@code null} when the source is not dated */
    /**
     * Where a view came from. {@code updatedAt}: when its source last received new data (null when it cannot tell);
     * {@code staleAfter}: the source's threshold (ISO-8601 duration) or null; {@code stale}: older than that.
     */
    /** {@code sutra}: the name of the Sutra that laid the view out, or null (inference alone); personal layouts are kept by it. */
    /**
     * {@code masked}: how many values in the view the caller's field masks replaced ({@code •••}), 0 when nothing is masked for them;
     * {@code maskedPanels}: the ids of the panels that hold some, so an embedding page can say "some values are hidden".
     */
    public record Provenance(String layout, String fingerprint, String source, long generation, String fetchedAt, boolean live,
            String businessDate, String updatedAt, String staleAfter, boolean stale, String sutra, int masked, List<String> maskedPanels) {

        public Provenance {
            maskedPanels = maskedPanels == null ? List.of() : List.copyOf(maskedPanels);
        }

        public Provenance(String layout, String fingerprint, String source, long generation, String fetchedAt, boolean live,
                String businessDate, String updatedAt, String staleAfter, boolean stale, String sutra) {
            this(layout, fingerprint, source, generation, fetchedAt, live, businessDate, updatedAt, staleAfter, stale, sutra, 0, List.of());
        }

        public Provenance(String layout, String fingerprint, String source, long generation, String fetchedAt, boolean live, String businessDate) {
            this(layout, fingerprint, source, generation, fetchedAt, live, businessDate, null, null, false, null);
        }

        /** The same provenance with the mask counts of a built view. */
        public Provenance withMasked(int count, List<String> panels) {
            return new Provenance(layout, fingerprint, source, generation, fetchedAt, live, businessDate, updatedAt, staleAfter, stale, sutra, count, panels);
        }
    }

    /**
     * @param id panel id
     * @param kind panel kind
     * @param title resolved title
     * @param code header tag
     * @param key function key
     * @param area {@code main} or {@code right}
     * @param inferred whether inference built or completed it
     * @param explanation why inference chose it
     * @param data kind-specific content
     * @param error set when binding this panel failed; the rest of the view is unaffected
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    /**
     * @param empty nothing to show (the document lacks what the panel asks for); the console says "No data available"
     * @param span the Sutra's width for the panel in columns of a 12-column grid, or null (the whole column)
     * @param height the Sutra's height for the panel in grid rows, or null (as tall as its content)
     */
    public record PanelView(
            String id, String kind, String title, String code, String key, String area, boolean inferred,
            String explanation, PanelData data, String error, boolean empty, Integer span, Integer height, String denied) {

        /** {@code denied} is set when the panel's {@code source} is an entity the caller may not open (no data, no error). */
        public PanelView(String id, String kind, String title, String code, String key, String area, boolean inferred,
                String explanation, PanelData data, String error, boolean empty, Integer span, Integer height) {
            this(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, null);
        }

        public PanelView(String id, String kind, String title, String code, String key, String area, boolean inferred,
                String explanation, PanelData data, String error, boolean empty) {
            this(id, kind, title, code, key, area, inferred, explanation, data, error, empty, null, null);
        }
    }
}
