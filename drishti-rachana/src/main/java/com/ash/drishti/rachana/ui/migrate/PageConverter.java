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
package com.ash.drishti.rachana.ui.migrate;

import com.ash.drishti.rachana.ui.BoardCheck;
import com.ash.drishti.rachana.ui.BoardCheckContext;
import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.rachana.ui.ChipCatalog;
import com.ash.drishti.rachana.ui.ChipDoc;
import com.ash.drishti.rachana.ui.ChipRef;
import com.ash.drishti.rachana.ui.PinSpec;
import com.ash.drishti.rachana.ui.UiDocumentException;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.ash.drishti.rachana.ui.UiProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts an entity page (a Sutra) into a board (docs/architecture/UI_BOARD.md 11.1, phase P5). The board takes the record's id as its parameter
 * {@code id}; every chip reads the record through {@code connector: <pack>/<kind>} as the viewer. The page's title, strip, controls and keys become
 * the board-local header chip. Each panel becomes:
 * <ul>
 * <li>a block use ({@code use: deal-ticket@1}) the instance of the seed chip of that block, its {@code with} as the instance's controls;</li>
 * <li>a panel a generic record chip ({@link #RECORD_CHIPS}, UI_BOARD.md 12.4) or the seed chip {@code panel-<kind>} carries whole (every option
 * a pin or a constant of it) an instance of that chip, the field names in its pin mapping and controls;</li>
 * <li>anything else a board-local chip wrapping the panel, its record reads written over the document pin ({@code $.X} as {@code @doc.X}).</li>
 * </ul>
 * A chip choice is accepted only when {@link BoardSutra} paints it back into exactly the panel it came from. A panel that reads a page control
 * ({@code ctl.x}) is wired to the header's out pin {@code ctl-x} by a trace (follow the selection). Links to other pages stay as navigation.
 */
public final class PageConverter {

    private static final Pattern CTL = Pattern.compile("ctl\\.([A-Za-z0-9_-]+)");
    private static final Pattern LINK = Pattern.compile("link\\(");
    private static final Pattern WHOLE = Pattern.compile("%\\{([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)?)(?:\\|[^}]*)?}");
    private static final Pattern AT_PIN = Pattern.compile("@([A-Za-z][A-Za-z0-9_-]*)");
    private static final Pattern PATH = Pattern.compile("\\$\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");
    static final String HEADER = "header";

    private final ChipCatalog chips;

    public PageConverter(ChipCatalog chips) {
        this.chips = chips;
    }

    /** A chip or board name from free text: lower case, letters, digits and hyphens, starting with a letter, at most 64. */
    static String name(String s) {
        String n = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) {
            n = "p-" + n;
        }
        return n.length() > 64 ? n.substring(0, 64).replaceAll("-$", "") : n;
    }

    /** Converts one Sutra's text; null when the text is not a Sutra (a block). */
    public Conversion convert(String pack, String text) {
        JsonNode s;
        try {
            s = UiDocuments.tree(text);
        } catch (UiDocumentException e) {
            return failed("sutra:?", pack, null, "?", "the Sutra cannot be read: " + e.getMessage());
        }
        if (!s.hasNonNull("sutra")) {
            return null;
        }
        String sutra = s.path("sutra").asText();
        String screen = "sutra:" + sutra + "@" + s.path("version").asInt(1);
        String kind = s.path("match").path("kind").asText(null);
        String board = name(pack + "-" + sutra);
        if (kind == null) {
            return failed(screen, pack, null, board, "the Sutra names no kind in 'match' (a board needs the kind of its record)");
        }
        String supply = pack + "/" + kind;
        ObjectNode b = BoardSutra.JSON.createObjectNode();
        b.put("rachana", 1).put("board", board).put("title", title(s, sutra));
        b.put("description", "Converted from the Sutra " + sutra + "@" + s.path("version").asInt(1) + " of the pack " + pack + " (UI_BOARD.md 11).");
        b.putObject("params").putObject("id").put("type", "id").put("of", supply).put("required", true);
        ObjectNode instances = b.putObject("chips");
        ArrayNode locals = b.putArray("local-chips");
        ArrayNode traces = BoardSutra.JSON.createArrayNode();
        List<String> seeds = new ArrayList<>();
        List<Conversion.Local> made = new ArrayList<>();
        Set<String> controls = new LinkedHashSet<>();
        s.path("controls").forEach(c -> controls.add(c.path("id").asText()));

        ObjectNode page = BoardSutra.JSON.createObjectNode();
        for (String k : List.of("title", "strip", "controls", "keys")) {
            if (s.has(k)) {
                page.set(k, BoardSutra.mapText(s.get(k), BoardSutra::toPin));
            }
        }
        ObjectNode header = localChip(HEADER, "The page header of " + sutra + ": its title, key figures, controls and function keys.", supply, Set.of(),
                controls, BoardSutra.JSON.createObjectNode().set("page", page));
        locals.add(header);
        instances.set(HEADER, instance("local-" + HEADER + "@1", null, supply, null));
        int links = count(LINK, s.toString());

        Set<String> used = new LinkedHashSet<>(List.of(HEADER));
        List<String> pageTraces = new ArrayList<>();
        for (JsonNode p : s.path("panels")) {
            String base = name(p.hasNonNull("id") ? p.path("id").asText() : p.hasNonNull("use") ? p.path("use").asText().replaceAll("@.*", "") : "panel");
            String inst = base;
            for (int n = 2; used.contains(inst); n++) {
                inst = base + "-" + n;
            }
            used.add(inst);
            Set<String> reads = new LinkedHashSet<>();
            Matcher m = CTL.matcher(p.toString());
            while (m.find()) {
                if (controls.contains(m.group(1))) {
                    reads.add(m.group(1));
                }
            }
            String why = reads.isEmpty() ? null : "it reads the page control" + (reads.size() > 1 ? "s " : " ") + String.join(", ", reads);
            ObjectNode seed = linked(p, supply, inst);
            if (seed != null && !reads.isEmpty()) {
                seed = ChipRef.parse(seed.path("use").asText()).flatMap(chips::find).map(e -> e.doc().inPins().containsKey(PAGE)).orElse(false)
                        ? seed : null;                           // a generic chip reads the page controls through its 'page' pin
            }
            if (seed != null) {
                instances.set(inst, seed);
                seeds.add(seed.path("use").asText());
                if (!reads.isEmpty()) {
                    pageTraces.add(inst);
                }
                continue;
            }
            if (why == null) {
                why = p.has("use") ? "no seed chip carries the block use " + p.path("use").asText() + " with these parameters"
                        : p.has("source") ? "it reads another record through source: " + p.path("source").asText()
                                + " (a chip names no source, CP-02; on a board that is a second supply)"
                        : p.path("rows").isTextual() && !PATH.matcher(p.path("rows").asText()).matches()
                                ? "its rows are computed over the record (" + abbrev(p.path("rows").asText()) + "), not a path a pin maps"
                        : "no Chip Store chip (" + String.join(", ", RECORD_CHIPS.getOrDefault(p.path("kind").asText(""), List.of())) + ", panel-" + p.path("kind").asText("?")
                                + ") carries every option of the panel";
            }
            String chip = "local-" + inst;
            locals.add(localChip(inst, "Panel '" + inst + "' of the page " + sutra + ", converted as it was.", supply, reads, Set.of(),
                    BoardSutra.JSON.createObjectNode().set("panels", BoardSutra.JSON.createArrayNode().add(BoardSutra.mapText(p, BoardSutra::toPin)))));
            instances.set(inst, instance(chip + "@1", null, supply, null));
            made.add(new Conversion.Local(chip, inst, why));
            for (String c : reads) {
                traces.addObject().put("from", HEADER + "." + ctlPin(c)).put("to", inst + "." + ctlPin(c));
            }
        }
        if (!pageTraces.isEmpty()) {                             // the header's page controls, as one 'page' out pin, into the generic chips
            ObjectNode pins = (ObjectNode) header.path("pins");
            (pins.get("out") instanceof ObjectNode o ? o : pins.putObject("out")).putObject(PAGE).put("type", "spec");
            ((ObjectNode) header.path("manual").path("pins")).put(PAGE, "Out. Every page control as the person sets it, by id (ctl.<id>).");
            pageTraces.forEach(i -> traces.addObject().put("from", HEADER + "." + PAGE).put("to", i + "." + PAGE));
        }
        if (!traces.isEmpty()) {
            b.set("traces", traces);
        }
        layout(b, s, used);
        return checked(screen, pack, kind, board, b, seeds, made, links, traces.size());
    }

    private Conversion checked(String screen, String pack, String kind, String board, ObjectNode b, List<String> seeds, List<Conversion.Local> made,
            int links, int traces) {
        String text = b.toString();
        List<UiProblem> problems = BoardCheck.check(text, BoardCheckContext.personal(chips));
        Optional<UiProblem> error = problems.stream().filter(UiProblem::error).findFirst();
        Conversion.Status st = error.isPresent() ? Conversion.Status.FAILS : made.isEmpty() ? Conversion.Status.CLEAN : Conversion.Status.LOCAL;
        String reason = error.map(e -> "board check " + e.rule() + " at " + e.path() + ": " + e.message()).orElse(null);
        return new Conversion(screen, pack, kind, board, b, st, seeds, made, links, traces, reason, problems);
    }

    /** The pin a page control travels on: {@code ctl-<id>} in the pin name form. */
    static String ctlPin(String control) {
        return "ctl-" + control.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
    }

    /**
     * The board's layout from the page's: the header across the top, then each panel in the main column (8 of 12) or, for
     * {@code area: right}, the side column (4 of 12), in page order; on a phone one column in page order.
     */
    private static void layout(ObjectNode b, JsonNode s, Set<String> instances) {
        ObjectNode layout = b.putObject("layout");
        ObjectNode desktop = layout.putObject("desktop");
        desktop.put("columns", 12);
        ArrayNode areas = desktop.putArray("areas");
        List<String> names = new ArrayList<>(instances);
        int main = 2;
        int side = 2;
        for (int i = 0; i < names.size(); i++) {
            ObjectNode a = areas.addObject().put("chip", names.get(i));
            if (i == 0) {
                a.putArray("at").add(1).add(1);
                a.putArray("span").add(12).add(1);
                continue;
            }
            boolean right = "right".equals(s.path("panels").path(i - 1).path("area").asText(""));
            a.putArray("at").add(right ? 9 : 1).add(right ? side : main);
            a.putArray("span").add(right ? 4 : 8).add(4);
            if (right) {
                side += 4;
            } else {
                main += 4;
            }
        }
        ArrayNode order = layout.putObject("phone").putArray("order");
        names.forEach(order::add);
    }

    static Conversion failed(String screen, String pack, String kind, String board, String reason) {
        return new Conversion(screen, pack, kind, board, null, Conversion.Status.FAILS, List.of(), List.of(), 0, 0, reason, List.of());
    }

    private static String title(JsonNode s, String sutra) {
        JsonNode t = s.path("title");
        String pill = t.isTextual() ? t.asText() : t.path("pill").asText("");
        return pill.isBlank() || pill.contains("$") ? sutra : pill;
    }

    private static int count(Pattern p, String text) {
        int n = 0;
        for (Matcher m = p.matcher(text); m.find(); ) {
            n++;
        }
        return n;
    }

    static ObjectNode instance(String use, String title, String supply, ObjectNode controls) {
        ObjectNode i = BoardSutra.JSON.createObjectNode();
        i.put("use", use);
        if (title != null) {
            i.put("title", title);
        }
        ObjectNode sup = i.putObject("supply");
        sup.put("connector", supply);
        sup.putObject("query").put("id", "%{board.params.id}");
        i.putObject("map").put(BoardSutra.DOC + ".id", "id");
        if (controls != null && controls.size() > 0) {
            i.set("controls", controls);
        }
        return i;
    }

    /** A board-local chip over the page's record: the document pin, a control pin per page control it reads, an out pin per control it owns. */
    private static ObjectNode localChip(String inst, String purpose, String supply, Set<String> reads, Set<String> owns, ObjectNode body) {
        ObjectNode c = BoardSutra.JSON.createObjectNode();
        c.put("rachana", 1).put("chip", "local-" + inst).put("version", 1).put("title", inst).put("category", "local");
        ObjectNode man = c.putObject("manual");
        man.put("purpose", purpose);
        ObjectNode mp = man.putObject("pins");
        mp.put(BoardSutra.DOC, "The page's record, as the viewer may see it. Required.");
        ObjectNode pins = c.putObject("pins");
        ObjectNode in = pins.putObject("in");
        ObjectNode doc = in.putObject(BoardSutra.DOC);
        doc.put("role", "data").put("type", "document").put("of", supply).put("required", true);
        doc.putObject("shape").putObject("id").put("type", "id").put("required", true);
        for (String r : reads) {
            in.putObject(ctlPin(r)).put("role", "control").put("type", "text");
            mp.put(ctlPin(r), "The page control '" + r + "' (a trace from the header).");
        }
        if (!owns.isEmpty()) {
            ObjectNode out = pins.putObject("out");
            for (String o : owns) {
                out.putObject(ctlPin(o)).put("type", "text");
                mp.put(ctlPin(o), "Out. The page control '" + o + "' as the person sets it.");
            }
        }
        man.put("behaviour", "As the panel on the page it was converted from.");
        man.put("defaults", "The panel's own.");
        man.put("limits", "As the panel's.");
        man.putArray("examples");
        man.put("not-for", "Any other page: a board-local chip belongs to its board until it is published.");
        man.put("accessibility", "As the panel's.");
        c.set("body", body);
        return c;
    }

    /** The generic chips' pin for the page controls (traced from the header's out pin of the same name): settings read them as {@code ctl.<id>}. */
    static final String PAGE = "page";
    private static final Pattern LINK_SOURCE = Pattern.compile("link\\(\\$\\.([A-Za-z_][A-Za-z0-9_]*), '([a-z0-9-]+)'\\)");

    /**
     * As {@link #seedInstance}; a panel reading the record its record links to ({@code source: link($.QUOTE_ID, 'quote')}) is the chip with a
     * second supply: the linked kind's connector, the id from the page record's field ({@code query: {id-from: QUOTE_ID}}) (12.4).
     */
    private ObjectNode linked(JsonNode p, String supply, String inst) {
        if (!p.has("source")) {
            return seedInstance(p, supply, inst);
        }
        Matcher m = LINK_SOURCE.matcher(p.path("source").asText(""));
        if (!m.matches()) {
            return null;
        }
        ObjectNode bare = p.deepCopy();
        bare.remove("source");
        String second = supply.substring(0, supply.indexOf('/') + 1) + m.group(2);
        ObjectNode i = seedInstance(bare, second, inst);
        if (i != null) {
            ((ObjectNode) i.path("supply")).putObject("query").put(BoardSutra.ID_FROM, m.group(1));
        }
        return i;
    }

    /** The instance of a Chip Store chip that carries {@code p} whole and paints back into it exactly, or null. */
    private ObjectNode seedInstance(JsonNode p, String supply, String inst) {
        if (p.has("use")) {
            ChipRef ref;
            try {
                ref = ChipRef.parse(p.path("use").asText()).orElse(null);
            } catch (RuntimeException e) {
                return null;
            }
            ChipDoc chip = chips.find(ref).map(ChipCatalog.Entry::doc).orElse(null);
            if (chip == null) {
                return null;
            }
            ObjectNode controls = BoardSutra.JSON.createObjectNode();
            var it = p.path("with").fields();
            while (it.hasNext()) {
                var e = it.next();
                PinSpec pin = chip.inPins().get(e.getKey());
                if (pin == null || pin.role() != PinSpec.PinRole.CONTROL) {
                    return null;
                }
                controls.set(e.getKey(), e.getValue().deepCopy());
            }
            ObjectNode i = instance(ref.toString(), null, supply, controls);
            return paintsBack(i, inst, p) ? i : null;
        }
        if (!p.hasNonNull("kind")) {
            return null;
        }
        String kind = p.path("kind").asText();
        for (String name : RECORD_CHIPS.getOrDefault(kind, List.of())) {
            ObjectNode i = chips.find(new ChipRef(name, 1)).map(e -> fit(e.doc(), p, supply, inst)).orElse(null);
            if (i != null && paintsBack(i, inst, p)) {
                return i;
            }
        }
        ObjectNode i = chips.find(new ChipRef("panel-" + kind, 1)).map(e -> fit(e.doc(), p, supply, inst)).orElse(null);
        return i != null && paintsBack(i, inst, p) ? i : null;
    }

    /**
     * The generic record chips a panel kind may become, tried in order (UI_BOARD.md 12.4, P5 follow-up); each chip is named for what it shows,
     * not for the kind. A kind whose panels come in more than one shape has a chip per shape ({@code kv} over the rows of a path, or over named
     * fields of the record). Then the seed chip {@code panel-<kind>}.
     */
    static final java.util.Map<String, List<String>> RECORD_CHIPS = java.util.Map.ofEntries(
            java.util.Map.entry("table", List.of("list-table")), java.util.Map.entry("ladder", List.of("dated-steps-ladder")),
            java.util.Map.entry("surface", List.of("value-grid-surface")), java.util.Map.entry("line", List.of("series-line")),
            java.util.Map.entry("hbar", List.of("ranked-bar-list")), java.util.Map.entry("area", List.of("area-series")),
            java.util.Map.entry("pivot", List.of("cross-tab-grid")), java.util.Map.entry("candlestick", List.of("price-candles")),
            java.util.Map.entry("scatter", List.of("point-scatter")), java.util.Map.entry("waterfall", List.of("step-waterfall")),
            java.util.Map.entry("histogram", List.of("distribution-histogram")), java.util.Map.entry("graph", List.of("relation-graph")),
            java.util.Map.entry("kv", List.of("kv-list", "kv-summary")), java.util.Map.entry("metric", List.of("headline-figure")),
            java.util.Map.entry("markdown", List.of("field-note")), java.util.Map.entry("map", List.of("map-highlight")),
            java.util.Map.entry("links", List.of("linked-entities")));

    /**
     * The instance of {@code chip} that carries panel {@code p}: each option of the panel lands on the slot the chip's body gives it, a
     * {@code %{control}} as a constant (its record reads written as {@code @doc.X}), an {@code @data} pin as the record path it reads
     * ({@code rows: $.POINTS} as {@code map: {rows: POINTS}}), a {@code %{pin.column}} as the column's mapped attribute ({@code x: TENOR} as
     * {@code map: {rows.x: TENOR}}), a literal only when equal. Null when an option has no slot. The field names stay in the board, never the chip.
     */
    private static ObjectNode fit(ChipDoc chip, JsonNode p, String supply, String inst) {
        JsonNode body = chip.body() == null ? null : chip.body().path("panels");
        if (body == null || body.size() != 1 || !chip.inPins().containsKey(BoardSutra.DOC) && hasDocRead(body)) {
            return null;
        }
        JsonNode b = body.get(0);
        ObjectNode controls = BoardSutra.JSON.createObjectNode();
        ObjectNode map = BoardSutra.JSON.createObjectNode();
        String title = null;
        var it = p.fields();
        while (it.hasNext()) {
            var e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (k.equals("id") || k.equals("kind")) {
                continue;
            }
            if (k.equals("title")) {
                if (!v.isTextual()) {
                    return null;
                }
                title = BoardSutra.toPin(v.asText());
                continue;
            }
            JsonNode slot = b.get(k);
            if (slot == null) {
                return null;
            }
            String st = slot.asText("");
            Matcher m = WHOLE.matcher(st);
            Matcher at = AT_PIN.matcher(st);
            String pinName = m.matches() ? m.group(1) : at.matches() ? at.group(1) : null;
            if (pinName == null) {
                if (!slot.equals(v)) {
                    return null;
                }
                continue;
            }
            if (pinName.contains(".")) {
                if (!v.isTextual() || !chip.inPins().containsKey(pinName.substring(0, pinName.indexOf('.')))) {
                    return null;
                }
                map.put(pinName, v.asText());
                continue;
            }
            PinSpec pin = chip.inPins().get(pinName);
            if (pin == null) {
                return null;
            }
            if (pin.role() == PinSpec.PinRole.CONTROL && m.matches()) {
                controls.set(pin.name(), BoardSutra.mapText(v, BoardSutra::toPin));
            } else if (pin.role() == PinSpec.PinRole.DATA) {
                Matcher pm = PATH.matcher(v.asText(""));
                if (pm.matches()) {
                    map.put(pin.name(), pm.group(1));
                } else if (v.isTextual() && v.asText().contains("$")) {
                    map.put(pin.name(), "=" + BoardSutra.toPin(v.asText()));     // computed rows over the record: group(...), flatten(...) (12.4)
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }
        if (chip.inPins().containsKey(BoardSutra.PANEL_ID) && p.hasNonNull("id") && !p.path("id").asText().equals(inst)) {
            controls.put(BoardSutra.PANEL_ID, p.path("id").asText());
        }
        ObjectNode i = instance(chip.ref().toString(), title, supply, controls);
        if (!chip.inPins().containsKey(BoardSutra.DOC)) {
            ((ObjectNode) i.path("map")).remove(BoardSutra.DOC + ".id");
        }
        ((ObjectNode) i.path("map")).setAll(map);
        if (i.path("map").isEmpty()) {
            i.remove("map");
        }
        return i;
    }

    private static String abbrev(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private static boolean hasDocRead(JsonNode body) {
        return body.toString().contains("@doc");
    }

    /** True when the instance paints back into exactly the panel it was made from. */
    private boolean paintsBack(ObjectNode inst, String name, JsonNode panel) {
        ChipRef ref = ChipRef.parse(inst.path("use").asText()).orElse(null);
        if (ref == null) {
            return false;
        }
        ChipDoc chip = chips.find(ref).map(ChipCatalog.Entry::doc).orElse(null);
        if (chip == null) {
            return false;
        }
        ObjectNode one = BoardSutra.JSON.createObjectNode();
        one.put("rachana", 1).put("board", "x").putObject("chips").set(name, inst);
        BoardDoc.Instance in;
        try {
            in = UiDocuments.readBoard(one.toString()).chips().get(name);
        } catch (UiDocumentException e) {
            return false;
        }
        List<JsonNode> painted = BoardSutra.paint(chip, in, false);
        return painted.size() == 1 && painted.get(0).equals(panel);
    }
}
