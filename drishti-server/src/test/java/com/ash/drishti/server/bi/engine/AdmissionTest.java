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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Admission by work class and share, and the cost budget (wave B task 5, docs/architecture/BI_DESIGN.md sections 6.3 and 6.7): a waiting interactive query is
 * served before background work that waited longer; one model holds at most its share of the slots; standing work does not use a person's share; a query
 * whose estimated rows are over its budget is refused before it runs, with the estimate.
 */
class AdmissionTest {

    @TempDir Path store;

    @Test
    void anInteractiveQueryIsServedBeforeBackgroundWorkThatWaitedLonger() throws Exception {
        Admission a = new Admission(1, 4, 1.0);
        Admission.Ticket first = a.enter("u1", "m", QueryContext.WorkClass.INTERACTIVE, Duration.ofSeconds(1));
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch queued = new CountDownLatch(2);
        Thread bg = Thread.ofVirtual().start(() -> {
            queued.countDown();
            try (Admission.Ticket t = a.enter("system", "m", QueryContext.WorkClass.BACKGROUND, Duration.ofSeconds(5))) {
                order.add("background");
            }
        });
        Thread.sleep(50);
        Thread fg = Thread.ofVirtual().start(() -> {
            queued.countDown();
            try (Admission.Ticket t = a.enter("u2", "m", QueryContext.WorkClass.INTERACTIVE, Duration.ofSeconds(5))) {
                order.add("interactive");
            }
        });
        assertThat(queued.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        first.close();
        bg.join(5000);
        fg.join(5000);
        assertThat(order).containsExactly("interactive", "background");
    }

    @Test
    void aModelHoldsAtMostItsShareAndStandingWorkIsNotAPersonsShare() {
        Admission a = new Admission(4, 1, 0.5);
        Admission.Ticket t1 = a.enter("u1", "hot", QueryContext.WorkClass.STANDING, Duration.ofMillis(10));
        Admission.Ticket t2 = a.enter("u1", "hot", QueryContext.WorkClass.STANDING, Duration.ofMillis(10));    // u1's share is 1: standing work does not use it
        assertThatThrownBy(() -> a.enter("u2", "hot", QueryContext.WorkClass.INTERACTIVE, Duration.ofMillis(20))).hasMessageContaining("DRS-9025")
                .hasMessageContaining("per-model-share");
        Admission.Ticket other = a.enter("u2", "cold", QueryContext.WorkClass.INTERACTIVE, Duration.ofMillis(10));
        assertThatThrownBy(() -> a.enter("u2", "cold", QueryContext.WorkClass.INTERACTIVE, Duration.ofMillis(20))).hasMessageContaining("max-concurrent-per-user");
        t1.close();
        t2.close();
        other.close();
        assertThat(a.stats()).containsEntry("running", 0);
    }

    @Test
    void aQueryOverItsRowBudgetIsRefusedWithTheEstimate() {
        TestLake lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC())) {
            QueryService.Loaded ds = new QueryService.Loaded("file:dataset:cost", Layers.key("file:dataset:cost"), DatasetParser.parse(TestLake.yaml()).dataset(), "h");
            QueryService.Caller rita = new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
            QueryRequest q = new QueryRequest(List.of("trades.cpty"), List.of("Notional"), List.of(), TestLake.DAY2, TestLake.DAY2, List.of(), null, "columns");
            assertThatThrownBy(() -> QueryContext.with(new QueryContext.Ctx(QueryContext.WorkClass.INTERACTIVE, 10L, null), () -> service.query(ds, q, rita, false)))
                    .hasMessageContaining("DRS-9029").hasMessageContaining("about 40 rows").hasMessageContaining("options.budget.rows").hasMessageContaining("rollup");
            assertThat(QueryContext.with(new QueryContext.Ctx(QueryContext.WorkClass.INTERACTIVE, 1000L, 5000L), () -> service.query(ds, q, rita, false)).rows()).isPositive();
            assertThat(service.stats()).containsKey("admission");
        }
    }
}
