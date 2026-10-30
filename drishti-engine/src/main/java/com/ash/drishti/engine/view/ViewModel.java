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
 * @param controls the Sutra's controls with their options and current values (empty when it declares none)
 * @param selects the panels whose rows or bars set a control when clicked
 * @param layout the Sutra's {@code layout:} (SUTRA_NEXT.md 13: areas, columns, split, tabs, sections, sticky, arrange, phone) with each
 *     top-level panel's place ({@code place: {id: {area, order, section, collapsible}}}), or null: the panels flow
 * @param contract the ViewModel contract this view speaks, {@code major.minor} ({@link #CONTRACT}; VIEWMODEL.md section 8): a client
 *     refuses a major it does not know (the renderer's {@code DRS-9105}) and ignores what a newer minor adds
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
        Map<String, Double> timings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ControlView> controls,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SelectView> selects,
        Map<String, Object> layout,
        String contract) {

    /** The contract every ViewModel this server builds speaks (VIEWMODEL.md section 8): the minor moves for additions, the major for a break. */
    public static final String CONTRACT = "1.0";

    /** A view with its layout, stamped with this server's {@link #CONTRACT}. */
    public ViewModel(Ref ref, String mnemonic, TitleView title, List<Cell> strip, List<PanelView> panels, List<KeyView> keys,
            Provenance provenance, Map<String, Double> timings, List<ControlView> controls, List<SelectView> selects, Map<String, Object> layout) {
        this(ref, mnemonic, title, strip, panels, keys, provenance, timings, controls, selects, layout, CONTRACT);
    }

    /** A view whose panels flow (no {@code layout:}). */
    public ViewModel(Ref ref, String mnemonic, TitleView title, List<Cell> strip, List<PanelView> panels, List<KeyView> keys,
            Provenance provenance, Map<String, Double> timings, List<ControlView> controls, List<SelectView> selects) {
        this(ref, mnemonic, title, strip, panels, keys, provenance, timings, controls, selects, null);
    }

    public ViewModel {
        controls = controls == null ? List.of() : List.copyOf(controls);
        selects = selects == null ? List.of() : List.copyOf(selects);
        contract = contract == null || contract.isBlank() ? CONTRACT : contract;
    }

    /** A view without controls. */
    public ViewModel(Ref ref, String mnemonic, TitleView title, List<Cell> strip, List<PanelView> panels, List<KeyView> keys,
            Provenance provenance, Map<String, Double> timings) {
        this(ref, mnemonic, title, strip, panels, keys, provenance, timings, List.of(), List.of(), null);
    }

    /** The same view with another provenance. */
    public ViewModel withProvenance(Provenance p) {
        return new ViewModel(ref, mnemonic, title, strip, panels, keys, p, timings, controls, selects, layout);
    }

    /** The same view with its controls and selects. */
    public ViewModel withControls(List<ControlView> controls, List<SelectView> selects) {
        return new ViewModel(ref, mnemonic, title, strip, panels, keys, provenance, timings, controls, selects, layout);
    }

    /** The same view with its screen layout (SUTRA_NEXT.md 13). */
    public ViewModel withLayout(Map<String, Object> layout) {
        return new ViewModel(ref, mnemonic, title, strip, panels, keys, provenance, timings, controls, selects, layout);
    }

    /** One option of a control: the value that is set and the text shown. */
    public record Opt(String value, String label) {}

    /**
     * A control as the reader sees it: what it is, the options they may pick (never a masked value), its value now and the panels
     * that read it (only those are rebuilt when it changes).
     *
     * @param id the control's id ({@code ctl.<id>})
     * @param kind {@code dropdown}, {@code multi-select}, {@code cascading}, {@code toggle}, {@code segmented}, {@code date},
     *     {@code date-range}, {@code number-range}, {@code top-n} or {@code search}
     * @param label the text beside it
     * @param placement {@code strip}, {@code header} or {@code side}
     * @param panel the panel whose header holds it, for {@code header}
     * @param parent the control a cascading one depends on
     * @param options the choices (a date-range's are its presets, resolved on the calendar)
     * @param value the current value (a range is {@code from..to}; empty when none)
     * @param values the current choices of a multi-select
     * @param required whether it cannot be cleared
     * @param remember whether the reader's last choice is kept
     * @param min a number range's lower bound (from the data or fixed), or null
     * @param max its upper bound
     * @param step a slider's step, or null
     * @param range a slider with two handles (its value is {@code from..to})
     * @param reads ids of the panels (and {@code strip} or {@code title}) whose expressions read it
     * @param truncated whether the options were cut at the limit
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ControlView(String id, String kind, String label, String placement, String panel, String parent, List<Opt> options,
            String value, List<String> values, boolean required, boolean remember, String min, String max, String step, boolean range,
            List<String> reads, boolean truncated) {}

    /**
     * A panel whose click sets a control: a table row sets {@code by} to the value of its {@code field}; a bar sets it to its label.
     *
     * @param panel the panel clicked
     * @param by the control set
     * @param filter the panels the author says it filters (those that read the control are rebuilt)
     */
    public record SelectView(String panel, String by, List<String> filter) {}

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
            String businessDate, String updatedAt, String staleAfter, boolean stale, String sutra, int masked, List<String> maskedPanels,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean partial, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> missing) {

        public Provenance {
            maskedPanels = maskedPanels == null ? List.of() : List.copyOf(maskedPanels);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }

        public Provenance(String layout, String fingerprint, String source, long generation, String fetchedAt, boolean live,
                String businessDate, String updatedAt, String staleAfter, boolean stale, String sutra, int masked, List<String> maskedPanels) {
            this(layout, fingerprint, source, generation, fetchedAt, live, businessDate, updatedAt, staleAfter, stale, sutra, masked, maskedPanels,
                    false, List.of());
        }

        public Provenance(String layout, String fingerprint, String source, long generation, String fetchedAt, boolean live,
                String businessDate, String updatedAt, String staleAfter, boolean stale, String sutra) {
            this(layout, fingerprint, source, generation, fetchedAt, live, businessDate, updatedAt, staleAfter, stale, sutra, 0, List.of());
        }

        /**
         * The same provenance marked partial: the document says some of what makes it did not answer (a grouped page gathered across
         * Drishti*net with a node down: {@code partial: true} and {@code missing}, one line per node), so the page says so.
         */
        public Provenance withPartial(List<String> what) {
            return new Provenance(layout, fingerprint, source, generation, fetchedAt, live, businessDate, updatedAt, staleAfter, stale, sutra, masked,
                    maskedPanels, true, what);
        }

        public Provenance(String layout, String fingerprint, String source, long generation, String fetchedAt, boolean live, String businessDate) {
            this(layout, fingerprint, source, generation, fetchedAt, live, businessDate, null, null, false, null);
        }

        /** The same provenance with the mask counts of a built view. */
        public Provenance withMasked(int count, List<String> panels) {
            return new Provenance(layout, fingerprint, source, generation, fetchedAt, live, businessDate, updatedAt, staleAfter, stale, sutra, count, panels,
                    partial, missing);
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
    /**
     * What the console needs of a panel's Sutra Next wiring (SUTRA_NEXT.md 7, 8): the events a click fires, the words shown while it waits on
     * an event (set only while it waits), its groups of children (where and when they show) and the source it reads.
     *
     * @param emits the events, each {@code name} and {@code on}
     * @param waits set while the panel waits on an event not fired: what it says instead of data
     * @param children per group of child panels: {@code place} and {@code show}
     * @param source the kind (and pack) of the source it reads, or null
     * @param parent the parent panel's id for a child panel, or null
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Wire(List<Map<String, String>> emits, String waits, List<Map<String, String>> children, Map<String, String> source, String parent) {}

    /**
     * How often the console fetches a panel again ({@code refresh:} in the Sutra, SUTRA_NEXT.md 9).
     *
     * @param every seconds between fetches: the fastest the reader may choose
     * @param userCanPause whether the reader may pause it
     */
    public record RefreshView(int every, boolean userCanPause) {}

    /**
     * How fresh a panel with its own {@code source:} is (SUTRA_NEXT.md 9), apart from the page's: the connector that answered it and
     * the kind it read, when it was fetched, when that connector last received new data, its threshold and whether it is past it. The
     * console says "updated N s ago", "stale" and "X isn't answering" for that panel from these, falling back to the page's provenance.
     *
     * @param source the connector that answered (or the source the document names), or null
     * @param kind the kind the panel's source reads
     * @param fetchedAt when the source's document was fetched (ISO-8601), or null when it cannot tell (a list of entities)
     * @param updatedAt when the connector last received new data, or null
     * @param staleAfter the connector's threshold (ISO-8601 duration), or null
     * @param stale older than {@code staleAfter}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PanelProvenance(String source, String kind, String fetchedAt, String updatedAt, String staleAfter, boolean stale) {}

    /**
     * The state of a panel whose source is a computed kind (REACTIVE_CONNECTORS.md section 12.4): never a blank panel. The console draws
     * the result when there is one (dimmed while {@code computing}, amber when {@code stale}), the progress, and the message in words
     * ({@code failed}, {@code refused}); it listens to {@code requestId} on the live channel for progress and for the result when it lands.
     *
     * @param state {@code ready}, {@code computing}, {@code stale}, {@code failed} or {@code refused}
     * @param requestId the correlation id of this panel's read (its events go to this viewer only)
     * @param connector the reactive connector that computes it
     * @param message why it failed or was refused, or what it waits for; null when ready
     * @param progress the latest progress ({@code done}, {@code of}, a partial estimate), or null
     * @param hasResult whether a result (current or last good) is shown
     * @param stamp the result's stamp (pin, computedAt, engine, inputs), or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PanelCompute(String state, String requestId, String connector, String message, Map<String, Object> progress, boolean hasResult,
            Map<String, Object> stamp) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    /**
     * @param empty nothing to show (the document lacks what the panel asks for); the console says "No data available"
     * @param span the Sutra's width for the panel in columns of a 12-column grid, or null (the whole column)
     * @param height the Sutra's height for the panel in grid rows, or null (as tall as its content)
     */
    public record PanelView(
            String id, String kind, String title, String code, String key, String area, boolean inferred,
            String explanation, PanelData data, String error, boolean empty, Integer span, Integer height, String denied, Wire wire, RefreshView refresh,
            Map<String, Object> form, PanelProvenance provenance, PanelCompute compute) {

        /** A panel whose source is not computed. */
        public PanelView(String id, String kind, String title, String code, String key, String area, boolean inferred,
                String explanation, PanelData data, String error, boolean empty, Integer span, Integer height, String denied, Wire wire, RefreshView refresh,
                Map<String, Object> form, PanelProvenance provenance) {
            this(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, refresh, form, provenance, null);
        }

        /** The same panel with the state of its computed source (REACTIVE_CONNECTORS.md section 12.4), or none (null). */
        public PanelView withCompute(PanelCompute next) {
            return new PanelView(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, refresh, form, provenance, next);
        }

        /** A panel without a provenance of its own (it reads the page's record). */
        public PanelView(String id, String kind, String title, String code, String key, String area, boolean inferred,
                String explanation, PanelData data, String error, boolean empty, Integer span, Integer height, String denied, Wire wire, RefreshView refresh,
                Map<String, Object> form) {
            this(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, refresh, form, null);
        }

        /** The same panel with the provenance of its own {@code source:} (SUTRA_NEXT.md 9), or none (null). */
        public PanelView withProvenance(PanelProvenance next) {
            return new PanelView(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, refresh, form, next, compute);
        }

        /** A panel without a form (SUTRA_NEXT.md 10). */
        public PanelView(String id, String kind, String title, String code, String key, String area, boolean inferred,
                String explanation, PanelData data, String error, boolean empty, Integer span, Integer height, String denied, Wire wire, RefreshView refresh) {
            this(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, refresh, null);
        }

        /**
         * The same panel with its form for the console ({@code form:}, SUTRA_NEXT.md 10): the fields as this viewer may edit them,
         * the rules, the hints, the lookups' choices and, for a table, each row's values; null for none.
         */
        public PanelView withForm(Map<String, Object> next) {
            return new PanelView(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, refresh, next, provenance, compute);
        }

        /** A panel that does not refresh on its own. */
        public PanelView(String id, String kind, String title, String code, String key, String area, boolean inferred,
                String explanation, PanelData data, String error, boolean empty, Integer span, Integer height, String denied, Wire wire) {
            this(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, null);
        }

        /** The same panel refreshing as the Sutra says ({@code refresh:}, SUTRA_NEXT.md 9), or not at all (null). */
        public PanelView withRefresh(RefreshView next) {
            return new PanelView(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, wire, next, form, provenance, compute);
        }

        /** The same panel showing {@code next} (a mask, a filter), everything else kept. */
        public PanelView withData(PanelData next) {
            return new PanelView(id, kind, title, code, key, area, inferred, explanation, next, error, empty, span, height, denied, wire, refresh, form, provenance, compute);
        }

        /** A panel without Sutra Next wiring. */
        public PanelView(String id, String kind, String title, String code, String key, String area, boolean inferred,
                String explanation, PanelData data, String error, boolean empty, Integer span, Integer height, String denied) {
            this(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, null);
        }

        /** The same panel with its wiring for the console. */
        public PanelView withWire(Wire next) {
            return new PanelView(id, kind, title, code, key, area, inferred, explanation, data, error, empty, span, height, denied, next, refresh, form, provenance, compute);
        }

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
