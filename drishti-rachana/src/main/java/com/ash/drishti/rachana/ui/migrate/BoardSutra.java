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

import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.rachana.ui.ChipDoc;
import com.ash.drishti.rachana.ui.ChipRef;
import com.ash.drishti.rachana.ui.PinSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paints a converted entity board back into the grammar (docs/architecture/UI_BOARD.md 11.3, phase P5): each chip instance's body with its pins
 * and controls filled in, as the Sutra panels the grammar engine binds. The proof binds this Sutra over the record the board's supplier reads as
 * the viewer and compares the result with the old page; the per-pack switch serves it. Stateless.
 *
 * <p>Filling a body: {@code %{title|D}} is the instance's title (else D); {@code %{pin}} and {@code %{pin|d}} a control pin's constant (else its
 * default, else d; an empty value leaves the key out) or a scalar data pin's mapped attribute as {@code $.ATTR}; {@code @doc} (a document pin over
 * the page's record) is the record root {@code $}; {@code of: '@doc'} is dropped (the record is the page's root); a block use's {@code params} are
 * its {@code with}. A single-panel body of a seed chip takes the instance's name as its panel id. A body's {@code page:} part (the board-local
 * header chip) gives the Sutra's title, strip, controls and keys.
 */
public final class BoardSutra {

    static final ObjectMapper JSON = new ObjectMapper();
    /** The document pin name a converted chip reads the page's record through. */
    public static final String DOC = "doc";
    private static final Pattern WHOLE = Pattern.compile("%\\{([A-Za-z0-9_-]+)(?:\\|([^}]*))?}");
    private static final Pattern DOC_REF = Pattern.compile("@doc(?=[.\\[])");
    private static final Pattern ROOT_REF = Pattern.compile("(?<![\\w$@])\\$(?=[.\\[])");

    private BoardSutra() {}

    /** {@code text} with the record root {@code $.} / {@code $[} written as the document pin {@code @doc} (a board-local chip's pure body). */
    public static String toPin(String text) {
        return ROOT_REF.matcher(text).replaceAll("@doc");
    }

    /** The inverse of {@link #toPin}. */
    public static String toRoot(String text) {
        return DOC_REF.matcher(text).replaceAll(Matcher.quoteReplacement("$"));
    }

    /** {@code node} with every string passed through {@code f} (a deep copy). */
    static JsonNode mapText(JsonNode node, Function<String, String> f) {
        if (node.isTextual()) {
            return TextNode.valueOf(f.apply(node.asText()));
        }
        if (node.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            node.fields().forEachRemaining(e -> o.set(e.getKey(), mapText(e.getValue(), f)));
            return o;
        }
        if (node.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            node.forEach(n -> a.add(mapText(n, f)));
            return a;
        }
        return node.deepCopy();
    }

    /**
     * The Sutra a converted entity board paints as.
     *
     * @param board the board
     * @param chips the store's chips (seed and published); the board's own local chips are looked up first
     * @return the Sutra document (YAML-ready), or empty when the board is not an entity board (no {@code id} parameter of a kind)
     */
    public static Optional<ObjectNode> toSutra(BoardDoc board, Function<ChipRef, Optional<ChipDoc>> chips) {
        PinSpec id = board.params().get("id");
        if (id == null || id.type() == null || id.type().of() == null) {
            return Optional.empty();
        }
        String of = id.type().of();
        ObjectNode s = JSON.createObjectNode();
        s.put("rachana", 1).put("sutra", board.board()).put("version", 1);
        if (board.description() != null) {
            s.put("description", board.description());
        }
        s.putObject("match").put("kind", of.substring(of.indexOf('/') + 1)).put("priority", 10);
        ArrayNode panels = JSON.createArrayNode();
        for (BoardDoc.Instance in : board.chips().values()) {
            ChipDoc chip = board.localChips().stream().filter(c -> c.ref().equals(in.use())).findFirst().or(() -> chips.apply(in.use())).orElse(null);
            if (chip == null || chip.body() == null) {
                continue;
            }
            boolean local = board.localChips().contains(chip);
            JsonNode page = chip.body().path("page");
            if (page.isObject()) {
                ObjectNode filled = (ObjectNode) fill(page, chip, in);
                filled.fields().forEachRemaining(e -> s.set(e.getKey(), e.getValue()));
            }
            panels.addAll(paint(chip, in, local));
        }
        s.set("panels", panels);
        return Optional.of(s);
    }

