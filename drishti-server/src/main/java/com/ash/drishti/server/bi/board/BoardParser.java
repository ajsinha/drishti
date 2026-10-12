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
package com.ash.drishti.server.bi.board;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.ash.drishti.server.dist.SubscriptionSchedule;
import java.util.regex.Pattern;

/**
 * Reads a board's YAML (docs/guides/BOARDS.md) into a {@link Board} and checks its structure; what a tile names inside its report is checked by
 * {@link com.ash.drishti.server.bi.board.BiBoardService}. Never throws for a bad document: the problems come back with their DRS code ({@code DRS-9052}, and
 * {@code DRS-9053} for what the grammar still reserves: {@code notifications} and {@code subscriptions}) and the path of the offending key.
 */
public final class BoardParser {

    /** The outcome: the board when the structure was readable (null otherwise), and every problem found. */
    public record Result(Board board, List<Problem> problems) {
        public boolean ok() {
            return board != null && problems.stream().noneMatch(Problem::error);
        }

        public String firstError() {
            return problems.stream().filter(Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("");
        }
    }

    static final String INVALID = "DRS-9052";
    static final String NOT_BUILT = "DRS-9053";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,39}");
    private static final Set<String> TOP = Set.of("rachana", "board", "version", "title", "description", "sample", "rotate", "pages", "owner", "tags", "endorsement", "endorsedBy",
            "audience", "notify");
    private static final Set<String> RESERVED_TOP = Set.of("notifications", "subscriptions");
    private static final Set<String> PAGE = Set.of("id", "title", "tiles");
    private static final Set<String> TILE = Set.of("id", "report", "page", "visual", "title", "at", "alert");
    private static final Set<String> ALERT = Set.of("on", "warn", "crit", "notify", "hysteresis");
    private static final Set<String> NOTIFY = Set.of("level", "channels", "cooldown", "trigger", "severity");
    private static final Set<String> CHANNELS = Set.of("inbox", "email", "chat");
    private static final Set<String> OPS = Set.of("lt", "le", "gt", "ge", "eq", "ne");
    public static final int MAX_PAGES = 20;
    public static final int MAX_TILES_PER_PAGE = 48;

    private BoardParser() {}

    public static Result parse(String yaml) {
        List<Problem> out = new ArrayList<>();
        JsonNode root;
        try {
            root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            out.add(error("", "the YAML is not valid: " + e.getOriginalMessage()));
            return new Result(null, out);
        }
        if (root == null || !root.isObject()) {
            out.add(error("", "a board is a YAML mapping (key: value) with at least `board:` and `pages:`"));
            return new Result(null, out);
        }
        root.fieldNames().forEachRemaining(k -> {
            if (RESERVED_TOP.contains(k)) {
                out.add(new Problem("error", NOT_BUILT, k, "'" + k + "' is reserved and not built: a board notifies through `notify:` on a tile's alert (and defaults in a top-level `notify:`)"));
            } else if (!TOP.contains(k)) {
                out.add(new Problem("warning", INVALID, k, "unknown key '" + k + "' is ignored"));
            }
        });
        if (root.path("rachana").asInt(1) != 1) {
            out.add(error("rachana", "this server reads Rachana version 1, not " + root.path("rachana").asInt()));
        }
        String name = text(root, "board", "");
        if (name.isBlank()) {
            out.add(error("board", "`board:` names the board (letters, digits, hyphen, underscore)"));
        } else if (!NAME.matcher(name).matches()) {
            out.add(error("board", "'" + name + "' is not a usable name (letters, digits, hyphen, underscore, dot; up to 64)"));
        }
        Integer rotate = null;
        if (root.has("rotate") && !root.get("rotate").isNull()) {
            java.time.Duration d = DatasetParser.span(root.get("rotate").asText());
            if (d == null || d.toSeconds() < 3 || d.toSeconds() > 3600) {
                out.add(error("rotate", "rotate is how long a page shows in kiosk mode before the next: 3s to 1h, for example 20s or 2m"));
            } else {
                rotate = (int) d.toSeconds();
            }
        }
        Board.Notify defaults = root.has("notify") ? notify(root.get("notify"), "notify", out, null) : null;
        List<Board.Page> pages = pages(root.path("pages"), out, defaults);
        return new Result(new Board(name, root.path("version").asInt(1), text(root, "title", name), text(root, "description", ""), root.path("sample").asBoolean(false), rotate, pages,
                defaults), out);
    }

