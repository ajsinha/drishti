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
package com.ash.drishti.tables.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.yaml.TableYaml;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A table's rows over sample documents: the grain expanded, $ @ ^ walked, filter and calculated columns by Rachana, the sample checks. */
class PreviewTest {

    @TempDir Path dir;

    static final String DOCS = """
            {"id":"T1","business_date":"2026-10-09","doc":{"TRADE_ID":"T1","STATUS":"LIVE","MTM":2500000,"LEGS":[{"LEG_ID":1,"CURRENCY":"USD","CASH_FLOWS":[{"AMOUNT":10},{"AMOUNT":20}]},{"LEG_ID":2,"CURRENCY":"EUR","CASH_FLOWS":[{"AMOUNT":5}]}]}}
            {"TRADE_ID":"T2","business_date":"2026-10-09","STATUS":"MATURED","MTM":-100,"LEGS":[{"LEG_ID":1,"CURRENCY":"GBP","CASH_FLOWS":[]}]}

            {"TRADE_ID":"T3","business_date":"2026-10-09","MTM":7,"LEGS":[]}
            """;

    Path sample(boolean gz) throws Exception {
        Path f = dir.resolve(gz ? "trades.jsonl.gz" : "trades.jsonl");
        if (gz) {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
                out.write(DOCS.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            Files.writeString(f, DOCS);
        }
        return f;
    }

    static TableDef table(String yaml) {
        TableYaml.Result r = TableYaml.read(yaml);
        assertThat(r.table()).as(r.problems().toString()).isNotNull();
        return r.table();
    }

    @Test
    void oneRowPerEntityWithAFilterAndACalculatedColumn() throws Exception {
        TableDef t = table("""
                rachana: 3
                table: trading.trades
                from: { kind: trade }
                filter: "$.MTM > 0"
                columns:
                  TRADE_ID: $.TRADE_ID
                  MTM_M:    { expr: "$.MTM / 1000000", type: decimal }
                """);
        Preview.Rows r = Preview.rows(t, Preview.read(sample(false), 100), 50, RowEvaluator.rachana());
        assertThat(r.documents()).isEqualTo(3);
        assertThat(r.columns()).containsExactly("id", "business_date", "TRADE_ID", "MTM_M");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).subList(0, 3)).containsExactly("T1", "2026-10-09", "T1");
        assertThat(((Number) r.rows().get(0).get(3)).doubleValue()).isEqualTo(2.5);
    }

    @Test
    void theGrainExpandsListsInsideListsAndCaretReadsTheParent() throws Exception {
        TableDef t = table("""
                rachana: 3
                table: trading.trade_cash_flows
                from: { kind: trade }
                grain: $.LEGS[*].CASH_FLOWS[*]
                columns:
                  TRADE_ID: { path: $.TRADE_ID }
                  LEG_ID:   { path: "^.LEG_ID" }
                  CURRENCY: { path: "^.CURRENCY" }
                  AMOUNT:   { path: "@.AMOUNT" }
                  DOUBLED:  { expr: "@.AMOUNT * 2" }
                """);
        Preview.Rows r = Preview.rows(t, Preview.read(sample(true), 100), 50, RowEvaluator.rachana());
        assertThat(r.columns()).containsExactly("id", "business_date", "index", "TRADE_ID", "LEG_ID", "CURRENCY", "AMOUNT", "DOUBLED");
        assertThat(r.rows()).hasSize(3);
        assertThat(r.rows()).extracting(x -> x.get(5)).containsExactly("USD", "USD", "EUR");
        assertThat(r.rows()).extracting(x -> ((Number) x.get(6)).intValue()).containsExactly(10, 20, 5);
        assertThat(r.rows()).extracting(x -> ((Number) x.get(7)).intValue()).containsExactly(20, 40, 10);
        assertThat(r.rows()).extracting(x -> x.get(2)).containsExactly(0, 1, 0);
    }

    @Test
    void whatChecksModeAddsWaitsAndTheRestIsShown() throws Exception {
        TableDef t = table("""
                rachana: 3
                table: trading.trades
                from: { kind: trade }
                columns:
                  TRADE_ID: $.TRADE_ID
                  OPEN:     { expr: "$.STATUS not in ['MATURED']", type: boolean }
                """);
        Preview.Rows r = Preview.rows(t, Preview.read(sample(false), 100), 50, RowEvaluator.rachana());
        assertThat(r.rows()).hasSize(3);
        assertThat(r.problems()).singleElement().satisfies(p -> assertThat(p.code()).isEqualTo(TableCodes.PENDING));
    }

    @Test
    void theSampleChecksFindKeysThatAreNotUniqueAndEmptyPaths() throws Exception {
        TableDef t = table("""
                rachana: 3
                table: trading.legs
                from: { kind: trade }
                grain: $.LEGS[*]
                keys: [LEG_ID]
                columns:
                  LEG_ID: "@.LEG_ID"
                  NOPE:   "@.NOT_THERE"
                """);
        List<Problem> ps = Preview.check(t, Preview.read(sample(false), 100), RowEvaluator.rachana());
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.SAMPLE) && p.isError() && p.message().contains("not unique"));
        assertThat(ps).anyMatch(p -> p.path().equals("columns.NOPE.path") && p.message().contains("empty"));
        TableDef keyed = table("""
                rachana: 3
                table: trading.legs
                from: { kind: trade }
                grain: $.LEGS[*]
                keys: [TRADE_ID, LEG_ID]
                columns:
                  TRADE_ID: $.TRADE_ID
                  LEG_ID: "@.LEG_ID"
                """);
        assertThat(Preview.check(keyed, Preview.read(sample(false), 100), RowEvaluator.rachana())).noneMatch(Problem::isError);
    }
}
