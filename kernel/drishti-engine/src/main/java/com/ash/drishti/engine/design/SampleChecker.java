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
package com.ash.drishti.engine.design;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.view.ViewModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one answer to "does this Sutra work on these samples": renders it against each sample (through a {@link Renderer} the
 * caller supplies, which carries its field masks and open rights) and returns a panel by sample matrix whose cells are
 * {@code ok}, {@code empty}, {@code error} (with the message) or {@code noAccess}, with counts per panel and overall. Studio's
 * test, auto-design's pruning and the Build workbench's check all delegate here, so they agree. Stateless and thread-safe
 * when its renderer is.
 */
public final class SampleChecker {

    public static final String OK = "ok";
    public static final String EMPTY = "empty";
    public static final String ERROR = "error";
    public static final String NO_ACCESS = "noAccess";

    /** One sample to check: a document, or a reference to a stored entity (the renderer reads it again with its rights). */
    public record Input(String name, JsonNode document, EntityRef ref) {}

    /** Renders the Sutra for one sample; throws {@link NoAccess} when the caller may not open it. */
    @FunctionalInterface
    public interface Renderer {
        ViewModel render(Input input);
    }

    /** The caller may not open the sample's entity; the whole column is {@code noAccess}. */
    public static final class NoAccess extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NoAccess(String message) {
            super(message);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(String status, String message) {}

    /** Cells by status. */
    public record Counts(int ok, int empty, int error, int noAccess) {

        Counts add(String status) {
            return switch (status) {
                case OK -> new Counts(ok + 1, empty, error, noAccess);
                case EMPTY -> new Counts(ok, empty + 1, error, noAccess);
                case ERROR -> new Counts(ok, empty, error + 1, noAccess);
                default -> new Counts(ok, empty, error, noAccess + 1);
            };
        }
    }

    /**
     * One panel across the samples.
     *
     * @param id the panel's id
     * @param cells one per sample, in the samples' order
     * @param counts the cells by status
     */
    public record PanelRow(String id, List<Cell> cells, Counts counts) {

        /** The first error message in the row, or null. */
        public String firstError() {
            return cells.stream().filter(c -> ERROR.equals(c.status())).map(Cell::message).findFirst().orElse(null);
        }
    }

    /** A header key figure and in how many samples it came out blank. */
    public record StripRow(String label, int blank) {}

    /**
     * One sample as rendered.
     *
     * @param name the sample's name
     * @param layout the Sutra that rendered it (name@version), or null when it did not render
     * @param status {@code ok}, {@code error} (it did not render: {@code message} says why) or {@code noAccess}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SampleRow(String name, String layout, String status, String message) {}

    /**
     * The result.
     *
     * @param ok no cell is an error and no sample is out of reach
     * @param samples one entry per sample
     * @param panels one row per panel
     * @param strip one entry per header key figure
     * @param counts every cell by status
     */
    public record Matrix(boolean ok, List<SampleRow> samples, List<PanelRow> panels, List<StripRow> strip, Counts counts) {

        public PanelRow panel(String id) {
            return panels.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        }
    }

    /**
     * @param panelIds the Sutra's panels in order (rows even for a sample that did not render); empty: the panels the first
     *     rendering shows
     * @param inputs the samples
     * @param renderer renders one sample
     */
    public Matrix check(Collection<String> panelIds, List<Input> inputs, Renderer renderer) {
        Map<String, List<Cell>> rows = new LinkedHashMap<>();
        panelIds.forEach(id -> rows.put(id, new ArrayList<>()));
        Map<String, Integer> blank = new LinkedHashMap<>();
        List<SampleRow> samples = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            String name = in.name() == null ? "sample " + (i + 1) : in.name();
            ViewModel vm = null;
            String status = OK;
            String message = null;
            try {
                vm = renderer.render(in);
            } catch (NoAccess e) {
                status = NO_ACCESS;
                message = e.getMessage();
            } catch (RuntimeException e) {
                status = ERROR;
                message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            Map<String, Cell> column = new LinkedHashMap<>();
            if (vm != null && vm.panels() != null) {
                vm.panels().forEach(p -> column.put(p.id(), p.denied() != null ? new Cell(NO_ACCESS, p.denied())
                        : p.error() != null ? new Cell(ERROR, p.error()) : p.empty() ? new Cell(EMPTY, null) : new Cell(OK, null)));
                if (vm.strip() != null) {
                    vm.strip().forEach(c -> blank.merge(c.label(), c.text() == null || c.text().isBlank() || "—".equals(c.text()) ? 1 : 0, Integer::sum));
                }
            }
            for (String id : column.keySet()) {
                rows.computeIfAbsent(id, k -> new ArrayList<>());
            }
            for (Map.Entry<String, List<Cell>> row : rows.entrySet()) {
                row.getValue().add(vm == null ? new Cell(status, message) : column.getOrDefault(row.getKey(), new Cell(EMPTY, null)));
            }
            samples.add(new SampleRow(name, vm == null || vm.provenance() == null ? null : vm.provenance().layout(), status, message));
        }
        List<PanelRow> panels = new ArrayList<>();
        Counts all = new Counts(0, 0, 0, 0);
        for (Map.Entry<String, List<Cell>> row : rows.entrySet()) {
            List<Cell> cells = row.getValue();
            while (cells.size() < inputs.size()) {
                cells.add(0, new Cell(EMPTY, null));            // a panel first seen in a later sample was absent in the earlier ones
            }
            Counts c = new Counts(0, 0, 0, 0);
            for (Cell cell : cells) {
                c = c.add(cell.status());
                all = all.add(cell.status());
            }
            panels.add(new PanelRow(row.getKey(), List.copyOf(cells), c));
        }
        List<StripRow> strip = new ArrayList<>();
        blank.forEach((label, n) -> strip.add(new StripRow(label, n)));
        boolean ok = all.error() == 0 && all.noAccess() == 0 && samples.stream().allMatch(s -> OK.equals(s.status()));
        return new Matrix(ok, List.copyOf(samples), List.copyOf(panels), List.copyOf(strip), all);
    }
}
