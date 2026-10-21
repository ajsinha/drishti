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
package com.ash.drishti.rachana.ui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks a chip version passes before it is published (UI_BOARD.md 2 "Rules for a chip body" and 9.1):
 * <ul>
 *   <li>{@code CP-01} the manual is complete: every key, a line for every pin, at least one example;</li>
 *   <li>{@code CP-02} pure: the body reads only its pins, never a source, model, document path or URL by name;</li>
 *   <li>{@code CP-03} every out pin is driven by an {@code emits:} or {@code exposes:}, and every one names a declared out pin;</li>
 *   <li>{@code CP-04} deterministic: no clock, no random, no viewer name in the body;</li>
 *   <li>{@code CP-05} every manual example passes the board check.</li>
 * </ul>
 * Stateless and thread-safe.
 */
public final class ChipPublishCheck {

    /** Keys a pure body never has: the supplier is the board's business. */
    static final Set<String> SOURCE_KEYS = Set.of("source", "sources", "connector", "model", "dataset", "live", "url", "fetch", "sql");
    /** {@code %{...}} names that are not pins: the instance's frame title. */
    static final Set<String> SLOT_BUILTINS = Set.of("title");
    /** {@code @...} names that are not pins: the current row's name and value, the filtered rows, the row index. */
    static final Set<String> AT_BUILTINS = Set.of("name", "value", "filtered", "row", "index", "count", "total");

    private static final Pattern SLOT = Pattern.compile("%\\{([A-Za-z][\\w-]*)");
    private static final Pattern AT = Pattern.compile("(?<![\\w@.])@([A-Za-z][\\w-]*)");
    private static final Pattern IMPURE = Pattern.compile("\\b(now|today|random|rand|uuid|clock)\\s*\\(|%\\{\\s*(viewer|user|me)\\b|@(viewer|user|me)\\b");

    private ChipPublishCheck() {}

    /** Checks a chip's text: it reads (BC-01), then the publish rules. */
    public static List<UiProblem> check(String text, ChipCatalog others) {
        UiDocuments.Read<ChipDoc> r = UiDocuments.parseChip(text);
        List<UiProblem> p = new ArrayList<>(r.problems());
        if (r.doc() != null) {
            p.addAll(check(r.doc(), others));
        }
        return p;
    }

    /** The publish rules over a chip read; {@code others} finds the chips its examples use beside itself. */
    public static List<UiProblem> check(ChipDoc chip, ChipCatalog others) {
        List<UiProblem> p = new ArrayList<>();
        manual(chip, p);
        Set<String> emitted = new LinkedHashSet<>();
        body(chip, chip.body(), "body", p, emitted);
        for (String out : chip.outPins().keySet()) {
            if (!emitted.contains(out)) {
                p.add(UiProblem.error("CP-03", "pins.out." + out, "out pin '" + out + "' is never driven by the body",
                        "add emits: " + out + " to the action that sets it, or exposes: { " + out + ": <value> }"));
            }
        }
        for (String e : emitted) {
            if (!chip.outPins().containsKey(e)) {
                p.add(UiProblem.error("CP-03", "body", "the body emits '" + e + "', which is not a declared out pin", "declare it under pins.out, or fix the name"));
            }
        }
        if (chip.manual() != null) {
            ChipCatalog self = ChipCatalog.of(ChipState.PUBLISHED, List.of(chip)).then(others == null ? ChipCatalog.empty() : others);
            BoardCheckContext ctx = BoardCheckContext.personal(self).asFragment();
            for (int i = 0; i < chip.manual().examples().size(); i++) {
                ChipManual.Example ex = chip.manual().examples().get(i);
                if (ex.board() == null) {
                    continue;                                            // CP-01 said so
                }
                for (UiProblem x : BoardCheck.check(ex.board(), ctx)) {
                    if (x.error()) {
                        p.add(UiProblem.error("CP-05", "manual.examples[" + i + "]." + x.path(), x.rule() + ": " + x.message(), x.fix()));
                    }
                }
            }
        }
        return p;
    }