    /** One instance's panels, filled in. */
    public static List<JsonNode> paint(ChipDoc chip, BoardDoc.Instance in, boolean local) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode body = chip.body() == null ? null : chip.body().path("panels");
        if (body == null || !body.isArray()) {
            return out;
        }
        for (JsonNode p : body) {
            if (p.has("use")) {
                out.add(use(p, chip, in));
                continue;
            }
            JsonNode f = fill(p, chip, in);
            if (f instanceof ObjectNode o) {
                if (!local && body.size() == 1 && o.has("id")) {
                    o.put("id", in.name());
                }
                out.add(o);
            }
        }
        return out;
    }

    /**
     * A block use ({@code use: name@N, of: '@doc', params: {k: '%{k}'}}) as the grammar writes it: {@code {use, with}}, the {@code with} holding
     * only the controls the instance sets (an unset one is the block's own default, not the chip's).
     */
    private static JsonNode use(JsonNode p, ChipDoc chip, BoardDoc.Instance in) {
        ObjectNode o = JSON.createObjectNode();
        p.fields().forEachRemaining(e -> {
            switch (e.getKey()) {
                case "id", "of", "params" -> { }
                default -> {
                    JsonNode v = fill(e.getValue(), chip, in);
                    if (v != null) {
                        o.set(e.getKey(), v);
                    }
                }
            }
        });
        ObjectNode with = JSON.createObjectNode();
        p.path("params").fields().forEachRemaining(e -> {
            Matcher m = WHOLE.matcher(e.getValue().asText(""));
            String pin = m.matches() ? m.group(1) : null;
            if (pin != null && in.controls().containsKey(pin)) {
                with.set(e.getKey(), in.controls().get(pin).deepCopy());
            } else if (pin == null) {
                with.set(e.getKey(), e.getValue().deepCopy());
            }
        });
        if (with.size() > 0) {
            o.set("with", with);
        }
        return o;
    }

    private static JsonNode fill(JsonNode node, ChipDoc chip, BoardDoc.Instance in) {
        if (node.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if ("of".equals(e.getKey()) && "@doc".equals(e.getValue().asText(null))) {
                    continue;
                }
                JsonNode v = fill(e.getValue(), chip, in);
                if (v != null) {
                    o.set(e.getKey(), v);
                }
            }
            return o;
        }
        if (node.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            node.forEach(n -> {
                JsonNode v = fill(n, chip, in);
                if (v != null) {
                    a.add(v);
                }
            });
            return a;
        }
        if (!node.isTextual()) {
            return node.deepCopy();
        }
        String t = node.asText();
        Matcher m = WHOLE.matcher(t);
        if (m.matches()) {
            return value(m.group(1), m.group(2), chip, in);
        }
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode v = value(m.group(1), m.group(2), chip, in);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v.asText()));
        }
        m.appendTail(sb);
        String s = toRoot(sb.toString());
        PinSpec data = s.startsWith("@") ? chip.inPins().get(s.substring(1)) : null;
        if (data != null && data.role() == PinSpec.PinRole.DATA && in.map().containsKey(data.name())) {
            return TextNode.valueOf("$." + in.map().get(data.name()).attributes().get(0));
        }
        return TextNode.valueOf(s);
    }

    /** A pin's value in a body: null leaves the key out. */
    private static JsonNode value(String pin, String dflt, ChipDoc chip, BoardDoc.Instance in) {
        if ("title".equals(pin)) {
            return in.title() != null ? TextNode.valueOf(in.title()) : dflt == null || dflt.isEmpty() ? null : TextNode.valueOf(dflt);
        }
        PinSpec p = chip.inPins().get(pin);
        if (p != null && p.role() == PinSpec.PinRole.DATA && in.map().containsKey(pin)) {
            return TextNode.valueOf("$." + in.map().get(pin).attributes().get(0));
        }
        JsonNode v = in.controls().get(pin);
        if (v == null && p != null && p.hasDefault()) {
            v = p.defaultValue();
        }
        if (v == null && dflt != null) {
            v = TextNode.valueOf(dflt);
        }
        return v == null || v.isNull() || (v.isTextual() && v.asText().isEmpty()) ? null : v.deepCopy();
    }
}
