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
package com.ash.drishti.server.live;

import com.ash.tools.codec.Hashes;
import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.engine.search.SearchFilter;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.api.HistoryController;
import com.ash.drishti.server.api.SearchAnswers;
import com.ash.drishti.server.net.PeerSources;
import com.ash.drishti.server.net.SearchScatter;
import com.ash.drishti.server.security.Entitlements;
import com.ash.rachana.live.LiveTables;
import com.ash.rachana.table.LiveTableSpec;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A FORM's result, and the search page's, as a query on a live table (docs/guides/USER_GUIDE.md, Live results and blotters: The engine: snapshot, deltas, scale, retention; LIVE_TABLES LT-5):
 * the search compiled once ({@link SearchFilter}) into a filter tree, an order and a limit, read from a table of the kind that holds the
 * columns the search needs. The table is declared on first use and shared by everyone who runs a search over those columns on that day;
 * each reader sees it through their own view (kind access, row rules, masks: the authoriser), the condition applied to the row as they see
 * it, so a masked value never matches. It follows the kind's changes on the live business date and is a static snapshot of any other
 * day (the lake's time travel). Two cases keep the condition in the table itself (its {@code where:}): a condition outside the filter
 * tree's subset ({@code matches}, {@code size}), and a kind other Drishti*net members also hold, whose table spans them (each member's
 * share of the search and changes, merged by key, as this person's audience sees them there, so such a table is the audience's own).
 * A condition that reads a field masked for the person answers no row, as the search does (the protocol refuses such a filter).
 */
@Component
public class FormResults {

    private final LiveTables tables;
    private final LiveSources sources;
    private final StructuredSearch search;
    private final Entitlements entitlements;
    private final SourceRouter router;
    private final SearchAnswers answers;
    private final com.ash.drishti.engine.command.Mnemonics mnemonics;
    private com.ash.drishti.server.edition.EditionCommands edition = com.ash.drishti.server.edition.EditionCommands.NONE;
    private ObjectProvider<SearchScatter> scatter;
    private ObjectProvider<PeerSources> peers;

    public FormResults(LiveTables tables, LiveSources sources, StructuredSearch search, Entitlements entitlements, SourceRouter router,
            SearchAnswers answers, com.ash.drishti.engine.command.Mnemonics mnemonics) {
        this.tables = tables;
        this.sources = sources;
        this.search = search;
        this.entitlements = entitlements;
        this.router = router;
        this.answers = answers;
        this.mnemonics = mnemonics;
    }

    /** The edition's command allow-list (a kind switched off in it answers no result). */
    @Autowired(required = false)
    public void setEdition(com.ash.drishti.server.edition.EditionCommands edition) {
        this.edition = edition;
    }

    /** Drishti*net (absent in a test that makes this class itself). */
    @Autowired(required = false)
    void net(ObjectProvider<SearchScatter> s, ObjectProvider<PeerSources> p) {
        this.scatter = s;
        this.peers = p;
    }

    /**
     * A search opened as a query on a table.
     *
     * @param table the table's name, or null when the result is empty by the rules (a condition on a field masked for the person)
     * @param query the Data Protocol query document: {@code filter} (when the condition travels as a tree), {@code sort}, {@code limit}
     * @param live whether the table follows the kind's changes
     * @param filter the compiled search
     */
    public record Opened(String table, Map<String, Object> query, boolean live, SearchFilter filter) {}

    /** Whether a table name is a search's whose source expired ({@link LiveSources#expired}). */
    public boolean expired(String table) {
        return sources.expired(table);
    }

    /** Opens a search for a person on a business date: the kind's access and edition checked, the table declared on first use. */
    public Opened open(String command, AsOf asOf, Principal p) {
        if (command == null || command.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a result names a search: q=TRD where ...");
        }
        SearchFilter f = SearchFilter.compile(search, command.strip());
        entitlements.requireOpen(p, f.kind());
        edition.requireKind(f.kind());
        AsOf at = asOf == null ? AsOf.LATEST : asOf;
        boolean live = at.isLatest();
        List<String> members = members(f.kind(), p);
        boolean kept = !f.pushed() || !members.isEmpty();             // the condition stays in the table
        Map<String, Object> query = new LinkedHashMap<>();
        if (!kept && f.tree() != null) {
            query.put("filter", f.tree());
        }
        query.put("sort", f.sort());
        query.put("limit", f.limit());
        if (f.reads().stream().anyMatch(c -> entitlements.masked(p, c))) {
            return new Opened(null, query, live, f);                  // a condition on a field masked for the person matches no row, as the search
        }
        boolean whole = !router.columnar(f.kind()).containsAll(f.columns().stream()
                .filter(c -> !SearchFilter.KEY.equals(c) && !KindRows.BUSINESS_DATE.equals(c)).toList());
        String audience = members.isEmpty() ? "" : DrishtiLiveTables.signature(entitlements, p) + "|" + entitlements.rowRule(p, f.kind())
                .map(r -> r.text() + "|" + p.user()).orElse("");
        String name = name(f, at, kept ? f.condition() : null, audience);
        if (sources.get(name).isEmpty() || !tables.has(name)) {
            synchronized (this) {
                if (sources.get(name).isEmpty() || !tables.has(name)) {
                    KindSnapshot.Peers rows = members.isEmpty() ? null : () -> peerRows(f, command.strip(), at, p, members);
                    KindStream.Peers changes = members.isEmpty() || !live || peers == null || peers.getIfAvailable() == null ? null
                            : l -> peers.getObject().subscribeKind(f.kind(), p, l)::close;
                    sources.put(name, new LiveSources.Source(f.kind(), at, whole, rows, changes));
                    DrishtiLiveTables.declare(tables, spec(name, f, live, kept));
                }
            }
        }
        return new Opened(name, query, live, f);
    }