    private static void manual(ChipDoc chip, List<UiProblem> p) {
        ChipManual m = chip.manual();
        if (m == null) {
            p.add(UiProblem.error("CP-01", "manual", "the chip has no manual; a chip without one is refused at publish", "add manual: with " + ChipManual.KEYS));
            return;
        }
        Map<String, String> texts = new java.util.LinkedHashMap<>();
        texts.put("purpose", m.purpose());
        texts.put("behaviour", m.behaviour());
        texts.put("defaults", m.defaults());
        texts.put("limits", m.limits());
        texts.put("not-for", m.notFor());
        texts.put("accessibility", m.accessibility());
        texts.forEach((k, v) -> {
            if (v == null || v.isBlank()) {
                p.add(UiProblem.error("CP-01", "manual." + k, "the manual has no '" + k + "'", null));
            }
        });
        Set<String> pins = new LinkedHashSet<>(chip.inPins().keySet());
        pins.addAll(chip.outPins().keySet());
        for (String pin : pins) {
            String v = m.pins().get(pin);
            if (v == null || v.isBlank()) {
                p.add(UiProblem.error("CP-01", "manual.pins." + pin, "the manual does not say what pin '" + pin + "' means", "add manual.pins." + pin + ": <what it means, what happens when it is empty>"));
            }
        }
        for (String pin : m.pins().keySet()) {
            if (!pins.contains(pin)) {
                p.add(UiProblem.error("CP-01", "manual.pins." + pin, "the manual describes '" + pin + "', which is not a pin", "remove it or fix the name"));
            }
        }
        if (m.examples().isEmpty()) {
            p.add(UiProblem.error("CP-01", "manual.examples", "the manual has no wiring example", "add examples: [ { title, board: <a board fragment> } ]"));
        }
        for (int i = 0; i < m.examples().size(); i++) {
            if (m.examples().get(i).board() == null) {
                p.add(UiProblem.error("CP-01", "manual.examples[" + i + "].board", "the example has no board fragment", null));
            }
        }
    }

    private static void body(ChipDoc chip, JsonNode n, String at, List<UiProblem> p, Set<String> emitted) {
        if (n == null || n.isMissingNode()) {
            return;
        }
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                String k = e.getKey();
                JsonNode v = e.getValue();
                String here = at + "." + k;
                if (SOURCE_KEYS.contains(k)) {
                    p.add(UiProblem.error("CP-02", here, "a chip body may not name a " + k + "; it reads only its pins", "add a data pin and let the board supply it"));
                    return;
                }
                if (k.equals("bind") && v.isTextual() && !v.asText().strip().startsWith("@") && !v.asText().strip().startsWith("%{")) {
                    p.add(UiProblem.error("CP-02", here, "bind '" + v.asText() + "' reads a document path; a chip binds only pins (@pin, @.column) and slots (%{pin})",
                            "bind @<pin> or @.<column>"));
                }
                if (k.equals("emits") && v.isTextual()) {
                    emitted.add(v.asText());
                }
                if (k.equals("exposes") && v.isObject()) {
                    v.fieldNames().forEachRemaining(emitted::add);
                }
                body(chip, v, here, p, emitted);
            });
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                body(chip, n.get(i), at + "[" + i + "]", p, emitted);
            }
        } else if (n.isTextual()) {
            text(chip, n.asText(), at, p);
        }
    }

    private static void text(ChipDoc chip, String s, String at, List<UiProblem> p) {
        if (IMPURE.matcher(s).find()) {
            p.add(UiProblem.error("CP-04", at, "'" + s + "' reads the clock, a random value or the viewer; a chip paints the same for the same input",
                    "add a control pin and let the board wire the value"));
        }
        Matcher m = SLOT.matcher(s);
        while (m.find()) {
            String pin = m.group(1);
            if (!chip.inPins().containsKey(pin) && !SLOT_BUILTINS.contains(pin)) {
                p.add(UiProblem.error("CP-02", at, "%{" + pin + "} names no in pin", "declare pin '" + pin + "' or fix the name; in pins: " + chip.inPins().keySet()));
            }
        }
        Matcher a = AT.matcher(s);
        while (a.find()) {
            String pin = a.group(1);
            if (!chip.inPins().containsKey(pin) && !AT_BUILTINS.contains(pin)) {
                p.add(UiProblem.error("CP-02", at, "@" + pin + " names no in pin", "read @<pin> or @.<column>; in pins: " + chip.inPins().keySet()));
            }
        }
    }
}
