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
package com.ash.drishti.lakeload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** The loader's small parts: Python's number and string text, multi-member gzip, globs, the sort key and the configuration's checks. */
class CodecsTest {

    @Test
    void floatsAreWrittenAsPythonsReprWritesThem() {
        assertThat(PyJson.floatRepr(1.0)).isEqualTo("1.0");
        assertThat(PyJson.floatRepr(-4754754.0)).isEqualTo("-4754754.0");
        assertThat(PyJson.floatRepr(0.1)).isEqualTo("0.1");
        assertThat(PyJson.floatRepr(1e-5)).isEqualTo("1e-05");
        assertThat(PyJson.floatRepr(0.0001)).isEqualTo("0.0001");
        assertThat(PyJson.floatRepr(1e16)).isEqualTo("1e+16");
        assertThat(PyJson.floatRepr(1e15)).isEqualTo("1000000000000000.0");
        assertThat(PyJson.floatRepr(123456789012345678.0)).isEqualTo("1.2345678901234568e+17");
        assertThat(PyJson.floatRepr(1.5e300)).isEqualTo("1.5e+300");
        assertThat(PyJson.floatRepr(-0.0)).isEqualTo("-0.0");
        assertThat(PyJson.floatRepr(2.5e-7)).isEqualTo("2.5e-07");
        assertThat(PyJson.floatRepr(Double.NaN)).isEqualTo("NaN");
        assertThat(PyJson.intText("-0")).isEqualTo("0");
        assertThat(PyJson.idOfFloat(1000008.0)).isEqualTo("1000008");
        assertThat(PyJson.idOfFloat(1.5)).isEqualTo("1.5");
    }

    @Test
    void stringsAreEscapedAsPythonsEncoderEscapesThem() {
        Bytes b = new Bytes(4);
        PyJson.string(b, "a\"b\\c\nd\u0001é–😀\t");
        assertThat(new String(b.a, 0, b.n, StandardCharsets.UTF_8)).isEqualTo("\"a\\\"b\\\\c\\nd\\u0001é–😀\\t\"");
    }

