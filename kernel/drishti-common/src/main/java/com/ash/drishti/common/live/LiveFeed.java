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
package com.ash.drishti.common.live;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.KindListeners;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.api.live.LiveIntake;
import com.ash.drishti.api.live.LiveMessage;
import com.ash.drishti.api.live.LivePosition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The kernel's live intake for one live connector (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 3, 4 and 7), written once for
 * every transport: <b>decode</b> (the companion's {@code shape:}: {@code document}, {@code envelope} or {@code jsonl}), <b>map</b> (the
 * connector's {@code where}, {@code set} and {@code map}, as for a lake load), <b>validate</b> (the kind's contract, by its
 * {@code live.validate}), <b>order</b> (the contract's {@code live.order}, then the transport position) and <b>apply</b> to the
 * {@link LiveState}, then <b>notify</b> the open views and live sets of the row. A rejected message goes to the {@link RejectLog} and its
 * counter; nothing is ever an error on a page.
 *
 * <p>The decode settings of the connector: {@code shape} or {@code shape.<topic>} (default per topic: {@code document} when the
 * connector names the topic's kind, else {@code envelope}), {@code kind} or {@code kind.<topic>} (the kind of a document), and, for a
 * kind whose contract has no key,
 * {@code id-field} or {@code id-field.<topic>} (else the transport's message key). An envelope is
 * {@code {"kind", "key", "op", "asOf", "doc"}} ({@code id} is read for {@code key}).
 */
public final class LiveFeed implements LiveIntake {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SHAPES = List.of("document", "envelope", "jsonl");

    /**
     * The shape of a stream's messages: its {@code shape.<topic>} or {@code shape} setting, else {@code document} when the connector
     * names the topic's kind ({@code kind.<topic>}, {@code kind}, or the one kind it serves), else {@code envelope}.
     */
    String shapeOf(String stream) {
        String declared = settings.getOrDefault("shape." + stream, settings.get("shape"));
        if (declared != null && !declared.isBlank()) {
            return declared.strip().toLowerCase(java.util.Locale.ROOT);
        }
        String kind = settings.getOrDefault("kind." + stream, settings.get("kind"));
        return (kind != null && !kind.isBlank()) || kinds.size() == 1 ? "document" : "envelope";
    }

    /** The largest message accepted when neither the kind's contract nor the connector says ({@code max-message-kb}). */
    public static final int DEFAULT_MAX_KB = 1024;

    /** The counters of a feed (section 7.9), by name: applied, duplicate, late, filtered, expired, and rejected by reason. */
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final String connector;
    private final Set<String> kinds;
    private final Map<String, String> settings;
    private final KindContracts contracts;
    private final UnaryOperator<EntityDocument> shape;
    private final Function<byte[], DataNode> parse;
    private final LiveState state;
    private final RejectLog rejects;
    private final KindListeners listeners = new KindListeners();
    private final HitIndex index = new HitIndex();
    private final int maxKb;
    private volatile boolean caughtUp;
    private volatile Instant lastMessage;
    private final LiveDates dates;
    private final LiveCheckpoints checkpoints;
    private final java.nio.file.Path reconcileDir;
    /** Records of the next business date, held until the roll (section 5), bounded by {@code next-date.max-rows}. */
    private final java.util.Deque<Held> held = new java.util.ArrayDeque<>();
    private final int maxHeld;
    private volatile LocalDate seenLiveDate;
    private volatile LiveState.Reconcile lastReconcile;

    private record Held(LiveMessage message, LiveRecord record, LiveRules rules) {}

    /** As the full constructor, with no dates checked, no checkpoints kept and no reconcile record written (a feed outside a server). */
    public LiveFeed(String connector, Set<String> kinds, Map<String, String> settings, KindContracts contracts, UnaryOperator<EntityDocument> shape,
            Function<byte[], DataNode> parse, LiveState state, RejectLog rejects) {
        this(connector, kinds, settings, contracts, shape, parse, state, rejects, LiveDates.NONE, new LiveCheckpoints(null), null);
    }

    /**
     * @param connector the live connector's name
     * @param kinds     the kinds it serves (empty: any)
     * @param settings  its settings (the decode settings above, {@code max-message-kb})
     * @param contracts the loaded kinds' contracts
     * @param shape     the connector's where/set/map (null when it has none): null back means its where leaves the row out
     * @param parse     JSON bytes to the document tree (the plugin context's parser)
     * @param state     the live state the rows are applied to
     * @param rejects   the reject log
     * @param dates     the live date and the next business date (section 5)
     * @param checkpoints what a replay must not bring back (section 6)
     * @param reconcileDir where reconcile records are written ({@code <data folder>/live/reconcile}, absolute), or null
     */
    public LiveFeed(String connector, Set<String> kinds, Map<String, String> settings, KindContracts contracts, UnaryOperator<EntityDocument> shape,
            Function<byte[], DataNode> parse, LiveState state, RejectLog rejects, LiveDates dates, LiveCheckpoints checkpoints,
            java.nio.file.Path reconcileDir) {
        this.dates = dates == null ? LiveDates.NONE : dates;
        this.checkpoints = checkpoints == null ? new LiveCheckpoints(null) : checkpoints;
        this.reconcileDir = reconcileDir;
        this.connector = connector;
        this.kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        this.settings = settings == null ? Map.of() : Map.copyOf(settings);
        this.contracts = contracts == null ? KindContracts.NONE : contracts;
        this.shape = shape;
        this.parse = parse;
        this.state = state;
        this.rejects = rejects;
        this.settings.forEach((k, v) -> {
            if ((k.equals("shape") || k.startsWith("shape.")) && !SHAPES.contains(v.strip().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(connector + ": " + k + " '" + v + "' is document, envelope or jsonl");
            }
        });
        this.maxKb = parseInt(this.settings.get("max-message-kb"), DEFAULT_MAX_KB);
        this.maxHeld = parseInt(this.settings.get("next-date.max-rows"), 100_000);
    }

    // -- the intake -----------------------------------------------------------------------------------------------------------

    @Override
    public void accept(LiveMessage m) {
        try {
            lastMessage = m.timestamp() == null ? Instant.now() : m.timestamp();
            roll();
            take(m);
        } catch (RuntimeException e) {
            reject(null, LiveReject.CONTRACT, "the message could not be read: " + e.getMessage(), m);
        } finally {
            if (m.ack() != null) {
                m.ack().run();
            }
        }
    }

    @Override
    public void caughtUp() {
        if (caughtUp) {
            return;
        }
        List<EntityHit> hits = new ArrayList<>();
        for (String kind : state.kinds()) {
            state.entries(kind).filter(e -> !e.deleted()).forEach(e -> hits.add(hit(kind, e.key())));
        }
        index.replaceAll(hits);
        caughtUp = true;
    }

    /** True once the transport has replayed what its broker held. */
    public boolean isCaughtUp() {
        return caughtUp;
    }

    private void take(LiveMessage m) {
        int limit = maxKb;
        if (m.value() != null && m.value().length() > (long) limit * 1024) {
            reject(null, LiveReject.TOO_LARGE, "the message is " + m.value().length() / 1024 + " KB, over " + limit + " KB", m);
            return;
        }
        String shapeName = shapeOf(m.stream());
        if (shapeName.equals("jsonl") && m.value() != null) {
            for (String line : m.value().split("\n")) {
                if (!line.isBlank()) {
                    row(m, kindOf(m), m.key(), LiveRecord.Op.UPSERT, line, null, null);
                }
            }
            return;
        }
        if (shapeName.equals("envelope")) {
            envelope(m);
            return;
        }
        row(m, kindOf(m), m.key(), m.tombstone() ? LiveRecord.Op.DELETE : LiveRecord.Op.UPSERT, m.value(), null, null);
    }

    private void envelope(LiveMessage m) {
        if (m.tombstone()) {
            String[] k = m.key() == null ? new String[0] : m.key().split("/", 2);
            if (k.length < 2) {
                reject(null, LiveReject.NO_KEY, "a tombstone on an envelope topic needs the message key <kind>/<key>", m);
                return;
            }
            row(m, k[0], k[1], LiveRecord.Op.DELETE, null, null, null);
            return;
        }
        JsonNode env;
        try {
            env = JSON.readTree(m.value());
        } catch (IOException e) {
            reject(null, LiveReject.NOT_JSON, firstLine(e.getMessage()), m);
            return;
        }
        if (env == null || !env.isObject()) {
            reject(null, LiveReject.NOT_JSON, "an envelope is a JSON object", m);
            return;
        }
        String kind = text(env.get("kind"));
        String key = text(env.has("key") ? env.get("key") : env.get("id"));
        String op = text(env.get("op"));
        JsonNode doc = env.get("doc");
        LiveRecord.Op o;
        if (op == null || op.equals("upsert")) {
            o = doc == null || doc.isNull() ? LiveRecord.Op.DELETE : LiveRecord.Op.UPSERT;
        } else if (op.equals("delete")) {
            o = LiveRecord.Op.DELETE;
        } else if (op.equals("patch")) {
            o = LiveRecord.Op.PATCH;
        } else {
            reject(kind, LiveReject.CONTRACT, "op '" + op + "' is not upsert, delete or patch", m);
            return;
        }
        row(m, kind, key, o, o == LiveRecord.Op.DELETE ? null : doc.toString(), key, text(env.get("asOf")));
    }

    /**
     * One row of a message: the kind checked, the row mapped, keyed, merged, validated, ordered and applied.
     *
     * @param given the key the envelope states (it must equal the row's), or null
     */
    private void row(LiveMessage m, String kind, String messageKey, LiveRecord.Op op, String body, String given, String envelopeAsOf) {
        if ((kind == null || kind.isBlank()) && body != null && settings.containsKey("kind-by.field")) {
            kind = kindBy(body);
        }
        if (kind == null || kind.isBlank()) {
            reject(null, LiveReject.UNKNOWN_KIND, "the message names no kind (shape " + shapeOf(m.stream()) + "; the connector's kind: setting names it for documents)", m);
            return;
        }
        if (!kinds.isEmpty() && !kinds.contains(kind)) {
            reject(kind, LiveReject.UNKNOWN_KIND, "kind " + kind + " is not one this connector serves (" + String.join(", ", kinds) + ")", m);
            return;
        }
        LiveRules rules = contracts.rules(kind);
        if (!rules.allowed()) {
            reject(kind, LiveReject.NOT_ALLOWED, "the " + kind + " contract takes no live feed (live.allowed: false, a computed kind, or rules to fix)", m);
            return;
        }
        if (op == LiveRecord.Op.DELETE) {
            String key = messageKey;
            if (key == null || key.isBlank()) {
                reject(kind, LiveReject.NO_KEY, "a delete needs the row's key", m);
                return;
            }
            if (shapeOf(m.stream()).equals("document") && !rules.key().isEmpty()) {
                key = stripKind(kind, key);
            }
            apply(m, new LiveRecord(kind, key, op, List.of(), m.timestamp(), null, envelopeAsOf, null, m.position(), connector), rules);
            return;
        }
        ObjectNode doc;
        try {
            JsonNode n = JSON.readTree(body);
            if (n == null || !n.isObject()) {
                reject(kind, LiveReject.NOT_JSON, "a row is a JSON object", m);
                return;
            }
            doc = (ObjectNode) n;
        } catch (IOException e) {
            reject(kind, LiveReject.NOT_JSON, firstLine(e.getMessage()), m);
            return;
        }
        if (shape != null) {
            EntityDocument shaped = shape.apply(new EntityDocument(EntityRef.of(kind, messageKey == null ? "?" : messageKey),
                    parse.apply(bytes(doc)), new Provenance(connector, 0, m.timestamp(), true)));
            if (shaped == null) {
                count("filtered");                                     // the connector's where leaves it out: not this connector's row
                return;
            }
            doc = JSON.valueToTree(shaped.data().unwrap());
        }
        String key = rules.key().isEmpty() && given != null ? given : rules.apply() == LiveRules.Apply.LAND && !rules.landKey().isEmpty()
                ? landKey(rules, doc) : keyOf(kind, rules, doc, m);
        if (key == null) {
            reject(kind, LiveReject.NO_KEY, rules.key().isEmpty() ? "no key: the message has no key and the connector names no id-field"
                    : "the key " + String.join(", ", rules.key()) + " is missing or empty", m);
            return;
        }
        if (given != null && !given.equals(key)) {
            reject(kind, LiveReject.KEY_MISMATCH, "the envelope says key " + given + ", the row says " + key, m);
            return;
        }
        if (!rules.key().isEmpty() && shapeOf(m.stream()).equals("document") && m.key() != null && !m.key().equals(key) && !m.key().equals(kind + "/" + key)) {
            reject(kind, LiveReject.KEY_MISMATCH, "the message key says " + m.key() + ", the row says " + key, m);
            return;
        }
        Map<String, Object> contract = contracts.contract(kind);
        if (op == LiveRecord.Op.PATCH && rules.partial() != LiveRules.Partial.MERGE) {
            reject(kind, LiveReject.CONTRACT, "a patch on a kind whose contract says partial: whole (every message is the whole row)", m);
            return;
        }
        if (rules.partial() == LiveRules.Partial.MERGE) {
            Optional<byte[]> base = state.document(kind, key);
            if (base.isPresent()) {
                try {
                    doc = merge((ObjectNode) JSON.readTree(base.get()), doc);
                } catch (IOException e) {
                    base = Optional.empty();
                }
            }
            if (base.isEmpty() && op == LiveRecord.Op.PATCH && LiveValidator.check(contract, LiveRules.Validate.LENIENT, doc).isPresent()) {
                reject(kind, LiveReject.PATCH_WITHOUT_BASE, "a patch of " + key + " with no row to apply it to and not every required field", m);
                return;
            }
            op = LiveRecord.Op.UPSERT;
        }
        Optional<String> fault = LiveValidator.check(contract, rules.validate(), doc);
        if (fault.isPresent()) {
            reject(kind, LiveReject.CONTRACT, fault.get(), m);
            return;
        }
        List<Object> order = new ArrayList<>();
        for (String f : rules.order()) {
            JsonNode v = doc.get(f);
            order.add(v == null || v.isNull() ? null : v.isNumber() ? v.numberValue() : v.asText());
        }
        Instant changedAt = instant(rules.changedAt() == null ? null : doc.get(rules.changedAt()), m.timestamp());
        String changedBy = rules.changedBy() == null ? null : text(doc.get(rules.changedBy()));
        String asOf = rules.asOf() == null ? envelopeAsOf : Optional.ofNullable(text(doc.get(rules.asOf()))).orElse(envelopeAsOf);
        apply(m, new LiveRecord(kind, key, op, order, changedAt, changedBy, asOf, doc, m.position(), connector), rules);
    }

    /**
     * The record's business date against the live date (section 5): the live date (or none: the live date) is applied; the next business
     * date is held for the roll; an earlier one is rejected ({@code closed-date}: a landed kind's restatements are the lander's); a later
     * one is rejected ({@code future-date}). A record a load has absorbed already (a replay, section 6) is dropped and counted.
     */
    private void apply(LiveMessage m, LiveRecord r, LiveRules rules) {
        if (checkpoints.absorbed(r)) {
            count("absorbed");
            return;
        }
        LocalDate live = dates.live();
        if (live != null) {
            LocalDate d = date(r.asOf());
            if (d == null) {
                r = new LiveRecord(r.kind(), r.key(), r.op(), r.order(), r.changedAt(), r.changedBy(), live.toString(), r.doc(), r.position(), r.source());
            } else if (d.isBefore(live) && !(rules.apply() == LiveRules.Apply.LAND && rules.closedDates() == LiveRules.ClosedDates.RESTATE)) {
                reject(r.kind(), LiveReject.CLOSED_DATE, "the row is of " + d + ", before the live date " + live, m);
                return;
            } else if (d.isAfter(live)) {
                if (!d.equals(dates.next(live))) {
                    reject(r.kind(), LiveReject.FUTURE_DATE, "the row is of " + d + ", after the next business date " + dates.next(live), m);
                    return;
                }
                synchronized (held) {
                    if (held.size() >= maxHeld) {
                        reject(r.kind(), LiveReject.FUTURE_DATE, "the row is of the next business date " + d + " and " + maxHeld
                                + " rows of it are held already (next-date.max-rows)", m);
                        return;
                    }
                    held.addLast(new Held(m, r, rules));
                }
                count("held");
                return;
            }
        }
        if (rules.apply() == LiveRules.Apply.LAND) {
            land(r);
            return;
        }
        applied(r);
    }

    /**
     * A landed kind's row (section 2.4): into the lander's window, the newest per key; the window becomes one batch of the lake. A row with
     * no lander (a feed outside a server) is rejected as not landed.
     */
    private void land(LiveRecord r) {
        LiveLander l = lander;
        if (l == null || r.doc() == null) {
            count(r.doc() == null ? "late" : "rejected");               // a tombstone carries no row of a landed kind; no lander: nothing lands
            return;
        }
        count(l.offer(r, r.doc()) ? "windowed" : "late");
    }

    private volatile LiveLander lander;

    /** The lander of this connector's landed kinds. */
    public void lander(LiveLander l) {
        lander = l;
    }

    /** The lander, or null. */
    public LiveLander lander() {
        return lander;
    }

    /** Lands the window when it is due (called every second by the connector); its rejects go to the reject log. */
    public void landIfDue() {
        LiveLander l = lander;
        if (l != null) {
            l.flushIfDue((kind, code, why) -> {
                count("rejected");
                count("rejected.landing");
                if (rejects != null) {
                    rejects.write(connector, kind, code, why);
                }
            });
        }
    }

    /**
     * The roll (section 5): when the live date moves, the rows held for it are applied; the rows the state holds stay, still over the
     * base, as carried from their date until a load absorbs them or their retention ends.
     */
    private void roll() {
        LocalDate live = dates.live();
        if (live == null || live.equals(seenLiveDate)) {
            return;
        }
        seenLiveDate = live;
        List<Held> now = new ArrayList<>();
        synchronized (held) {
            for (java.util.Iterator<Held> it = held.iterator(); it.hasNext();) {
                Held h = it.next();
                LocalDate d = date(h.record().asOf());
                if (d != null && !d.isAfter(live)) {
                    it.remove();
                    if (d.equals(live)) {
                        now.add(h);
                    } else {
                        count("rejected.closed-date");             // held for a date that passed without its roll (a long pause)
                    }
                }
            }
        }
        now.forEach(h -> applied(h.record()));
    }

    /** The live date as this feed saw it last, or null. */
    public LocalDate liveDate() {
        return seenLiveDate;
    }

    /** How many rows of the next business date are held for the roll. */
    public int held() {
        synchronized (held) {
            return held.size();
        }
    }

    private static LocalDate date(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private void applied(LiveRecord r) {
        if (contracts.rules(r.kind()).apply() == LiveRules.Apply.LAND) {
            land(r);                                                   // a held row of a landed kind, applied at the roll
            return;
        }
        byte[] json = r.doc() == null ? null : bytes(r.doc());
        LiveState.Outcome o = state.apply(r, json);
        count(o.name().toLowerCase(java.util.Locale.ROOT));
        if (o != LiveState.Outcome.APPLIED) {
            return;
        }
        EntityRef ref = EntityRef.of(r.kind(), r.key());
        Provenance p = new Provenance(connector, r.position() == null ? 0 : r.position().offset(), r.changedAt(), true);
        if (r.op() == LiveRecord.Op.DELETE) {
            if (caughtUp) {
                index.remove(ref);
            }
            listeners.tell(ref, EntityDocument.deleted(ref, p));
            return;
        }
        if (caughtUp) {
            index.add(hit(r.kind(), r.key()));
        }
        if (listeners.wanted(ref)) {
            listeners.tell(ref, new EntityDocument(ref, parse.apply(json), p));
        }
    }

    /** A landed row's window key: the first of the contract's {@code land-key} fields the row has (a position's PVs: POSITION_ID). */
    private static String landKey(LiveRules rules, ObjectNode doc) {
        for (String f : rules.landKey()) {
            String v = text(doc.get(f));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private String keyOf(String kind, LiveRules rules, ObjectNode doc, LiveMessage m) {
        if (!rules.key().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String f : rules.key()) {
                String v = text(doc.get(f));
                if (v == null) {
                    return null;
                }
                parts.add(v);
            }
            return String.join("|", parts);
        }
        String field = settings.getOrDefault("id-field." + m.stream(), settings.get("id-field"));
        if (field != null && !field.isBlank()) {
            String v = text(doc.get(field.strip()));
            if (v != null) {
                return v;
            }
        }
        return m.key() == null || m.key().isBlank() ? null : stripKind(kind, m.key());
    }

    /**
     * The kind a row's field names ({@code kind-by.field: VALUE_TYPE} with {@code kind-by.PV: scenario-pv}, {@code kind-by.PNL:
     * pnl-vector}): one topic of rows of two kinds told apart by a field. Null when the field's value names none.
     */
    private String kindBy(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            String v = n == null ? null : text(n.get(settings.get("kind-by.field").strip()));
            return v == null ? null : settings.get("kind-by." + v);
        } catch (IOException e) {
            return null;                                               // not JSON: the row's own read rejects it
        }
    }

    private String kindOf(LiveMessage m) {
        String k = settings.getOrDefault("kind." + m.stream(), settings.get("kind"));
        if (k != null && !k.isBlank()) {
            return k.strip();
        }
        return kinds.size() == 1 ? kinds.iterator().next() : null;
    }

    /**
     * JSON Merge Patch (RFC 7386): fields present replace, fields absent stay, an explicit null clears, arrays and nested objects are
     * replaced whole (section 3.3).
     */
    static ObjectNode merge(ObjectNode base, ObjectNode patch) {
        ObjectNode out = base.deepCopy();
        patch.fields().forEachRemaining(e -> {
            if (e.getValue().isNull()) {
                out.remove(e.getKey());
            } else {
                out.set(e.getKey(), e.getValue());
            }
        });
        return out;
    }

    private void reject(String kind, LiveReject reason, String detail, LiveMessage m) {
        count("rejected." + reason.reason());
        count("rejected");
        if (rejects != null) {
            rejects.write(connector, kind, reason, detail, m);
        }
    }

    private void count(String what) {
        counts.computeIfAbsent(what, k -> new LongAdder()).increment();
    }

    // -- what the kernel reads ------------------------------------------------------------------------------------------------

    /** The row of {@code ref} as the live state holds it now; empty when it holds none or the row was deleted. */
    public Optional<EntityDocument> fetch(EntityRef ref) {
        Optional<LiveState.Entry> e = state.entry(ref.kind(), ref.id()).filter(x -> !x.deleted());
        if (e.isEmpty()) {
            return Optional.empty();
        }
        return state.document(ref.kind(), ref.id()).map(b -> new EntityDocument(ref, parse.apply(b),
                new Provenance(connector, e.get().position() == null ? 0 : e.get().position().offset(), e.get().changedAt(), true)));
    }

    /** True when the state holds a delete of {@code ref} (a tombstone): the row is gone. */
    public boolean deleted(EntityRef ref) {
        return state.entry(ref.kind(), ref.id()).map(LiveState.Entry::deleted).orElse(false);
    }

    /** Every row of {@code kind} the state holds, deletes left out. */
    public void documents(String kind, Consumer<EntityDocument> sink) {
        state.entries(kind).filter(e -> !e.deleted()).forEach(e -> fetch(EntityRef.of(kind, e.key())).ifPresent(sink));
    }

    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        return listeners.entity(ref, listener);
    }

    public Subscription subscribeKind(String kind, Consumer<EntityDocument> listener) {
        return listeners.kind(kind, listener);
    }

    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    /** The kinds the feed has rows of. */
    public Set<String> heldKinds() {
        return state.kinds();
    }

    /** When the newest message arrived, or null. */
    public Instant lastMessage() {
        return lastMessage;
    }

    /** The state this feed applies to. */
    public LiveState state() {
        return state;
    }

    /** The reject log. */
    public RejectLog rejects() {
        return rejects;
    }

    /** One counter's value ({@code applied}, {@code rejected.contract} ...). */
    public long counted(String what) {
        LongAdder a = counts.get(what);
        return a == null ? 0 : a.sum();
    }

    /**
     * Reconciles {@code kind} against a load of {@code loadDate} (section 6), by the kind's {@code absorb:} rule, and leaves a record: the
     * counts, and the first 100 orphans as JSONL at {@code <reconcile dir>/<kind>/<load date>.jsonl}. A by-cutoff or reset reconcile
     * leaves a checkpoint so a replay does not bring the absorbed rows back.
     *
     * @param cutoff the load's cut-off (its extract time; else when it was received, less the settle time)
     * @param lake   the order values of a key in the load ({@code live.order}'s fields), empty when the load does not hold it
     */
    public LiveState.Reconcile reconcile(String kind, LocalDate loadDate, Instant cutoff, Function<String, Optional<List<Object>>> lake) {
        LiveRules rules = contracts.rules(kind);
        LiveState.Reconcile r = state.reconcile(kind, loadDate, rules.absorb(), cutoff, lake);
        if (rules.absorb() == LiveRules.Absorb.BY_CUTOFF && cutoff != null) {
            checkpoints.cutoff(kind, cutoff);
        } else if (rules.absorb() == LiveRules.Absorb.RESET && !r.positions().isEmpty()) {
            checkpoints.reset(kind, r.positions());
        }
        if (caughtUp && (r.absorbed() > 0 || r.orphans() > 0)) {
            List<EntityHit> hits = new ArrayList<>();
            state.entries(kind).filter(e -> !e.deleted()).forEach(e -> hits.add(hit(kind, e.key())));
            for (String other : state.kinds()) {
                if (!other.equals(kind)) {
                    state.entries(other).filter(e -> !e.deleted()).forEach(e -> hits.add(hit(other, e.key())));
                }
            }
            index.replaceAll(hits);
        }
        counts.computeIfAbsent("absorbed", k -> new LongAdder()).add(r.absorbed());
        counts.computeIfAbsent("orphans", k -> new LongAdder()).add(r.orphans());
        lastReconcile = r;
        record(r);
        return r;
    }

    /** The last reconcile, or null. */
    public LiveState.Reconcile lastReconcile() {
        return lastReconcile;
    }

    private void record(LiveState.Reconcile r) {
        if (reconcileDir == null) {
            return;
        }
        try {
            java.nio.file.Path dir = reconcileDir.resolve(r.kind());
            java.nio.file.Files.createDirectories(dir);
            StringBuilder b = new StringBuilder();
            ObjectNode head = JSON.createObjectNode();
            head.put("at", Instant.now().toString()).put("connector", connector).put("kind", r.kind())
                    .put("load", String.valueOf(r.loadDate())).put("rule", r.rule().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                    .put("cutoff", String.valueOf(r.cutoff())).put("absorbed", r.absorbed()).put("carried", r.carried())
                    .put("orphans", r.orphans()).put("deletesCarried", r.deletesCarried());
            b.append(head).append('\n');
            r.orphanRows().stream().limit(100).forEach(e -> {
                ObjectNode o = JSON.createObjectNode();
                o.put("orphan", e.key()).put("changedAt", String.valueOf(e.changedAt())).put("position", String.valueOf(e.position()));
                b.append(o).append('\n');
            });
            java.nio.file.Files.writeString(dir.resolve(r.loadDate() + ".jsonl"), b.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            System.getLogger(LiveFeed.class.getName()).log(System.Logger.Level.WARNING, "the reconcile record of " + r.kind() + " cannot be written to "
                    + reconcileDir + ": " + e.getMessage());
        }
    }

    /** Removes what is past each kind's retention; the rows removed unabsorbed are counted ({@code expired}). */
    public void expire(Instant now) {
        roll();
        for (String kind : state.kinds()) {
            LiveRules r = contracts.rules(kind);
            int n = state.expire(kind, now, r.ttl(), r.maxRows());
            if (n > 0) {
                counts.computeIfAbsent("expired", k -> new LongAdder()).add(n);
            }
        }
    }

    /**
     * Reads a row back from its message when the state no longer holds its document (section 7.6): the transport's message decoded and
     * shaped again, the row of that key taken.
     */
    public Optional<byte[]> reread(LiveState.Entry e, Function<LivePosition, Optional<LiveMessage>> readBack) {
        Optional<LiveMessage> m = e.position() == null ? Optional.empty() : readBack.apply(e.position());
        if (m.isEmpty() || m.get().value() == null) {
            return Optional.empty();
        }
        String shapeName = shapeOf(m.get().stream());
        List<String> bodies = shapeName.equals("jsonl") ? List.of(m.get().value().split("\n")) : List.of(m.get().value());
        for (String b : bodies) {
            try {
                JsonNode n = JSON.readTree(b);
                if (shapeName.equals("envelope")) {
                    n = n == null ? null : n.get("doc");
                }
                if (n == null || !n.isObject()) {
                    continue;
                }
                ObjectNode doc = (ObjectNode) n;
                if (shape != null) {
                    EntityDocument s = shape.apply(new EntityDocument(EntityRef.of(e.kind(), e.key()), parse.apply(bytes(doc)),
                            new Provenance(connector, 0, Instant.now(), true)));
                    if (s == null) {
                        continue;
                    }
                    doc = JSON.valueToTree(s.data().unwrap());
                }
                if (e.key().equals(keyOf(e.kind(), contracts.rules(e.kind()), doc, m.get()))) {
                    return Optional.of(bytes(doc));
                }
            } catch (IOException ignored) {
                // a line that is not a row of this key
            }
        }
        return Optional.empty();
    }

    /** The feed's measures (section 7.9): its counters, the rows and bytes held, whether it has caught up. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caughtUp", caughtUp);
        out.put("rows", state.size());
        out.put("memoryMb", Math.round(state.memoryBytes() / 1048576.0 * 10) / 10.0);
        if (state.disk() != null) {
            out.put("diskMb", Math.round(state.disk().sizeOnDisk() / 1048576.0 * 10) / 10.0);
        }
        Map<String, Long> c = new java.util.TreeMap<>();
        counts.forEach((k, v) -> c.put(k, v.sum()));
        out.put("counts", c);
        out.put("lastMessage", lastMessage == null ? null : lastMessage.toString());
        out.put("liveDate", seenLiveDate == null ? null : seenLiveDate.toString());
        out.put("heldForNextDate", held());
        LiveLander l = lander;
        if (l != null) {
            out.put("landing", l.describe());
        }
        LiveState.Reconcile rc = lastReconcile;
        if (rc != null) {
            out.put("lastReconcile", Map.of("kind", rc.kind(), "load", String.valueOf(rc.loadDate()), "absorbed", rc.absorbed(), "carried", rc.carried(),
                    "orphans", rc.orphans(), "deletesCarried", rc.deletesCarried()));
        }
        return out;
    }

    // -- small helpers ----------------------------------------------------------------------------------------------------------

    private EntityHit hit(String kind, String key) {
        return new EntityHit(EntityRef.of(kind, key), key, kind + " · " + connector);
    }

    private static String stripKind(String kind, String key) {
        return key.startsWith(kind + "/") ? key.substring(kind.length() + 1) : key;
    }

    private static byte[] bytes(JsonNode n) {
        return n.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || n.isContainerNode()) {
            return null;
        }
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    private static Instant instant(JsonNode n, Instant dflt) {
        String s = text(n);
        if (s == null) {
            return dflt;
        }
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return dflt;
        }
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s == null || s.isBlank() ? dflt : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
