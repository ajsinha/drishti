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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.rachana.ui.ChipDoc;
import com.ash.drishti.rachana.ui.PinSpec;
import com.ash.drishti.rachana.ui.PinType;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One render session of one board for one person (docs/architecture/UI_BOARD.md section 6.2): the layout first, then each chip's frame as its supplier
 * answers, a detail chip after its master's out pin has a value, and on a pin change only the chips downstream of it. Every supplier is read as the
 * owner; a masked id leaves a chip only as an {@link IdTokens} token.
 */
public final class BoardRender implements AutoCloseable {

    /** Receives the stream's events in order; called from several threads, one event at a time. */
    @FunctionalInterface
    public interface Sink {
        void event(String name, Object data);
    }

    /** One painting pass (the first render or a pin change). */
    public record Outcome(int chips, int errors, long ms) {}

    public static final String MASK = "•••";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PARAM = Pattern.compile("%\\{board\\.params\\.([A-Za-z0-9_-]+)}");

    private final String id;
    private final Principal owner;
    private final BoardDoc board;
    private final String boardKey;
    private final String boardHash;
    private final Map<String, ChipDoc> chips;
    private final Map<String, Object> params;
    private final String device;
    private final BoardSuppliers suppliers;
    private final IdTokens tokens;
    private final RenderSettings props;
    private final Executor exec;
    private final Sink sink;
    /** {@code inst.pin} to its value. */
    private final Map<String, Object> outs = new ConcurrentHashMap<>();
    private final Map<String, AutoCloseable> follows = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> downstream = new LinkedHashMap<>();
    private final Map<String, Set<String>> upstream = new LinkedHashMap<>();
    private final Set<String> suppliersRead = ConcurrentHashMap.newKeySet();
    private volatile long lastUsed = System.currentTimeMillis();
    private volatile boolean closed;

    public BoardRender(String id, Principal owner, BoardDoc board, String boardKey, String boardHash, Map<String, ChipDoc> chips, Map<String, Object> params,
            String device, BoardSuppliers suppliers, IdTokens tokens, RenderSettings props, Executor exec, Sink sink) {
        this.id = id;
        this.owner = owner;
        this.board = board;
        this.boardKey = boardKey;
        this.boardHash = boardHash;
        this.chips = Map.copyOf(chips);
        this.params = params == null ? Map.of() : new LinkedHashMap<>(params);
        this.device = device == null || device.isBlank() ? "desktop" : device;
        this.suppliers = suppliers;
        this.tokens = tokens;
        this.props = props;
        this.exec = exec;
        this.sink = sink;
        for (String inst : board.chips().keySet()) {
            downstream.put(inst, new LinkedHashSet<>());
            upstream.put(inst, new LinkedHashSet<>());
        }
        for (BoardDoc.Trace t : board.traces()) {
            String from = instanceOf(t.from());
            String to = instanceOf(t.to());
            if (from != null && to != null && board.chips().containsKey(from) && board.chips().containsKey(to) && !from.equals(to)) {
                downstream.get(from).add(to);
                upstream.get(to).add(from);
            }
        }
    }

    public String id() {
        return id;
    }

    public Principal owner() {
        return owner;
    }

    public String boardKey() {
        return boardKey;
    }

    public long lastUsed() {
        return lastUsed;
    }

    /** The suppliers read so far ({@code model:x}, {@code connector:p/k}), for the audit row. */
    public Set<String> suppliersRead() {
        return Set.copyOf(suppliersRead);
    }

    /** The {@code render} and {@code layout} events, then every chip, then {@code done}. */
    public CompletableFuture<Outcome> start() {
        Map<String, String> uses = new LinkedHashMap<>();
        board.chips().forEach((n, i) -> uses.put(n, i.useText()));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("renderId", id);
        r.put("board", board.board());
        r.put("boardHash", boardHash);
        r.put("chips", uses);
        sink.event("render", r);
        sink.event("layout", layout());
        return paint(board.chips().keySet());
    }