    /**
     * The search's result as the console's result tables read it: {@code {table, query, live, page, search}}, the first page read for the
     * person, the search's summary (kind, mnemonic, columns and labels, matched) and {@code asOfNotices} (the sources answering from an earlier
     * date).
     */
    public Map<String, Object> answer(String command, AsOf asOf, Principal p) {
        long t0 = System.nanoTime();
        Opened o = open(command, asOf, p);
        Map<String, Object> page = o.table() == null ? Map.of("columns", List.of(), "values", List.of(), "total", 0L)
                : tables.rows(o.table(), o.query(), p);
        long total = page.get("total") instanceof Number n ? n.longValue() : 0L;
        SearchFilter f = o.filter();
        SearchQuery q = SearchQuery.pick(command.strip());
        StructuredSearch.Result r = new StructuredSearch.Result(f.kind(), f.condition(), q.orderBy(), f.paths(), List.of(), (int) total, (int) total,
                false, Math.round((System.nanoTime() - t0) / 1e4) / 100.0, Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", o.table());
        out.put("query", o.query());
        out.put("live", o.live());
        out.put("page", page);
        out.put("search", answers.summary(command.strip(), q, r, asOf == null ? AsOf.LATEST : asOf));
        out.put("asOfNotices", asOfNotices(f.kind(), asOf == null || asOf.live() ? null : asOf.businessDate()));
        return out;
    }

    /**
     * The sources of the kind (each trading system's connector) that answer the date (null: the latest) from an earlier one, in words: the
     * records page shows them per source system, so a date with no load is never shown silently with an older date's rows.
     */
    List<Map<String, Object>> asOfNotices(String kind, java.time.LocalDate date) {
        try {
            return com.ash.drishti.server.pivot.KindSources.asOfNotices(router.carried(kind, date));
        } catch (RuntimeException e) {
            return List.of();                                          // a source that cannot say never fails the page
        }
    }

    /** The members besides this node that hold other records of the kind for this person (none outside a cluster). */
    private List<String> members(String kind, Principal p) {
        SearchScatter sc = scatter == null ? null : scatter.getIfAvailable();
        return sc == null ? List.of() : sc.targets(kind, p);
    }

    /** Each member's share of the search (as the person, one hop, within the scatter budget), as rows of the table. */
    private List<Map<String, Object>> peerRows(SearchFilter f, String command, AsOf at, Principal p, List<String> members) {
        SearchScatter sc = scatter.getObject();
        String all = command.replaceAll("(?i)\\s+limit\\s+\\d+\\s*$", "") + " limit " + SearchQuery.MAX_LIMIT;
        List<Map<String, Object>> out = new ArrayList<>();
        for (SearchScatter.Share s : sc.ask(members, SearchScatter.sharePath(all), at, p, sc.budget(), "live", f.kind())) {
            if (s.answer() == null) {
                continue;                                             // a member that did not answer adds nothing (its changes still arrive)
            }
            for (JsonNode row : s.answer().path("rows")) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put(SearchFilter.KEY, row.path("ref").path("id").asText());
                if (row.hasNonNull("title") && !row.path("title").asText().equals(row.path("ref").path("id").asText())) {
                    r.put(KindSnapshot.TITLE, row.path("title").asText());
                }
                row.path("values").fields().forEachRemaining(e -> put(r, e.getKey(), plain(e.getValue())));
                out.add(r);
            }
        }
        return out;
    }

    /** A value at a path ({@code $.a.b}) put into a row as nested maps. */
    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> row, String path, Object v) {
        String[] parts = (path.startsWith("$.") ? path.substring(2) : path).split("\\.");
        Map<String, Object> at = row;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = at.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
            if (!(next instanceof Map<?, ?>)) {
                return;
            }
            at = (Map<String, Object>) next;
        }
        at.putIfAbsent(parts[parts.length - 1], v);
    }

    private static Object plain(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    /** The table's declaration: the kind, the key, the columns the search reads (labelled as it labels them), its condition when kept. */
    private LiveTableSpec spec(String name, SearchFilter f, boolean live, boolean kept) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String c : f.columns()) {
            columns.add(Map.of("name", c, "label", SearchFilter.KEY.equals(c) ? "Id" : HistoryController.label(c)));
            if (SearchFilter.KEY.equals(c)) {                         // the search's title of each row, after its id (LS1)
                columns.add(Map.of("name", KindSnapshot.TITLE, "label", "Title"));
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("rachana", 1);
        doc.put("live-table", name);
        String code = mnemonics.codeFor(f.kind());
        doc.put("title", (code == null ? f.kind() : code) + " search");
        doc.put("kind", f.kind());
        doc.put("snapshot", name);
        if (live) {
            doc.put("stream", name);
        }
        doc.put("key", SearchFilter.KEY);
        doc.put("columns", columns);
        if (kept && f.condition() != null) {
            doc.put("where", f.condition());
        }
        return LiveTableSpec.of(doc, DrishtiLiveTables.PROFILE_KEYS, x -> true);
    }

    /** A table's name: the kind, its columns, the day, a kept condition and an audience, as a short hash (none of them shows in it). */
    static String name(SearchFilter f, AsOf at, String condition, String audience) {
        String basis = f.kind() + "\u001f" + String.join(",", f.columns()) + "\u001f" + (at.isLatest() ? "live" : at.businessDate() + "@" + at.knownAt())
                + "\u001f" + (condition == null ? "" : condition) + "\u001f" + audience;
        byte[] h = Hashes.of(Hashes.SHA_256, basis);
        return LiveSources.SEARCH + HexFormat.of().formatHex(h, 0, 12);
    }
}
