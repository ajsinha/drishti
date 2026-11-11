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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** History queries end to end in the engine: window, read, masks, changes, summary, cache and errors. */
class HistoryServiceTest {

    /** Business days 2026-09-21 .. 2026-10-02 (no USNY holiday); the clock stands on Friday 2026-10-02. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T20:00:00Z"), ZoneOffset.UTC);

    /** A dated store: trade T-1 on every business day from 09-22, quantity 100 until 09-29 then 120; equity EQ-1 with a rising price. */
    static final class Lake implements SourcePlugin {
        final AtomicInteger reads = new AtomicInteger();
        final String version;
        final boolean dated;
        final LocalDate last;

        Lake(String version, boolean dated) {
            this(version, dated, TODAY);
        }

        Lake(String version, boolean dated, LocalDate last) {
            this.version = version;
            this.dated = dated;
            this.last = last;
        }

        public Optional<LocalDate> latestDate(String kind) {
            return Optional.of(last);
        }

        public PluginManifest manifest() {
            return new PluginManifest("lake", "t", Set.of("trade", "equity"), new SourceCapabilities(false, false, false, dated));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.of(TODAY));
        }

        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            reads.incrementAndGet();
            LocalDate d = asOf.businessDate() == null ? TODAY : asOf.businessDate();
            if (d.isBefore(LocalDate.of(2026, 9, 22)) || d.isAfter(last) || d.getDayOfWeek().getValue() > 5) {
                return Optional.empty();
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            if (ref.id().equals("T-GAP") && d.equals(LocalDate.of(2026, 9, 24))) {
                return Optional.empty();                                          // a real gap inside the loaded span
            }
            if (ref.kind().equals("trade") && (ref.id().equals("T-1") || ref.id().equals("T-GAP"))) {
                doc.put("TRADE_ID", "T-1");
                doc.put("QUANTITY", d.isBefore(LocalDate.of(2026, 9, 29)) ? 100 : 120);
                doc.put("TRADER_ID", "u" + d.getDayOfMonth());                  // changes every day, but masked
                doc.put("STAMP", d.toString());                                  // changes every day, ignored
                doc.put("LEGS", List.of(Map.of("N", 1), Map.of("N", 2)));
            } else if (ref.kind().equals("equity") && ref.id().equals("EQ-1")) {
                doc.put("PRICE", 10.0 + d.getDayOfMonth() % 7);
                doc.put("NAME", "Equity one");
                List<Map<String, Object>> hist = new ArrayList<>();
                hist.add(Map.of("DATE", "2026-09-16", "CLOSE", 9.0));
                hist.add(Map.of("DATE", "2026-09-17", "CLOSE", 9.1));
                hist.add(Map.of("DATE", "2026-09-18", "CLOSE", 9.2));
                hist.add(Map.of("DATE", "2026-09-21", "CLOSE", 9.3));
                hist.add(Map.of("DATE", "2026-09-30", "CLOSE", 9.5));
                hist.add(Map.of("DATE", "2026-10-01", "CLOSE", 9.75));
                doc.put("HISTORY", hist);
            } else {
                return Optional.empty();
            }
            return Optional.of(new EntityDocument(ref, DataNode.of(doc), new Provenance("lake", d.getDayOfMonth(), Instant.now(), false,
                    dated ? d : null)));
        }