    /**
     * An out pin changed in the browser (a selection, a typed filter): the chips downstream of it are painted again, in trace order.
     *
     * @throws DrishtiException DRS-2079 once the session is closed; DRS-2071 when {@code instance.pin} is no out pin
     */
    public CompletableFuture<Outcome> pin(String instance, String pin, Object value) {
        if (closed) {
            throw new DrishtiException(ErrorCode.UI_RENDER_ENDED, "the render " + id + " has ended; render again");
        }
        ChipDoc chip = chips.get(instance);
        if (chip == null || chip.out(pin).isEmpty()) {
            throw new DrishtiException(ErrorCode.UI_CHECK_FAILED, "'" + instance + "." + pin + "' is not an out pin of this board");
        }
        lastUsed = System.currentTimeMillis();
        if (value == null) {
            outs.remove(instance + "." + pin);
        } else {
            outs.put(instance + "." + pin, value);
        }
        Set<String> targets = new LinkedHashSet<>();
        for (BoardDoc.Trace t : board.traces()) {
            if (t.from().equals(instance + "." + pin)) {
                collect(instanceOf(t.to()), targets);
            }
        }
        return paint(targets);
    }

    private void collect(String inst, Set<String> into) {
        if (inst != null && board.chips().containsKey(inst) && into.add(inst)) {
            downstream.getOrDefault(inst, Set.of()).forEach(d -> collect(d, into));
        }
    }

    @Override
    public void close() {
        closed = true;
        follows.values().forEach(BoardRender::quietly);
        follows.clear();
    }

    public boolean closed() {
        return closed;
    }

    // ---- painting ---------------------------------------------------------------------------------------------------

    private CompletableFuture<Outcome> paint(Collection<String> targets) {
        long t0 = System.nanoTime();
        Set<String> reported = ConcurrentHashMap.newKeySet();
        AtomicInteger errors = new AtomicInteger();
        Map<String, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (String inst : order(targets)) {
            List<CompletableFuture<Void>> deps = new ArrayList<>();
            for (String up : upstream.getOrDefault(inst, Set.of())) {
                CompletableFuture<Void> f = futures.get(up);
                if (f != null) {
                    deps.add(f);
                }
            }
            CompletableFuture<Void> f = CompletableFuture.allOf(deps.toArray(CompletableFuture[]::new))
                    .handle((v, e) -> null)
                    .thenComposeAsync(v -> paintOne(inst, reported, errors), exec)
                    .exceptionally(e -> null);
            futures.put(inst, f);
        }
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
                .orTimeout(props.boardBudget().toMillis(), TimeUnit.MILLISECONDS)
                .handle((v, e) -> {
                    for (String inst : futures.keySet()) {
                        if (reported.add(inst)) {                      // not painted in the board's budget
                            errors.incrementAndGet();
                            sink.event("chip", error(inst, ErrorCode.UI_SUPPLIER_TIMEOUT.code(), "not painted within the board's "
                                    + props.boardBudget().toSeconds() + " s"));
                        }
                    }
                    Outcome o = new Outcome(futures.size(), errors.get(), (System.nanoTime() - t0) / 1_000_000);
                    sink.event("done", Map.of("ms", o.ms(), "chips", o.chips(), "errors", o.errors()));
                    return o;
                });
    }