    @Test
    void severalGzipMembersReadAsOneStreamAndACorruptOneFails() throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (String part : List.of("first\n", "second\n", "", "third")) {
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(one)) {
                gz.write(part.getBytes(StandardCharsets.UTF_8));
            }
            all.write(one.toByteArray());
        }
        try (GzipMembers in = new GzipMembers(new ByteArrayInputStream(all.toByteArray()), 7)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("first\nsecond\nthird");
        }
        byte[] bad = all.toByteArray();
        bad[bad.length - 6] ^= 0x55;                                  // the last member's CRC
        assertThatThrownBy(() -> {
            try (GzipMembers in = new GzipMembers(new ByteArrayInputStream(bad), 1 << 16)) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    void globsMatchOneFolderOrAny() {
        assertThat("pv-1.jsonl.gz").matches(Inputs.globRegex("*.jsonl.gz"));
        assertThat("sub/pv-1.jsonl.gz").doesNotMatch(Inputs.globRegex("*.jsonl.gz"));
        assertThat("sub/deeper/pv-1.jsonl.gz").matches(Inputs.globRegex("**/*.jsonl.gz"));
        assertThat("cob-2026-10-28.part-003.jsonl").matches(Inputs.globRegex("cob-????-??-28.part-[0-9]*.jsonl"));
    }

    @Test
    void sortKeysOrderNullsNumbersAndTextAndCutAtGroups() {
        Bytes a = new Bytes(8);
        RowPlan.keyText(a, "LE-NY".getBytes(StandardCharsets.UTF_8), 0, 5);
        RowPlan.keyText(a, "D1".getBytes(StandardCharsets.UTF_8), 0, 2);
        int group = a.n;
        RowPlan.keyText(a, "P-1".getBytes(StandardCharsets.UTF_8), 0, 3);
        assertThat(RowPlan.prefixLength(a.a, 0, a.n, 2)).isEqualTo(group);
        assertThat(RowPlan.components(a.a, 0, a.n)).containsExactly("LE-NY", "D1", "P-1");
        Bytes n1 = new Bytes(8);
        RowPlan.keyNumber(n1, -5);
        Bytes n2 = new Bytes(8);
        RowPlan.keyNumber(n2, 3);
        Bytes nul = new Bytes(8);
        RowPlan.keyNull(nul);
        Bytes t = new Bytes(8);
        RowPlan.keyText(t, new byte[] {'a'}, 0, 1);
        Bytes t0 = new Bytes(8);
        RowPlan.keyText(t0, new byte[] {'a', 0}, 0, 2);
        assertThat(Runs.compare(nul.a, 0, nul.n, n1.a, 0, n1.n)).isNegative();
        assertThat(Runs.compare(n1.a, 0, n1.n, n2.a, 0, n2.n)).isNegative();
        assertThat(Runs.compare(n2.a, 0, n2.n, t.a, 0, t.n)).isNegative();
        assertThat(Runs.compare(t.a, 0, t.n, t0.a, 0, t0.n)).isNegative();
        assertThat(RowPlan.components(n1.a, 0, n1.n)).containsExactly(-5.0);
    }

    @Test
    void aPackGivesTheDomainTableLayoutKeyAndFlattenedFieldsAsLakeBuildReadsThem() throws IOException {
        java.nio.file.Path packs = java.nio.file.Path.of("").toAbsolutePath().getParent().resolve("config/packs");
        LoadConfig risk = LoadConfig.read(null, List.of("source.files=x.jsonl", "source.pack=" + packs.resolve("market-risk"), "source.date=BUSINESS_DATE",
                "target.lake=/l", "target.kind=scenario-pv"));
        assertThat(risk.target().domain()).isEqualTo("risk");
        assertThat(risk.source().vectors()).containsExactly("SCENARIO_PV");
        assertThat(risk.source().promote()).contains("LEGAL_ENTITY", "DESK", "TT_BOOK");
        LoadConfig trades = LoadConfig.read(null, List.of("source.files=x.jsonl", "source.pack=" + packs.resolve("trading"), "source.connector=trade-endur",
                "source.as-of=2026-10-15", "target.lake=/l", "target.kind=trade"));
        assertThat(trades.tablePath()).isEqualTo("/l/trading/trade_endur");
        assertThat(trades.source().id()).isEqualTo("TRADE_ID");
        assertThat(trades.source().flatten()).containsEntry("NOTIONAL", "LEGS[0].NOTIONAL");
    }

    @Test
    void theConfigurationNamesAMistakeAndTheBudgetHoldsItsShares() throws IOException {
        assertThatThrownBy(() -> LoadConfig.read(null, List.of("source.files=x.jsonl", "source.date=D", "target.path=/t", "target.layuot.x=1")))
                .hasMessageContaining("layuot is not a key");
        assertThatThrownBy(() -> LoadConfig.read(null, List.of("source.files=x.jsonl", "target.path=/t"))).hasMessageContaining("source.date");
        assertThatThrownBy(() -> LoadConfig.read(null, List.of("source.files=x.jsonl", "source.date=D", "target.path=/t",
                "source.storage-options.secret-key=abc"))).hasMessageContaining("never put in the file");
        LoadConfig c = LoadConfig.read(null, List.of("source.files=x.jsonl", "source.date=D", "target.path=/l/risk/pv", "memory.budget-mb=100",
                "source.storage-options.endpoint=${NO_SUCH_VAR:-http://minio:9000}"));
        assertThat(c.memory().budget()).isEqualTo(100L << 20);
        assertThat(c.memory().queueBytes() + c.memory().sortBytes() + c.memory().writeBytes()).isEqualTo(100L << 20);
        assertThat(c.source().storage()).containsEntry("s3.endpoint", "http://minio:9000");
        assertThat(c.domainAndTable()).containsExactly("risk", "pv");
        assertThat(c.lakeRoot()).isEqualTo("/l");
    }
}