        public Optional<String> dataVersion(String kind) {
            return Optional.ofNullable(version);
        }
    }

    private static HistoryService service(Lake lake, Map<String, HistorySpec> specs) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, Map.of());
        SourceRouter router = new SourceRouter(new SourceRegistry(List.of(lake), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
        BusinessDates dates = new BusinessDates(new BusinessDateProperties("USNY", "UTC", null), CLOCK);
        return new HistoryService(router, dates, HistoryProperties.defaults(), new SearchProperties(null, null, Map.of("trade", List.of("TRADE_ID"))),
                Formats.defaults(), new ElCompiler(), k -> specs.getOrDefault(k, HistorySpec.defaults(k)), k -> k.equals("trade") ? "TRD" : null);
    }

    private static final UnaryOperator<DataNode> MASK_TRADER = d -> {
        if (!(d instanceof DataNode.Obj o)) {
            return d;
        }
        LinkedHashMap<String, DataNode> f = new LinkedHashMap<>(o.fields());
        f.computeIfPresent("TRADER_ID", (k, v) -> DataNode.masked());
        return new DataNode.Obj(f);
    };

    private static final HistoryService.Viewer VIEWER = HistoryService.Viewer.of(MASK_TRADER, "TRADER_ID"::equals);

    private static HistoryService.Query days(String kind, String id, int n) {
        return new HistoryService.Query(kind, id, n, null, null, null, Set.of(), false, null, false, null);
    }

    private static HistorySpec trade() {
        List<String> problems = new ArrayList<>();
        HistorySpec s = HistorySpec.parse("trade", Map.of("ignore-changes", List.of("STAMP")), 366, problems);
        assertThat(problems).isEmpty();
        return s;
    }

    @Test
    void theLastDaysWithDataWithABaselineAndTheChangesMarked() {
        HistoryAnswer a = service(new Lake("v1", true), Map.of("trade", trade())).read(days("trade", "T-1", 5), VIEWER);
        assertThat(a.rows()).extracting(HistoryAnswer.Row::date).containsExactly("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02");
        assertThat(a.window().baseline()).isEqualTo("2026-09-25");
        assertThat(a.window().mode()).isEqualTo("data-days");
        assertThat(a.window().from()).isEqualTo("2026-09-28");
        assertThat(a.window().days()).isEqualTo(5);
        assertThat(a.mnemonic()).isEqualTo("TRD");
        assertThat(a.shape()).isEqualTo("document");
        assertThat(a.basis()).isEqualTo("documents");
        // QUANTITY moved on 09-29 only; TRADER_ID is masked (never a change), STAMP is ignored
        assertThat(a.rows().get(0).changed()).isEmpty();
        assertThat(a.rows().get(1).changed()).containsExactly("QUANTITY");
        assertThat(a.rows().get(2).changed()).isEmpty();
        assertThat(a.changedDays()).isEqualTo(1);
        assertThat(a.rows()).allSatisfy(r -> assertThat(r.values().get("TRADER_ID")).isEqualTo(DataNode.MASK));
        assertThat(a.columns()).filteredOn(c -> c.path().equals("TRADER_ID")).singleElement().satisfies(c -> assertThat(c.masked()).isTrue());
        assertThat(a.columns().get(0).path()).isEqualTo("TRADE_ID");
        assertThat(a.columns().get(0).key()).isTrue();
        assertThat(a.parts()).extracting(HistoryAnswer.Part::path).containsExactly("LEGS");
        assertThat(a.rows().get(0).parts()).containsEntry("LEGS", 2);
        assertThat(a.rows().get(1).values()).containsEntry("QUANTITY", 120);
        assertThat(a.rows().get(1).display()).containsEntry("QUANTITY", "120");
        assertThat(a.summary()).isEqualTo(HistoryAnswer.Summary.NONE);
        assertThat(a.partial()).isFalse();
        assertThat(a.window().missing()).isEmpty();
    }

    @Test
    void daysAfterTheNewestLoadedDateAreNotMissing() {
        // the lake was last loaded on 09-30; the business date is 10-02
        HistoryService svc = service(new Lake("v1", true, LocalDate.of(2026, 9, 30)), Map.of());
        HistoryAnswer n = svc.read(days("trade", "T-1", 3), VIEWER);
        assertThat(n.window().to()).isEqualTo("2026-09-30");                        // data days end at the newest day with data
        assertThat(n.window().missing()).isEmpty();
        assertThat(n.window().loadedTo()).isNull();
        HistoryAnswer span = svc.read(new HistoryService.Query("trade", "T-1", null, "2W", null, null, Set.of(), false, null, false, null), VIEWER);
        assertThat(span.window().to()).isEqualTo("2026-10-02");
        assertThat(span.window().loadedTo()).isEqualTo("2026-09-30");
        assertThat(span.window().missing()).isEmpty();                                // not 10-01 and 10-02, not 09-21 before the first day
        assertThat(span.window().loadedFrom()).isEqualTo("2026-09-22");
    }

    @Test
    void theAnswerIsCachedByDataVersionAndNotWithoutOne() {
        Lake lake = new Lake("v1", true);
        HistoryService svc = service(lake, Map.of());
        assertThat(svc.read(days("trade", "T-1", 3), VIEWER).cache()).isEqualTo("miss");
        int reads = lake.reads.get();
        HistoryAnswer again = svc.read(days("trade", "T-1", 3), HistoryService.Viewer.NONE);
        assertThat(again.cache()).isEqualTo("hit");
        assertThat(lake.reads.get()).isEqualTo(reads);
        assertThat(again.rows().get(0).values().get("TRADER_ID")).isNotEqualTo(DataNode.MASK);   // masks per answer, not in the cache
        assertThat(service(new Lake(null, true), Map.of()).read(days("trade", "T-1", 3), VIEWER).cache()).isEqualTo("off");
    }

    @Test
    void aCalendarRangeListsTheDaysWithoutData() {
        HistoryAnswer a = service(new Lake("v1", true), Map.of()).read(
                new HistoryService.Query("trade", "T-1", null, "2W", null, null, Set.of(), true, null, true, null), VIEWER);
        assertThat(a.window().mode()).isEqualTo("calendar");
        assertThat(a.window().from()).isEqualTo("2026-09-19");
        assertThat(a.window().missing()).isEmpty();                                   // 09-21 is before the first day with data
        assertThat(a.window().loadedFrom()).isEqualTo("2026-09-22");
        assertThat(a.window().baseline()).isNull();                                 // 09-18 had no data
        HistoryAnswer gap = service(new Lake("v1", true), Map.of()).read(
                new HistoryService.Query("trade", "T-GAP", null, "2W", null, null, Set.of(), false, null, false, null), VIEWER);
        assertThat(gap.window().missing()).containsExactly("2026-09-24");             // a real gap inside the loaded span
        assertThat(a.rows().get(0).date()).isEqualTo("2026-10-02");                  // order=desc
        assertThat(a.rows()).allSatisfy(r -> assertThat(r.changes()).isNotNull());
        assertThat(a.rows()).filteredOn(r -> r.date().equals("2026-09-29")).singleElement()
                .satisfies(r -> assertThat(r.changes()).extracting(HistoryAnswer.Change::path).contains("QUANTITY"));
    }

    @Test
    void aMarketKindHasItsHeadlineNumbers() {
        List<String> problems = new ArrayList<>();
        HistorySpec eq = HistorySpec.parse("equity", Map.of("chart", List.of("PRICE"), "columns", List.of("PRICE", "NAME")), 366, problems);
        assertThat(problems).isEmpty();
        HistoryAnswer a = service(new Lake("v1", true), Map.of("equity", eq)).read(days("equity", "EQ-1", 5), VIEWER);
        assertThat(a.shape()).isEqualTo("market");
        assertThat(a.chart()).extracting(HistoryAnswer.Chart::path).containsExactly("PRICE");
        HistoryAnswer.Summary s = a.summary();
        assertThat(s.field()).isEqualTo("PRICE");
        // 09-28..10-02: 10 + day % 7 = 10, 11, 12, 11, 12
        assertThat(s.first()).isEqualTo(10.0);
        assertThat(s.last()).isEqualTo(12.0);
        assertThat(s.change()).isEqualTo(2.0);
        assertThat(s.changePct()).isEqualTo(20.0);
        assertThat(s.high()).isEqualTo(12.0);
        assertThat(s.highDate()).isEqualTo("2026-09-30");
        assertThat(s.low()).isEqualTo(10.0);
        assertThat(s.lowDate()).isEqualTo("2026-09-28");
        assertThat(s.range()).isEqualTo(2.0);
        assertThat(s.average()).isEqualTo(11.2);
    }

    @Test
    void anEmbeddedSeriesWhenNoSourceKeepsDailyDocuments() {
        List<String> problems = new ArrayList<>();
        HistorySpec eq = HistorySpec.parse("equity", Map.of("chart", List.of("PRICE"),
                "series", Map.of("path", "HISTORY", "date", "DATE", "value", "CLOSE")), 366, problems);
        assertThat(problems).isEmpty();
        HistoryAnswer a = service(new Lake("v1", false), Map.of("equity", eq)).read(days("equity", "EQ-1", 5), VIEWER);
        assertThat(a.basis()).isEqualTo("embedded");
        assertThat(a.rows()).extracting(r -> r.values().get("PRICE")).containsExactly(9.1, 9.2, 9.3, 9.5, 9.75);
        assertThat(a.rows()).allSatisfy(r -> assertThat(r.source()).isEqualTo("series"));
        assertThat(a.summary().change()).isEqualTo(0.65);
    }

    @Test
    void storedDaysAreFilledBackFromTheSeriesBeforeTheOldestStoredDay() {
        List<String> problems = new ArrayList<>();
        HistorySpec eq = HistorySpec.parse("equity", Map.of("chart", List.of("PRICE"), "columns", List.of("PRICE", "NAME"),
                "series", Map.of("path", "HISTORY", "date", "DATE", "value", "CLOSE")), 366, problems);
        assertThat(problems).isEmpty();
        // the store keeps 09-22 .. 10-02; 2W asks from 09-19 (the baseline candidate 09-18): 09-21 and 09-18 come from the series
        HistoryAnswer a = service(new Lake("v1", true), Map.of("equity", eq)).read(
                new HistoryService.Query("equity", "EQ-1", null, "2W", null, null, Set.of(), true, null, false, null), VIEWER);
        assertThat(a.basis()).isEqualTo("documents+series");
        assertThat(a.window().baseline()).isEqualTo("2026-09-18");
        assertThat(a.rows().get(0).date()).isEqualTo("2026-09-21");
        assertThat(a.rows().get(0).source()).isEqualTo("series");
        assertThat(a.rows().get(0).values().get("PRICE")).isEqualTo(9.3);
        assertThat(a.rows().get(1).source()).isEqualTo("lake");                       // stored documents win on the days they cover
        assertThat(a.rows().get(1).values().get("PRICE")).isEqualTo(11.0);
        assertThat(a.rows().get(1).changed()).containsExactly("PRICE");               // NAME is not known on a series day: not a change
        assertThat(a.rows().get(1).changes()).extracting(HistoryAnswer.Change::path).containsExactly("PRICE");
        assertThat(a.summary().first()).isEqualTo(9.3);                                // the summary covers the whole span
        assertThat(a.summary().low()).isEqualTo(9.3);
        assertThat(a.window().missing()).isEmpty();
        assertThat(a.window().loadedFrom()).isNull();
    }

    @Test
    void aColumnReadsAsItsChartFieldAndASeriesRowsPartsAreNotRead() {
        // QA1-15: the table's PRICE had no format (21.657685) while the chart's had price2; a row from the embedded series counted its
        // parts as 0 although they were not read that day
        List<String> problems = new ArrayList<>();
        HistorySpec eq = HistorySpec.parse("equity", Map.of("chart", List.of(Map.of("path", "PRICE", "format", "price2")), "columns", List.of("PRICE", "NAME"),
                "parts", List.of("HISTORY"), "series", Map.of("path", "HISTORY", "date", "DATE", "value", "CLOSE")), 366, problems);
        assertThat(problems).isEmpty();
        HistoryAnswer a = service(new Lake("v1", true), Map.of("equity", eq)).read(
                new HistoryService.Query("equity", "EQ-1", null, "2W", null, null, Set.of(), true, null, false, null), VIEWER);
        assertThat(a.columns()).filteredOn(c -> c.path().equals("PRICE")).extracting(HistoryAnswer.Column::format).containsExactly("price2");
        HistoryAnswer.Row series = a.rows().get(0);
        assertThat(series.source()).isEqualTo("series");
        assertThat(series.display().get("PRICE")).isEqualTo("9.30");
        assertThat(series.parts()).containsEntry("HISTORY", null);
        assertThat(a.rows().get(1).parts().get("HISTORY")).as("a stored day counts its parts").isEqualTo(6);
    }

    @Test
    void theErrors() {
        HistoryService svc = service(new Lake("v1", true), Map.of("trade", HistorySpec.parse("trade", false, 366, new ArrayList<>())));
        assertThatThrownBy(() -> svc.read(days("trade", "T-1", 5), VIEWER)).satisfies(e -> code(e, ErrorCode.HISTORY_NOT_KEPT));
        HistoryService on = service(new Lake("v1", true), Map.of());
        assertThatThrownBy(() -> on.read(days("trade", "T-1", 400), VIEWER)).satisfies(e -> code(e, ErrorCode.HISTORY_RANGE_TOO_LARGE));
        assertThatThrownBy(() -> on.read(new HistoryService.Query("trade", "T-1", null, null, "2026-09-30", "2026-09-01", Set.of(), false, null, false,
                null), VIEWER)).satisfies(e -> code(e, ErrorCode.HISTORY_BAD_RANGE));
        assertThatThrownBy(() -> on.read(new HistoryService.Query("trade", "T-1", null, "3X", null, null, Set.of(), false, null, false, null), VIEWER))
                .satisfies(e -> code(e, ErrorCode.HISTORY_BAD_RANGE));
        assertThatThrownBy(() -> on.read(new HistoryService.Query("trade", "T-1", null, null, "2026-09-01", "2026-09-15", Set.of(), false, null, false,
                null), VIEWER)).satisfies(e -> code(e, ErrorCode.HISTORY_NO_DATA));
        assertThatThrownBy(() -> on.read(days("trade", "NOPE", 5), VIEWER)).satisfies(e -> code(e, ErrorCode.ENTITY_NOT_FOUND));
        assertThatThrownBy(() -> service(new Lake("v1", false), Map.of()).read(days("trade", "T-1", 5), VIEWER))
                .satisfies(e -> code(e, ErrorCode.HISTORY_NOT_KEPT));
    }

    private static void code(Throwable e, ErrorCode c) {
        assertThat(e).isInstanceOf(DrishtiException.class);
        assertThat(((DrishtiException) e).errorCode()).isEqualTo(c);
    }

    @Test
    void theSpecReportsItsProblems() {
        List<String> p = new ArrayList<>();
        HistorySpec.parse("equity", Map.of("chart", List.of("A", "B", "C", "D", "E"), "headline", "Z", "shape", "pie", "max-days", 999, "colour", 1),
                366, p);
        assertThat(p).anyMatch(s -> s.startsWith("error: history.equity.chart: at most 4"));
        assertThat(p).anyMatch(s -> s.startsWith("error: history.equity.headline: Z is not one of chart"));
        assertThat(p).anyMatch(s -> s.startsWith("error: history.equity.shape"));
        assertThat(p).anyMatch(s -> s.startsWith("error: history.equity.max-days"));
        assertThat(p).anyMatch(s -> s.startsWith("warning: history.equity.colour"));
        assertThat(HistorySpec.hasErrors(p)).isTrue();
        HistorySpec d = HistorySpec.parse("trade", Map.of("default-days", 20, "max-days", 100), 366, new ArrayList<>());
        assertThat(d.defaultDays()).isEqualTo(20);
        assertThat(d.maxDays()).isEqualTo(100);
        assertThat(d.market()).isFalse();
    }

    @Test
    void windows() {
        var cal = com.ash.drishti.common.BusinessCalendar.of("USNY");
        LocalDate earliest = LocalDate.of(2021, 1, 4);
        HistoryWindow ytd = HistoryWindow.resolve(null, "ytd", null, null, 30, TODAY, TODAY, earliest, 366, cal);
        assertThat(ytd.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(ytd.dates().get(0)).isEqualTo(LocalDate.of(2025, 12, 31));      // the baseline candidate
        HistoryWindow m3 = HistoryWindow.resolve(null, "3M", null, null, 30, TODAY, TODAY, earliest, 366, cal);
        assertThat(m3.from()).isEqualTo(LocalDate.of(2026, 7, 3));
        HistoryWindow qtd = HistoryWindow.resolve(null, "QTD", null, null, 30, TODAY, TODAY, earliest, 366, cal);
        assertThat(qtd.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        HistoryWindow n = HistoryWindow.resolve(null, null, null, null, 30, TODAY, TODAY, earliest, 366, cal);
        assertThat(n.lastDays()).isEqualTo(31);
        assertThat(n.dates()).hasSize(367);
        assertThat(n.dates()).doesNotContain(LocalDate.of(2026, 9, 7));             // Labor Day
        assertThatThrownBy(() -> HistoryWindow.resolve(null, null, "2026-10-05", null, 30, TODAY, TODAY, earliest, 366, cal))
                .satisfies(e -> code(e, ErrorCode.HISTORY_BAD_RANGE));
        assertThatThrownBy(() -> HistoryWindow.resolve(null, "2Y", null, null, 30, TODAY, TODAY, earliest, 366, cal))
                .satisfies(e -> code(e, ErrorCode.HISTORY_RANGE_TOO_LARGE));
    }
}
