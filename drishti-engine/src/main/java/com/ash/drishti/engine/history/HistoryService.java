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
package com.ash.drishti.engine.history;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HistoryDay;
import com.ash.drishti.api.HistoryReads;
import com.ash.drishti.api.HistoryRequest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.source.SourceHistory;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * History queries (DATA_ARCHITECTURE.md › History queries: an entity over time, section 7.2): one entity over the business days it has data, as the caller may see it. Resolves the
 * window, reads the days through {@link SourceRouter#history} (or the cache), redacts every day for the caller, then builds the columns,
 * changes and summary ({@link HistoryRows}). The REST endpoint, the field dialog's series, the CLI and the BI source all come here.
 */
public final class HistoryService {

    /**
     * What is asked. Exactly one of {@code days}, {@code range} and {@code from} names the range (none: the kind's default days).
     *
     * @param end the date box (the request's business date; null: the current one)
     */
    public record Query(String kind, String id, Integer days, String range, String from, String to, Set<String> fields, boolean diff,
            Instant knownAt, boolean descending, LocalDate end) {}

    /** The caller's masks: a document redacted for them, and whether a top-level field is masked. */
    public interface Viewer {
        DataNode redact(DataNode data);

        boolean masked(String field);

        /**
         * The caller's security signature as every node sees it (their roles and embedding app): two callers with the same signature are
         * masked alike and reach the same nodes. Days read through a peer come back masked there for the caller, so they are cached under
         * it and never served to a caller with another one (SOAK2-01). Null: not known, and days read through a peer are not cached.
         */
        default String signature() {
            return null;
        }

        /** Nobody masked: for tests and internal callers that must not show a document to a user. */
        Viewer NONE = new Viewer() {
            public DataNode redact(DataNode data) {
                return data;
            }

            public boolean masked(String field) {
                return false;
            }

            public String signature() {
                return "none";
            }
        };

        /** A viewer without a signature: days read through a peer are not cached for it. */
        static Viewer of(UnaryOperator<DataNode> redact, Predicate<String> masked) {
            return of(redact, masked, null);
        }

        static Viewer of(UnaryOperator<DataNode> redact, Predicate<String> masked, String signature) {
            return new Viewer() {
                public DataNode redact(DataNode data) {
                    return redact.apply(data);
                }

                public boolean masked(String field) {
                    return masked.test(field);
                }

                public String signature() {
                    return signature;
                }
            };
        }
    }

    /**
     * The shared cache key of a read (DATA_ARCHITECTURE.md › History queries: an entity over time, section 11), or null when it must not be cached: the days this node's own sources
     * answered, unmasked, one entry for every caller (masks are applied per answer).
     */
    static String cacheKey(String kind, String id, LocalDate readFrom, LocalDate to, int lastDays, Set<String> wanted, Instant knownAt,
            String dataVersion) {
        return dataVersion == null ? null : String.join("|", kind, id, String.valueOf(readFrom), String.valueOf(to), String.valueOf(lastDays),
                String.valueOf(new TreeSet<>(wanted)), String.valueOf(knownAt), dataVersion);
    }

    /**
     * The key of a read some of whose days a peer answered (SOAK2-01): they arrive masked there for the caller, so the entry is the
     * caller's security signature's alone, never another caller's. Null (not cached) when the caller has no signature.
     */
    static String signedKey(String shared, String signature) {
        return shared == null || signature == null ? null : shared + "|viewer=" + signature;
    }

    private final SourceRouter router;
    private final BusinessDates dates;
    private final HistoryProperties props;
    private final SearchProperties search;
    private final Formats formats;
    private final ElCompiler el;
    private final Function<String, HistorySpec> specs;
    private final Function<String, String> mnemonics;
    private final HistoryCache cache = new HistoryCache();
    private record Gate(int limit, Semaphore permits) {}

    private final java.util.concurrent.atomic.AtomicReference<Gate> gate = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * @param specs the kind's {@code history:} entry (the defaults when its pack has none)
     * @param mnemonics the kind's mnemonic ({@code TRD}), or null
     */
    public HistoryService(SourceRouter router, BusinessDates dates, HistoryProperties props, SearchProperties search, Formats formats, ElCompiler el,
            Function<String, HistorySpec> specs, Function<String, String> mnemonics) {
        this.router = router;
        this.dates = dates;
        this.props = props;
        this.search = search;
        this.formats = formats;
        this.el = el;
        this.specs = specs;
        this.mnemonics = mnemonics;
    }

    public HistoryProperties.Limits limits() {
        return props.limits();
    }

    public HistoryCache cache() {
        return cache;
    }

    /** The spec of a kind's history (its pack's {@code history:} entry, or the defaults). */
    public HistorySpec spec(String kind) {
        HistorySpec s = specs.apply(kind);
        return s == null ? HistorySpec.defaults(kind) : s;
    }

    public HistoryAnswer read(Query q, Viewer viewer) {
        long t0 = System.nanoTime();
        HistoryProperties.Limits limits = props.limits();
        EntityRef ref = EntityRef.of(q.kind(), q.id());
        if (!limits.enabled()) {
            throw new DrishtiException(ErrorCode.HISTORY_NOT_KEPT, "history queries are switched off on this server (drishti.history.enabled)");
        }
        HistorySpec spec = spec(q.kind());
        if (!spec.enabled()) {
            throw new DrishtiException(ErrorCode.HISTORY_NOT_KEPT, q.kind() + " keeps no daily history here: its pack turns history off for it");
        }
        int maxDays = spec.maxDays() > 0 ? Math.min(spec.maxDays(), limits.maxDays()) : limits.maxDays();
        int defaultDays = Math.min(maxDays, spec.defaultDays() > 0 ? spec.defaultDays() : limits.defaultDays());
        LocalDate end = q.end() == null ? dates.current() : q.end();
        HistoryWindow w = HistoryWindow.resolve(q.days(), q.range(), q.from(), q.to(), defaultDays, end, dates.current(), dates.earliest(), maxDays,
                dates.calendar());
        Semaphore gate = gate(limits.maxConcurrent());
        try {
            if (!gate.tryAcquire(limits.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new DrishtiException(ErrorCode.HISTORY_TIMEOUT, limits.maxConcurrent() + " history requests are running on this server; "
                        + "try again in a moment (drishti.history.max-concurrent)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.HISTORY_TIMEOUT, "interrupted while waiting for a history slot");
        }
        try {
            return answer(q, viewer, spec, w, limits, ref, t0);
        } finally {
            gate.release();
        }
    }

    /** The permits for {@code limit} requests at once; a new limit (a settings reload) starts a new set. */
    private Semaphore gate(int limit) {
        return gate.updateAndGet(g -> g != null && g.limit() == limit ? g : new Gate(limit, new Semaphore(limit))).permits();
    }

    private HistoryAnswer answer(Query q, Viewer viewer, HistorySpec spec, HistoryWindow w, HistoryProperties.Limits limits, EntityRef ref,
            long t0) {
        Set<String> wanted = q.fields() == null ? Set.of() : Set.copyOf(q.fields());
        boolean kept = router.keepsHistory(ref, w.to());
        if (!kept && spec.series() == null) {
            List<String> undated = router.undatedSources(q.kind());
            throw new DrishtiException(ErrorCode.HISTORY_NOT_KEPT, q.kind() + " keeps no daily history here: "
                    + (undated.isEmpty() ? "no connector serves it" : String.join(", ", undated) + " hold" + (undated.size() == 1 ? "s" : "")
                            + " today's data only"));
        }
        // the peers' versions too: days a peer holds are read through it, and a load there must change the key (QA1-01)
        String local = router.dataVersion(q.kind(), AsOf.LATEST).orElse(null);
        String peers = local == null ? null : router.peerVersion(ref, AsOf.of(w.to())).orElse(null);
        String dataVersion = local == null || peers == null ? null : peers.isEmpty() ? local : local + "|" + peers;
        List<HistoryDay> days = List.of();
        List<SourceHistory.Failure> failures = List.of();
        String cacheState = "off";
        String basis = "documents";
        if (kept) {
            HistoryRequest request = new HistoryRequest(ref, w.readFrom(), w.to(), w.dates(), wanted, q.knownAt(), w.lastDays(), limits.timeout(),
                    limits.parallelDays());
            // SOAK2-01: the days this node holds are kept unmasked under the shared key; a read a peer answered part of is masked there
            // for this caller, so it is kept under the caller's signature only and never served to a caller with another one
            String shared = cacheKey(q.kind(), q.id(), w.readFrom(), w.to(), w.lastDays(), wanted, q.knownAt(), dataVersion);
            String signed = signedKey(shared, viewer.signature());
            List<HistoryDay> hit = shared == null ? null : cache.get(shared);
            if (hit == null && signed != null) {
                hit = cache.get(signed);
            }
            if (hit != null) {
                days = hit;
                cacheState = "hit";
            } else {
                SourceHistory.Routed routed = router.history(request);
                days = routed.days();
                failures = routed.failures();
                String key = routed.fromPeer() ? signed : shared;
                cacheState = key == null ? "off" : "miss";
                if (key != null && failures.isEmpty() && !days.isEmpty()) {   // a failure, a partial or an empty answer is never kept
                    cache.put(key, days, limits.cacheMb() * 1024L * 1024L);
                }
            }
        }
        HistorySpec used = spec;
        if (days.isEmpty() && failures.isEmpty() && spec.series() != null) {
            Embedded e = embedded(ref, spec, w, viewer);
            if (!e.days().isEmpty()) {
                days = e.days();
                used = e.spec();
                basis = "embedded";
                cacheState = "off";
            }
        } else if (!days.isEmpty() && spec.series() != null && days.get(0).date().isAfter(w.readFrom())) {
            // the stored days start inside the window: the days before them come from the newest document's own series (the stored
            // documents win on the days they cover), so a 3M chart covers 3 months though the store keeps a few days
            LocalDate oldest = days.get(0).date();
            List<HistoryDay> earlier;
            try {
                earlier = embedded(ref, spec, w, viewer).days().stream().filter(d -> d.date().isBefore(oldest)).toList();
            } catch (RuntimeException e) {
                earlier = List.of();                                         // the newest document cannot be read: the stored days alone
            }
            if (!earlier.isEmpty()) {
                List<HistoryDay> merged = new ArrayList<>(earlier);
                merged.addAll(days);
                if (w.lastDays() > 0 && merged.size() > w.lastDays()) {
                    merged = merged.subList(merged.size() - w.lastDays(), merged.size());
                }
                days = List.copyOf(merged);
                if (days.get(0).date().isBefore(oldest)) {                   // the last N days may all be stored ones after all
                    basis = "documents+series";
                }
            }
        }
        boolean baselineFirst = !days.isEmpty() && (HistoryWindow.DATA_DAYS.equals(w.mode())
                ? days.size() == w.lastDays()
                : days.get(0).date().isBefore(w.from()));
        if (days.isEmpty() || (baselineFirst && days.size() == 1)) {
            nothing(ref, w, failures, q.knownAt());
        }
        HistoryRows.Built built = new HistoryRows(used, search.columnsOf(q.kind()), formats, el, viewer::redact, viewer::masked,
                limits.maxColumns()).build(days, baselineFirst, wanted, q.diff());
        List<HistoryAnswer.Row> rows = built.rows();
        boolean truncated = false;
        if (rows.size() > limits.maxRows()) {
            rows = rows.subList(rows.size() - limits.maxRows(), rows.size());
            truncated = true;
        }
        LocalDate shownFrom = HistoryWindow.DATA_DAYS.equals(w.mode()) ? LocalDate.parse(rows.get(0).date()) : w.from();
        // a span that starts before the entity's first day with data: the days before it are not missing (not loaded here, or the
        // entity did not exist yet); loadedFrom says from when there is data
        LocalDate oldestRow = LocalDate.parse(rows.get(0).date());
        LocalDate firstBusiness = w.dates().stream().filter(d -> !d.isBefore(w.from())).findFirst().orElse(w.from());
        LocalDate loadedFrom = HistoryWindow.DATA_DAYS.equals(w.mode()) || !oldestRow.isAfter(firstBusiness) ? null : oldestRow;
        // data days end at the entity's newest day with data; a calendar span keeps its end, but the days after the newest date the kind's
        // sources hold are "not loaded yet" (loadedTo), not missing
        LocalDate newest = LocalDate.parse(rows.get(rows.size() - 1).date());
        LocalDate loadedTo = router.latestDate(q.kind()).filter(l -> l.isBefore(w.to())).orElse(null);
        if (loadedTo != null && loadedTo.isBefore(newest)) {
            loadedTo = newest;
        }
        boolean dataDays = HistoryWindow.DATA_DAYS.equals(w.mode());
        LocalDate shownTo = dataDays ? newest : w.to();
        LocalDate gapsTo = dataDays ? newest : loadedTo != null ? loadedTo : w.to();
        List<String> missing = missing(w, loadedFrom != null ? loadedFrom : shownFrom, gapsTo, rows, failures);
        HistoryAnswer.Window window = new HistoryAnswer.Window(w.asked(), shownFrom.toString(), shownTo.toString(), w.days(), w.mode(),
                built.baseline(), missing, q.knownAt() == null ? null : q.knownAt().toString(),
                !dataDays && loadedTo != null ? loadedTo.toString() : null, loadedFrom == null ? null : loadedFrom.toString());
        List<HistoryAnswer.Row> ordered = rows;
        if (q.descending()) {
            ordered = new ArrayList<>(rows);
            java.util.Collections.reverse(ordered);
        }
        List<HistoryAnswer.Problem> problems = problems(failures, shownFrom);
        return new HistoryAnswer(new HistoryAnswer.Ref(q.kind(), q.id()), mnemonics.apply(q.kind()), built.title() == null ? q.id() : built.title(),
                used.market() ? HistorySpec.MARKET : HistorySpec.DOCUMENT, basis, window, built.columns(), built.parts(), built.chart(),
                List.copyOf(ordered), built.summary(), built.changedDays(), !problems.isEmpty(), problems, truncated, dataVersion, cacheState,
                (System.nanoTime() - t0) / 1_000_000);
    }

    /** No row: the reads failed (DRS-1014 or the source's failure), the entity is unknown (DRS-1001), or it has no data then (DRS-1013). */
    private void nothing(EntityRef ref, HistoryWindow w, List<SourceHistory.Failure> failures, Instant knownAt) {
        if (!failures.isEmpty() && failures.stream().allMatch(SourceHistory.Failure::unreachable)) {
            throw new DrishtiException(ErrorCode.NET_PEER_FAILED, "no node that answered holds the history of " + ref + ", and "
                    + failures.get(0).reason() + "; try again in a moment");
        }
        if (!failures.isEmpty()) {
            boolean timedOut = failures.stream().allMatch(f -> HistoryReads.TIMED_OUT.equals(f.reason()));
            SourceHistory.Failure f = failures.get(0);
            if (timedOut && f.source().startsWith("net:")) {                  // a peer: one call for all the days
                throw new DrishtiException(ErrorCode.HISTORY_TIMEOUT, "the node " + f.source().substring(4) + " did not answer the history of "
                        + ref + " within " + props.limits().timeout().toSeconds() + " s; try again in a moment");
            }
            if (timedOut) {
                throw new DrishtiException(ErrorCode.HISTORY_TIMEOUT, f.source() + " did not answer " + failures.size() + " days of " + ref
                        + " within " + props.limits().timeout().toSeconds() + " s");
            }
            if (f.source().startsWith("net:")) {                              // another node failed: the server that answered did not
                throw new DrishtiException(ErrorCode.NET_PEER_FAILED, "the node " + f.source().substring(4) + " that holds " + ref.kind()
                        + " failed reading the history of " + ref + ": " + f.reason() + "; try again in a moment");
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, f.source() + " failed reading the history of " + ref + ": " + f.reason());
        }
        try {
            router.fetch(ref, AsOf.LATEST).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof DrishtiException de && (de.errorCode() == ErrorCode.ENTITY_NOT_FOUND
                    || de.errorCode() == ErrorCode.NET_PEER_FAILED)) {
                throw de;
            }
        }
        // QA1-11: say when it has data, so the page can offer that span
        java.util.Optional<LocalDate[]> days = router.daysHeld(ref, dates.earliest(), dates.current());
        String span = HistoryWindow.DATA_DAYS.equals(w.mode()) ? " in the " + w.dates().size() + " business days to " + w.to()
                : " from " + w.from() + " to " + w.to();
        if (knownAt != null) {
            // QA1-205: the days listed are those known now, not at knownAt: naming them as the entity's days sent the reader to the same 404
            String now = days.map(d -> "; as known now its days with data run " + (d[0].equals(d[1]) ? "on " + d[0] : "from " + d[0] + " to "
                    + d[1]) + ", which may have been loaded after " + knownAt + ": ask without knownAt, or with a later one").orElse("");
            throw new DrishtiException(ErrorCode.HISTORY_NO_DATA, ref + " has no data" + span + " as known at " + knownAt
                    + ": none of its data in that window was known then" + now);
        }
        String held = days.map(d -> d[0].equals(d[1]) ? "; its only day with data is " + d[0] : "; its days with data run from " + d[0] + " to " + d[1])
                .orElse("");
        throw new DrishtiException(ErrorCode.HISTORY_NO_DATA, ref + " has no data" + span + held);
    }

    private static List<String> missing(HistoryWindow w, LocalDate shownFrom, LocalDate gapsTo, List<HistoryAnswer.Row> rows,
            List<SourceHistory.Failure> failures) {
        Set<String> have = new HashSet<>();
        rows.forEach(r -> have.add(r.date()));
        failures.forEach(f -> have.add(f.date().toString()));
        List<String> out = new ArrayList<>();
        for (LocalDate d : w.dates()) {
            if (!d.isBefore(shownFrom) && !d.isAfter(gapsTo) && !have.contains(d.toString()) && out.size() < 400) {
                out.add(d.toString());
            }
        }
        return out;
    }

    private static List<HistoryAnswer.Problem> problems(List<SourceHistory.Failure> failures, LocalDate shownFrom) {
        Map<String, List<SourceHistory.Failure>> by = new LinkedHashMap<>();
        for (SourceHistory.Failure f : failures) {
            if (!f.date().isBefore(shownFrom)) {
                boolean timeout = HistoryReads.TIMED_OUT.equals(f.reason());
                by.computeIfAbsent(f.source() + "|" + timeout, k -> new ArrayList<>()).add(f);
            }
        }
        List<HistoryAnswer.Problem> out = new ArrayList<>();
        by.forEach((k, list) -> {
            boolean timeout = k.endsWith("|true");
            String source = list.get(0).source();
            List<String> ds = list.stream().map(f -> f.date().toString()).sorted().toList();
            if (list.get(0).unreachable()) {
                out.add(new HistoryAnswer.Problem(ErrorCode.NET_PEER_FAILED.code(), source, ds, list.get(0).reason() + ": " + ds.size() + " day"
                        + (ds.size() == 1 ? "" : "s") + " of the window may be held there"));
                return;
            }
            String msg = timeout ? source + " did not answer " + ds.size() + " day" + (ds.size() == 1 ? "" : "s") + " within the budget"
                    : source + " failed reading " + ds.size() + " day" + (ds.size() == 1 ? "" : "s") + ": " + list.get(0).reason();
            // a peer's failure is about another node (DRS-5052 naming it, as views say), never this server's own source failure (SOAK-02)
            String code = timeout ? ErrorCode.HISTORY_TIMEOUT.code() : source.startsWith("net:") ? ErrorCode.NET_PEER_FAILED.code()
                    : ErrorCode.SOURCE_FAILED.code();
            out.add(new HistoryAnswer.Problem(code, source, ds, msg));
        });
        return out;
    }

    // ---- embedded series (section 6.2) ----

    /** The source a row taken from a document's embedded series names. */
    public static final String SERIES = "series";

    private record Embedded(List<HistoryDay> days, HistorySpec spec) {}

    private Embedded embedded(EntityRef ref, HistorySpec spec, HistoryWindow w, Viewer viewer) {
        HistorySpec.Series s = spec.series();
        EntityDocument doc;
        try {
            doc = router.fetch(ref, AsOf.LATEST).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof DrishtiException de ? de : new DrishtiException(ErrorCode.SOURCE_FAILED, String.valueOf(e.getCause()));
        }
        DataNode list = viewer.redact(doc.data()).at(s.path());
        // one value and one chart field: the value reads as the chart field (CLOSE as PRICE), so the chart and headline work as on documents
        List<String> keys = new ArrayList<>(s.values());
        if (keys.size() == 1 && !spec.chart().isEmpty()) {
            keys.set(0, spec.chart().get(0).key());
        }
        java.util.TreeMap<LocalDate, HistoryDay> byDate = new java.util.TreeMap<>();
        for (int i = 0; i < list.size(); i++) {
            DataNode item = list.get(i);
            LocalDate d;
            try {
                d = LocalDate.parse(item.get(s.date()).asText().substring(0, Math.min(10, item.get(s.date()).asText().length())));
            } catch (RuntimeException e) {
                continue;
            }
            if (d.isAfter(w.to()) || d.isBefore(w.readFrom())) {
                continue;
            }
            LinkedHashMap<String, DataNode> fields = new LinkedHashMap<>();
            for (int k = 0; k < s.values().size(); k++) {
                fields.put(keys.get(k), item.get(s.values().get(k)));
            }
            Provenance p = doc.provenance();
            byDate.put(d, new HistoryDay(d, new EntityDocument(ref, new DataNode.Obj(fields),
                    new Provenance(SERIES, p.generation(), p.fetchedAt(), false, d)), null));
        }
        List<HistoryDay> days = new ArrayList<>(byDate.values());
        if (w.lastDays() > 0 && days.size() > w.lastDays()) {
            days = days.subList(days.size() - w.lastDays(), days.size());
        }
        List<HistorySpec.Column> cols = keys.stream().map(k -> new HistorySpec.Column(k, null, null,
                spec.chart().stream().filter(c -> c.key().equals(k)).findFirst().map(HistorySpec.Column::format).orElse(null))).toList();
        HistorySpec used = new HistorySpec(spec.kind(), true, spec.chart().isEmpty() ? spec.shape() : HistorySpec.MARKET, cols, Set.of(), Set.of(),
                List.of(), spec.chart().isEmpty() ? List.of(new HistorySpec.Column(keys.get(0), null, null, null)) : spec.chart(), spec.headline(),
                s, spec.defaultDays(), spec.maxDays());
        return new Embedded(List.copyOf(days), used);
    }
}
