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
package com.ash.drishti.server.enginehost;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.source.RowPredicate;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.LifecycleState;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The server's side of the engine host: refusals as HTTP, row rules as engine filters, the lifecycle series' numbers, the cron. */
class EngineHostServerPartsTest {

    @Test
    void refusalsAnswerTheirHttpStatus() {
        assertThat(EngineHostController.status(new EngineException("ENG-5023", EngineException.Kind.REFUSED, "suspended"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(EngineHostController.status(new EngineException("ENG-5020", EngineException.Kind.REFUSED, "x"))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(EngineHostController.status(new EngineException("ENG-4003", EngineException.Kind.NOT_PERMITTED, "x"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(EngineHostController.status(new EngineException("ENG-5001", EngineException.Kind.LIMIT, "x"))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(EngineHostController.status(new EngineException("ENG-1014", EngineException.Kind.UNAVAILABLE, "x")))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aRowRuleOfOneFieldAndConstantsIsAFilterOthersAreNot() {
        assertThat(new PrincipalAccess.Predicate(RowPredicate.parse("DESK = 'RATES'")).asFilter()).contains(new Filter.Eq("DESK", "RATES"));
        assertThat(new PrincipalAccess.Predicate(RowPredicate.parse("DESK in ('A', 'B')")).asFilter()).hasValueSatisfying(f ->
                assertThat(f).isInstanceOf(Filter.In.class));
        assertThat(new PrincipalAccess.Predicate(RowPredicate.parse("DESK = 'A' and BOOK = 'B'")).asFilter()).isEmpty();
        assertThat(new PrincipalAccess.Predicate(RowPredicate.parse("DESK = 'RATES'")).test(Map.of("DESK", "RATES")::get)).isTrue();
        assertThat(new PrincipalAccess.Predicate(RowPredicate.parse("DESK = 'RATES'")).test(Map.of("DESK", "FX")::get)).isFalse();
    }

    @Test
    void theLifecycleSeriesNumbersAreTheLifecycleDocuments() {
        assertThat(EngineHostConfiguration.state(LifecycleState.RUNNING)).isEqualTo(2);
        assertThat(EngineHostConfiguration.state(LifecycleState.SUSPENDED)).isEqualTo(3);
        assertThat(EngineHostConfiguration.state(LifecycleState.SHUTDOWN)).isEqualTo(5);
    }

    @Test
    void aCronScheduleFiresInItsZone() {
        Instant next = EngineHostConfiguration.nextCron("0 30 18 * * MON-FRI", "America/Toronto", Instant.parse("2026-11-20T12:00:00Z"));
        assertThat(next).isEqualTo(Instant.parse("2026-11-20T23:30:00Z"));
    }
}
