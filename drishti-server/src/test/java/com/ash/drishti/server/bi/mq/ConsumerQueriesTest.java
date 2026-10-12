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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.QueryRequest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A consumer's engine request as a model query (docs/architecture/BI_MODEL_SPEC.md section 5.9), and the codes a consumer's refusal keeps. */
class ConsumerQueriesTest {

    final ModelView model = MqHarness.model(MqEngineTest.MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);

    @Test
    void fieldsGetTheirTableMeasureFiltersBecomeHavingAndDatesBecomeTheTime() {
        LocalDate d = LocalDate.of(2026, 10, 8);
        QueryRequest r = new QueryRequest(List.of("region", "trades.currency"), List.of("Notional"),
                List.of(new QueryRequest.Filter("trades.currency", "in", List.of("USD")), new QueryRequest.Filter("Notional", "between", List.of(1, "2.5"))), d, d,
                List.of(new QueryRequest.Sort("Notional", true), new QueryRequest.Sort("region", false)), 10, "columns");
        ModelQuery q = ConsumerQueries.of(model, r);
        assertThat(q.model()).isEqualTo(model.id());
        assertThat(q.fields()).containsExactly("desks.region", "trades.currency");
        assertThat(q.where()).extracting(ModelQuery.Where::field, ModelQuery.Where::op).containsExactly(org.assertj.core.groups.Tuple.tuple("trades.currency", "in"));
        assertThat(q.having()).extracting(ModelQuery.Having::op).containsExactly("ge", "le");
        assertThat(q.having().get(1).values()).containsExactly(2.5);
        assertThat(q.time().asOf()).isEqualTo(d);
        assertThat(q.sort()).extracting(ModelQuery.Sort::by).containsExactly("Notional", "desks.region");
        assertThat(q.limit()).isEqualTo(10);
        assertThat(q.options().layer()).isEqualTo("columns");
        // two dates are a window; no dates the model's default
        assertThat(ConsumerQueries.of(model, new QueryRequest(List.of(), List.of("Notional"), List.of(), d.minusDays(3), d, List.of(), null, "auto")).time().from())
                .isEqualTo(d.minusDays(3));
        assertThat(ConsumerQueries.of(model, new QueryRequest(List.of(), List.of("Notional"), List.of(), null, null, List.of(), null, "auto")).time())
                .isEqualTo(ModelQuery.Time.DEFAULT);
    }

    @Test
    void aConnectorQueryBindsOnlyTheNamedDatesNeverText() {
        LocalDate d = LocalDate.of(2026, 10, 8);
        List<Object> values = new java.util.ArrayList<>();
        String sql = DatasetSources.bind("SELECT a::text, :x FROM t WHERE as_of = :asOf AND d BETWEEN :f AND :t", java.util.Map.of("asOf", "as-of", "f", "from", "t", "to"),
                d, d.minusDays(2), null, values);
        assertThat(sql).isEqualTo("SELECT a::text, :x FROM t WHERE as_of = ? AND d BETWEEN ? AND ?");
        assertThat(values).containsExactly(java.sql.Date.valueOf(d), java.sql.Date.valueOf(d.minusDays(2)), java.sql.Date.valueOf(d.minusDays(2)));
    }

    @Test
    void aRefusalKeepsTheEndpointsCodeAndTheLanguagesProblem() {
        MqException masked = new MqException(MqException.Problem.of("DRS-9161", "where[0].field", "masked")).legacy();
        assertThat(masked.errorCode()).isEqualTo(ErrorCode.QUERY_MASKED);
        assertThat(masked.problems().get(0).code()).isEqualTo("DRS-9161");
        assertThat(new MqException(MqException.Problem.of("DRS-9152", "select.fields[0]", "no")).legacy().errorCode()).isEqualTo(ErrorCode.QUERY_INVALID);
        assertThat(new MqException(MqException.Problem.of("DRS-9151", "model", "no")).legacy().errorCode()).isEqualTo(ErrorCode.MQ_MODEL);
        assertThat(new MqException(MqException.Problem.of("DRS-9109", "source.model", "loop")).legacy().errorCode()).isEqualTo(ErrorCode.LAYER_SOURCE_CYCLE);
    }
}
