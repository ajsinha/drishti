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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The history cache never serves days masked for one caller to another (SOAK2-01). A peer masks the days it answers for the caller it acts
 * for (here: a role, as the on-behalf-of token carries it), so a read a peer answered part of is kept under the caller's
 * security signature; the days this node holds are kept unmasked once for every caller and masked per answer.
 */
class HistoryCacheMasksTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T20:00:00Z"), ZoneOffset.UTC);
    private static volatile String acting = "risk";      // who the peer acts for (the on-behalf-of token); the tests run one at a time

    private static DataNode maskQuantity(DataNode d) {
        if (!(d instanceof DataNode.Obj o)) {
            return d;
        }
        Map<String, DataNode> out = new LinkedHashMap<>(o.fields());
        out.computeIfPresent("QUANTITY", (k, v) -> DataNode.masked());
        return new DataNode.Obj(out);
    }

    /** A peer: masks QUANTITY for a trader, as the peer's own rules say. */
    private static final class MaskingPeer extends HistoryPeersTest.Store {
        MaskingPeer() {
            super("net:p", Set.of("T-9"), LocalDate.of(2026, 9, 30), "P1");
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            return super.fetch(ref, asOf).map(d -> "trader".equals(acting)
                    ? new EntityDocument(d.ref(), maskQuantity(d.data()), d.provenance()) : d);
        }
    }

    private final HistoryPeersTest.Store local = new HistoryPeersTest.Store("lake", Set.of("T-1"), LocalDate.of(2026, 10, 2), "L1");
    private final HistoryPeersTest.Peers peers = new HistoryPeersTest.Peers(new MaskingPeer());

    private static HistoryService.Viewer viewer(String role, boolean masksHere, String signature) {
        UnaryOperator<DataNode> redact = masksHere ? HistoryCacheMasksTest::maskQuantity : UnaryOperator.identity();
        return HistoryService.Viewer.of(redact, f -> masksHere && "QUANTITY".equals(f), signature);
    }

    private static final HistoryService.Viewer TRADER = viewer("trader", true, "trader|");
    private static final HistoryService.Viewer RISK = viewer("risk", false, "risk|");

    private HistoryService service() {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, Map.of());
        SourceRouter router = new SourceRouter(new SourceRegistry(List.of(local), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
        router.fallback(peers);
        BusinessDates dates = new BusinessDates(new BusinessDateProperties("USNY", "UTC", null), CLOCK);
        return new HistoryService(router, dates, HistoryProperties.defaults(), new SearchProperties(null, null, Map.of("trade", List.of("TRADE_ID"))),
                Formats.defaults(), new ElCompiler(), HistorySpec::defaults, k -> "TRD");
    }

    private static HistoryService.Query days(String id, int n) {
        return new HistoryService.Query("trade", id, n, null, null, null, Set.of(), false, null, false, null);
    }

    private static HistoryAnswer as(String role, HistoryService svc, String id, HistoryService.Viewer v) {
        acting = role;
        try {
            return svc.read(days(id, 3), v);
        } finally {
            acting = "risk";
        }
    }

    private static List<Object> quantities(HistoryAnswer a) {
        return a.rows().stream().map(r -> r.values().get("QUANTITY")).toList();
    }

    private static boolean columnMasked(HistoryAnswer a) {
        return a.columns().stream().filter(c -> c.path().equals("QUANTITY")).findFirst().orElseThrow().masked();
    }

    private static void raw(HistoryAnswer a) {
        assertThat(quantities(a)).isNotEmpty().doesNotContain(DataNode.MASK);
        assertThat(columnMasked(a)).isFalse();
        // QUANTITY is the day of the month: it changes every day, and the changes show
        assertThat(a.rows().subList(1, a.rows().size())).isNotEmpty().allSatisfy(r -> assertThat(r.changed()).contains("QUANTITY"));
        assertThat(a.changedDays()).isPositive();
    }

    private static void masked(HistoryAnswer a) {
        assertThat(quantities(a)).isNotEmpty().containsOnly(DataNode.MASK);
        assertThat(columnMasked(a)).isTrue();
        assertThat(a.rows()).allSatisfy(r -> assertThat(r.changed()).doesNotContain("QUANTITY"));
    }

    @Test
    void aTraderFirstThenRiskReadThroughAPeerSeesTheRealValues() {
        HistoryService svc = service();
        masked(as("trader", svc, "T-9", TRADER));
        HistoryAnswer risk = as("risk", svc, "T-9", RISK);
        raw(risk);
        assertThat(risk.cache()).isEqualTo("miss");                          // the trader's entry is not risk's
        for (int i = 0; i < 3; i++) {
            HistoryAnswer again = as("risk", svc, "T-9", RISK);
            raw(again);
            assertThat(again.cache()).isEqualTo("hit");
            HistoryAnswer trader = as("trader", svc, "T-9", TRADER);
            masked(trader);
            assertThat(trader.cache()).isEqualTo("hit");
        }
    }

    @Test
    void riskFirstThenATraderStaysMasked() {
        HistoryService svc = service();
        raw(as("risk", svc, "T-9", RISK));
        for (int i = 0; i < 3; i++) {
            masked(as("trader", svc, "T-9", TRADER));
            raw(as("risk", svc, "T-9", RISK));
        }
    }

    @Test
    void aFieldOnlyThePeerMasksReadsAsMaskedAndNeverChanges() {
        // this node does not mask QUANTITY for the trader, the peer does ("masks twice"): the column says masked, and no change is shown
        HistoryAnswer a = as("trader", service(), "T-9", viewer("trader", false, "trader|"));
        masked(a);
    }

    @Test
    void aCallerWithoutASignatureIsNotCachedThroughAPeer() {
        HistoryService svc = service();
        HistoryService.Viewer anonymous = HistoryService.Viewer.of(UnaryOperator.identity(), f -> false);
        assertThat(as("trader", svc, "T-9", anonymous).cache()).isEqualTo("off");
        raw(as("risk", svc, "T-9", RISK));
    }

    @Test
    void theDaysThisNodeHoldsAreSharedUnmaskedAndMaskedPerAnswer() {
        HistoryService svc = service();
        HistoryAnswer trader = as("trader", svc, "T-1", TRADER);
        masked(trader);
        assertThat(trader.cache()).isEqualTo("miss");
        HistoryAnswer risk = as("risk", svc, "T-1", RISK);
        raw(risk);
        assertThat(risk.cache()).isEqualTo("hit");                           // one entry for every caller
        masked(as("trader", svc, "T-1", TRADER));
    }

    @Test
    void theCacheKeyCarriesTheSignatureOnlyForDaysAPeerAnswered() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        String shared = HistoryService.cacheKey("trade", "T-9", from, to, 3, Set.of("B", "A"), null, "lake=L1|net:p{P1}");
        assertThat(shared).isEqualTo("trade|T-9|2026-09-01|2026-09-30|3|[A, B]|null|lake=L1|net:p{P1}").doesNotContain("viewer=");
        assertThat(HistoryService.cacheKey("trade", "T-9", from, to, 3, Set.of(), null, null)).isNull();
        String trader = HistoryService.signedKey(shared, "trader|");
        String risk = HistoryService.signedKey(shared, "risk|");
        assertThat(trader).startsWith(shared).endsWith("|viewer=trader|").isNotEqualTo(risk).isNotEqualTo(shared);
        assertThat(HistoryService.signedKey(shared, "trader|")).isEqualTo(trader);
        assertThat(HistoryService.signedKey(shared, null)).isNull();
        assertThat(HistoryService.signedKey(null, "risk|")).isNull();
    }
}
