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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.board.Board;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.dist.SubscriptionSchedule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One alert, read and checked: where its number comes from ({@link Source}), what makes it fire ({@link Condition}), when it is looked at ({@link Trigger}),
 * how it avoids flapping (hysteresis and cooldown) and where it is delivered ({@link Delivery}). Immutable. {@link #parse} reads the JSON the API takes and
 * throws {@code DRS-9062} naming the key that is wrong; {@link #toJson} writes it back in a fixed order (that is what is stored).
 *
 * <p>An alert owned by a person is evaluated as that person. An alert derived from a board tile ({@link BoardRef} set) is evaluated for each person who can
 * read the board, each as themselves.
 */
public record AlertSpec(String name, String owner, String title, String severity, boolean enabled, Source source, Condition condition, Trigger trigger,
        double hysteresis, Duration cooldown, Delivery delivery, BoardRef board) {

    public static final Set<String> SEVERITIES = Set.of("info", "warn", "critical");
    public static final Set<String> REDUCERS = Set.of("first", "last", "sum", "min", "max", "avg", "count");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Where the number comes from. {@code visual}: one visual of a report (the first number column, or {@code measure}, reduced to one number by
     * {@code reduce}); {@code dataset}: one measure of a dataset under {@code filters}; {@code field}: a field of a Sutra entity; {@code tile}: the level of a
     * board tile.
     */
    public record Source(String type, String report, String page, String visual, JsonNode state, String dataset, String measure, String reduce, JsonNode filters,
            String kind, String id, String field, Board.Alert tile) {

        public boolean isBi() {
            return type.equals("visual") || type.equals("dataset") || type.equals("tile");
        }

        /** A short name for the history and the messages. */
        public String label() {
            return switch (type) {
                case "visual" -> report + " · " + visual + (measure.isBlank() ? "" : " · " + measure);
                case "dataset" -> dataset + " · " + measure;
                case "field" -> kind + "/" + id + " · " + field;
                default -> report + " · " + visual;
            };
        }
    }

    /**
     * When it fires. {@code threshold}: {@code op} is above, below, between or outside ({@code value} the low edge, {@code high} the high one); {@code change}:
     * {@code mode} pct or abs, {@code op} rises, falls or moves, {@code against} previous-run or previous-day; {@code nodata}: nothing for today's business date by
     * {@code by}; {@code level}: a board tile reaches {@code level} (warn or crit).
     */
    public record Condition(String type, String op, double value, double high, String mode, String against, LocalTime by, String level) {}

    /** {@code schedule} (with a schedule text), {@code after-load} (with an optional load kind) or {@code live} (on change, after a quiet time). */
    public record Trigger(String mode, String schedule, String loadKind, Duration debounce) {}

    public record Delivery(boolean inbox, boolean email, boolean bridge) {}

    /** The board, page and tile an alert was derived from. */
    public record BoardRef(String board, String page, String tile) {}

    /** The key an alert's state and history are kept under. */
    public String key() {
        return board == null ? owner + "/" + name : "board:" + board.board() + "/" + board.page() + "/" + board.tile();
    }

    // ---- reading -------------------------------------------------------------------------------------------------------------

    public static AlertSpec parse(String owner, String name, JsonNode body, MeasureAlertProperties props) {
        if (!NAME.matcher(name).matches()) {
            throw invalid("an alert's name is 1-64 letters, digits, spaces, dots, dashes or underscores");
        }
        if (body == null || !body.isObject()) {
            throw invalid("the body is an object: { source, condition, trigger, delivery }");
        }
        Source source = source(body.path("source"));
        Condition condition = condition(body.path("condition"), source);
        Trigger trigger = trigger(body.path("trigger"), source, props);
        String severity = body.path("severity").asText("warn").toLowerCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw invalid("severity is one of " + new java.util.TreeSet<>(SEVERITIES));
        }
        double hysteresis = body.path("hysteresis").asDouble(0);
        if (body.has("hysteresis") && (!body.get("hysteresis").isNumber() || hysteresis < 0)) {
            throw invalid("hysteresis is a number of zero or more: how far back inside the limit the value must come before the alert re-arms");
        }
        Duration cooldown = props.cooldown();
        if (body.hasNonNull("cooldown")) {
            cooldown = span(body.get("cooldown").asText(), "cooldown");
        }
        JsonNode d = body.path("delivery");
        Delivery delivery = new Delivery(d.path("inbox").asBoolean(true), d.path("email").asBoolean(false), d.path("bridge").asBoolean(false));
        if (!delivery.inbox() && !delivery.email() && !delivery.bridge()) {
            throw invalid("delivery names at least one of inbox, email, bridge");
        }
        String title = body.path("title").asText("").strip();
        return new AlertSpec(name, owner, title.isEmpty() ? defaultTitle(source, condition) : title, severity, body.path("enabled").asBoolean(true), source, condition, trigger,
                hysteresis, cooldown, delivery, null);
    }

    private static Source source(JsonNode s) {
        if (!s.isObject()) {
            throw invalid("source is { type: visual | dataset | field, ... }");
        }
        String type = s.path("type").asText("");
        switch (type) {
            case "visual" -> {
                String report = need(s, "report", "source.report is the report's id");
                String visual = need(s, "visual", "source.visual is the id of a visual of the report");
                String reduce = s.path("reduce").asText("first");
                if (!REDUCERS.contains(reduce)) {
                    throw invalid("source.reduce is one of " + new java.util.TreeSet<>(REDUCERS) + " (how a visual with many rows becomes one number)");
                }
                JsonNode state = s.path("state");
                if (!state.isMissingNode() && !state.isNull() && !state.isObject()) {
                    throw invalid("source.state is the report's view state, an object");
                }
                return new Source(type, report, s.path("page").asText("").strip(), visual, state.isObject() ? state : null, "", s.path("measure").asText("").strip(), reduce, null,
                        "", "", "", null);
            }
            case "dataset" -> {
                String dataset = need(s, "dataset", "source.dataset is the dataset's id");
                String measure = need(s, "measure", "source.measure names a measure of the dataset");
                ObjectNode probe = JSON.createObjectNode();
                probe.putArray("measures").add(measure);
                JsonNode filters = s.path("filters");
                if (!filters.isMissingNode() && !filters.isNull()) {
                    if (!filters.isArray()) {
                        throw invalid("source.filters is a list of { field, op, values }");
                    }
                    probe.set("filters", filters);
                }
                try {
                    QueryRequest.of(probe);
                } catch (DrishtiException e) {
                    throw invalid("source.filters: " + e.getMessage());
                }
                return new Source(type, "", "", "", null, dataset, measure, "first", filters.isArray() ? filters : null, "", "", "", null);
            }
            case "field" -> {
                return new Source(type, "", "", "", null, "", "", "first", null, need(s, "kind", "source.kind is the Sutra kind, for example TRD"), need(s, "id", "source.id is the entity id"),
                        need(s, "field", "source.field is the field, for example mtm or utilisation"), null);
            }
            default -> throw invalid("source.type is visual, dataset or field");
        }
    }

    private static Condition condition(JsonNode c, Source source) {
        if (!c.isObject()) {
            throw invalid("condition is { type: threshold | change | nodata, ... }");
        }
        String type = c.path("type").asText("");
        switch (type) {
            case "threshold" -> {
                String op = c.path("op").asText("");
                if (!Set.of("above", "below", "between", "outside").contains(op)) {
                    throw invalid("condition.op is above, below, between or outside");
                }
                double low = number(c, "value", "condition.value is a number (for between and outside, the low edge)");
                double high = low;
                if (op.equals("between") || op.equals("outside")) {
                    high = number(c, "high", "condition.high is the high edge of the range");
                    if (high <= low) {
                        throw invalid("condition.high must be greater than condition.value");
                    }
                }
                return new Condition(type, op, low, high, "", "", null, "");
            }
            case "change" -> {
                String mode = c.path("mode").asText("pct");
                String op = c.path("op").asText("moves");
                String against = c.path("against").asText("previous-run");
                if (!Set.of("pct", "abs").contains(mode)) {
                    throw invalid("condition.mode is pct (percent) or abs (absolute)");
                }
                if (!Set.of("rises", "falls", "moves").contains(op)) {
                    throw invalid("condition.op is rises, falls or moves");
                }
                if (!Set.of("previous-run", "previous-day").contains(against)) {
                    throw invalid("condition.against is previous-run or previous-day (the previous business day)");
                }
                double v = number(c, "value", "condition.value is the size of the change (a number above zero)");
                if (v <= 0) {
                    throw invalid("condition.value is the size of the change: a number above zero");
                }
                return new Condition(type, op, v, v, mode, against, null, "");
            }
            case "nodata" -> {
                try {
                    return new Condition(type, "", 0, 0, "", "", LocalTime.parse(pad(need(c, "by", "condition.by is the time of day, for example 09:00"))), "");
                } catch (DateTimeParseException e) {
                    throw invalid("condition.by is a time of day, HH:MM, in the business-date zone");
                }
            }
            default -> throw invalid("condition.type is threshold, change or nodata");
        }
    }

    private static Trigger trigger(JsonNode t, Source source, MeasureAlertProperties props) {
        String mode = t.path("mode").asText("schedule");
        switch (mode) {
            case "schedule" -> {
                String text = t.path("schedule").asText("hourly").strip();
                try {
                    if (SubscriptionSchedule.parse(text).isAfterLoad()) {
                        throw invalid("trigger.mode after-load is its own mode: use { mode: after-load }");
                    }
                } catch (IllegalArgumentException e) {
                    throw invalid("trigger.schedule: " + e.getMessage());
                }
                return new Trigger(mode, text, "", Duration.ZERO);
            }
            case "after-load" -> {
                return new Trigger(mode, "", t.path("kind").asText("").strip(), Duration.ZERO);
            }
            case "live" -> {
                Duration d = t.hasNonNull("debounce") ? span(t.get("debounce").asText(), "trigger.debounce") : props.liveDebounce();
                if (d.compareTo(props.minDebounce()) < 0) {
                    throw invalid("trigger.debounce is at least " + props.minDebounce().toMillis() + " ms (drishti.alerts.measure.min-debounce)");
                }
                return new Trigger(mode, "", "", d);
            }
            default -> throw invalid("trigger.mode is schedule, after-load or live");
        }
    }

    // ---- writing -------------------------------------------------------------------------------------------------------------

    public ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("name", name).put("owner", owner).put("title", title).put("severity", severity).put("enabled", enabled);
        ObjectNode s = n.putObject("source");
        s.put("type", source.type());
        switch (source.type()) {
            case "visual" -> {
                s.put("report", source.report()).put("page", source.page()).put("visual", source.visual()).put("measure", source.measure()).put("reduce", source.reduce());
                if (source.state() != null) {
                    s.set("state", source.state());
                }
            }
            case "dataset" -> {
                s.put("dataset", source.dataset()).put("measure", source.measure());
                if (source.filters() != null) {
                    s.set("filters", source.filters());
                }
            }
            case "field" -> s.put("kind", source.kind()).put("id", source.id()).put("field", source.field());
            default -> {
                s.put("report", source.report()).put("page", source.page()).put("visual", source.visual());
                if (source.tile() != null) {
                    s.set("tile", JSON.valueToTree(source.tile().toMap()));
                }
            }
        }
        ObjectNode c = n.putObject("condition");
        c.put("type", condition.type());
        switch (condition.type()) {
            case "threshold" -> {
                c.put("op", condition.op()).put("value", condition.value());
                if (condition.op().equals("between") || condition.op().equals("outside")) {
                    c.put("high", condition.high());
                }
            }
            case "change" -> c.put("mode", condition.mode()).put("op", condition.op()).put("value", condition.value()).put("against", condition.against());
            case "nodata" -> c.put("by", condition.by().toString());
            default -> c.put("level", condition.level());
        }
        ObjectNode t = n.putObject("trigger");
        t.put("mode", trigger.mode());
        if (trigger.mode().equals("schedule")) {
            t.put("schedule", trigger.schedule());
        } else if (trigger.mode().equals("after-load") && !trigger.loadKind().isBlank()) {
            t.put("kind", trigger.loadKind());
        } else if (trigger.mode().equals("live")) {
            t.put("debounce", trigger.debounce().toMillis() % 1000 == 0 ? trigger.debounce().toSeconds() + "s" : trigger.debounce().toMillis() + "ms");
        }
        n.put("hysteresis", hysteresis);
        n.put("cooldown", cooldown.isZero() ? "0" : cooldown.toSeconds() % 3600 == 0 ? cooldown.toHours() + "h" : cooldown.toSeconds() % 60 == 0 ? cooldown.toMinutes() + "m" : cooldown.toSeconds() + "s");
        n.putObject("delivery").put("inbox", delivery.inbox()).put("email", delivery.email()).put("bridge", delivery.bridge());
        if (board != null) {
            n.putObject("board").put("board", board.board()).put("page", board.page()).put("tile", board.tile());
        }
        return n;
    }

    /** The condition in words, for a title, a message or a list. */
    public String describe() {
        return switch (condition.type()) {
            case "threshold" -> switch (condition.op()) {
                case "above" -> "above " + num(condition.value());
                case "below" -> "below " + num(condition.value());
                case "between" -> "between " + num(condition.value()) + " and " + num(condition.high());
                default -> "outside " + num(condition.value()) + " to " + num(condition.high());
            };
            case "change" -> condition.op() + " by " + num(condition.value()) + (condition.mode().equals("pct") ? "%" : "") + " against the "
                    + condition.against().replace('-', ' ');
            case "nodata" -> "no data by " + condition.by();
            default -> "reaches " + condition.level();
        };
    }

    public static String num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%,.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String defaultTitle(Source s, Condition c) {
        return s.label() + " " + new AlertSpec("x", "x", "", "warn", true, s, c, null, 0, Duration.ZERO, null, null).describe();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------

    private static String need(JsonNode n, String key, String message) {
        String v = n.path(key).asText("").strip();
        if (v.isEmpty()) {
            throw invalid(message);
        }
        return v;
    }

    private static double number(JsonNode n, String key, String message) {
        if (!n.path(key).isNumber()) {
            throw invalid(message);
        }
        return n.get(key).asDouble();
    }

    private static String pad(String hhmm) {
        return hhmm.matches("\\d:\\d\\d") ? "0" + hhmm : hhmm;
    }

    private static Duration span(String text, String what) {
        if (text.strip().equals("0")) {
            return Duration.ZERO;
        }
        Duration d = DatasetParser.span(text);
        if (d == null) {
            throw invalid(what + " is a time such as 500ms, 10s, 30m, 2h or 1d");
        }
        return d;
    }

    static DrishtiException invalid(String message) {
        return new DrishtiException(ErrorCode.ALERT_INVALID, message);
    }
}