    private static List<Board.Page> pages(JsonNode n, List<Problem> out, Board.Notify defaults) {
        List<Board.Page> list = new ArrayList<>();
        if (!n.isArray() || n.isEmpty()) {
            out.add(error("pages", "`pages:` is a list of pages, each with `tiles:`"));
            return list;
        }
        if (n.size() > MAX_PAGES) {
            out.add(error("pages", "a board has at most " + MAX_PAGES + " pages"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n.size() && i < MAX_PAGES; i++) {
            JsonNode p = n.get(i);
            String path = "pages[" + i + "]";
            if (!p.isObject()) {
                out.add(error(path, "a page is a mapping with `id`, `title` and `tiles`"));
                continue;
            }
            p.fieldNames().forEachRemaining(k -> {
                if (!PAGE.contains(k)) {
                    out.add(new Problem("warning", INVALID, path + "." + k, "unknown page key '" + k + "' is ignored"));
                }
            });
            String id = text(p, "id", "page" + (i + 1));
            if (!ID.matcher(id).matches()) {
                out.add(error(path + ".id", "'" + id + "' is not a page id (letters, digits, hyphen, underscore; up to 40)"));
            } else if (!seen.add(id)) {
                out.add(error(path + ".id", "page id '" + id + "' is used twice"));
            }
            list.add(new Board.Page(id, text(p, "title", id), tiles(p.path("tiles"), path + ".tiles", out, defaults)));
        }
        return list;
    }

    private static List<Board.Tile> tiles(JsonNode n, String path, List<Problem> out, Board.Notify defaults) {
        List<Board.Tile> list = new ArrayList<>();
        if (!n.isArray() || n.isEmpty()) {
            out.add(error(path, "`tiles:` is a list of tiles: { report: <report>, visual: <visual id>, at: { x, y, w, h } }"));
            return list;
        }
        if (n.size() > MAX_TILES_PER_PAGE) {
            out.add(error(path, "a page has at most " + MAX_TILES_PER_PAGE + " tiles"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n.size() && i < MAX_TILES_PER_PAGE; i++) {
            JsonNode t = n.get(i);
            String p = path + "[" + i + "]";
            if (!t.isObject()) {
                out.add(error(p, "a tile is a mapping: { report, visual, at }"));
                continue;
            }
            t.fieldNames().forEachRemaining(k -> {
                if (!TILE.contains(k)) {
                    out.add(new Problem("warning", INVALID, p + "." + k, "unknown tile key '" + k + "' is ignored"));
                }
            });
            String report = text(t, "report", "");
            String visual = text(t, "visual", "");
            if (report.isBlank()) {
                out.add(error(p + ".report", "a tile names the report its visual is in (its catalogue id, file name or report name)"));
            }
            if (visual.isBlank()) {
                out.add(error(p + ".visual", "a tile names the id of a visual of that report"));
            }
            String id = text(t, "id", "t" + (i + 1));
            if (!ID.matcher(id).matches()) {
                out.add(error(p + ".id", "'" + id + "' is not a tile id (letters, digits, hyphen, underscore; up to 40)"));
            } else if (!seen.add(id)) {
                out.add(error(p + ".id", "tile id '" + id + "' is used twice on the page"));
            }
            list.add(new Board.Tile(id, report, text(t, "page", ""), visual, text(t, "title", ""), grid(t.path("at"), p + ".at", out), alert(t.path("alert"), p + ".alert", out, defaults)));
        }
        return list;
    }

    private static Report.Grid grid(JsonNode n, String path, List<Problem> out) {
        if (!n.isObject() || !n.has("w") || !n.has("h")) {
            out.add(error(path, "at is the tile's place on the 12-column grid: { x: 0, y: 0, w: 4, h: 3 }"));
            return new Report.Grid(0, 0, 4, 3);
        }
        int x = n.path("x").asInt(0);
        int y = n.path("y").asInt(0);
        int w = n.path("w").asInt(0);
        int h = n.path("h").asInt(0);
        if (x < 0 || x >= Report.COLUMNS || w < 1 || x + w > Report.COLUMNS) {
            out.add(error(path, "x is 0 to 11 and w is 1 to 12, and x + w is at most 12 (the grid has 12 columns); this is x=" + x + ", w=" + w));
        }
        if (y < 0 || h < 1 || h > 40 || y > 1000) {
            out.add(error(path, "y is 0 or more and h is 1 to 40 rows"));
        }
        return new Report.Grid(Math.max(0, x), Math.max(0, y), Math.max(1, w), Math.max(1, h));
    }

    private static Board.Alert alert(JsonNode n, String path, List<Problem> out, Board.Notify defaults) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            out.add(error(path, "alert is { on: <measure>, warn: { op: lt, value: -1000000 }, crit: { op: lt, value: -5000000 } }"));
            return null;
        }
        n.fieldNames().forEachRemaining(k -> {
            if (RESERVED_TOP.contains(k)) {
                out.add(new Problem("error", NOT_BUILT, path + "." + k, "'" + k + "' is reserved and not built: a tile notifies with `notify:`"));
            } else if (!ALERT.contains(k)) {
                out.add(new Problem("warning", INVALID, path + "." + k, "unknown alert key '" + k + "' is ignored (on, warn, crit, hysteresis, notify)"));
            }
        });
        Board.Rule warn = rule(n.path("warn"), path + ".warn", out);
        Board.Rule crit = rule(n.path("crit"), path + ".crit", out);
        if (warn == null && crit == null) {
            out.add(error(path, "an alert needs a warn or a crit threshold"));
            return null;
        }
        double hysteresis = 0;
        if (n.has("hysteresis") && !n.get("hysteresis").isNull()) {
            if (n.get("hysteresis").isNumber() && n.get("hysteresis").asDouble() >= 0 && Double.isFinite(n.get("hysteresis").asDouble())) {
                hysteresis = n.get("hysteresis").asDouble();
            } else {
                out.add(error(path + ".hysteresis", "hysteresis is a number of 0 or more: how far back inside a threshold the value must be before the tile drops a level"));
            }
        }
        Board.Notify notify = null;
        if (n.has("notify") && !n.get("notify").isNull() && !(n.get("notify").isBoolean() && !n.get("notify").asBoolean())) {
            notify = n.get("notify").isBoolean() ? (defaults != null ? defaults : new Board.Notify("crit", List.of("inbox"), null, null, null))
                    : notify(n.get("notify"), path + ".notify", out, defaults);
            if (notify != null && notify.level().equals("crit") && crit == null) {
                out.add(error(path + ".notify", "notify level crit needs a crit threshold on this tile (or set level: warn)"));
            }
            if (notify != null && notify.level().equals("warn") && warn == null && crit == null) {
                out.add(error(path + ".notify", "notify level warn needs a warn threshold on this tile"));
            }
        }
        return new Board.Alert(text(n, "on", ""), warn, crit, notify, hysteresis);
    }

    /** Reads a {@code notify:} mapping; a key it leaves out comes from {@code base} (the board's defaults), else the built-in default. */
    private static Board.Notify notify(JsonNode n, String path, List<Problem> out, Board.Notify base) {
        if (!n.isObject()) {
            out.add(error(path, "notify is { level: crit | warn, channels: [inbox, email, chat], cooldown: 1h, trigger: live | after-load | <schedule>, severity: info | warn | critical }, or true"));
            return null;
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!NOTIFY.contains(k)) {
                out.add(new Problem("warning", INVALID, path + "." + k, "unknown notify key '" + k + "' is ignored (level, channels, cooldown, trigger, severity)"));
            }
        });
        String level = n.has("level") ? n.path("level").asText() : base != null ? base.level() : "crit";
        if (!level.equals("crit") && !level.equals("warn")) {
            out.add(error(path + ".level", "level is crit (notify on red) or warn (notify on amber or red)"));
            level = "crit";
        }
        List<String> channels = new ArrayList<>();
        if (n.has("channels")) {
            if (!n.get("channels").isArray() || n.get("channels").isEmpty()) {
                out.add(error(path + ".channels", "channels is a list of inbox, email, chat"));
            } else {
                for (JsonNode c : n.get("channels")) {
                    if (!CHANNELS.contains(c.asText())) {
                        out.add(error(path + ".channels", "'" + c.asText() + "' is not a channel: inbox, email, chat"));
                    } else if (!channels.contains(c.asText())) {
                        channels.add(c.asText());
                    }
                }
            }
        } else if (base != null) {
            channels.addAll(base.channels());
        }
        if (channels.isEmpty()) {
            channels.add("inbox");
        }
        String cooldown = n.has("cooldown") ? n.path("cooldown").asText() : base != null ? base.cooldown() : null;
        if (cooldown != null && !cooldown.equals("0") && DatasetParser.span(cooldown) == null) {
            out.add(error(path + ".cooldown", "cooldown is a time such as 30m, 1h or 1d"));
        }
        String trigger = n.has("trigger") ? n.path("trigger").asText() : base != null ? base.trigger() : null;
        if (trigger != null && !trigger.equals("live") && !trigger.equals("after-load")) {
            try {
                SubscriptionSchedule.parse(trigger);
            } catch (IllegalArgumentException e) {
                out.add(error(path + ".trigger", "trigger is live, after-load or a schedule (business-days 07:30, hourly): " + e.getMessage()));
            }
        }
        String severity = n.has("severity") ? n.path("severity").asText() : base != null ? base.severity() : null;
        if (severity != null && !Set.of("info", "warn", "critical").contains(severity)) {
            out.add(error(path + ".severity", "severity is info, warn or critical"));
        }
        return new Board.Notify(level, List.copyOf(channels), cooldown, trigger, severity);
    }

    private static Board.Rule rule(JsonNode n, String path, List<Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        String op = n.path("op").asText("");
        if (!n.isObject() || !OPS.contains(op) || !n.path("value").isNumber()) {
            out.add(error(path, "a threshold is { op: lt|le|gt|ge|eq|ne, value: <number> }"));
            return null;
        }
        return new Board.Rule(op, n.path("value").asDouble());
    }

    static Problem error(String path, String message) {
        return new Problem("error", INVALID, path, message);
    }

    private static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? dflt : v.asText();
    }
}