    /** {@code targets} with every master before its details (a back edge of a selection cycle is dropped). */
    private List<String> order(Collection<String> targets) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        for (String t : targets) {
            visit(t, Set.copyOf(targets), seen, onPath, out);
        }
        return out;
    }

    private void visit(String inst, Set<String> in, Set<String> seen, Set<String> onPath, List<String> out) {
        if (seen.contains(inst) || onPath.contains(inst)) {
            return;
        }
        onPath.add(inst);
        for (String up : upstream.getOrDefault(inst, Set.of())) {
            if (in.contains(up)) {
                visit(up, in, seen, onPath, out);
            }
        }
        onPath.remove(inst);
        seen.add(inst);
        out.add(inst);
    }

    private CompletableFuture<Void> paintOne(String inst, Set<String> reported, AtomicInteger errors) {
        BoardDoc.Instance in = board.chips().get(inst);
        ChipDoc chip = chips.get(inst);
        try {
            Map<String, Object> inputs = new LinkedHashMap<>();
            Map<String, Object> filters = new LinkedHashMap<>();
            Map<String, Object> traced = new LinkedHashMap<>();
            Map<String, Boolean> combined = new HashMap<>();
            chip.inPins().forEach((n, p) -> {
                if (p.hasDefault()) {
                    inputs.put(n, JSON.convertValue(p.defaultValue(), Object.class));
                }
            });
            in.controls().forEach((k, v) -> inputs.put(k, JSON.convertValue(v, Object.class)));
            for (BoardDoc.Trace t : board.traces()) {
                if (!inst.equals(instanceOf(t.to()))) {
                    continue;
                }
                String pin = t.to().substring(inst.length() + 1);
                Object v = source(t.from());
                if (v == null) {
                    inputs.remove(pin);
                    continue;
                }
                if (t.transform() != null && t.transform().kind() == BoardDoc.TransformKind.FILTER_ON) {
                    BoardDoc.Mapping m = in.map().get(t.transform().column());
                    if (m == null || m.attributes().isEmpty()) {
                        throw new DrishtiException(ErrorCode.UI_CHECK_FAILED, "the filter column " + t.transform().column() + " is not mapped");
                    }
                    filters.put(m.attributes().get(0), real(v));
                    inputs.put(pin, Map.of("on", t.transform().column(), "value", v));
                } else if (t.transform() != null && t.transform().kind() == BoardDoc.TransformKind.FIRST && v instanceof List<?> l) {
                    inputs.put(pin, l.isEmpty() ? null : l.get(0));
                } else if (chip.in(pin).map(p -> p.type().type() == PinType.FILTER).orElse(false) && combined.containsKey(pin)) {
                    List<Object> all = new ArrayList<>();                     // filters combine: every trace into a filter pin holds (12.4)
                    Object had = inputs.get(pin);
                    if (had instanceof List<?> l && combined.get(pin) == Boolean.TRUE) {
                        all.addAll(l);
                    } else if (had != null) {
                        all.add(had);
                    }
                    all.add(v);
                    inputs.put(pin, all);
                    combined.put(pin, Boolean.TRUE);
                } else {
                    inputs.put(pin, v);
                    if (chip.in(pin).map(p -> p.type().type() == PinType.FILTER).orElse(false)) {
                        combined.put(pin, Boolean.FALSE);
                    }
                }
                if (chip.in(pin).map(p -> p.role() == PinSpec.PinRole.DATA).orElse(false)) {
                    traced.put(pin, v);
                }
            }
            List<String> waiting = new ArrayList<>();
            String recordId = null;
            for (PinSpec p : chip.inPins().values()) {
                if (p.role() != PinSpec.PinRole.CONTROL) {
                    continue;
                }
                Object v = inputs.get(p.name());
                if (p.required() && v == null) {
                    waiting.add(p.name());
                } else if (p.type().type() == PinType.ID && v != null && recordId == null) {
                    recordId = String.valueOf(real(v));
                }
            }
            if (recordId == null && waiting.isEmpty() && opensById(in.supply())) {
                // an entity opened by id (entity-card over trading/trade) waits for its id like a required pin, rather than listing every record
                chip.inPins().values().stream().filter(p -> p.role() == PinSpec.PinRole.CONTROL && p.type().type() == PinType.ID)
                        .findFirst().ifPresent(p -> waiting.add(p.name()));
            }
            resetOuts(inst, chip);
            if (!waiting.isEmpty()) {                                     // the detail has nothing to show until its master sends a value
                Map<String, Object> e = frameOf(inst, in, chip, Map.of(), inputs, null);
                e.put("state", "waiting");
                e.put("waiting", waiting);
                emit(inst, reported, e);
                return CompletableFuture.completedFuture(null);
            }
            if (in.supply() == null) {
                emit(inst, reported, data(inst, in, chip, null, inputs, traced));
                return CompletableFuture.completedFuture(null);
            }
            BoardSuppliers.Ask ask = new BoardSuppliers.Ask(in.supply(), substitute(in.supply().query()), recordId, filters, props.rowsPerChip());
            suppliersRead.add(in.supply().key());
            BoardSuppliers.Rows rows = read(ask);
            if (rows.pending() != null) {                                  // the BI question became a run: its card shows, the chip follows
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("instance", inst);
                e.put("state", "running");
                e.put("runId", rows.runId());
                sink.event("chip", e);
                return rows.pending().thenAccept(done -> emit(inst, reported, data(inst, in, chip, done, inputs, traced)))
                        .exceptionally(x -> {
                            errors.incrementAndGet();
                            emit(inst, reported, failure(inst, x));
                            return null;
                        });
            }
            emit(inst, reported, data(inst, in, chip, rows, inputs, traced));
            follow(inst, in, chip, ask, inputs, traced);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            emit(inst, reported, failure(inst, e));
            return CompletableFuture.completedFuture(null);
        }
    }

    private BoardSuppliers.Rows read(BoardSuppliers.Ask ask) {
        CompletableFuture<BoardSuppliers.Rows> f = CompletableFuture.supplyAsync(() -> suppliers.read(owner, ask), exec);
        try {
            // a model's inline wait is the chip budget itself (then a run card): a little slack before the non-BI timeout
            return f.get(props.chipBudget().toMillis() + (ask.supply().kind() == BoardDoc.SupplyKind.MODEL ? 2_000 : 0), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new DrishtiException(ErrorCode.UI_SUPPLIER_TIMEOUT, ask.supply().key() + " did not answer within " + props.chipBudget().toSeconds() + " s");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new CompletionException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.UI_RENDER_ENDED, "the render was stopped");
        }
    }

    private void follow(String inst, BoardDoc.Instance in, ChipDoc chip, BoardSuppliers.Ask ask, Map<String, Object> inputs, Map<String, Object> traced) {
        quietly(follows.remove(inst));
        if (in.supply().kind() != BoardDoc.SupplyKind.LIVE || closed) {
            return;
        }
        AutoCloseable sub = suppliers.follow(owner, ask, rows -> {
            if (!closed) {
                Map<String, Object> d = data(inst, in, chip, rows, inputs, traced);
                sink.event("frame", Map.of("instance", inst, "patch", d.get("viewmodel")));
            }
        });
        if (sub != null) {
            follows.put(inst, sub);
        }
    }

    private void emit(String inst, Set<String> reported, Map<String, Object> event) {
        if (!closed && reported.add(inst)) {
            sink.event("chip", event);
        }
    }

    private void resetOuts(String inst, ChipDoc chip) {
        chip.outPins().forEach((n, p) -> {
            if (p.hasDefault()) {
                outs.put(inst + "." + n, JSON.convertValue(p.defaultValue(), Object.class));
            } else if (p.shapeOf() != null) {
                outs.remove(inst + "." + n);
            }
        });
    }

    /** A trace's source value: a board parameter (the request's, else its default) or an out pin's current value. */
    private Object source(String from) {
        if (from.startsWith(BoardDoc.PARAMS)) {
            String p = from.substring(BoardDoc.PARAMS.length());
            Object v = params.get(p);
            if (v == null && board.params().containsKey(p) && board.params().get(p).hasDefault()) {
                v = JSON.convertValue(board.params().get(p).defaultValue(), Object.class);
            }
            return v;
        }
        return outs.get(from);
    }

    /** A token stands for the id it hides, for its owner only. */
    private Object real(Object v) {
        return IdTokens.isToken(v) ? tokens.resolve(owner.user(), (String) v) : v;
    }

    private JsonNode substitute(JsonNode query) {
        if (query == null || query.isNull() || query.isMissingNode()) {
            return null;
        }
        String text = query.toString();
        Matcher m = PARAM.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = source(BoardDoc.PARAMS + m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : JSON.valueToTree(String.valueOf(v)).toString().replaceAll("^\"|\"$", "")));
        }
        m.appendTail(sb);
        try {
            return JSON.readTree(sb.toString());
        } catch (java.io.IOException e) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the supply query cannot be read after its parameters: " + e.getMessage());
        }
    }

    // ---- the frame ----------------------------------------------------------------------------------------------------

    /** The chip event of a painted chip: its ViewModel (body, pin data in the chip's own column names, controls) and meta. */
    private Map<String, Object> data(String inst, BoardDoc.Instance in, ChipDoc chip, BoardSuppliers.Rows rows, Map<String, Object> inputs,
            Map<String, Object> traced) {
        Map<String, Object> pins = new LinkedHashMap<>(traced);
        Map<String, Object> meta = new LinkedHashMap<>();
        List<Object> keys = null;
        if (rows != null) {
            boolean truncated = rows.rows().size() > props.rowsPerChip();
            List<Map<String, Object>> shown = truncated ? rows.rows().subList(0, props.rowsPerChip()) : rows.rows();
            Set<String> maskedColumns = new LinkedHashSet<>();
            for (PinSpec p : chip.inPins().values()) {
                if (p.role() == PinSpec.PinRole.DATA && !traced.containsKey(p.name())) {
                    List<Map<String, Object>> mapped = map(in, p, rows, shown, maskedColumns);
                    pins.put(p.name(), p.type().type() == PinType.DOCUMENT ? (mapped.isEmpty() ? null : mapped.get(0)) : mapped);
                }
            }
            keys = keys(in, chip, rows, shown);
            meta.put("rows", shown.size());
            meta.put("truncated", truncated);
            if (truncated || rows.total() > shown.size()) {
                meta.put("total", Math.max(rows.total(), rows.rows().size()));
            }
            if (rows.runId() != null) {
                meta.put("runId", rows.runId());
            }
            meta.put("reused", rows.reused());
            meta.put("masked", List.copyOf(maskedColumns));
        }
        chip.outPins().forEach((n, p) -> {
            if (p.shapeOf() != null && pins.get(p.shapeOf()) != null) {
                outs.put(inst + "." + n, pins.get(p.shapeOf()));          // e.g. 'visible': the rows as painted, for a chart or a total to follow
            }
        });
        Map<String, Object> e = frameOf(inst, in, chip, pins, inputs, keys);
        e.put("meta", meta);
        return e;
    }

    private Map<String, Object> frameOf(String inst, BoardDoc.Instance in, ChipDoc chip, Map<String, Object> pins, Map<String, Object> inputs, List<Object> keys) {
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("chip", chip.ref().toString());
        vm.put("title", in.title() != null ? in.title() : chip.title());
        vm.put("body", chip.body());
        vm.put("pins", pins);
        vm.put("controls", inputs);
        if (keys != null) {
            vm.put("keys", keys);
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("instance", inst);
        e.put("state", "data");
        e.put("viewmodel", vm);
        return e;
    }

    /** The rows of one data pin in the chip's column names; a masked attribute paints as {@value #MASK}; a hidden required one is the chip's error. */
    private static List<Map<String, Object>> map(BoardDoc.Instance in, PinSpec pin, BoardSuppliers.Rows rows, List<Map<String, Object>> shown,
            Set<String> maskedColumns) {
        Map<String, BoardDoc.Mapping> cols = new LinkedHashMap<>();
        in.map().forEach((k, m) -> {
            if (k.startsWith(pin.name() + ".")) {
                cols.put(k.substring(pin.name().length() + 1), m);
            }
        });
        Set<String> present = new HashSet<>(rows.columns());
        for (Map<String, Object> r : shown) {
            present.addAll(r.keySet());
        }
        for (Map.Entry<String, BoardDoc.Mapping> c : cols.entrySet()) {
            for (String a : c.getValue().attributes()) {
                if (rows.masked().contains(a)) {
                    maskedColumns.add(c.getKey());
                } else if (!present.contains(a) && !shown.isEmpty()) {
                    PinSpec.ColumnSpec spec = pin.shape().get(c.getKey());
                    if (spec != null && spec.required()) {
                        throw new DrishtiException(ErrorCode.UI_FORBIDDEN, "the column " + a + " (" + pin.name() + "." + c.getKey() + ") is not shown to you");
                    }
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(shown.size());
        for (Map<String, Object> r : shown) {
            Map<String, Object> row = new LinkedHashMap<>();
            cols.forEach((col, m) -> {
                if (m.list()) {
                    List<Map<String, Object>> many = new ArrayList<>();
                    for (String a : m.attributes()) {
                        Map<String, Object> nv = new LinkedHashMap<>();
                        nv.put("name", a);
                        nv.put("value", value(rows, r, a));
                        many.add(nv);
                    }
                    row.put(col, many);
                } else {
                    row.put(col, value(rows, r, m.attributes().get(0)));
                }
            });
            out.add(row);
        }
        return out;
    }

    private static Object value(BoardSuppliers.Rows rows, Map<String, Object> r, String a) {
        return rows.masked().contains(a) ? MASK : r.get(a);
    }

    /**
     * Per row, what a selection of it sends: the id itself, or, where the id is masked for the person, a token only they can resolve (14.1 #10).
     * Null when no out pin carries a row's id.
     */
    private List<Object> keys(BoardDoc.Instance in, ChipDoc chip, BoardSuppliers.Rows rows, List<Map<String, Object>> shown) {
        String column = null;
        for (PinSpec p : chip.outPins().values()) {
            if (p.type().type() == PinType.ID && p.ofPin() != null && in.map().containsKey(p.ofPin())) {
                column = p.ofPin();
                break;
            }
        }
        if (column == null) {
            return null;
        }
        String attr = in.map().get(column).attributes().get(0);
        boolean masked = rows.masked().contains(attr);
        List<Object> keys = new ArrayList<>(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            if (!masked) {
                keys.add(shown.get(i).get(attr));
            } else {
                String ref = rows.refs() == null || i >= rows.refs().size() ? null : rows.refs().get(i);
                keys.add(ref == null ? null : tokens.issue(owner.user(), ref));
            }
        }
        return keys;
    }

    private static Map<String, Object> error(String inst, String code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("instance", inst);
        e.put("state", "error");
        e.put("code", code);
        e.put("message", message);
        return e;
    }

    private static Map<String, Object> failure(String inst, Throwable x) {
        Throwable t = x instanceof CompletionException && x.getCause() != null ? x.getCause() : x;
        if (t instanceof DrishtiException d) {
            return error(inst, d.errorCode().code(), d.getMessage());
        }
        return error(inst, ErrorCode.BAD_REQUEST.code(), String.valueOf(t.getMessage()));
    }

    // ---- the layout ---------------------------------------------------------------------------------------------------

    private Map<String, Object> layout() {
        BoardDoc.Layout l = board.layout();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("device", device);
        out.put("theme", l.theme());
        if ("phone".equals(device)) {
            List<String> order = new ArrayList<>(l.phone() == null ? List.of() : l.phone().order());
            board.chips().keySet().stream().filter(c -> !order.contains(c)).forEach(order::add);       // BC-W4: left out, appended
            out.put("order", order);
            out.put("collapsed", l.phone() == null ? List.of() : l.phone().collapsed());
        } else {
            BoardDoc.Grid g = "tablet".equals(device) && l.tablet() != null ? l.tablet() : l.desktop();
            out.put("columns", g == null ? 12 : g.columns());
            List<Map<String, Object>> areas = new ArrayList<>();
            if (g != null) {
                for (BoardDoc.Area a : g.areas()) {
                    areas.add(Map.of("chip", a.chip(), "at", List.of(a.column(), a.row()), "span", List.of(a.width(), a.height())));
                }
            }
            out.put("areas", areas);
        }
        Map<String, Object> frames = new LinkedHashMap<>();
        board.chips().forEach((n, i) -> {
            ChipDoc c = chips.get(n);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("title", i.title() != null ? i.title() : c.title());
            f.put("chip", c.ref().toString());
            f.put("skeleton", c.category() == null ? "panel" : c.category());
            frames.put(n, f);
        });
        out.put("frames", frames);
        return out;
    }

    /** A connector or live supply naming a kind (not a {@code -search}) with no id in its query. */
    private static boolean opensById(BoardDoc.Supply s) {
        return s != null && (s.kind() == BoardDoc.SupplyKind.CONNECTOR || s.kind() == BoardDoc.SupplyKind.LIVE) && !s.name().endsWith("-search")
                && (s.query() == null || !s.query().has("id"));
    }

    private static String instanceOf(String pinPath) {
        if (pinPath == null || pinPath.startsWith(BoardDoc.PARAMS)) {
            return null;
        }
        int dot = pinPath.indexOf('.');
        return dot < 0 ? null : pinPath.substring(0, dot);
    }

    private static void quietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // a live subscription already gone
            }
        }
    }

    /** For tests: the current value of an out pin. */
    Object out(String pinPath) {
        return outs.get(pinPath);
    }

    static Map<String, Object> copy(Map<String, Object> m) {
        return new HashMap<>(m);
    }
}
